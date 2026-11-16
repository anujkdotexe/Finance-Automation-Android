# Finance Automation Android - Complete Analysis Report

Date: 2026-11-16
Branch reviewed: claude/android-app-code-review-4f293f (clean tree, no source changes made)
Device checked: one Realme RMX3998 phone running Android 16, app version 1.0.0 (versionCode 1)
Companion file: `docs/CODE_REVIEW_2026-11-16.md` holds the working notes and per-finding file references in the order they were reported. This file is the consolidated analysis. Where this report says "see review notes", the detail is there.

## 1. Executive summary

The app is far along. Sign-in, workbook provisioning, SQLCipher storage, the capture pipeline, overlay, widget, quick-settings tile, accounting engine, config sync and a large UI are all present and wired through production code. Privacy rules for notification content are respected (whitelist first, privacy blacklist, SMS sender pre-filter, hash-only storage, Sheets writes as RAW values, no secrets or keystores in git).

It is not ready for friends or release. The weak points are concentrated in three places:

1. The hand-off from capture to ledger loses or corrupts entries. A dismissed capture disappears, credit alerts are logged as expenses, debit SMS containing a credit word are dropped as credits, and the Needs Review screen cannot show captures at all.
2. The popup can get stuck on screen. Device evidence supports this.
3. Several sync and storage paths can fail silently or permanently: year rollover, a poison queue item, a failed encryption migration, the year-close upload, dashboard regeneration writing to sheet id 0.

Also missing for any public build: an in-app privacy policy and open source licences screen, tests that run against real SQL and migrations, and CI.

Verdict: do not distribute until the 8 "fix first" items in section 9 are done and re-tested on the phone.

Counts (after de-duplication across reviewers): 6 critical or blocking, 12 high, about 45 medium, about 70 low, plus about 60 dead or unwired items.

## 2. Scope, method and honesty about coverage

Six independent reviewers read the code in parallel, then the lead reviewer verified the top findings and checked the connected phone.

| Reviewer | Access | Slice | Coverage |
|---|---|---|---|
| Blind A | code only | service, manifest, parts of repository | about 8 percent read fully, rest grep |
| Blind B | code only | UI, ViewModels, overlay | about 65 of 105 files read fully |
| Blind C | code only | domain, service, workers, resources | every file in slice read fully |
| Blind D | code only | tests, build, R8, tools | about 40 percent read fully, rest sampled |
| Blind E | code only | data, remote, repositories, core, di | all read except about two thirds of the provisioner file |
| Docs-aware | AGENTS.md, README, docs, git log | whole app against the documents | key flows traced, large files partly read |

Verified by the lead reviewer with grep or direct reading: default-account preference key mismatch, dismissal state handling, credit kind never read by the UI, window removal order in the overlay, privacy and licences links, process kill history on the phone.

Not covered: line-by-line read of the whole Sheets provisioner, most Compose component files (small ones, theme folder, error screens), the Python and Java harnesses beyond a run, and every behaviour that needs a live Google account. Kotlin unit tests were never compiled or run in this review because the Gradle daemon failed to start in the reviewer sandbox. All "uncertain" items are marked as such. Everything not listed as verified is a reviewer claim backed by code tracing, not by running the app.

## 3. What is solid

- Layering is mostly respected: no `android.*` imports in the domain layer.
- Theming: no hardcoded colours or dp values outside the theme folder (one `10.sp` literal).
- Sheets writes are RAW everywhere; append plus UUID is one atomic batchUpdate with a persisted plan and a re-append guard; carry-forward and void-inflow are idempotent.
- Backup is off and the key file is excluded from extraction; cleartext traffic is disabled; the notification service is permission protected; the exported entry activity is hardened with an internal auth check.
- Room migrations 2 to 14 are contiguous, schemas are exported, the wipe table list matches the entities.
- No emojis and no local machine paths found in source or docs (the only `c:\` text is the rule itself).
- Split and netting math (just me, equal, custom, waterfall, repayment) reads correct with HALF_UP rounding.
- Sync worker has a network constraint, backoff and unique work names; token refresh avoids blocking the main thread.

## 4. Blocking and high-severity findings (fix before any distribution)

### 4.1 Capture integrity (money and entries lost or wrong)

| ID | Problem | Where | Evidence level |
|---|---|---|---|
| C-1 | Dismissing the Quick Log sheet marks the capture DISMISSED, which no screen reads and which is later pruned. The spec says it must go to Needs Review. The entry disappears. | `QuickLogTransactionHelper.kt:67-81`, `PendingCaptureDao.kt:36,39,75` | Verified |
| C-2 | A debit SMS containing "credited", "received" or "added" (for example "debited ... credited to merchant", or an ATM text ending "if cash not received") is classified CREDIT and no expense is created. Test fixture 24 encodes the wrong result. | `ParseNotificationUseCase.kt:252-254`, `RegexPatterns.kt:50-57` | Reviewer, code traced |
| C-3 | Needs Review screen has no UI for captures, new-account captures or duplicate resolution (keep both, keep one). The badge counts captures but the screen can show "All caught up". | `ReviewInboxViewModel.kt`, `ReviewInboxScreen.kt:121-250` | Reviewer, code traced |
| C-4 | Credit alerts are shown as expense sheets and logged as debits. `kind = CREDIT` is stored but never read by the overlay, the sheet or the heads-up action receiver. Docs say this was fixed. | `NotificationProcessor.kt:207`, `QuickActionReceiver.kt` | Verified |
| C-5 | Default account preference is read as "default_account" but stored as "key_default_account", so captures land in "Other". | `NotificationProcessor.kt:151` | Verified |
| C-6 | Dashboard regeneration falls back to sheet id 0 when the Dashboard tab is not found or the fetch fails, then writes formulas and validation to that sheet, usually a ledger tab. | `RegenerateDashboardUseCase.kt:49,74,83,99` | Reviewer, code traced |

### 4.2 Popup that cannot be closed

Device evidence: the trail shows four "user dismissed" events about 11 seconds apart with no new overlay shown, and the daily funnel shows overlay shown 2 times against dismissed 10 times. Android's permission record shows an overlay use lasting about 13 minutes around the testing session. Causes found in code:

1. `OverlayController.kt:255-261,305-308`: dismiss launches the database write in the ViewModel scope and immediately destroys the scope, so the write is cancelled and the capture stays WAITING. The watcher then re-prompts about 700 ms later. (H-2)
2. `OverlayController.kt:387-400`: `clearCurrentWindow` destroys the lifecycle owner before removing the window inside one try block. If the destroy throws, the window is never removed but the controller forgets it, so every later dismiss does nothing until the process dies. (H-3, verified by reading)
3. The overlay is non-focusable, so the Back key never closes it.
4. The date picker inside the overlay is built from the application context and probably crashes with a bad-token error. Uncertain.
5. Needs Review cannot recover a dismissed capture (C-3).

### 4.3 Sync, storage and workbook

| ID | Problem | Where |
|---|---|---|
| D-H1 | Year rollover: the per-year spreadsheet id falls back to the global id, so on 1 Jan 2027 the 2026 workbook is reused and the Jan27 tab is created inside Finance2026. `setSpreadsheetIdForYear` also overwrites the global id for any year. | `PreferencesRepositoryImpl.kt:107-127`, `WorkbookResolver.kt:61-70,143-147,215-219` |
| D-H2 | One throwing queue item blocks the whole sync queue (poison pill). A category named "Saving" throws in the mapper and the item never leaves PENDING. | `TransactionRepositoryImpl.kt:779-1237`, `CategoryConstants.kt:46-52`, `SheetPayloadMapper.kt:22,61` |
| D-H3 | Plaintext to SQLCipher migration recovery cannot run (Room builds lazily, so the encrypted file does not exist yet), so a failed migration leaves an empty ledger, and the unencrypted copy stays on disk forever. | `FinanceDatabase.kt:239-256`, `DatabaseEncryptionMigrator.kt:135-199` |
| D-H4 | Year-close upload to Drive uses `multipart/form-data` instead of `multipart/related`, likely returns 400, and the failure is ignored so the app reports success. | `GoogleDriveClient.kt:404-414`, `AccountingSyncExecutor.kt:371-376` |
| H-1 | Income, payable and repayment saves in `ModalBottomSheetActivity` ignore the result, show "Recorded" even on failure, crash on exceptions and have no double-submit guard. | `ModalBottomSheetActivity.kt:191-239` |
| H-8 | Captures can be stranded in SHOWN with no UI when overlay permission is off and notifications are blocked, or the heads-up is swiped away. Only a restart resets them. | `ForegroundHeadsUpHelper.kt:134-139` |

### 4.4 Release readiness gaps

- No in-app privacy policy and no open source licences screen (section 8).
- Beta and release signing: beta builds silently fall back to the debug key if `keystore.properties` is missing; `versionCode` is hardcoded to 1.
- The beta build ships the notification simulator (`ENABLE_SIMULATOR=true`), against the beta plan.

## 5. Medium-severity findings (grouped)

Full file references are in the review notes. Items below are the substance.

**Capture pipeline and services**
- Notification dedup is check-then-insert on a multi-threaded scope, and active notifications are replayed on listener reconnect, so the same alert can create two captures.
- One sync lock is held across the whole network sync, so user edits and one-tap notification actions wait and can be killed.
- Amount extraction can pick an account balance as the amount (fallback to the first match even if all matches follow a balance word).
- Merchant extraction can return the user's own UPI address as the payee; "from Rahul" alerts give "Unknown Payee".
- Foreground detection only looks back 5 minutes (guide says 10), so a payment app open longer reads as background and the sheet pops over it.
- Watcher start race can leave a capture waiting until the next restart; an overlay add failure has no backoff and can retry every 700 ms.
- Boot receiver does database work without `goAsync()`.
- "Capture is off" alert (id 9001) stays after a restart; the health worker posts a second alert (id 8888) that nothing cancels.
- Spec says no capture timer, code expires captures after 24 hours; spec says merchant need not match for a cross-source merge, code requires payee compatibility.

**Accounting and data**
- "Saving" throws instead of normalising to "Savings" (also feeds D-H2).
- Money is stored and compared as Double with tolerances up to 0.05; `round2` throws on NaN or Infinity.
- Open-balance check misses people in multi-companion custom splits, so removing them is not blocked.
- Config push stamps push time as `updatedAt`, so an old offline edit can overwrite a newer edit from another device; deletions for several entity types are pushed as not deleted (uncertain).
- Subscription "Yes" is not atomic, so a double broadcast can log two expenses.
- Renaming an account, category or friend does not update transactions, budgets or counts.
- Inflow type labels are stored as translated text, so settlements and transfers can display as "Income" (uncertain).
- Split edits on a settled transaction are silently dropped; editing an old transaction re-tags it with the active trip; the split is stale after adding a friend; the income and repay sheets default to a "Cash" account that may not exist.
- Transient network failures are counted toward the 8-retry cap, so a day offline can park items as FAILED with no automatic recovery.
- Default-account changes never sync; the clear-then-set is not one transaction.
- Stale Google token can be served after an account change; account wipe is not serialised with a running sync.
- Row allocation is scan-then-write, so another device or a hand edit in between is overwritten.
- Drive lookups ignore non-2xx responses and can create a second workbook; the workbook choice has no ordering and backup copies can match.
- Config can be overwritten with local defaults when a read error message happens to contain "Config".
- Accounting op on a tab that failed to create becomes a permanent failure.
- Two void paths for inflows behave differently; rows deleted on the sheet persist locally.
- Database key file is written non-atomically, and the key recovery branch is dead, so a lost Keystore alias leaves the app stuck on "Storage unavailable".

**UI and flows**
- Insights drill-down is reset by the Transactions route (month and filter go back to defaults).
- Custom categories cannot be picked in the expense and friend-paid sheets or used as a Transactions filter.
- Fake data shown as real: "Synced 2m ago", "Finance Ledger 2026", fake fallback accounts, default subscription amount 649.00, fixed due date "20 Dec 2026", and a "9:00 PM" reminder row that actually changes an unrelated setting.
- Preferences "Clear voided history" is composed inside the account dialog branch and is unreachable.
- Onboarding can save "Authorized User" as the account email, continue after a cancelled picker, and lose the consent request on rotation.
- Deep links to Sync/Backup and Capture screens bypass the route validator.
- Transactions row tap opens the detail sheet and launches the edit screen at once.
- Trip sheet "Start new trip" is disabled while a trip is active and the people chips do nothing; friend-paid date and category are ignored; QuickLog "Void" is never wired; Add Category colour is never saved; Setup Health Google "Fix" and Diagnostics "Run sync now" do nothing.
- Subscriptions can only be added, not edited, paused or deleted.
- `ButtonPair` requires labels of 14 characters or fewer and crashes composition otherwise.
- Many ViewModel actions have no error handling and can crash or leave spinners stuck.

**Privacy and diagnostics**
- Google error text (can echo spreadsheet ids or cell content) is stored in Diagnostics and `sync_queue.lastErrorDetail` and reaches the shareable compatibility report.
- Fatal-crash exception messages are not sanitised for Crashlytics.
- Domain layer imports data and presentation classes, and one use case does raw HTTP, against the architecture rule.

## 6. Tests and build

- Time bombs: tests hardcode the "Nov26" tab and about 100 month references while production reads the real clock; likely failures from 1 Dec 2026. Several tests mix `currentTimeMillis` with `YearMonth.now()` and can fail in the first minute of a month or on a non-IST machine.
- Migrations 7-to-8 through 10-to-11 are never executed in tests; 2-to-7 have none; no Room query is ever run against real SQL; no `androidTest` source set exists.
- Fake DAOs diverge from Room (category dropped in `updateReviewState`, `getRecentSimilar` always empty).
- About 28 tests read source text instead of testing behaviour, including account-switch safety tests.
- The `tests/` folder (715 Python tests, 3 Java harnesses) tests a Python copy of the logic, not the Kotlin app; a run showed 19 failures and 3 errors; no CI exists.
- Not tested at all: encryption migrator, key provider, account wipe against Room, equal-split rounding (100 split three ways), crash payload privacy, Config sync, foreground watcher.
- Build: `okhttp-logging` shipped but unused; `compileSdk` 36 on AGP 8.8 via a suppression flag; R8 rules lack `SourceFile` and `LineNumberTable` (release stack traces unreadable); Gson and Moshi rules with no dependency; debug-signed beta fallback; hardcoded `versionCode`.

## 7. Low severity, dead code and drift (summary)

- 8 uses of `!!`; no `AppLog` wrapper (about 30 direct `Log.` calls); about 12 empty `catch` blocks; 21 non-lifecycle `collectAsState()` calls; unmanaged coroutine scopes in several activities and the application class.
- Hardcoded English strings across the UI, overlay messages and notifications; about 40 unused strings from an abandoned onboarding checklist; placeholder text such as "Coming in round 4"; "2 items saved" hardcoded; mixed UK and US spelling.
- Accessibility: Undo target under 48 dp, chips under 48 dp, duplicate content descriptions.
- Parsing edge cases: comma stripped as thousands separator ("12,5" becomes 125), substring wording checks ("sent" in "consent"), loose subscription token matching.
- Dead code: `calculateRollover`, `calculateInPlaceVoid`, `inferAccount`, inflow and payable models with four mapper functions, about 40 DAO methods with no main caller, several composables and ViewModel members, an empty dashboard validation stub, legacy Config mirror functions.
- Unreachable screens: Notification Access, Onboarding route and three error screens have no navigation entry.
- Doc drift: README phase status and Room version, `AGENTS.md` OAuth scopes (code uses `drive.file` only), audit table evidence, "no tests" policy versus 152 test files, beta plan `oauth.client.id`, `.env.example` keys the app does not read, spec statements on append mode, backoff time, watcher window, "no timer", and extra features not in the spec (budgets, Insights, app lock, month widget, subscriptions, CSV export, trips).
- The gitignored handoff files were absent, so the last build-agent report could not be compared with the code.

## 8. Privacy policy and open source licences

Confirmed: both are links only. `AboutScreen.kt:305-312` and `:357-364` open `https://financeautomation.app/privacy` and `/licenses`; onboarding opens `/help` (`OnboardingScreen.kt:217-218`). There is no in-app screen, no bundled text (the only asset is `oem_routes.json`), no licence-listing plugin, and the handlers swallow every failure in an empty `catch`. Whether the domain exists was not checked.

Needed: an in-app privacy screen stating what is read (whitelisted payment apps and bank SMS only, never WhatsApp, Telegram or OTP), what is stored (encrypted on device), what leaves the device (only the user's own Google Sheet; optional Crashlytics with technical data only) and how to delete data; an open source licences screen generated from the dependency list; and a visible message when a link cannot be opened.

## 9. Evidence from the connected phone

Read-only checks over adb and on-screen reading. Nothing was installed, cleared or changed in the app data.

- App installed 2026-11-12, last updated 2026-11-14. Notification listener enabled and running. Overlay and usage-access permissions allowed. The app is not debuggable, so its private files could not be read.
- Diagnostics screen: funnel for the day showed overlay shown 2, user dismissed 10, user saved 3, sync queued 63, sync success 39, capture done 2. Four dismiss events about 11 seconds apart with no overlay shown between them support the stuck-window diagnosis. The screen also exposes four simulator presets (120, 85 and 250 rupee examples and an "amount not found" case), which means developer mode and the simulator are reachable in this build.
- Transactions this month: two expenses, 50 at 12:10 pm and 69 at 12:11 pm, Food & Drink, account Other, Personal. They line up with the app's own quick-entry screens in the usage log (12:09:53, 12:10:58, 12:11:16) and were created by the user's friend while testing. No payment-app or SMS notification events were logged in the retained window of about one day. Payment apps and SMS apps are installed (Google Pay, BHIM, Paytm, Google Messages, default SMS app); Google Pay was last opened on 6 and 7 November.
- Needs Review shows "All caught up", which is not proof of an empty inbox because that screen cannot display captures (C-3).
- Process exit history: no crash, no freeze and no native crash. The system killed the app 8 times between 15 Nov 00:47 and 16 Nov 04:47 (vendor background killer on this phone). That endangers capture reliability and should be handled through Setup Health guidance and the OEM hardening steps.
- The device log contains no app lines, so field problems depend on the Diagnostics screen and the compatibility report.

## 10. Root-cause themes

1. Features were wired in the ViewModel and domain but not connected to the UI or to each other (Needs Review captures, credit kind, QuickLog void, trip chips, friend-paid date, Google fix button). Tests exercise the pieces, not the path.
2. State is split across independent places (capture state, queue state, preferences with two keys, per-year versus global spreadsheet id) with no single owner, so one missed update loses or duplicates an entry.
3. Tests mirror implementation: Python copies, source-text greps, over-faked DAOs, hardcoded months. They pass while the real wiring is wrong.
4. Failure handling is optimistic: ignored results, empty catches, success reported after ignored errors, throw-in-loop poison pills.
5. Docs and code drifted apart in both directions, so neither can be trusted alone.

## 11. Remediation plan

Fix first (blocking), in this order:
1. C-1, C-4, C-5 and H-2/H-3 (dismiss state and overlay teardown), plus H-8, so no capture is lost or stuck.
2. C-2 direction detection from the verb attached to the amount, and update fixture 24.
3. C-3 make Needs Review show captures, duplicates and dismissed items.
4. C-6 and D-H1 (never write to sheet id 0; per-year workbook ids).
5. D-H2 wrap each queue item and normalise "Saving" instead of throwing.
6. D-H3 and D-H4 (migration restore after open and plaintext cleanup; correct multipart type and check the result).
7. H-1 check results and guard double submit in the sheet activity.
8. Privacy policy and licences screens, and remove the simulator and debug-key fallback from beta.

Then medium items by area: capture (dedup lock, balance and payee extraction, foreground window, boot receiver, alerts), accounting (renames, locked edits, retries, config timestamps), UI (fake data, unreachable controls, error handling), privacy (sanitise diagnostics and crash messages).

Testing plan:
- Add real SQL tests: Room migration tests for every version, `RoomAccountDataStore.wipe`, each DAO query used by sync.
- Replace hardcoded months and mixed clocks with an injected fixed clock.
- Add behaviour tests through production constructors for dismiss, credit, debit-with-credit-word SMS, year rollover, poison item, and equal-split rounding.
- Remove or quarantine the Python and Java harnesses; add CI that runs unit tests, lint and a minified release build.

Device verification after fixes: dismiss by outside tap and button with the overlay on and off, a credit SMS, a debit SMS containing "credited", app killed by the system then notification arrives, offline for a day then reconnect, account switch and wipe, and a 1 January rollover with a fixed test clock.

## 12. Open questions for the owner

1. Is one workbook across years intended, or one per year as the resolver comment says?
2. Should the notification simulator exist in beta builds at all?
3. Should the missing SMS-sender whitelist be tightened (bank sender IDs only) now that Google Messages and the default SMS app are read?
4. Does `financeautomation.app` exist and host the privacy policy, or should the policy ship inside the app?
