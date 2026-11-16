# Finance Automation Android - Engineering and Test Plan

Status: Draft v8.0.0, kept as the design reference. Updated 2026-11-16: the sections marked HISTORICAL below describe
the early rounds and no longer match the code. `AGENTS.md` and `README.md` are authoritative where they differ (the
rules for tests, builds and commits changed after this plan was written). Companion to
[ANDROID_V8_PRODUCT_AND_ARCHITECTURE_SPEC.md](./ANDROID_V8_PRODUCT_AND_ARCHITECTURE_SPEC.md). Read that first; it
defines what the app does. This document defines how it is built across Android brands and how it is proven on a
real device.

---

## 1. Architecture (unchanged layering, new components)

Clean Architecture with MVVM and the repository pattern, Kotlin, Jetpack Compose, Material 3, coroutines and
`StateFlow`, Room, WorkManager, OkHttp. The domain layer has no Android dependencies.

New or reworked components:

| Layer | Component | Responsibility |
| :--- | :--- | :--- |
| service | `NotificationListenerServiceImpl` | Whitelist filter, parse, write a pending capture. No UI work. |
| service | `ForegroundWatcher` | While a pending capture exists, reports when its source app leaves the foreground (Usage Access events). Idle otherwise. |
| service | `OverlayService` | Hosts the Compose sheet and result pill in an overlay window. Single entry point for every trigger. |
| presentation | `QuickLogSheet`, `ResultPill`, action menu screens | The UI in spec section 3. |
| presentation | `TranslucentFallbackActivity` | Same sheet as a translucent, non-history, excluded-from-recents activity, used when the overlay permission is missing. |
| presentation | `SetupHealthScreen` | Live permission and OEM-setting checks, with a self-test. |
| widget | `LauncherWidgetProvider` | 1x1 responsive widget (spec section 5). |
| domain | `SettlementWaterfallUseCase`, `RepaymentWaterfallUseCase`, `NettingUseCase`, `CarryForwardUseCase`, `CalculateSplitUseCase` | The iOS accounting engine as pure Kotlin. |
| data | `SheetGateway` | Reads tabs in batches, computes nothing, writes with one batch update per operation. |
| data | `WorkbookProvisioner`, `ConfigSheetManager`, `DashboardFormulaBuilder` | Create and maintain the workbook. |

Existing code is reused where it already matches. The first milestone audits it (section 6).

---

## 2. Device compatibility

Goal: the same behaviour on Samsung, Xiaomi / Redmi / Poco, Oppo, Realme, Vivo, OnePlus and Pixel / AOSP, Android 9
(API 28) and later. These brands differ mainly in how aggressively they stop background apps and which extra
permissions they gate. The strategy is capability checks and a guided setup, never brand-specific business logic.

### 2.1 Required permissions and settings

| Need | Why | How requested |
| :--- | :--- | :--- |
| Notification access | Read whitelisted alerts | Special access screen (`ACTION_NOTIFICATION_LISTENER_SETTINGS`), detail page for the app where supported |
| Display over other apps | Show the sheet and pill from the background | `ACTION_MANAGE_OVERLAY_PERMISSION` |
| Usage access | Know when the payment app is closed | `ACTION_USAGE_ACCESS_SETTINGS` |
| Post notifications (Android 13+) | Reconnect prompts, review reminder, fallback alerts | Runtime permission |
| Battery optimisation exemption | Keep the listener bound under OEM battery managers | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, with a fallback to the battery settings page |
| Auto-start / background launch (OEM) | Some brands block background starts of an app that is not whitelisted | OEM settings intent from the table below, with a fallback to the app info page |

### 2.2 Per-brand handling

| Brand (skin) | Typical problem | Handling |
| :--- | :--- | :--- |
| Samsung (One UI) | "Sleeping apps" and "deep sleeping apps" lists stop listeners | Guide the user to remove the app from sleeping lists and set battery to Unrestricted |
| Xiaomi / Redmi / Poco (MIUI, HyperOS) | Auto-start off by default; separate "display pop-up windows while running in the background" permission; aggressive task kill | Intents for Autostart and the app's Other permissions page; battery saver set to No restrictions; lock the app in Recents (explain it) |
| Oppo (ColorOS) | Auto-launch and background freeze | Intent for startup manager and battery page; allow background activity |
| Realme (Realme UI) | Same family as ColorOS | Same as Oppo |
| Vivo (Funtouch, OriginOS) | "High background power consumption" and background pop-up permission | Intents for the background power page and permission page; allow auto-start |
| OnePlus (OxygenOS, ColorOS-based) | Battery optimisation and "deep optimisation" | Battery page, set to Don't optimise; allow auto-launch |
| Pixel / AOSP | Few restrictions | Standard permissions only |

Every OEM intent is wrapped in a resolve check; when the intent does not resolve on a given firmware version the
app opens the generic app info page and shows plain instructions. Brand detection is used only to pick which
instructions to show, never to change app logic.

### 2.3 Verified versus targeted

The target list above is the goal. The **verified** list is only the devices actually tested. The test report
(section 9) records, per device model, firmware version and result. A brand is not called supported until it has a
recorded pass on a real device of that brand. Verification starts with the connected test phone and grows as other
devices are available.

---

## 3. Listener resilience

- The listener is system-bound; it cannot be made a foreground service. Resilience comes from recovery:
  - `onListenerDisconnected()` calls `requestRebind`.
  - On every app start and every boot completion, if access is granted but the listener is not connected, toggle the
    listener component enabled state to force a rebind (a long-standing workaround for OEM unbind).
  - A periodic WorkManager health check (long interval, battery friendly) verifies access is granted and the
    listener is bound, and raises one low-priority notification if the user has to act.
  - `onListenerConnected()` scans `getActiveNotifications()` and creates pending captures for whitelisted alerts
    that arrived while the listener was down, de-duplicated against existing captures.
- Force-stop by the user disables the listener until the app is opened again. Android offers no recovery. The
  setup health screen and the persistent low-priority notification say this plainly.
- Swiping the app out of Recents must not stop capture. This is a required live test (section 8).

---

## 4. Overlay engine

- The sheet and pill are Compose content hosted in an overlay window (`TYPE_APPLICATION_OVERLAY`) owned by
  `OverlayService`. The service supplies the lifecycle, view-model-store and saved-state owners Compose needs.
- A window is not an activity, so it does not enter Recents or the back stack, and it does not need a
  background-activity-start exemption. This is why the overlay is the primary path.
- Window flags: non-focusable by default so it never steals the foreground app's input; focusable only while the
  amount or payee field is being edited (so the keyboard works), then reset.
- Blur uses window background blur on Android 12+, a translucent scrim below that.
- Insets, notch and cutout are handled so the pill sits in the safe area on every device.
- If overlay permission is absent: `TranslucentFallbackActivity` (translucent theme, `excludeFromRecents`, empty task
  affinity, no history). It can only start from a user action (widget tap, notification tap); for background alerts
  the fallback is the heads-up notification.
- Dismissal is explicit (tap outside, swipe, Close). Dismissal moves the capture to Needs Review.

---

## 5. Widget implementation

- `AppWidgetProvider` with `targetCellWidth = 1` and `targetCellHeight = 1` (Android 12+), plus computed
  `minWidth` and `minHeight` for older versions. `resizeMode` none.
- Responsive layout: on `onAppWidgetOptionsChanged` and on first draw, read the actual size from
  `OPTION_APPWIDGET_SIZES` (or min/max width and height before Android 12) and draw the icon, padding and corner
  radius as proportions of that size. No fixed pixel dimensions.
- Corner radius uses the system widget radius where available.
- Tap target is a `PendingIntent` to `OverlayService` (or `TranslucentFallbackActivity` without overlay
  permission), opening on the action menu.
- Optional pinned shortcut via `ShortcutManager.requestPinShortcut`, same target.
- Limitation stated to the user: icon-pack and custom-shape matching is a launcher feature; the pinned shortcut is
  the closest match, the widget is the closest adaptive match.

---

## 6. Build milestones

Milestones M1 to M4 of the earlier plan are history. New sequence:

| # | Milestone | Done when |
| :--- | :--- | :--- |
| A | **Audit and stabilise** | Every existing feature is exercised on the device and classified working, broken, or missing. Broken items are fixed or removed. Output: an audit table in the repo docs. |
| B | **Onboarding and provisioning** | Google sign-in, folder, workbook, template, `Config` tab, current month tab, reinstall recovery all work against the test Google account. Dashboard reads back with no formula errors. |
| C | **Capture pipeline** | Pending captures from every whitelisted source; foreground wait; merge of duplicate alerts; no loss across process death and reboot. |
| D | **Overlay UI** | Quick Log sheet, result pill, action menu, trip mode, split flows, people list, category prediction, fallbacks. |
| E | **Accounting engine** | Waterfall, repayment waterfall, netting, carry-forward, void, idempotency, UUID verification, all against the live sheet. |
| F | **Widget** | Responsive 1x1 widget and pinned shortcut. |
| G | **Offline queue and sync** | Serial queue, backoff, 401 recovery, airplane-mode flush with no duplicates. |
| H | **OEM hardening and live matrix** | Setup health screen, OEM intents, run of the full live test plan on each available device. |
| I | **Documentation closure** | Old documents given a pointer to this pair, test report written. |

HISTORICAL: this plan first deferred testing until the MVP existed. That is no longer the rule. Unit tests run on the
JVM at any time (`testBetaUnitTest`, over a thousand tests at the time of writing), a fix needs a test that fails
before and passes after, and the live-device checks in section 8 are done by hand on a physical phone. Builds,
lint and installs run only when the owner asks (`AGENTS.md`, rule 9).

---

## 7. Sync engine

- One unique WorkManager chain (`ExistingWorkPolicy.APPEND_OR_REPLACE`), network-connected constraint, exponential
  backoff starting at 30 seconds.
- Each queue item is one logical operation (log debit, log credit with waterfall, log payable, repayment, void,
  carry-forward). The use case reads what it needs with `values.batchGet`, computes the plan in pure Kotlin, then
  applies it in one `batchUpdate` where possible, so a partially applied waterfall is not left behind.
- Result handling is the sealed `DataResult` hierarchy (`Success`, `SheetNotFound`, `AuthExpired`, `Network`,
  `Malformed`, `General`). `SheetNotFound` triggers workbook search and re-provisioning. Logical errors (invalid
  amount, unknown reference) go to Needs Review with a plain message; they are not retried forever. Transport
  errors stay queued.
- 401: clear the stale token, one silent re-authorise, then a single reconnect notification.
- The result pill shows the server-style message, for example "Logged Rs 450 for Dinner (2 debts reconciled)".

---

## 8. Live testing method

**Status: deferred.** No test work, offline or live, is required while the MVP is being built. This section
describes the test phase that starts once the MVP is in place. Until then, nothing in sections 8 to 11 gates a
milestone, and agents must not add test suites or report test results as part of build work.

Principle: the app is proven on the physical device against real Google Sheets, not on synthetic mocks.

### 8.1 Environment

- One Android device attached over ADB (USB or wireless), developer options and USB debugging on.
- A dedicated Google test account. The app creates its own test workbook in that account. The production ledger and
  the real iOS workbook are never touched.
- Real Google OAuth, real Drive v3 and Sheets v4 calls.
- Build variants: `debug` for tests, `release` for the release check. Test-only helpers exist only in `debug`.

### 8.2 Getting notifications to the listener

Real payment alerts are used when available. For repeatable tests:

- `adb shell cmd notification post` creates a notification from the shell package. The release whitelist rejects it
  by design, so the `debug` variant adds a debug-only allowlist entry for the shell package and for a small test
  companion app. This entry must not exist in `release`, and a build check fails if it does.
- Test alerts use realistic UPI and bank-debit text with synthetic amounts and payees. Real account numbers and real
  names are never used in tests, logs or committed files.
- A real payment for a small amount is part of the final run, to prove the real apps' notification format.

### 8.3 Test plan

| # | Area | Test | Pass condition |
| :--- | :--- | :--- | :--- |
| 1 | Onboarding | Fresh install, sign in, provisioning | Folder, workbook, template, `Config`, month tab exist; no manual step |
| 2 | Onboarding | Uninstall, reinstall, sign in | Same workbook found; config restored; no duplicate workbook |
| 3 | Auth | Revoke access in Google account, then log an expense | Reconnect prompt appears once; after reconnect the queued item syncs |
| 4 | Capture | One test alert per whitelisted package and the SMS source | Pending capture created with correct amount, payee, account |
| 5 | Privacy | Alerts from a non-whitelisted package, an OTP-style message, a personal messenger | Nothing stored, nothing logged, nothing shown |
| 6 | Trigger | Alert while the payment app is in the foreground | Nothing shown; sheet appears when the app leaves the foreground |
| 7 | Trigger | Alert after the payment app is already closed | Sheet appears immediately with pre-filled data |
| 8 | Trigger | App swiped from Recents, then alert | Capture still created and shown |
| 9 | Trigger | Reboot, then alert | Listener bound without opening the app |
| 10 | Merge | Payment alert then SMS for the same amount | One entry in the sheet; second source merged or flagged |
| 11 | Overlay | Open the sheet over three different apps | No Recents entry; underlying app not interrupted; look matches the design |
| 12 | Overlay | Keyboard entry in amount and payee | Keyboard opens, text accepted, flags reset after |
| 13 | Overlay | Permission denied | Fallback notification and translucent activity work |
| 14 | One tap | Confirm and Save on a pre-filled entry | Row appended to the right table, correct columns, result pill shown |
| 15 | Split | Just Me, Split Equally with 1 and 3 companions, Custom | Columns G, H, I, J, K match section 9.1 of the spec |
| 16 | Widget | Add on the home screen, change grid and icon size | Cell resizes with the launcher; tap opens the menu, not the app |
| 17 | Offline | Airplane mode, log ten entries, reconnect | Ten rows, no duplicates, correct order |
| 18 | Idempotency | Kill the process between append and UUID write, retry | No duplicate row |
| 19 | Waterfall | Settlement with explicit bills, then FIFO, then surplus | Rows, statuses and surplus payable match section 9.4 |
| 20 | Waterfall | Shared bill, one companion pays their share | Companion removed from the cell; others unchanged |
| 21 | Waterfall | Payment from an unknown name | Credit recorded unlinked, `unmatched` message |
| 22 | Repayment | Repay a friend with and without surplus | Payables become `Partial` or `Repaid`; surplus debit row as specified |
| 23 | Netting | Expense with a companion you owe; payable from a friend who owes you | Both directions reduce balances as specified |
| 24 | Carry-forward | Create a new month tab with open debts | `[Carried from MmmYY]` rows, origin `Carried Fwd`, Dashboard gross outflow unchanged |
| 25 | Void | Void a synced row | Row stays, `Void`, zero amounts, no gap, next append lands below |
| 26 | Sort safety | Sort the sheet manually, then update a row | Update finds the row by UUID |
| 27 | Dashboard | Read back every Dashboard cell after each scenario | No `#VALUE!`, `#REF!`, `#N/A`, `#DIV/0!`; KPI numbers equal the hand-computed values |
| 28 | Formula injection | Payee text starting with `=`, `+`, `-`, `@` | Stored as literal text |
| 29 | Config | Add, rename, archive a category; toggle burn exclusion | Dashboard regenerates and still evaluates cleanly |
| 30 | Battery | Leave the phone idle with the screen off for a long period, then alert | Listener still bound; capture created |

Items that need a human (look and feel, the real payment, the widget on the home screen) are tested by the user on
the device with the assistant reading the logs and reading the sheet back. Everything else is automated through ADB
and the Sheets API read-back.

### 8.4 Evidence

For each test: device model and firmware, build id, result, screenshot (`adb exec-out screencap`), a redacted
`logcat` excerpt, and the sheet read-back. Logs never contain notification text, amounts tied to real people, or
tokens.

### 8.5 Fast regression that remains

Pure domain math (split, waterfall, netting, carry-forward) keeps JVM unit tests because they cost nothing and catch
arithmetic regressions immediately. They are a supplement. They do not replace section 8.3 and no milestone closes
on them.

---

## 9. Test report

One table in the repo, updated by every run:

| Device model | Brand and skin | Android version | Test run date | Passed | Failed | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| (filled in per run) | | | | | | |

A brand is listed as supported only after a row with zero failures for that brand.

---

## 10. Rules carried over and rules retired

Carried over from `AGENTS.md` and the earlier plans: whitelist only, no personal messaging apps, no OTPs, no raw
text stored, no secrets or keystores in git, minimal OAuth scope, no `!!`, no emojis, no local machine paths in
documentation, no commit or push without explicit instruction, no mock or stub Google clients in production code,
`allowBackup=false`, `Savings` spelled in the plural.

Retired:

- "Zero live network calls / synthetic fixtures only" (replaced by section 8).
- Deduplication on a fixed 120 second settle delay as the capture trigger (replaced by the trigger rule in spec
  section 4.2; the dedupe merge remains).
- Python-suite pass counts as a release gate (kept only as supplementary regression).
- "Approved for release" style self-certification. Status is the test report in section 9.

---

## 11. Acceptance checklist

Applies at MVP sign-off, not as a gate for individual milestones. Unchecked until demonstrated on the device and
recorded in the test report.

- [ ] Fresh install to first logged expense in under two minutes, no manual sheet setup
- [ ] Alert while paying, sheet appears only after the payment app closes, pre-filled
- [ ] Alert after the payment app is closed, sheet appears immediately
- [ ] One tap saves; row correct in the sheet
- [ ] Works after swiping the app from Recents and after reboot
- [ ] Offline queue flushes with no duplicates
- [ ] Waterfall, repayment, netting, carry-forward, void match the iOS rules
- [ ] Dashboard has no formula errors after every scenario
- [ ] Widget adapts to launcher grid and icon-size changes and opens the menu
- [ ] Setup health screen correct on each tested brand
- [ ] Test report has a clean row for each brand claimed as supported

---

## 12. Milestone A audit (existing code)

HISTORICAL: this is the first audit, written on 2026-11-10 against the code of that day. Many findings below were
fixed in later rounds (for example the simulator receiver was removed, appends use `RAW`, the OAuth scope is
`drive.file` only, month tabs and the yearly workbook are created by the sync). Do not read this table as the current
state. Later reviews and their open findings are in `docs/reviews/`.

Date: 2026-11-10. Method: code reading of the Android source against this plan. **Nothing here was verified on a
device**: no phone was attached over ADB, and the Gradle build could not run in the audit session (it failed with
"Unable to establish loopback connection" before compiling, a local environment issue). Findings are therefore
"confirmed in code", not "reproduced". Re-verify each on the device when fixing it.

Reviewed: manifest, build script, notification listener and processor, overlay activity, main activity, widget,
auth, Drive client, Sheets client, payload mapper, transaction repository, sync and settle workers, sync queue DAO,
dependency container, parts of the settings and expense view models.
Not yet reviewed: `ParseNotificationUseCase` and regex patterns, `CalculateSplitUseCase`, `SheetProvisioner`,
`DashboardFormulaBuilder`, `ConfigSheetManager`, settings, inbox and dashboard screens, Room schema details.

### 12.1 Broken or missing (ordered by impact)

| # | Area | Finding (file) | Plan reference |
| :--- | :--- | :--- | :--- |
| 1 | Sheet writes | Appends default to `valueInputOption=USER_ENTERED`, so a payee such as `=IMPORTXML(...)` is evaluated as a formula (`GoogleSheetsClient.kt`, `appendTableScoped`, used by `appendDebit`, `appendInflow`, `appendPayable`, `appendTransaction`). Must be `RAW`. | Spec 9.2 |
| 2 | Offline queue | A failed sync item is set to `FAILED` and never retried, because `getAllPending` returns only `PENDING` (`TransactionRepositoryImpl.kt` sync loop, `SyncQueueDao.kt`). The repository also returns success when every item failed, so WorkManager never applies backoff. One offline moment strands an expense permanently. | Spec 7.3, test 17 |
| 3 | Month tabs | Only a tab named `Nov26` is created at provisioning (`GoogleSheetsClient.provisionMasterWorkbook`). Sync appends to the current month's tab name without creating it, so from December 2026 every append fails. No carry-forward exists. Workbook name is hardcoded `Finance 2026` with a space (`SettingsViewModel.autoProvisionMasterSheet`), not `FinanceYYYY`. | Spec 9.7, 6 |
| 4 | UUIDs and row safety | Sync writes only columns A to K (`mapTransactionToTable1`), so the hidden UUID columns are never populated. Updates and voids target the cached row number with no UUID check (`updateRow`), so a manual sort in the sheet corrupts the wrong row. | Spec 8.2, 9.3 |
| 5 | Accounting engine | No waterfall, repayment waterfall, netting, quotas or surplus routing. `updateLinkedDebitSettlement`, `appendInflow`, `appendPayable` and the rollover mappers exist but nothing calls them. Inflows, payables and repayments are never created or synced; only debits sync. | Spec 9.4 to 9.7 |
| 6 | Capture timing | The prompt starts immediately when the alert arrives (`NotificationListenerServiceImpl.handleNotification`), which can be during the PIN screen. There is no pending capture, no wait for the payment app to close, and no usage-access check. Starting an activity from the background depends on the overlay-permission exemption, which is fragile on Android 15 and blocked on some OEMs without their own pop-up permission. | Spec 4.2, 4.3 |
| 7 | Credits | Credit alerts are dropped (`NotificationProcessor`, direction filter), so Income / Return is never captured. | Spec 4.1 |
| 8 | Duplicates | On every listener (re)connect, active notifications from the last 5 minutes are processed again, but deduplication looks back only 120 seconds, so older ones become duplicate rows (`onListenerConnected`, `findRecentSimilar`). | Spec 4.2 |
| 9 | Auth | Uses the deprecated `GoogleSignIn` and a blocking `GoogleAuthUtil.getToken`; a consent-required error is swallowed and returns no token, so requests go out unauthenticated and fail with 401. Requests the broader `spreadsheets` scope in addition to `drive.file`. A real OAuth client ID is committed in source (`GoogleAuthTokenProvider.kt`), against `AGENTS.md`. The first 10 characters of every access token are written to logcat. Sign-in lives inside Settings; there is no first-run onboarding. Existing workbook is searched by name across the whole Drive instead of by `appProperties`. | Spec 6 |
| 10 | Test hook in release | `NotificationSimulatorReceiver` is exported and registered in all build types, so any app on the phone can inject fake expenses. It also bypasses the real listener path and launches the sheet itself. | Plan 8.2 |
| 11 | Listener resilience | Only `requestRebind` on disconnect. No boot receiver (the boot permission is declared but unused), no component-toggle rebind, no periodic health check, no usage-access or OEM guidance, no setup health screen. Foreground-service permissions are declared with no service. Errors are swallowed silently (`catch (_: Exception)` throughout the listener), so failures are invisible. | Plan 2, 3 |
| 12 | Overlay look | The sheet is a translucent activity with a solid surface and a dim backdrop: no blur, not an overlay window, not the iOS look. Main activity forces the light theme. App icon is the Android default. Heads-up category buttons are hardcoded to Food & Drink and Groceries instead of predicted. | Spec 3 |
| 13 | Menu and flows | No Choose Action menu, no Income / Return, Friend Paid For Me or Repaid a Friend flows. Trip tags exist in settings only. Category recency prediction and account inference from the alert are not present in the reviewed code. | Spec 3.2, 3.3 |
| 14 | Widget | A 1x1 widget exists with a dynamic icon size, but the size is only applied on Android 12 and later, it does not read the launcher-provided size, and it opens the translucent sheet, not an action menu. Needs a device check. | Spec 5 |
| 15 | Minor | `targetSdk` is 36, while `AGENTS.md` says 35 (decision D5). Settle window is 90 seconds in the worker and 120 in the settings model. The settle worker is mostly redundant because sync already skips unreviewed rows. OTP regex also matches "do not share", which can appear in legitimate debit SMS text. SMS package list lacks OEM messaging apps beyond the three listed. | Spec 4.1 |

### 12.2 Present and plausible (code reading only)

- Package whitelist and privacy blacklist with OTP suppression, own-package filter and amount-plus-merchant merge.
- Zero `!!` operators in `app/src/main` (searched). Five `Log` calls, two of which should be removed (token prefix, raw error body).
- Sealed `DataResult`, typed HTTP error mapping (401, 404, 5xx) in the Sheets client.
- Sheets table-scoped ranges `A3:K`, `M3:T`, `V3:AB`, `insertDataOption=OVERWRITE`.
- In-place void payload (G, I, J set to 0, status `Void`, `[Void] ` prefix) in `SheetPayloadMapper`.
- Single serialised WorkManager queue with `APPEND_OR_REPLACE` and a network constraint.
- Manual dependency injection container; `allowBackup=false`.
- Room entities for accounts, categories, companions, trip tags, preferences, sync queue, transactions.

### 12.3 Recommended fix order

1. Items 1, 2, 3, 4 (data safety and sync correctness). These lose or corrupt data and gate everything else.
2. Item 9 (auth and onboarding), so the live tests can run against the test account.
3. Items 6, 7, 8, 11 (capture pipeline and resilience).
4. Item 5 (accounting engine), then item 13 (flows).
5. Items 12, 14 (overlay look and widget).
6. Items 10 and 15 at any point; item 10 is small and should be done early.

---

## 13. Build, install and setup

Carried over from the earlier setup guide, adjusted for this plan.

- Requirements: JDK 17 or newer, Android SDK with platform 28 and the target platform, `local.properties` containing
  `sdk.dir` (gitignored). The Gradle wrapper is included.
- Debug build: `.\gradlew.bat assembleDebug`. Output: `app/build/outputs/apk/debug/app-debug.apk`.
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Grant listener access from the command line (debug convenience; the app's setup screen does the same by hand):
  `adb shell cmd notification allow_listener com.financeautomation/com.financeautomation.service.NotificationListenerServiceImpl`
- Debug-only shortcuts for the other special permissions:
  `adb shell appops set com.financeautomation SYSTEM_ALERT_WINDOW allow` and
  `adb shell appops set com.financeautomation GET_USAGE_STATS allow`.
- Launch: `adb shell am start -n com.financeautomation/com.financeautomation.presentation.ui.MainActivity`.
- Release build: `.\gradlew.bat assembleRelease`, then sign with `apksigner` using a keystore that is never committed.
  Distribution is by sideload or GitHub Releases, not the Play Store (Google Play Services is required on the device).
- Google Cloud setup (once, by the owner): an Android OAuth client for package `com.financeautomation` with the signing
  certificate SHA-1, the Drive and Sheets APIs enabled, and the test Google account added as a test user. No client id
  or secret goes into source. `.env.example` must not list any credential that the app no longer reads (Phase 1).

Beta testers (friends): each uses their own Google account and gets their own workbook. While the OAuth consent screen
is in testing status, add each tester's Google account as a test user before they install, and expect a periodic
reconnect prompt. Distribute the signed APK by direct sideload or GitHub Releases, and tell testers to follow the in-app
setup health screen on their brand of phone.

## 14. Test-phase material carried over from the earlier plan

Used when the test phase starts (section 8). Nothing here gates a milestone.

- **Sheet append matrix (nine cases):** empty month, asymmetric tables (one table much longer than another), a voided
  row in the middle, a totals row placed below a table by the user, an active filter or sort, a protected range,
  back-to-back appends, a blank gap left by the user, and a full grid. Each must end with correct rows and no overwrite.
- **Real redacted notification corpus:** collect real alerts from each whitelisted app and bank SMS format, with names,
  numbers and references removed, and check that the parser extracts amount and payee from every one. Keep the redacted
  samples in the test fixtures; never commit anything unredacted.
- **Scenario set:** standard UPI debit to one-tap save; dual alert (payment app then bank SMS) merging into one entry;
  cross-source duplicate flagged and voided; in-place void; month-end carry-forward; friend repayment settling a
  debt; formula-injection payee; privacy blacklist and OTP suppression.
- **Boundary ideas:** zero, negative and very large amounts, many decimals, maximum field lengths, date and time serial
  limits, first and last day of a month and year, leap day, emoji and right-to-left text in payees, payee text starting
  with `=`, `+`, `-` or `@`.
- **Combination ideas:** review state by sync state by split mode by source package by account.
