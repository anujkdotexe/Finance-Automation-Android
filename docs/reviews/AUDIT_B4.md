# AUDIT_B4 - Worker B, round 4 (read-only)

Scope: feat/ui-redesign at e0f389e, read through git archive and grep only. Nothing was edited, built or installed.
No real amounts, payees or account names appear below.
Method: code reading. Nothing here was executed on a device. CONFIRMED means the defect follows directly from the
code that I read. SUSPECTED means it depends on Play Services, WorkManager or timing behaviour I could not observe.

## Findings, most severe first

### R4-1  HIGH, CONFIRMED  Rotating the phone (or any configuration change) wipes what was typed in the floating add sheet
- Files: presentation/ui/ModalBottomSheetActivity.kt:284-313 (`handleIntent` is called from `onCreate` every time),
  presentation/viewmodel/QuickLogViewModel.kt:199-215 (`setManualMode` sets `amountText = ""`, `payee = ""`; `loadCapture` rebuilds state from the capture).
- The manifest has no `configChanges`, so the activity is recreated on rotation, dark mode switch, language change or
  font scale change. The ViewModel survives, but `onCreate` calls `handleIntent` again, which resets the amount and
  payee (manual add) or reloads the capture over the edits (captured payment).
- Scenario: user types an amount in the sheet, turns the phone sideways: the amount is empty. Same when the font scale is changed from the quick settings panel while the sheet is open.
- Fix: call `handleIntent` only when `savedInstanceState == null` (or when the ViewModel has not been initialised
  yet), and keep the form fields in `SavedStateHandle` so process death also keeps them.
- Related, same root cause: there is no `rememberSaveable` and no `SavedStateHandle` anywhere in presentation. Every form
  that keeps input in plain `remember { mutableStateOf }` loses it on rotation and process death:
  FriendPaidFlowSheet.kt:93-103 (amount, creditor, what for, category, date, new friend name), RepaidFriendFlowSheet,
  IncomeReturnFlowSheet, TripModeFlowSheet, AddSubscriptionSheet, AccountsScreen, CategoriesScreen, PeopleTripsScreen
  (8 states), TransactionsScreen (6 states), and the dialogs in progress.
- Process death: ViewModel state (Transactions filters, search text, Dashboard month offset in `monthOffsetFlow`,
  quick-log state) is lost as well; the app returns to the current month with no filter.

### R4-2  HIGH, SUSPECTED  The sync token is not tied to the Google account the app shows, and sign out does not sign out
- Files: data/remote/GoogleAuthTokenProvider.kt:76 (`AuthorizationRequest.builder()` with no `setAccount`),
  presentation/viewmodel/SettingsSyncCoordinator.kt `onGoogleSignOut` (clears only the in-memory token cache and the
  stored profile), presentation/screens/drawer/SyncBackupScreen.kt:78-90.
- No code in the project calls `GoogleSignInClient.signOut()` or `revokeAccess()` (grep over the whole tree).
- Scenario A: user signs out in Settings. `getAccessToken()` is account-less, so the next sync (periodic worker or
  save) silently authorizes again with the account Play Services already granted and keeps writing to the sheet. The
  sync path has no signed-in check. The app looks signed out but still syncs.
- Scenario B: user signs in as account B after A. The email stored and shown is B's (from the legacy sign-in picker),
  but the access token comes from the account-less authorization call and may still belong to A.
  The guard then records B as owner of data that is uploaded with A's token.
- Scenario C (Cancel on the switch dialog): the legacy picker has already made B the Play Services "last signed in
  account". `SyncBackupScreen` prefers `GoogleSignIn.getLastSignedInAccount(context)?.email` over its own state, so after
  Cancel the screen shows B as connected while the guard refused B and stored data belongs to A.
- Fix: pass the chosen account to the authorization request (`setAccount`), refuse to sync when no account email is
  stored, call `signOut()` on the Google sign-in client on sign out and on Cancel, and show only the stored email.
- Why SUSPECTED: how Play Services chooses the account for an account-less request when several accounts exist is not
  observable from the code.

### R4-3  MEDIUM, CONFIRMED  The account wipe is not atomic with its re-seeding
- File: data/account/RoomAccountDataStore.kt:23-33. The delete runs in `db.withTransaction`; `prepopulateDefaults(db)` runs after it.
- `prepopulateDefaults` is called only from the database `onCreate` callback (FinanceDatabase.kt:262-266) and from here.
- Scenario: the process is killed (swipe away, low memory, crash) between the commit and the seed. The database now has
  no categories and no accounts, and nothing seeds them again: the add sheet has nothing to pick from until app data is cleared.
- Fix: do the seed inside the same transaction (DAO inserts inside `withTransaction`), or seed whenever categories are
  empty at open.
- Checked: the table list in `WIPED_TABLES` plus `KEPT_TABLES` equals the 17 entity tables (accounts, budgets, categories,
  category_stats, companions, handled_notifications, inflows, lineage, payables, pending_captures, pipeline_events,
  preferences_table, subscription_cycles, subscriptions, sync_queue, transactions, trip_tags). The kept preference keys
  match the real key strings (theme, app lock, hide preview, developer mode, device id, settle delay, reminder time, hints, OEM flag, owner).

### R4-4  MEDIUM, CONFIRMED  After an account switch the subscription reminders stay off until the process restarts
- File: di/AppContainer.kt:563-580. `stopBackgroundWork` cancels `SubscriptionReminderWorker.WORK_NAME` and `WORK_NAME_ONCE`;
  `startBackgroundWork` only re-enqueues the sync worker and the monthly rollover.
- The only other scheduler is the collector in FinanceApplication.kt:127-137, which fires when the reminder time changes.
  The reminder time is a kept key, so it does not change and nothing re-enqueues the work.
- Fix: re-enqueue the reminder worker in `startBackgroundWork` with the stored time.

### R4-5  MEDIUM, CONFIRMED  The notification category chip can log the same capture twice
- File: service/QuickActionReceiver.kt (capture path, around the `captureDao.getById` call). It never checks the capture
  state and marks it done only after `logExpenseUseCase` returns.
- Scenario: two taps in quick succession (or the same broadcast delivered twice) run two receivers concurrently. Both read
  an open capture and both insert an expense.
- Fix: claim the capture first with a conditional update (`UPDATE ... SET state = DONE WHERE id = ? AND state IN (open states)`) and log only when one row changed; on failure put the state back.
- Also: when the capture amount is zero the receiver silently does nothing after the notification was cancelled, so
  the user believes it was logged (LOW).

### R4-6  MEDIUM, CONFIRMED  Screens that can disagree for the same month
Places that compute their own sums instead of calling LivingBurn: TransactionsViewModel.kt:526-527 and 553-554
(Total paid, Your share, day headers), DashboardViewModel.kt:510 (savings), 532 (gross), 548, 554, 557 (owed to me,
I owe), TripSummaryUseCase.kt:47-63, PersonBalance.kt:24-27, SubscriptionsViewModel.kt:69, and the payable maps in
presentation/overlay/OverlayController.kt:206 and ModalBottomSheetActivity.kt:112 (the same code twice).
Disagreements:
1. Transactions "Your share" is the sum of My share over every non-void row of the month. Dashboard living burn also
   drops NEEDS_REVIEW rows, Savings, Transfer and user-excluded categories and subtracts refunds. With any one of those present the two numbers differ for the same month. Neither is labelled as the other.
2. Transactions "Total paid" counts a carried-forward copy: `CalculateSplitUseCase.calculateRollover` creates a row with
   `totalAmount = owed`, `myShare = 0`, so the later month shows money out that never left. `TripSummaryUseCase` removes these copies; the Transactions header does not (TransactionsViewModel.kt:526).
3. Without a search or filter, the header totals use the whole month and ignore the account, person, trip and status
   filters, while the day headers below it follow the filters (TransactionsViewModel.kt:524-527 vs 553-554). With such a filter active the header is not the sum of the day headers.
4. Dashboard savings (DashboardViewModel.kt:504-512) adds Savings rows before the review-state check, so NEEDS_REVIEW
   Savings rows are counted there but are in neither the burn nor the unconfirmed line.
5. Dashboard category rows are keyed by the raw category string (line 508 and 532); Insights and budgets group by
   trimmed lower case. "Food" and "food " show as two Dashboard rows and one Insights slice. The row percentages use the
   gross category total, while the headline is net of refunds, so rows do not add up to the headline when a refund exists (the LivingBurn comment documents this; the UI does not say it).
6. CSV (ExportMonthCsvUseCase.kt:46-72) has no total line and no review-state column, so a spreadsheet sum of "My share"
   includes review rows and excluded categories and will not equal the Dashboard.
Checked and consistent: Dashboard, budgets (`BudgetProgressUseCase`), Insights (`InsightsCalculator`) and the widget
(`MonthBurnSummaryUseCase`) all call `LivingBurn` for the month window, the void rule, review rows, exclusions and refunds. People
"open balance" and the Dashboard owed-to-me total both use PENDING rows only. The month window is Asia/Kolkata everywhere
I checked.

### R4-7  MEDIUM, CONFIRMED  The switch confirmation can leave the app with background work stopped
- File: presentation/viewmodel/SettingsSyncCoordinator.kt:74-82 and OnboardingViewModel.kt:217-226.
  `switchTo` calls `stopBackgroundWork()`, then `wipe()`, then `startBackgroundWork()` with no try/finally. If the wipe throws
  (disk full, database busy), the exception escapes the `launch`, the sync, rollover and reminder workers stay cancelled,
  and the dialog state is never cleared.
- Fix: wrap in try/finally that always restarts the work, report the error to the user, keep the prompt open for a retry.
- Process death while the dialog is open: the pending prompt is ViewModel state and is lost; the email was not saved
  before confirmation, so the app is exactly in the state before the attempt. Safe.
- Process death during the wipe: the delete is one transaction, so either everything is gone or nothing; the owner
  email is written after, and the old owner key is kept, so the next sign in with the new account asks again (with zero
  entries). Safe apart from R4-3.
- In-flight sync (SUSPECTED): `switchTo` does not take the repository's `syncMutex`, so a sync that already read its queue and the old sheet id can finish its request after the wipe.

### R4-8  MEDIUM, SUSPECTED  An install from before the guard, signed out, cannot sign in to its own account without a wipe
- File: data/account/AccountSwitchGuard.kt `decide` (owner blank and data present gives CONFIRM_SWITCH) and
  `adoptSignedInAccount`, which FinanceApplication.kt:58 only runs for an account that is signed in at start.
- Scenario: the user upgraded while signed out with entries still on the phone, then signs in again with the same account: the dialog says the data "would be lost", and Cancel means they cannot sign in at all.
- Fix: when the owner is blank, adopt the last stored email (even if signed out) or compare with the last sheet owner before asking.

### R4-9  LOW, CONFIRMED  Font scale 1.3 and 2.0 on a 360dp screen
- presentation/components/Buttons.kt:76 and 125: `.height(48.dp)` then `defaultMinSize(minHeight = 48.dp)`; the label is
  `maxLines = 1, softWrap = false, ellipsis`. At 2.0 long labels ("Switch account", "Clear voided history", localized text) are cut off, and two-button rows or dialog button pairs lose their text. Fix: `heightIn(min = ...)` and allow two lines.
- presentation/components/ChoiceChips.kt:78 and 144: chips are fixed at 32dp and 40dp tall with `maxLines = 1`; at 2.0 the label
  fills or exceeds the height, and the 32dp chips are below the 48dp touch target. TransactionRow.kt:177 fixes the account chip at 22dp.
- The sheet frame (`SheetScaffold`) keeps the action row outside the scrolling body, so it never covers content, but the
  maximum overlay height is a fixed 760dp (Dimensions.kt:95); on a short landscape window the body can shrink to little
  more than the header at 2.0. The body slot itself does not scroll (SheetScaffold.kt:124-129), each caller must; QuickLogSheet and the four flow sheets do, which I checked by grep for `verticalScroll`.
- Not run on a device; the above is from the code.

### R4-10  LOW, CONFIRMED  After an account switch the old account's figures stay visible for a while
- The widget is not refreshed after `wipe()` (no `MonthBurnWidgetProvider.requestUpdate` call), so it shows the old burn until the next 30 minute run, and notifications already posted (capture heads-up, subscription reminders) stay in the shade. Their actions then find nothing and do nothing.
- Fix: call the widget update and cancel posted notifications after the wipe.

## Write paths while the app is locked (area 4)
- Quick Settings tile (service/QuickSettingsTileService.kt) and the quick-add widget (QuickLogWidgetProvider.kt): both only start
  `EntryActivity`; no data is shown; the tile calls `unlockAndRun` when the device is locked. Add stays available while the
  app lock is on, as decided. Existing captures and transactions are not loaded while locked (`ModalSheetLoadPolicy`).
- Month burn widget: shows only the month name and "Unlock to view" while the lock is on; `loadSummary` is not called.
- Notification chips (QuickActionReceiver) and subscription Yes/Not yet/Skip (SubscriptionActionReceiver) write without an
  unlock. With the lock on, the notification is private with a public version that has no actions, so the chips are not
  reachable from the lock screen; they are reachable once the phone is unlocked. Receivers are not exported.
- Cold start: receivers and the widget read `app.appContainer` and `awaitDatabase()`; all three wrap the work in
  try/catch/finally and finish the broadcast. `MonthBurnWidgetProvider.refresh` has no catch inside, but `requestUpdate` and the worker wrap it.
  I did not find a path that reads before the container is built.

## Checked and found correct
- Plain sign out wipes nothing and keeps the owner, so signing back in as the same account never prompts.
- Cancel on the switch dialog saves nothing (both Settings and onboarding); onboarding holds the picked account in a
  candidate and saves it only after the guard allows it.
- The wipe deletes in one Room transaction and keeps the device id, so a switch does not change the device identity used in Config rows.
- App lock state is in memory and set on cold start, so process death always returns to the lock prompt.
- CSV: Kolkata zone, void rows excluded, formula-injection guard, escaping, two-decimal rounding.
- Trip detail removes carried-forward copies and settles pending and returned amounts consistently.

## Not covered or only partly covered
- Token expiry in the middle of a sync and sign in during a running sync: I re-read the sign-in continuation (`continueSignIn`
  calls `syncPendingTransactions`, which takes the repository mutex, so it serialises with a running sync) but did not re-trace the 401 refresh path beyond what round 2 covered.
- Dialog state on rotation was read in the flow sheets and the quick-log sheet; I did not enumerate every dialog.
- Font scale findings are from the code, not from a screenshot.
