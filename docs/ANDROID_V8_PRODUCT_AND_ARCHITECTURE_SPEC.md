# Finance Automation Android - Product and Architecture Specification

Status: Draft v8.0.0. Written fresh. It replaces all earlier planning, setup and test documents, which have been
removed from the repository. Rules in `AGENTS.md` still apply unless this document says otherwise.

Companion document: [ANDROID_V8_ENGINEERING_AND_TEST_PLAN.md](./ANDROID_V8_ENGINEERING_AND_TEST_PLAN.md)
covers device compatibility, permissions, build milestones and the live-device test method.

Reference for accounting behaviour: [IOS_BACKEND_V2_3_0_SYNC_NOTES.md](./IOS_BACKEND_V2_3_0_SYNC_NOTES.md).

---

## 1. Mission

Zero-friction expense logging on Android, with the same feel as the iOS Shortcuts system.

- **Zero manual overhead.** The user never opens an expense tracker, types a date, scrolls a long category list,
  or copies an amount.
- **Interception to ledger.** A UPI notification or bank debit SMS is captured the moment it arrives. The data
  is pre-filled and waiting. When the user leaves the payment app, a translucent one-tap sheet appears. One tap
  saves the row into the master Google Sheet.
- **Zero setup of the sheet.** After one Google sign-in the app creates the Drive folder and the workbook by
  itself and keeps adding rows. No script, no URL, no token to copy.
- **Single source of truth.** The Google Sheet is the authoritative ledger. The phone is an input node with an
  offline queue. Nothing lives only in the local database.

## 2. Principles

1. The sheet is the ledger. Room holds only: the offline write queue, pending captures, a fast read cache, the
   people list, trip tags, and category recency statistics. Everything except unsynced queue items can be rebuilt
   from the sheet.
2. Looks and behaves like iOS Shortcuts: translucent sheets, short prompts, instant confirmation banner,
   server-style result messages.
3. Capture first, ask later. Parsing happens silently in the background; the user is only interrupted at the
   right moment (section 4).
4. Never lose a transaction. Every capture is persisted before anything is shown.
5. Privacy by construction: package whitelist, no personal messaging apps, no OTPs, no raw notification text
   stored, no third-party servers, no analytics.
6. The accounting engine is the iOS engine (cascading waterfall, bilateral netting, shared-row quotas,
   idempotency), implemented as pure Kotlin in the domain layer. Android keeps its own improvements: stable row
   UUIDs, in-place void, lineage in Room, config-driven categories and offline queue.
7. Works on every mainstream Android, not one brand. Behaviour is driven by capability checks, not by assuming
   stock Android (see the engineering plan).
8. Once the MVP is in place, verified on a real device with real Google APIs, not only by mocks. Until then the
   priority is building the product.

## 3. Interaction model (the iOS Shortcuts feel)

### 3.1 What the user sees

The iOS flow is: launch, choose an action, answer short translucent prompts, see a one-line confirmation. The
Android equivalent is a floating overlay window (not a full-screen activity) with two visual forms:

| Form | iOS equivalent | When shown |
| :--- | :--- | :--- |
| **Quick Log sheet** | Translucent bottom sheet with prompts | Pending capture is ready, or the launcher entry is tapped |
| **Result pill** | Top banner "Logged ... for ..." | After Confirm and Save, and for any server-style result message |

Visual rules:

- Translucent surface with background blur where the OS supports it (Android 12+ window blur); a translucent
  scrim on older versions. Large corner radius, spring animation in and out, light and dark themes, haptic tick
  on confirm.
- All colours, shapes and type come from `ui/theme/` tokens (AGENTS.md). No hardcoded values.
- The overlay never appears in Recents, never takes over the foreground task, and never resizes or pauses the app
  underneath.
- Target: sheet visible within 300 ms of the trigger on a mid-range device.

### 3.2 Quick Log sheet (expense)

Pre-filled from the capture, each field editable in one tap:

1. **Amount** (from the alert).
2. **Payee / item** (merchant from the alert).
3. **Account**, inferred from the bank name in the alert or SMS, otherwise the last used account.
4. **Category chips**, ordered by recency and frequency for that payee first, then global usage. The predicted
   category is pre-selected, so most entries need zero category taps.
5. **Split** as one segmented control: Just Me, Split Equally, Custom Split. Equal split shows companion chips
   from the people list with `+ Add New`. Custom split shows one numeric field.
6. **Trip tag** chip, shown only when a trip is active, with one-tap start and end.
7. **Confirm and Save**: commits, dismisses, shows the result pill.

The default path is one tap: Confirm and Save.

### 3.3 Full action menu (iOS parity)

When opened manually (launcher entry) the sheet starts with the same "Choose Action" menu as the iOS Run Shortcut:

1. Expense (the sheet above, empty).
2. Income / Return, with the Friend Settlement bill picker (multi-select plus Auto-Settle).
3. Friend Paid For Me (payable).
4. Repaid a Friend (repayment).
5. Trip Mode (start or end).

### 3.4 Main app

The full app (dashboard, ledger list, review inbox, settings, setup health screen) exists for review, settings
and setup. It is never required for daily logging.

## 4. Capture and lifecycle

### 4.1 Sources

Debit alerts only, from these notification sources:

| Source | Package |
| :--- | :--- |
| Google Pay | `com.google.android.apps.nbu.paisa.user` |
| BHIM UPI | `in.org.npci.upiapp` |
| PhonePe | `com.phonepe.app` |
| Paytm | `net.one97.paytm` |
| Supermoney | `com.supermoney.app` |
| Bank debit SMS | the device's system messaging app (package set is OEM-dependent, see the engineering plan) |

Credit alerts (inflows) use the same pipeline and the same privacy rules and map to Income / Return.

Strict privacy: anything else is dropped before inspection. Personal messaging apps (WhatsApp, Telegram, Signal)
and OTP or verification messages are never read, logged or cached. From a messaging app, a notification is parsed
only if its package is on the SMS list and it matches a bank debit pattern; everything else is discarded
immediately and unlogged. Raw title and body are processed in memory and discarded. Only amount, payee, source
package, timestamp and inferred account are kept.

### 4.2 The trigger rule (no timeout)

This copies how the iOS automation behaves: the prompt belongs to the moment the payment app is closed.

1. A whitelisted debit alert arrives. The listener parses it in the background and writes a **pending capture**
   to Room with all pre-filled fields. This happens even if the app is not open.
2. Then one of two cases:
   - **The payment app is already out of the foreground** (the user has left it): the sheet is shown immediately
     with the pre-filled data.
   - **The payment app is still in the foreground** (the user is on the PIN or success screen): nothing is shown.
     The pending capture waits. The moment the payment app leaves the foreground (home, back, switch app), the
     sheet is shown.
3. There is no timer anywhere in this flow. A pending capture is shown when its condition is met, however long
   that takes. It is only removed by Confirm and Save, or by an explicit user dismissal.
4. An explicit dismissal never deletes the capture. It moves to the **Needs Review** inbox, which is also reachable
   from a persistent low-priority notification.

If two alerts for the same payment arrive (for example a payment app alert and a bank SMS 15 seconds later), they
merge into the same pending capture when the amount matches and the sources differ. The sheet shows one entry. If
the merge is uncertain, the second one is flagged Possible Duplicate in the inbox and never dropped silently.

### 4.3 Foreground detection and fallbacks

Knowing when the payment app leaves the foreground needs the Usage Access permission. The wait uses
lightweight event queries and runs only while a pending capture exists.

If Usage Access is not granted, the app degrades in this order and tells the user in the setup health screen:

1. Show the sheet immediately when the alert arrives (the sheet is non-blocking and pre-filled).
2. If the overlay permission is also missing, post a heads-up notification with two top-category buttons and a
   Details button (the v7 notification model). Tapping Details opens the sheet as a translucent activity.

### 4.4 Unconfirmed captures and the ledger

An unconfirmed capture is not written to the sheet. Open decision D2 covers whether a capture that sits in Needs
Review for a long time should be auto-saved with category `Other`.

### 4.5 Alerts the parser cannot read

When an alert comes from a whitelisted package, passes the privacy checks (own package, blacklist, OTP) but no amount
can be extracted, the app still creates a pending capture, with the amount blank and the payee `Unknown Payee`. The
sheet opens with the amount field focused and the line "Payment detected, enter amount". Nothing from the alert text
is stored. This keeps the rule "never lose a transaction" true when a bank changes its message format.

### 4.6 Pending-to-success alerts and duplicate handling

- Own-package alerts are discarded first.
- Payment apps often post a "processing" alert and later a "success" alert under the same notification key. A repeat
  of the same key with identical content is dropped. A change from pending to success is an update of the same
  capture (refresh amount and payee), never a second capture.
- Cross-source matches (same amount, different source package, close in time, merchant not required to match) merge
  into the waiting capture, or are flagged Possible Duplicate if the capture has already been shown. They are never
  dropped silently.
- If the user marks a flagged item as a duplicate: nothing is written if it has not synced yet; if its row is already
  in the sheet, the row is voided in place (spec 9.8).

## 5. Launcher entry: dynamic 1x1 widget

Requirement: it must look like a normal app icon on the user's home screen and adapt to the user's launcher
settings, not use fixed pixel sizes.

Design:

- One 1x1 cell widget (`targetCellWidth = 1`, `targetCellHeight = 1` on Android 12+, a computed `minWidth` and
  `minHeight` on older versions). The launcher decides the pixel size from the user's grid and icon-size settings.
- The layout is size-responsive: it reads the actual size the launcher gives it on every resize and scales icon,
  padding and corner radius from that size. Nothing is hardcoded in pixels. Corner radius uses the system widget
  radius resource where it exists (Android 12+) and otherwise a proportion of the cell size.
- Light, dark and wallpaper-derived colours follow the system theme.
- Tapping it never opens the dashboard. It starts the Quick Log sheet on the Choose Action menu.
- Optional companion: a pinned launcher shortcut. A pinned shortcut is drawn by the launcher itself, so it
  matches the user's icon shape, size and pack exactly. Widgets cannot match third-party icon packs or custom
  icon shapes pixel for pixel, because Android exposes no API for the launcher's icon shape. Offering both lets
  the user pick whichever looks closer on their phone.

## 6. Onboarding and provisioning (fully automatic)

1. User signs in with Google through the Google Identity authorization flow. Minimal scope: `drive.file` (the app
   only touches files it creates).
2. The app creates the Drive folder `Personal Finance`, then the workbook `FinanceYYYY` inside it with
   `appProperties` marking it as this app's workbook, then applies the bundled template in one batch update.
3. The app saves the Google account, folder id and spreadsheet id. It does not store passwords or long-lived
   tokens. Access tokens are short-lived and obtained on demand from Google Play Services; on a 401 the app clears
   the stale token and re-authorises silently. If silent re-authorisation fails, the user gets one notification to
   reconnect.
4. The app runs the setup health screen (permissions and OEM settings, section 4 of the engineering plan).
5. After a reinstall, the app finds its own workbook through `appProperties`, restores configuration from the
   hidden `Config` tab, and continues. No data is lost, because the sheet is authoritative.

The workbook is created by this app. Opening the existing iOS-created workbook is not part of this version
(open decision D1).

## 7. Data architecture

### 7.1 Remote (authoritative)

Google Sheets v4 and Drive v3 over REST with OkHttp. No Google API Java client libraries, no backend, no
webhooks.

### 7.2 Local (Room)

| Table | Purpose | Rebuildable from the sheet |
| :--- | :--- | :--- |
| `sync_queue` | Offline writes not yet in the sheet, with a UUID per item | No (it is the queue) |
| `pending_captures` | Parsed alerts waiting to be shown | No (until confirmed) |
| `transactions_cache` | Rows read from the sheet for fast screens | Yes |
| `people` | Companion names and usage counts | Yes (scan the sheet) |
| `trip_tags` | Trip tags and the active one | Yes (mirrored in `Config`) |
| `category_stats` | Payee to category memory, recency and frequency | Partly (from past rows) |
| `preferences` | Settings, default account, burn-exclusion flags | Yes (mirrored in `Config`) |
| `lineage` | `carried_from_uuid`, `root_uuid` | Yes |

### 7.2a State model

A transaction in Room has two independent state axes plus a void flag, so that "confirmed" and "synced" never get
tangled:

- `review_state`: `NEEDS_REVIEW` or `CONFIRMED`.
- `sync_state`: `PENDING`, `SYNCED`, or `FAILED` (the existing code also uses `VOID`).
- `is_voided`: boolean.

Each `sync_queue` item has its own state (`PENDING`, or failed and needing attention after the retry cap), separate from
the transaction's. A transaction that failed logically returns to `NEEDS_REVIEW` with a message.

### 7.3 Offline behaviour

Confirm and Save always succeeds instantly: it writes to `sync_queue` and shows the result pill. A single
serialised WorkManager chain (network-connected constraint, exponential backoff) flushes the queue. The result
pill shows the pending state when offline ("Saved, will sync").

### 7.4 Sheet-first reads

Operations that need the current ledger (settlement pickers, waterfall, netting, people list) read the sheet at
the time of the operation, not a stale cache. Offline, the app shows cached data and marks it stale.

### 7.5 Local database encryption

The Room database is encrypted at rest. Decision: encrypt (SQLCipher).

- Use SQLCipher for Android with Room's `SupportOpenHelperFactory`.
- The passphrase is 32 random bytes generated on first run, encrypted with an Android Keystore key (AES-GCM, key never
  leaves the secure hardware or Keystore) and stored as ciphertext in app-private storage. The passphrase is never
  written in plain form, never logged, never in preferences in clear, and never synced to the sheet.
- Migration: a database that already exists unencrypted is converted once at first launch of the new version
  (export into an encrypted copy, verify the row counts, then delete the plain file). If the conversion fails, the
  app keeps the sync queue and pending captures and rebuilds everything else from the sheet.
- Loss of the key (for example after uninstall, since backup is disabled) loses only data that can be rebuilt from the
  sheet, plus any queue items not yet synced. This is the reason the sync queue flushes as soon as the network allows.
- Check APK size and 16 KB memory-page support for the chosen SQLCipher version before shipping (required for newer
  Android targets).
- Honest scope: this protects the stored copy of payees and amounts if the phone's storage is read outside the app
  (a stolen or imaged device, a rooted device, a bad backup). It does not protect against code running inside the app.

## 8. Ledger schema

The workbook follows the iOS template, which is the verified master, with Android's hidden UUID columns added.

- Columns A to K Debits, L spacer, M to T Inflows, U spacer, V to AB Payables, AC to AE hidden UUIDs
  (`AC` debit, `AD` inflow, `AE` payable). Three header rows, frozen, data from row 4.
- Appends target `A3:K`, `M3:T`, `V3:AB` with `valueInputOption=RAW` and `insertDataOption=OVERWRITE`. Nothing sits
  below a table. Grid is provisioned with about 1000 rows and expanded with `appendDimension` when needed.
- Dates are integer date serials with mask `dd/mm/yyyy`. Times are written with mask `hh:mm am/pm`. Currency mask
  `"₹"#,##0` as in the iOS template.
- Header names follow the iOS template (`Paid For / Companions`, `Linked Debit Row`), except Table 2 column `T`,
  which holds the linked debit **UUID** for rows the app creates.
- Locale `en_IN`, time zone `Asia/Kolkata` (or the user's configured values).

### 8.1 Status vocabulary (the iOS vocabulary)

| Table | Values |
| :--- | :--- |
| Debits (K) | `Personal` (nothing owed), `Pending`, `Settled`, `Carried Fwd`, `Void` |
| Payables (AB) | `Pending`, `Partial`, `Repaid`, `Carried Fwd`, `Void` |

The Dashboard owed formula sums rows whose status is exactly `Pending`, so no other word may be introduced.

### 8.2 Stable row identity

Every row gets a UUID in its table's hidden column. The app addresses rows by UUID, verifying the cached row
number before every update and falling back to a column scan if the user has sorted or inserted rows. Rows that
iOS wrote have no UUID; the app tolerates blank UUIDs and addresses those rows by a scan on date, time and amount.

### 8.3 Visual design (match the iOS workbook)

The workbook must look like the iOS template, not like a plain grid. The reference file is
`docs/ios_reference/Finance2026_Template.xlsx`. Read exact column widths, row heights, merges and chart anchors from
that file programmatically; do not retype them. The design values below are what the reference contains.

General: font Arial (the "This month at a glance" strip uses Google Sans), thin borders in a pale blue-grey,
currency mask `"₹"#,##0`, page background white on the Dashboard, `#ECEFF1` on spacers.

**Ledger tabs (`MmmYY` and `Template`)**

| Element | Style |
| :--- | :--- |
| Row 1 banner, merged across the tables | Fill `#19227E`, white bold 16, height 27, text `EXPENSE LEDGER - MMM YYYY` |
| Row 2 group headers | `EXPENSES (MONEY OUT)` fill `#C62828`; `INFLOWS (MONEY IN)` fill `#2E7D32`; `I OWE PEOPLE` fill `#F57F17`; white bold 11, centered, height 18 |
| Row 3 column headers | Fill `#37474F`, white bold 9, centered, height 21, thin border `#E5EAEF` |
| Spacer columns L and U | Width about 10 px, whole column filled `#ECEFF1` |
| Data rows | Arial 10, height 15, thin border `#E5EAEF`; column alignment as in the schema table |
| Frozen rows | 3 |
| Hidden columns | AC, AD, AE |

The app may keep its extra dropdown validation on Account and Category. Any extra conditional formatting must use
the same palette and must not change the look of an empty or normal row.

**Dashboard tab**

| Element | Style |
| :--- | :--- |
| Title, merged `A1:T1` | `PERSONAL FINANCE DASHBOARD - YYYY`, fill `#19227E`, white bold Arial 14, height 24 |
| Last updated, merged `A2:T2` | Formula `="Last updated: " & TEXT(NOW(), "DD MMM YYYY, HH:MM")`, fill `#EBEEF1`, Arial 9, grey `#747474` |
| Section strip, merged `A3:R3` | `THIS MONTH AT A GLANCE`, fill `#EBEEF1`, Google Sans 11 bold, blue `#3D85C6` |
| KPI cards | Six cards of three merged columns each (A-C, D-F, G-I, J-L, M-O, P-R). Row 4 label Arial 8 bold grey on white (the `I OWE PEOPLE` label uses fill `#283593` with white text, as in the iOS file). Row 5 value Arial 16 bold, centered, `"₹"#,##0`. Row 6 caption Arial 8 grey with a thin bottom border `#B0BEC4` |
| KPI value colours | Current burn `#C52727`, previous month `#202020`, owed to me `#2D7D32`, I owe people `#C52727`, subscriptions `#7A1FA1`, daily average `#1465BF` |
| Monthly table `U5:W17` | Header fill `#1465BF` bold, body fill `#F9F9F9`, Arial 10, centered, currency mask |
| Category table `Y5:Z15` | `Y5` fill `#EBEEF1` bold, `Z5` fill `#1465BF` bold, body fill `#F9F9F9`, values right aligned |
| Settlements banner, merged `A24:L24` | `ACTIVE SETTLEMENTS & PENDING BALANCES`, fill `#37464E`, white bold Arial 10 |
| `THEY OWE ME` (`A25:F25`) | Fill `#2D7D32`, white bold |
| `I OWE PEOPLE` (`H25:L25`) | Fill `#F47F17`, white bold |
| Settlement header row 26 | Fill `#455964`, white bold Arial 9, names left, numbers right, thin border `#DFE6E9` |
| Settlement rows 27 to 40 | Arial 10, thin border `#DFE6E9`, formats as in the iOS template |

**Charts (two, anchored below the KPI strip at row 7)**

1. Left, anchored at `A7`, about 520 by 266 px: clustered column chart titled
   `Full Year Spending by Month (Personal Burn vs Total Outflow)`. Categories `U6:U17`; series `V6:V17` (colour
   `#1465BF`) and `W6:W17` (colour `#90CAF8`); series names from `V5` and `W5`.
2. Right, anchored at `K7`, about 491 by 266 px: doughnut chart titled `Current Month Spend by Category`, categories
   `Y6:Y15`, values `Z6:Z15`, slice colours `#4285F4`, `#EA4335`, `#FBBC04`, `#34A853`, `#FF6D01`, `#46BDC6`, then the
   same family for the remaining slices.

The iOS file's doughnut range stops at row 14 and therefore leaves out `Other`. Use all ten categories (`Y6:Y15`).
Create the charts with `addChart` requests in the provisioning batch update.

## 9. Accounting rules

These are the iOS rules. Pure Kotlin, no Android dependencies, in the domain layer.

### 9.1 Split

- **Just Me:** my share = total, owed = 0, companions `Personal`, status `Personal`.
- **Split Equally (N companions):** my share = total / (N + 1) rounded to 2 decimals, owed = total - my share, status
  `Pending`.
- **Custom Split:** my share = input, clamped between 0 and total; owed = total - my share; status `Pending`.
- Status is derived from the owed amount: owed is zero means `Personal`, or `Settled` when netting absorbed all of it.
  Owed above zero is never anything but `Pending`.
- Category `Saving` is normalised to `Savings`.

### 9.2 Validation

Amounts reject zero, negative and non-numeric values, strip currency text and commas, and round to 2 decimals.
Names split on commas, `&` and `and`, are trimmed and compared case-insensitively and exactly (`Rohit` and `Rohit K`
are different people). Free text is written with RAW so it cannot execute as a formula.

### 9.3 Idempotency

Every write carries the transaction UUID. Before appending, the engine checks for an existing row with that UUID
(and, for rows interrupted between append and UUID write, an orphan matching date, second and amount). A replay
produces no new row. Queue items keep their UUID across retries and are never regenerated.

### 9.4 Settlement waterfall (Income / Return, Friend Settlement)

1. Settle explicitly selected bills first, in the order chosen. A shared bill may be settled in full only when it
   was explicitly selected and the payment covers the whole balance.
2. Cascade the remainder across the sender's other open debts, oldest month first. On shared rows a payment is
   capped at that person's share (owed / N), and the person is removed from the companions cell once covered.
3. Surplus becomes a payable ("Excess settlement credit", status `Pending`) only when at least one real debt was
   settled. When nothing matched, the credit is recorded unlinked and flagged `unmatched`.
4. The credit row records every row it settled.

### 9.5 Repayment waterfall (Repaid a Friend)

Repay an optional explicit payable first, then the creditor's other open payables oldest first, capped at the
creditor's share on shared rows. Status becomes `Partial` while a balance remains and `Repaid` at zero, with the
repaid date set. Surplus becomes a debit row (`[Carried Over] Excess repayment balance`, category `Other`, my share
0, owed = surplus, status `Pending`) only when at least one payable was repaid.

### 9.6 Bilateral netting

A new debit with companions first reduces open payables owed to those companions (cap per companion is owed / N).
A new payable first settles open debits owed by that creditor, oldest first. A payable fully absorbed by netting is
still written, with amount 0, status `Repaid`.

### 9.7 Carry-forward

Created when the first transaction of a new month needs its tab, or earlier by the advance-tab routine (from day 25).
The engine looks back up to 12 months for the most recent existing tab, including the prior year workbook.

- Pending debts with owed above zero are copied: original date and time, description prefixed
  `[Carried from MmmYY] `, **Total = owed**, my share 0, owed = owed, status `Pending`. The origin row becomes
  `Carried Fwd`.
- Pending or partial payables are copied the same way and the origin becomes `Carried Fwd`.
- Gross outflow in the Dashboard excludes rows whose description starts with `[Carried`, which is what keeps the
  total from double counting. The prefix text is therefore fixed and never localised.
- Later settlements follow carried rows forward using Room lineage first, and the prefix tag as a fallback.
- Year close writes a `CarryFwd` tab and `carry_forward_YYYY.json` to the Drive folder.

### 9.8 Void (Android improvement, kept)

Void is in place: status `Void`, description prefixed `[Void] `, total, my share and owed set to 0, UUID kept. A
row is never deleted or blanked, so appends never find a gap.

### 9.9 Concurrency

There is no script lock on Android. Writes are serialised through one WorkManager chain per device, and every
update re-verifies the target row's UUID first. Two devices writing at once is a documented risk, not a supported
mode in this version.

## 10. Dashboard and formulas

The Dashboard is the iOS Dashboard: KPI ribbon, 12-month summary, category breakdown (ten categories in `Y6:Y15`,
header in `Y5`), and the grouped settlement tables (`A27` they owe me, `H27` I owe). Formulas use dynamic
`INDIRECT(TEXT(TODAY(), ...))` so month rollover needs no manual step.

Living-burn exclusion stays config-driven: each category has an "excluded from living burn" flag (defaults:
`Savings`, plus the `Transfer` keyword the iOS formulas exclude). The app regenerates the exclusion formulas and the
category breakdown with one batch update when the list changes. A live check after every regeneration reads the
Dashboard back and fails on any `#VALUE!`, `#REF!`, `#N/A` or `#DIV/0!` (engineering plan, section 8).

## 11. Customisation (config-driven)

Tier 1 lists: accounts, categories, people, trip tags (add, rename, reorder, archive). Tier 2 behaviour: default
account, burn-exclusion flag per category, currency symbol, locale and time zone, monitored apps. Tier 3: per-app
amount and merchant patterns with length caps and timeout guards. Configuration is stored in Room and mirrored to a
hidden `Config` tab that contains no financial data. The author's setup ships only as a selectable preset.

Tier 3 detail: each monitored app's amount and merchant pattern can be edited in the app with a test box (paste a
sample alert, see what would be extracted). Patterns have a length cap, an execution time limit and sanity guards (for
example a plausible amount range), so a bad pattern cannot freeze the listener. A transaction that has no category
defaults to `Other`. Renaming a category applies to future transactions; an optional utility rewrites historical rows
on request.

## 12. Settings sync across devices

The sheet is the single source of truth, so a user's settings live in the sheet too. A user who changes phone, or
reinstalls, signs in and finds the same accounts, categories, people, trip tags and preferences, with no setup.

### 12.1 What syncs and what does not

| Syncs (stored in the hidden `Config` tab) | Stays on the device |
| :--- | :--- |
| Accounts (name, last four digits, default, archived, order) | Permission state (notification access, overlay, usage access, battery) |
| Categories (name, excluded from living burn, default, archived, order) | OEM setup progress and the setup health results |
| People (name, order, archived) | Widget placement and size |
| Trip tags (name, active, archived) | Pending captures, sync queue, local cache |
| Preferences: default account, currency symbol, locale, time zone, monitored apps, parser rules, appearance | Cached access token and the signed-in account |

The `Config` tab is hidden, holds no amounts, merchants or timestamps of transactions, and is never read by the
Dashboard formulas.

### 12.2 Config format (v2)

The current fixed 16-column grid limited to 49 rows per list, with no timestamps, cannot support more than one device.
Replace it with a key-value row layout, one row per setting, one tab:

`Type | Key | Value1 | Value2 | Value3 | Value4 | UpdatedAt | UpdatedBy | Deleted`

- `Type` is `account`, `category`, `person`, `trip`, or `pref`; `Key` is the item name or preference key.
- `UpdatedAt` is an ISO timestamp, `UpdatedBy` is a random device id created at install, `Deleted` is `true` for a
  tombstone.
- A header row carries a `schemaVersion`. The reader tolerates unknown columns and unknown types.
- No fixed row limit. Rows are appended and edited by key, never by position.

### 12.3 Sync rules

- **Pull:** after sign-in, on app start, and when the app returns to the foreground at most once every 10 minutes.
  Read the whole tab with one request. For each item, the newer `UpdatedAt` wins. Items that exist only remotely are
  inserted locally. Tombstones remove or archive the local item. Pull happens before any Dashboard regeneration.
- **Push:** when a setting changes, mark it dirty and write after a short delay (about 3 seconds, so a burst of edits
  becomes one request) through the same serialised queue as transactions, so it works offline and flushes later.
  A push writes only the changed rows.
- **Delete:** never remove a row. Write a tombstone so other devices learn about the deletion.
- **Conflict:** last writer wins per item, never per tab. Two devices editing different items never conflict.
- **New device:** sign in, find the workbook, pull, and only then regenerate the Dashboard dropdowns and category
  breakdown from the pulled configuration. A new device must never overwrite the cloud settings with local defaults.
- **First device:** if the `Config` tab is empty, push the local defaults once.
- **Failure:** a failed pull or push is retried by the queue; settings are never lost because Room keeps the local
  copy until the push succeeds.

### 12.4 What the first implementation did and did not cover

The earlier implementation mirrors configuration into a fixed-grid `Config` tab and can restore it. It overwrites the
whole tab on each mirror, has no timestamps or deletions, mirrors only the workbook id, default account and settle
delay as preferences, and does not restore automatically when an existing workbook is found. Phase 2.4 of the phase
guide replaces it with this design.

## 13. Cross-platform workbook contract (future iOS app)

The sheet is the source of truth, so a native iOS app can be built later on the same workbook. What makes that safe is
that every client, Android, a future iOS app, and the existing Apple Shortcuts system, follows one written contract.
This section is that contract. Anything not listed here is client-specific.

### 13.1 What every client must obey

1. **Layout:** the 31-column ledger (spec 8), spacer columns, hidden `AC` to `AE`, data from row 4, nothing below a
   table, tab names `MmmYY`, workbook name `FinanceYYYY` with `appProperties` `app=FinanceAutomation` and
   `fiscalYear=YYYY`.
2. **Writes:** `RAW` only, numbers and date or time serials as numbers, append to `A3:K`, `M3:T`, `V3:AB`, then write the
   UUID to the hidden column. Free text is never prefixed or evaluated.
3. **Identity:** every row has a UUID in its hidden column. A client must tolerate rows with a blank UUID (written by the
   Apple Shortcuts backend) and address them by tab and row after checking date, time and amount.
4. **Statuses:** exactly the words in spec 8.1. No client may introduce a new status word.
5. **Accounting:** the rules and worked examples in spec 9 and the phase guide (split, settlement waterfall, repayment
   waterfall, netting, carry-forward, void). Two clients given the same sheet and the same request must produce the same
   edits. Carry-forward uses `Total = owed`, my share 0, the fixed `[Carried from MmmYY] ` prefix, and origin status
   `Carried Fwd`.
6. **Idempotency:** an operation carries a UUID; a replay must change nothing.
7. **Settings:** the hidden `Config` tab in the key-value format with `schemaVersion` (spec 12). Unknown types and
   columns are ignored, never deleted, so an older client does not break a newer one. Every client bumps its own
   `UpdatedBy` device id.
8. **Dashboard:** formulas and layout as in spec 8.3 and 10; a client that regenerates the Dashboard must read the
   pulled configuration first.

### 13.2 Versioning

The `Config` tab carries `schemaVersion` (starting at 1). A client that meets a higher version than it knows must read
only, and warn the user to update, instead of writing. Any change to this contract is a version bump and is recorded in
this section.

### 13.3 What differs on iOS

An iOS app cannot read other apps' notifications, so capture is different, not the sheet side. The candidates are an
App Exit automation that opens the app, a Shortcuts action, a share sheet, a widget, and manual entry. Everything after
capture (the sheet contract, the accounting engine, settings sync) is portable. The accounting engine should be kept
free of platform dependencies and its worked examples kept as the shared reference, so it can be re-implemented in
Swift with the same results.

### 13.4 Two clients, one workbook: access

Opening one workbook from two apps is an access question, not a design one. With the `drive.file` scope an app sees only
the files it created or that the user explicitly opened with it. Options, in the order to try:

1. Keep both apps in the same Google Cloud project, since per-file access is granted to the project. This probably lets
   an iOS client see a workbook the Android client created, but it must be tested before relying on it.
2. Have the user pick the existing workbook once through the Google file picker, which grants per-file access to
   that app.
3. A wider Drive scope. This is a restricted scope with a verification process, so avoid it unless 1 and 2 fail.

Until an iOS app exists, the Android beta keeps one workbook per user created by the app (decision D1).

### 13.5 The beta

Each friend signs in with their own Google account and gets their own workbook. Nobody shares a workbook. While the
Google OAuth consent screen is in testing status, each tester's account must be added as a test user (a small fixed
limit applies), and Google may expire a tester's consent after about a week, which shows up as the reconnect prompt.
Check the current Google rules before inviting testers, and plan to publish the consent screen once the beta settles.

## 14. Open decisions

| # | Decision | Default used in this draft |
| :--- | :--- | :--- |
| D1 | Share the existing iOS workbook with Android, or keep a separate Android-created workbook | Separate workbook. Sharing needs a wider Drive scope or a one-time file picker and a decision on running both clients on one sheet |
| D2 | Unconfirmed capture sitting in Needs Review: keep waiting, or auto-save after a period as `Other` | Keep waiting (nothing reaches the ledger unreviewed) |
| D3 | Fallback when the overlay permission is missing | Heads-up notification with two category buttons and a Details button |
| D4 | Offer the pinned launcher shortcut alongside the widget | Offer both |
| D5 | Target SDK | API 35 per `AGENTS.md`; raise to 36 once verified on the device |
| D6 | Local database encryption | Decided: encrypt with SQLCipher, key wrapped by the Android Keystore (spec 7.5) |
| D7 | Future iOS app and shared workbook | Keep the workbook contract in section 13 portable now; sharing one workbook between apps waits for an iOS app and the access test in 13.4 |
