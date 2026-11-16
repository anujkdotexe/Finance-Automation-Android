# Independent Code Review - Finance Automation Android

Date: 2026-11-16. Branch: claude/android-app-code-review-4f293f. Read-only review, no source files were changed.

## 1. Method and coverage

Six reviewers ran in parallel.

| Reviewer | Access | Slice | Coverage |
|---|---|---|---|
| Blind A | code only, no docs or history | service, manifest, parts of repository | about 8 percent of files read fully, rest grep only |
| Blind B | code only | presentation (UI, ViewModels, overlay) | about 65 of 105 files read fully |
| Blind C | code only | domain, service, workers, resources, strings | every file read fully |
| Blind D | code only | tests, build files, R8, tools | about 40 percent read fully, rest sampled |
| Blind E | code only | data (remote, repositories, Room), core, di | PENDING, section 8 is added when it finishes |
| Docs-aware | AGENTS.md, README, docs, git log | whole app against the documents | key flows traced, large files partly read |

Items marked "verified" were re-checked by the lead reviewer with grep. Everything else is reported by one reviewer and not yet independently re-checked. Items marked "uncertain" depend on code outside what the reviewer read.

Not covered by anyone: the Sheets client and provisioner line by line (pending Blind E), repayment, netting, carry-forward and void use cases (docs-aware did not read them, Blind C did), and anything that needs a device. Kotlin unit tests were never compiled or run in this review because the Gradle daemon could not start in the reviewer sandbox.

## 2. User-reported issues and their likely causes

### 2.1 Popup cannot be closed (tapping close or outside does nothing, or it comes back)

Likely causes, in order of likelihood. None is confirmed on the device yet.

1. Dismiss does not reach the database, so the capture stays WAITING and the watcher shows the popup again about 700 ms later. In `OverlayController.kt:255-261` and `:305-308` the code calls `viewModel.onDismiss()` (which launches the DB write in `viewModelScope`) and then immediately `dismiss()`, which destroys the tree owner and clears the ViewModel store (`ComposeOverlayTreeOwners.destroy()`), cancelling that scope before the write runs. The capture never becomes DISMISSED, `ForegroundWatcher` still sees it as WAITING and re-prompts. To the user the popup looks unclosable. Fix: perform the write on a non-ViewModel scope or await it before `dismiss()`.
2. `clearCurrentWindow()` (`OverlayController.kt:387-400`) calls `currentTreeOwners?.destroy()` before `windowManager.removeView(view)` inside one try block. If `destroy()` throws, `removeView` is skipped, but `currentView` is still set to null afterwards. The window stays attached to the screen while the controller believes nothing is showing, so every later dismiss is a no-op and the stuck window cannot be removed until the process dies. Fix: always call `removeView` in its own try/finally and only null `currentView` after it succeeds.
3. The overlay window is created `FLAG_NOT_FOCUSABLE` (`:179`), so the Back key never reaches it. Closing depends only on the scrim tap and the sheet's own close button.
4. Dismiss only works if the scrim receives the tap. Anything inside the sheet that consumes taps leaves the user with no outside-tap route.
5. Date picker in the overlay builds an `android.app.DatePickerDialog` from the application context (`QuickLogSheet.kt:326`, `:382`, overlay context at `OverlayController.kt:240`). That can throw `BadTokenException` and crash or wedge the overlay. This is the main capture flow (uncertain).
6. The "Needs Review" screen cannot show or recover captures (finding C-3), so a dismissed capture has no way back.

Also relevant: the capture is moved to DISMISSED and then appears nowhere (finding C-1), so even a working dismiss loses the entry.

### 2.2 App captured random amounts and entries from unknown sources

Candidate sources found in the code. To pin down the real one the entries need to be matched to a source package or SMS sender.

1. SMS path is wide open. Package whitelist includes system SMS apps, and any SMS from a non-personal sender containing a bank keyword is parsed. Promotional and advisory SMS can still produce a capture (`ParseNotificationUseCase.kt:225-266`). The promotion filter exists but is a regex word list and is not applied identically to the SMS path.
2. Direction errors. A debit SMS that also contains "credited", "received" or "added" is classified as a credit (finding C-2), and a credit is turned into an expense sheet pre-filled with the amount (finding C-4). That produces entries that look like money you never spent.
3. Balance picked as the amount. `RegexPatterns.extractAmount` falls back to the first amount even when every match is preceded by a balance word, so "Avl bal Rs 12,500" can become a Rs 12,500 expense (finding B-5).
4. Replay of old notifications. `onListenerConnected` replays `activeNotifications` (`NotificationListenerServiceImpl.kt:96`) and the dedup check is not atomic (finding B-3). Old alerts still in the notification shade are captured again after every rebind, update or reboot, and can be captured twice.
5. Manual subscription captures. `AppContainer.kt:405-422` inserts a capture with source `manual`, a null amount and state SHOWN when a subscription reminder is answered "Yes". These appear as "needs amount" entries named after the subscription.
6. Beta build ships the notification simulator (`ENABLE_SIMULATOR = true` for beta), gated only by developer mode. If developer mode was unlocked, simulated presets can look like real captures.
7. Wrong payee. First UPI address in the text is used as the payee even when it is the user's own address; credit alerts of the form "from Rahul" give "Unknown Payee".
8. Default account preference is read under the wrong key, so captures land in "Other" instead of the chosen account (verified, finding C-5).

Suggested next step: on the device, open Diagnostics and the pipeline trail, note the source package of each unknown entry, and compare with the list above. Do not clear app data before this is done.

## 3. Critical and high severity findings

IDs: C = needs fixing before further testing, H = high. "Verified" means the lead reviewer re-checked it.

| ID | Finding | Location | Source |
|---|---|---|---|
| C-1 | Explicit dismissal of the Quick Log sheet sets state DISMISSED. Nothing moves it to NEEDS_REVIEW (only the 24 hour expiry does), and the inbox only reads NEEDS_REVIEW. The capture disappears. Spec says dismissal must go to Needs Review. Verified by grep. | `QuickLogTransactionHelper.kt:67-81`, `PendingCaptureDao.kt:36,39,75` | docs-aware |
| C-2 | A debit SMS containing a credit word ("credited to merchant", "if cash not received") is classified CREDIT and no expense is created. Test fixture 24 (SBI ATM) expects the wrong direction, so the test locks the bug in. | `ParseNotificationUseCase.kt:252-254`, `RegexPatterns.kt:50-57`, `sms_fixtures.json` | blind C |
| C-3 | Needs Review screen never shows captures, new-account captures, or duplicate resolution (keep both, keep one). ViewModel has the logic, UI does not use it. The badge counts captures but the screen can say "All caught up". | `ReviewInboxViewModel.kt:41-300`, `ReviewInboxScreen.kt:121-250` | blind B |
| C-4 | Credit alerts are shown and logged as expenses. `kind = CREDIT` is stored but never read by UI, overlay or `QuickActionReceiver`; the heads-up buttons log a debit with no sheet. Docs claim this is fixed. Verified: no consumer of `.kind` in presentation. | `NotificationProcessor.kt:207`, `QuickLogViewModel.kt` loadCapture, `QuickActionReceiver.kt` | docs-aware |
| C-5 | Default account preference read under the wrong key ("default_account" instead of "key_default_account"). Chosen default account is ignored for captures. Verified. | `NotificationProcessor.kt:151` | blind A |
| C-6 | `RegenerateDashboardUseCase` falls back to sheet id 0 if the Dashboard tab is not found or the properties fetch fails, then writes formulas and validation to sheet 0, which is usually a ledger tab. | `RegenerateDashboardUseCase.kt:49,74,83,99` | blind C |
| H-1 | Income, payable and repayment results are ignored in `ModalBottomSheetActivity`: a failed save shows "Recorded", an exception crashes, no double-submit guard. | `ModalBottomSheetActivity.kt:191-239` | blind B |
| H-2 | Dismiss races with ViewModel scope cancellation (see 2.1). | `OverlayController.kt:259-260,305-308` | blind B |
| H-3 | `clearCurrentWindow` can leave a window attached (see 2.1). | `OverlayController.kt:387-400` | lead review |
| H-4 | Insights drill-down is reset: Transactions route clears month and filter after Insights sets them. | `AppNavHost.kt:696-705,785-792` | blind B |
| H-5 | Custom categories cannot be chosen in the Expense and Friend-paid sheets and cannot be used as a Transactions filter (hardcoded `CategoryConstants`). | `QuickLogSheet.kt:414-427`, `FriendPaidFlowSheet.kt:364`, `TransactionsViewModel.kt:301-313` | blind B |
| H-6 | Fake data shown as real: "Synced 2m ago", "Finance Ledger 2026", "HDFC 4921"/"ICICI 1042" fallback accounts, default subscription amount 649.00, due date "20 Dec 2026", "9:00 PM" reminder row that actually edits the notification settle delay. | `TransactionsScreen.kt:262`, `SyncBackupScreen.kt:352,424`, `AddSubscriptionSheet.kt`, `PreferencesScreen.kt:217-249` | blind B |
| H-7 | Preferences "Data / Clear voided history" block is composed inside `if (showAccountDialog)`, so it is unreachable normally. | `PreferencesScreen.kt:286-364` | blind B |
| H-8 | Captures can be stranded in SHOWN with no visible UI when overlay permission is off and notifications are blocked or the heads-up is swiped away (no deleteIntent). Only a restart resets them. | `ForegroundHeadsUpHelper.kt:134-139`, `QuickActionReceiver.kt:~58` | docs-aware |

## 4. Medium severity findings

### Capture pipeline and services
- B-3 Notification dedup is check-then-insert on a multi-threaded scope; replay on reconnect can create duplicate captures. `NotificationProcessor` step 5, `NotificationListenerServiceImpl.kt`. (blind A, uncertain on frequency)
- B-4 One `syncMutex` is held across the whole network sync; edits and one-tap notification actions wait for it, and `goAsync()` receivers can be killed first. `TransactionRepositoryImpl.kt:244-246,433,526,602,628,747`. (blind A)
- B-5 Amount extraction can return a balance as the amount, and gives up if the first match is malformed. `RegexPatterns.kt:116-131`. (blind A)
- B-6 `BootReceiver` does DB work after `onReceive` without `goAsync()`. `BootReceiver.kt:24-70`. (blind A)
- Foreground check only looks back 5 minutes (guide says 10); a payment app foregrounded longer reads as background and the sheet pops over it. `ForegroundWatcher.kt:138-176`. (blind C, docs-aware)
- Watcher start race leaves a capture WAITING until next restart. `ForegroundWatcher.kt:194-310`. (blind C)
- Overlay add failure has no backoff and can retry every 700 ms. (blind C, uncertain)
- "Capture is off" alert (id 9001) is not cancelled after a process restart; the health worker posts a second alert (id 8888) that nothing cancels. `NotificationAccessAlertManager.kt:24,33`, `NotificationListenerHealthWorker.kt:75-88`. (blind C)
- Merchant extraction returns the user's own VPA as payee; credit alerts "from X" give Unknown Payee. `RegexPatterns.kt:137-152`. (blind C)
- Expired captures with a subscription flag become NEEDS_REVIEW rows but do not mark the cycle paid. (blind A, uncertain)
- Spec says "no timer" for captures; code has a 24 hour expiry. Spec also says merchant need not match for cross-source merge; code requires payee compatibility. (docs-aware)

### Accounting and data
- "Saving" throws instead of normalising to "Savings"; the throw sits in the sync path (`SheetPayloadMapper.kt:22,61`) so a user category named "Saving" can fail syncs permanently. `CategoryConstants.kt:46-52`. (docs-aware)
- Money is stored and compared as Double with tolerances up to 0.05; `round2` throws on NaN or Infinity. (blind A)
- `PersonBalance.hasOpenBalance` misses people in multi-companion custom splits, so removing them is not blocked. `PersonBalance.kt:19-24`, `AccountingUtils.kt:155-158`. (blind C)
- Config push stamps `updatedAt = now`, so an old offline edit can overwrite a newer one from another device; deletions are pushed as not deleted for several entity types (uncertain). `SyncConfigUseCase.kt:194-260`. (blind C)
- Subscription "Yes" is not atomic; a double broadcast can log two expenses. `SubscriptionActionUseCase.kt:52-91`. (blind C)
- Renaming an account, category or friend does not update existing transactions or budgets. `SettingsCustomizationCoordinator.kt:305-344`. (blind B)
- Inflow type labels are stored as translated text, so settlements and transfers display as "Income". `IncomeReturnFlowSheet.kt:102-110`, `TransactionRow.kt:212-225`. (blind B, uncertain)
- Split edits on a locked (settled) transaction are silently dropped; editing an old transaction re-tags it with the active trip; split is not recalculated after adding a friend; income and repay sheets capture the default account before accounts load. (blind B)

### UI and flows
- Onboarding saves "Authorized User" as the email, continues sign-in after a cancelled picker, and can lose the consent PendingIntent on rotation. `OnboardingViewModel.kt:97,128,135,143,242-246`. (blind B)
- Deep links to Sync/Backup and Capture screens bypass the route validator. `AppNavHost.kt:796-811`. (blind B)
- Transactions row click opens the detail sheet and launches the edit screen at the same time. `TransactionsScreen.kt:152-155`. (blind B)
- Trip sheet: "Start new trip" is permanently disabled while a trip is active; "People on trip" chips and place field are dead. Friend-paid sheet: date and category inputs are ignored. QuickLog "Void" is never wired. Add Category colour palette selection is never saved. (blind B)
- Many ViewModel actions have no error handling and can crash or leave spinners stuck: `ReviewInboxViewModel`, `TransactionsViewModel.kt:645-651`, `SettingsSyncCoordinator.kt:100-278`, `BudgetSheetStateHolder.kt:72,84`, `DashboardViewModel.kt:282-285,621-646`. (blind B)
- Setup Health "Fix" for Google connection does nothing (callback is null); Diagnostics "Run sync now" broadcasts an action with no receiver. (blind B)
- Subscriptions can only be added: no edit, pause, resume or delete; every card says "per month". (blind B)
- `ButtonPair` calls `require(text.length <= 14)` during composition, a latent crash for any longer or translated label. `Buttons.kt:219-224`. (blind B)

### Privacy and diagnostics
- Google error message text (can echo spreadsheet ids, file names) is stored in Diagnostics and included in the shared compatibility report. `GoogleApiError.kt:111-113`, `CompatibilityReportBuilder.kt:225-232`. (docs-aware, blind C)
- Fatal-crash exception messages are not sanitised for Crashlytics. `CrashlyticsManager.kt:20-30,98-108`. (docs-aware)
- Beta build ships the notification simulator (`ENABLE_SIMULATOR=true`), contrary to the beta plan; the promised release check against the debug whitelist entry does not exist. (docs-aware, blind A, blind D)
- Domain layer imports `data` and `presentation` classes (`GetOpenDebtsForPersonUseCase`, `RegenerateDashboardUseCase` performs raw HTTP, `SyncConfigUseCase`, `CompatibilityReportBuilder`). Breaks the architecture rule in AGENTS.md. (blind C, docs-aware found no `android.*` imports)

## 5. Tests and build

- Time bombs: `WorkbookResolverTest` hardcodes the "Nov26" tab and `TransactionRepositorySyncTest` has about 100 month references while production reads the real clock. Likely to fail from 1 Dec 2026 (uncertain which tests). Several tests mix `System.currentTimeMillis()` with `YearMonth.now()` and can fail in the first minute of a month or on a non-IST machine. (blind D)
- Migrations 7-to-8 through 10-to-11 are never executed in tests; migrations 2-to-7 have no tests; no Room query is ever run against real SQL; there is no `androidTest` source set. (blind D)
- Fake DAOs diverge from Room: `updateReviewState` ignores the category, `getRecentSimilar` always returns empty, so duplicate detection is not exercised. (blind D)
- About 28 tests read source text instead of behaviour, including account-switch safety tests. (blind D)
- The `tests/` folder (715 Python tests and 3 Java harnesses) tests a Python copy of the logic, not the Kotlin app. A run showed 19 failures and 3 errors. No CI exists. (blind D)
- Untested risk: `DatabaseEncryptionMigrator`, `DatabaseKeyProvider`, `RoomAccountDataStore.wipe`, equal-split rounding (100 split three ways), crash payload privacy, `ConfigSheetManager`, `ForegroundWatcher`.
- Build: beta builds silently fall back to the debug signing key when `keystore.properties` is missing (only the release task is blocked); `versionCode` is hardcoded to 1; `okhttp-logging` is shipped but unused; `compileSdk` 36 with AGP 8.8 relies on a suppression flag; R8 rules lack `SourceFile` and `LineNumberTable`; Gson and Moshi keep rules have no matching dependency.

## 6. Low severity

- 8 uses of `!!` (`AccountingPlanJsonAdapter.kt:122`, `RecurringDetector.kt:68,106`, `SyncBackupScreen.kt:255,263`, `OnboardingViewModel.kt:128,135`); no `AppLog` wrapper (about 30 direct `Log.` calls); several empty `catch` blocks (AboutScreen, CapturePermissionsScreen, SyncBackupScreen, ErrorScreens, NotificationAccessScreen, OnboardingScreen, MainActivity, SettingsSyncCoordinator, StorageAvailability, ProfileAvatarManager).
- 21 `collectAsState()` calls and no lifecycle-aware collection.
- Unmanaged `CoroutineScope(...)` in `EntryActivity`, `SubscriptionYesActivity`, `MonthBurnWidgetProvider`, `FinanceApplication`, `GoogleApiError`, `ResultPillOverlay`.
- Database key file is written non-atomically; a kill during first creation can make storage permanently unavailable (`DatabaseKeyProvider.kt:130-140`).
- `ExpressionEvaluator` strips commas (12,5 becomes 125) and can overflow the stack on long unary minus runs. Wording checks use substring matches ("sent" matches "consent"). Subscription matching is too loose (short tokens match any merchant).
- Subscription reminders say "due today" on later days, are consumed when notifications are denied, and hardcode "Amount varies".
- `MonthlyRolloverWorker` retries forever on a permanent carry-forward error.
- Accessibility: small Undo target and no live region on the result pill, chips below 48dp, duplicate contentDescription on the drawer icon.
- Hardcoded English strings in `AppNavHost`, `ModalBottomSheetActivity`, `OverlayActionDispatcher`, `PeopleTripsScreen`, `SyncBackupScreen`, `ForegroundHeadsUpHelper`, `SubscriptionNotifier` and others; about 40 unused strings from an abandoned onboarding checklist and capture screen; placeholder text "Coming in round 4"; "2 items saved" hardcoded; mixed UK and US spelling.
- Stale or voided Undo: voiding two rows quickly restores only the last.
- SDK checks that can never fail with minSdk 28; hardcoded "Asia/Kolkata" in several places next to `DateSerialUtils.SHEET_ZONE`.

## 7. Half-finished, dead and unwired work

Unwired UI: Review inbox capture actions; QuickLog Void; Trip sheet people and place; Friend-paid date and category; Add Category colour; Setup Health Google Fix; Diagnostics Run sync now; Preferences reminder time row; Subscriptions pause and edit; Speed dial entries "Friend settled" and "Income / refund" both open the Income flow.

Unreachable screens: Notification Access, Onboarding, and three error screens (no code navigates to them).

Dead code: `CalculateSplitUseCase.calculateRollover` and `calculateInPlaceVoid`, `ParseNotificationUseCase.inferAccount`, `InflowTransaction` and `PayableTransaction` models with four unused mapper functions, `Companion.defaultSplitRatio`, `CrashlyticsManager.recordNonFatal`, composables `TonalCard`, `PersonDebtRow`, `FieldRow`, `ChoiceChips`, `HomeScreenIconCard`, several ViewModel members (`QuickLogViewModel.clearState`, `TransactionsViewModel.undoSave`, `SubscriptionsViewModel.toggleSubscriptionActive`, others), `NotificationListenerServiceImpl.createNotificationChannel`, unused locals in `RegenerateDashboardUseCase`.

Doc versus code drift:
- README status table says Phase 5 and 6 planned and Room v5; code is Room v14 with all phases built.
- AGENTS.md lists scopes drive.file and spreadsheets; code and decision D7 use drive.file only.
- Phase guide audit table cites wrong evidence (UUID columns, overlay class).
- Docs say agents must not add tests; the repo has 152 test files.
- Beta plan references `oauth.client.id` in `local.properties`; build file does not read it. `.env.example` lists keys the app does not read.
- Spec "insertDataOption=OVERWRITE" versus code writing to a computed row; WorkManager backoff 30 s in docs versus 10 s minimum in code.
- Features present in code but absent from the specs: budgets, Insights, app lock, month-burn widget, subscriptions, CSV export, trip detail, refund handling in net burn, extra Config row types.
- Handoff files (`docs/handoff/FROM_AGENT.md` and others) are gitignored and absent in this worktree, so the last agent report could not be compared with the code.

## 8. Data, remote and repository layer

Reviewer E (blind) read all of `core/`, `data/local`, `data/repository`, `data/account`, security and health, and `data/remote` except about two thirds of `SheetProvisioner.kt` (mostly formatting requests). Nothing was run. No critical findings were confirmed.

### High
- D-H1 Year rollover never creates a new workbook. `getSpreadsheetIdForYearSync(year)` falls back to the global `key_spreadsheet_id`, and `resolveForYear()` returns early whenever that is non-blank. On 1 Jan 2027 it returns the 2026 workbook and the Jan27 tab is created inside Finance2026. `setSpreadsheetIdForYear()` also overwrites the global key for any year, defeating the "current year only" guard. Tests use a strict per-year fake, so this is untested. `PreferencesRepositoryImpl.kt:107-127`, `WorkbookResolver.kt:61-70,143-147,215-219`. Whether one workbook across years is intended is unclear; the docs say Finance{year}.
- D-H2 One throwing queue item blocks the whole sync queue (poison pill). The per-item body is not wrapped; the item keeps PENDING and the next run hits it first. Known throw sources: `CategoryConstants.normalizeCategory()` for "Saving" (also imported verbatim from the sheet at `TransactionRepositoryImpl.kt:1397`), and JSON adapter failures. `TransactionRepositoryImpl.kt:779-1237`.
- D-H3 Plaintext-to-SQLCipher migration recovery cannot run: Room builds lazily, so the encrypted file does not exist when `restorePlainBackupIntoEncryptedDb()` runs. After a failed migration the user starts with an empty ledger, and the unencrypted `finance_automation.db.plain_migration_failed_<ts>` file stays on disk forever. `FinanceDatabase.kt:239-256`, `DatabaseEncryptionMigrator.kt:135-199`.
- D-H4 Year-close upload to Drive uses `multipart/form-data` instead of `multipart/related`, so it very likely returns 400, and `executeYearClose` ignores the result and reports success. `GoogleDriveClient.kt:404-414`, `AccountingSyncExecutor.kt:371-376`.

### Medium
- D-M1 Transient failures become permanent: every retryable failure bumps `retryCount`; at 8 the item is FAILED and only a manual reset revives it. About 2 hours offline with the 15 minute worker can park captures. `TransactionRepositoryImpl.kt:1629-1639`, `SyncQueueDao.kt:448-453`.
- D-M2 Default-account changes never sync (`AccountDao.setDefaultFlag/clearDefaultFlag` do not set dirty or `updatedAt`); the clear-then-set is not one transaction.
- D-M3 Stale token served after an account change: `getAccessToken()` does not compare `cachedFor` with the stored email. `GoogleAuthTokenProvider.kt:148-163`.
- D-M4 Account wipe is not serialized with a running sync or workbook resolution; a sync in flight can write after the wipe. `RoomAccountDataStore.wipe()`, `AccountSwitchGuard.switchTo()`.
- D-M5 Row allocation is scan-then-PUT, so another device, the iOS app or a hand edit between scan and write is overwritten. `GoogleSheetsClient.kt:304-405`.
- D-M6 Drive lookups ignore non-2xx search responses and fall through to creating a second workbook; `findWorkbookForYear` takes `files[0]` with no ordering, and backup copies may match the app property filter. `GoogleDriveClient.kt:142-179,244-278`.
- D-M7 Config can be overwritten on a misclassified read error: any message containing "Config" or "Unable to parse range" is treated as a missing Config tab, which triggers a push of local defaults over cloud config. A 403 or 5xx echoing the range can trigger it. `ConfigSheetManager.kt:169`, `WorkbookResolver.kt:113-116,157-160`.
- D-M8 Accounting op on a month tab whose creation failed is made a permanent failure (tab create result ignored). `TransactionRepositoryImpl.kt:891-894`.
- D-M9 Write-endpoint Google error text (can echo payee or amount) is recorded in Diagnostics, `sync_queue.lastErrorDetail` and the shareable compatibility report, although the endpoints are documented as summary only. `GoogleApiError.kt` parse.
- D-M10 `DatabaseKeyProvider` recovery branch is dead (alias is recreated before the check); a lost Keystore alias or truncated key file leaves the app stuck on "Storage unavailable". `DatabaseKeyProvider.kt:49-111`.
- D-M11 Hard-delete repository APIs (account, category, companion, trip) leave no tombstone and would resurrect on pull. Dormant (no callers).
- D-M12 Two void paths for inflows: the UI path skips `syncMutex` and does not drop a queued credit, so the row can be written then voided; the repository path can leave an orphan row after a partial apply.
- D-M13 Rows deleted on the sheet persist locally (only payables have delete detection); blank-UUID rows import as synthetic `sheet_<tab>_<row>` ids that can map to the wrong row if rows shift.

### Low
- Config upsert appends row by row (about 3 requests per row) and can 429 mid-push; Config mirror caps at 49 rows per column.
- Config `markClean` can mark an edited row clean without pushing it; a cleared active trip is resurrected by pull.
- Year close does not clear the sheet before writing, leaving stale rows; add-sheet results are ignored.
- `DashboardFormulaBuilder.burnFormula` does not escape double quotes in category names, so one such category makes the dashboard batchUpdate fail with 400; only the first 10 categories show.
- `TokenRecoveryInterceptor` attaches the Drive bearer token to every request on the shared client (avatar fetch goes to googleusercontent.com); a host allowlist is safer.
- `DateSerialUtils.fromTimeSerial` rounds, so a serial within half a second of midnight wraps to the same date.
- No migration 1 to 2; rows beyond 1000 do not inherit column formats; unique name indexes are case-sensitive while lookups are NOCASE; `TransactionStatus.fromString` maps unknown statuses ("Partial", "Repaid") to Personal so a later update rewrites them as Personal.
- No `callTimeout` on OkHttp, so cancelling a job waits for the in-flight call; completed pending captures are never pruned (`pruneOldCompletedCaptures` unused).
- `SheetRepairSafetyValidator` blocks delete and clear requests but not sortRange, findReplace, insertDimension or appendCells.

### Dead code in this layer
- `DashboardFormulaBuilder.buildDataValidationRequests` (empty stub); `GoogleSheetsClient.appendTransaction`, `updateLinkedDebitSettlement` (also has a latent "1,200" parses as 0 bug), `appendTransactionDataResult`, `getSheetMetadataDataResult`.
- `ConfigSheetManager.mirrorConfig`, `ensureConfigTabExists`, `buildConfigGrid`, `parseConfigGrid`; eight unused `SheetPayloadMapper` functions; `TransactionRepositoryImpl.applyPlan` duplicates `AccountingSyncExecutor.applyAccountingPlan`.
- About 40 DAO methods with no main caller (for example `LineageDao.updateUuid`, `PendingCaptureDao.getByState`, `SyncQueueDao.getNextPending`, `TransactionDao.updateReviewState`), plus unused `WorkbookResolver.findExisting`, `ConfigSyncCoordinator.pushNow`, `CrashlyticsManager.setOptIn`, `ProfileAvatarManager.getAvatarFile`.

### Checked and sound
Append plus UUID is one atomic batchUpdate with a persisted plan and a re-append guard; carry-forward and void-inflow are idempotent; backup is disabled and the key file is excluded from extraction; the wipe table list matches the Room entities; entities and migrations 2 to 14 line up.

## 9. Verified by the lead reviewer

- C-5 default account key mismatch: confirmed, only `NotificationProcessor.kt:151` uses the unprefixed key.
- C-1 dismissal state: confirmed, `STATE_DISMISSED` is set by `QuickLogTransactionHelper.kt:75` and `NotificationProcessor.kt:181`, while `STATE_NEEDS_REVIEW` is set only in the expiry code at `NotificationProcessor.kt:500-551`.
- C-4 credit kind: confirmed, no `.kind` reader in presentation.
- H-3 window removal order: confirmed by reading `OverlayController.kt:387-400` and `ComposeOverlayTreeOwners.kt`.

## 10. Suggested fix order

1. Capture integrity: C-1, C-2, C-4, C-5, H-2, H-3, H-8, then B-3 and B-5. These cause lost, wrong or phantom entries and the stuck popup.
2. C-3 (make Needs Review usable) so nothing can be lost.
3. C-6 (sheet id 0 write) and H-1 (ignored save results).
4. Add tests that go through production wiring for each fix, fix the time bombs in tests, and add real migration tests.
5. Remove fake placeholder data and unwired controls (H-6, H-7, section 7), or hide them until built.
6. Documentation clean-up and the build items in section 5.

## 11. Device observations (read-only, 2026-11-16)

Checked over adb on the connected phone. Nothing was installed, cleared or changed.

- The app is installed as version 1.0.0 (versionCode 1), first installed 2026-11-12, last updated 2026-11-14. The notification listener is enabled and its service is running.
- Overlay (draw over other apps) and usage access are both allowed.
- The overlay permission record shows the last overlay use about 50 minutes before the check, with a duration of about 12 minutes 50 seconds. That is consistent with an overlay window staying on screen for roughly 13 minutes, which matches the "popup cannot be closed" report and findings H-2 and H-3. It is a hint, not proof.
- At the time of the check no app window was on screen, so the stuck state had already cleared (the process had likely been restarted).
- The app log contained no overlay or pipeline lines, so the cause of the unknown entries could not be read from the log. The pipeline trail and the Needs Review list inside the app (Diagnostics screen) are the next place to look. Notification shade at the time held items from system UI, Google search and Truecaller only; none is a whitelisted payment package.

### 11.1 Diagnostics screen read from the phone (13:09 to 13:10 local)

The private app files cannot be read over adb (the build is not debuggable), so the Diagnostics screen was read on screen instead.

- Stuck popup evidence: the trail shows four PROMPT USER_DISMISSED events within about 11 seconds (roughly 3 to 6 seconds apart) with no OVERLAY_SHOWN between them, and the daily funnel shows OVERLAY_SHOWN 2 against USER_DISMISSED 10. Repeated close taps were recorded while no new overlay was shown, which fits a window that stayed attached after dismiss (finding H-3) and capture state not updating (H-2).
- Unknown entries: the Diagnostics screen exposes the notification simulator (developer mode is on) with four presets: PhonePe "Debited INR 120.00 to Bean & Brew Cafe", Paytm "Paid Rs 85.00 to Quick Mart Grocery", Bank SMS "debited for INR 250.00 ... Corner Bakery", and "Amount not found test" ("Payment completed successfully to Corner Store"). If the unknown entries have these amounts or names, they are simulator presets, not real captures.
- Funnel: SYNC QUEUED 63 against SYNC SUCCESS 39 for the day; CAPTURE DONE 2; USER_SAVED 3.

## 12. Follow-up device checks and confirmations

### 12.1 Unknown entries explained
The two transactions this month (50 at 12:10 pm and 69 at 12:11 pm, Food & Drink, account Other, Personal) line up with the app's own quick-entry screens in the usage log (`ModalBottomSheetActivity` 12:09:53, `EntryActivity` 12:10:58 and 12:11:16) and were created by a friend while testing. No payment-app or SMS notification events were logged in the retained window (about the last day). Section 2.2 stays as the list of code-level ways wrong entries can appear, but none of them is shown to have fired on this phone.

### 12.2 Process exits and crashes (Android exit-info, crash buffer)
- No app crash, ANR or native crash appears in the exit history or the crash log buffer.
- The process was killed by the system 8 times between 2026-11-15 00:47 and 2026-11-16 04:47 (reason "other kills by system", vendor codes 2003, 2033 and 6010, state empty). This is the phone maker's background killer (Realme RMX3998, Android 16). The capture feature depends on the notification listener staying bound and rebinding, so these kills are a real risk to capture reliability. Setup Health and the OEM hardening steps should be checked on this device (battery optimisation off, auto-start allowed, app locked in Recents).
- Several earlier exits are "package updated" from reinstalls on 2026-11-14.
- The app writes almost nothing to the device log (no app lines at all during a full session), which is good for privacy but means problems on a device can only be diagnosed from the in-app Diagnostics screen and the compatibility report.

### 12.3 Privacy policy and open source licences are links only
Confirmed in code. `AboutScreen.kt:305-312` and `:357-364` open `https://financeautomation.app/privacy` and `https://financeautomation.app/licenses` in the browser. There is no in-app screen, no bundled policy or licence text (the only asset is `oem_routes.json`), and no licence-reporting plugin in the Gradle files. Both handlers wrap the call in an empty `catch`, so if the page does not exist or no browser is available the tap does nothing and the user is told nothing. Whether that domain exists was not checked. For a financial app that reads SMS and notifications this is a release blocker: add an in-app privacy screen (what is read, what is stored, what leaves the device, the Crashlytics opt-in) and an open source licences screen (for example generated from the dependency list), and make the link failure visible. `OnboardingScreen.kt:218` has the same issue with `/help`.
