# AUDIT_B5 - Worker B, round 5: regression review of fafdabe..5408376 (read-only)

Method: I read the diffs of the 14 commits (811b8bb to 5408376) and the resulting files at 5408376 through git diff and
git archive. Nothing was edited, built or run, so compile errors can only be judged by reading. CONFIRMED means the defect
follows from the code I read. SUSPECTED means it depends on behaviour I could not observe.
No real amounts, payees or account names appear below.

## Findings, most severe first

### R5-1  MEDIUM, CONFIRMED  "Clear voided history" and "Discard" on a failed void can bring a voided row back to life
- Files: data/repository/TransactionRepositoryImpl.kt:576-648 (`scanVoided`, `clearVoidedHistory`, `discardQueueItem`),
  reconcile at ~1304-1357.
- `scanVoided` skips a voided row only while a queue item for it is PENDING or IN_PROGRESS. A voided row whose void
  queue item is FAILED (the void never reached the sheet) is counted as deletable, and `clearVoidedHistory` deletes the row
  and its queue items (`deleteByTransactionId`). The sheet still shows the row as a normal, live entry. The new reconcile guard
  skips only sheet rows whose text starts with "[Void]" or whose status is Void, so the next sync imports that live row as a new
  expense and the total goes up by an amount the user had voided.
- Same mechanism for `discardQueueItem` on an OP_VOID item: the local row stays VOID, the queue is empty, and reconcile
  (which overwrites a local row that has no queue item and no pending edit) writes the sheet's live values over it.
  So "discard is final" does not hold for a void.
- Fix: delete a voided row only when its void is known to be on the sheet (no queue item of any status for that row, and a sync
  state that means delivered); count FAILED ones as skipped with the existing message. For a discarded void, keep the row out of
  reconcile (a local tombstone) or refuse the discard and say why.
- Also (SUSPECTED): the delete is not conditional. A void undone between `scanVoided` and the transaction would still be deleted. Use `DELETE ... WHERE id = ? AND status = 'Void'`.
- Checked and fine: inflows and transactions are the only tables touched; non-void rows are never in the id lists; the sheet is
  never written; counts shown at confirm come from a fresh scan under the sync mutex.

### R5-2  MEDIUM, SUSPECTED  A void whose row is "not found" is now treated as delivered
- File: TransactionRepositoryImpl.kt ~1175-1190. `findRowByUuid(spreadsheetId, tabName, "AC", id)` looks only in the tab the app
  computes for the row. If the row lives in another month tab (the date was edited across a month, or the tab name differs), the void is
  now dropped as "nothing to void" instead of failing loudly. With R5-1 the next reconcile then restores the row.
- A read error is now retried (good); only a clean "not found" is treated as done.
- Fix: look in every month tab before concluding the row is absent, or compare with the full snapshot.

### R5-3  MEDIUM, CONFIRMED  A signed-out app still posts the "reconnect Google" notification
- File: data/remote/GoogleAuthTokenProvider.kt, `refreshAccessToken` (the `else` branch calls `postReconnectNotification()`), together with the new
  `NoAccount` result in `requestAuthorization`.
- Scenario: signed out with queued items and a stored workbook id. A periodic sync calls `getAccessToken()`, which returns null for NoAccount, the request goes out
  without a bearer header (TokenRecoveryInterceptor), gets 401, and the interceptor calls `refreshAccessToken()`. That also gets NoAccount, takes the
  `else` branch and posts the notification (at most one per half hour). The comment on `requestAuthorization` promises "no notification".
- Fix: return early from `refreshAccessToken` for NoAccount without a notification, and make the sync entry points skip (not fail) when no account is stored.

### R5-4  MEDIUM, CONFIRMED  Cancelling the Google picker on the Setup page shows a raw error
- File: presentation/screens/OnboardingScreen.kt:129. The picker result handler always ends with `viewModel.startSignIn()`, also when the
  user backed out (the exception is swallowed). With no candidate and no stored email, `requestAuthorization` returns
  `Failed(AuthExpiredException("No Google account is signed in"))`, which step 1 shows as an English error text (`res.exception.message`).
  Before this change an account-less request would have started the consent flow.
- Fix: call `startSignIn` only when the picker returned an account, and map NoAccount to a string resource.

### R5-5  MEDIUM, SUSPECTED  No way to choose another account on the Setup page once an email is stored
- File: presentation/viewmodel/OnboardingViewModel.kt:64-79 (`withPreferences` marks step 1 complete when an email exists) and OnboardingScreen.kt:280-300
  (the sign-in card exists only while step 1 is not done).
- Since the token is now bound to the stored email, an account that was removed from the phone, or a consent that was revoked, makes the workbook step fail
  with no button to pick another account on the first-run Setup page (the account menu with Sign out is not there yet). The email is also saved before consent, so denying consent still shows step 1 as done.
- Fix: put "Use a different account" on the step 1 card, and save the email only after a token was obtained.

### R5-6  MEDIUM, CONFIRMED  Transactions header: the claim "sum of the rows below" holds only in some cases
- Files: presentation/viewmodel/TransactionsViewModel.kt (header block, ~537-548) and domain/usecase/LivingBurn.kt (`sumOfCounted`, `totalPaid`).
- Small fixture, computed by hand: one split expense (my share 50 of 100), one Savings row (500), one review row (30), one refund of 20.
  Dashboard burn = 50 - 20 = 30. Unfiltered header "Your share" = `LivingBurn.total(...)` = 30 (parity holds). The day header = `sumOfCounted` of the day's rows = 50, so
  the header (30) is not the sum of the day headers (50) when a refund exists.
- Filtered: category = Savings shows "Your share" 0 and 0 on every day header while the visible rows have shares, because `counts` drops Savings, Transfer and excluded categories.
  Review rows are listed but add 0 to the day header.
- Fix: decide the rule on the screen. Either label the figure "Living burn" when it uses the burn rule, or sum the visible rows' My share when a category or status filter is active.
- Related, LOW: the Dashboard row for a category sums trimmed, case-folded names (`LivingBurn.byCategory`), but the Transactions filter compares
  `txn.category.equals(filter.category.trim(), ignoreCase = true)` (TransactionsViewModel.kt:422) with the untrimmed stored value. A row stored with a trailing space is in the Dashboard figure and missing from the list it opens.
- Checked: `values[9]` in the combine is the categories flow (index counted: transactions, inflows, month, query, filter, queue, scope, syncing, budgets, categories);
  "Total paid" drops carried-forward copies in both the header and the day header; no screen other than the ones listed still sums My share on its own except trip and people totals, which use
  their own use cases on purpose.

### R5-7  LOW, CONFIRMED  Capture claim: a kill between claim and insert loses the payment
- File: service/CaptureClaim.kt, service/QuickActionReceiver.kt. The claim moves the capture to DONE first and logs after. A failure or exception restores
  the previous state (good, tested). A process kill between the two leaves the capture DONE with no expense, and the notification is already cancelled.
- Fix: insert the expense and mark the capture DONE in one database transaction, or give the expense an id derived from the capture id so a retry is idempotent.
- SUSPECTED: the floating sheet saves a capture without using the claim, so a chip tap and a sheet save on the same capture can still both insert.
- Checked: the state strings in `claimForLogging` equal the `STATE_*` constants; DISMISSED and DONE are refused; the claim reads and writes in one Room transaction.

### R5-8  LOW, CONFIRMED  Account switch leftovers and rough edges
- RoomAccountDataStore.wipe deletes the database rows and non-kept preferences, but cache/exports (CSV of the previous account) stays until the next export or app start; the
  cleanup runs only then.
- `afterSwitch` calls `NotificationManagerCompat.cancelAll()`, which also removes the persistent notification-access alert until the next health check.
- A different email with no data still shows the confirmation ("removes the data on this phone"), because the owner is set. The wipe is still right (it clears the old account's sheet ids), but the text is wrong for that case.
- An install that was already signed out before this update, still holding data and with no recorded owner, is asked to confirm even for its own account (no last email exists yet).
- `signOutGoogle` waits on `signOut().addOnCompleteListener` with no timeout; if the task never completes, the stored email is never cleared.

### R5-9  LOW, SUSPECTED  SavedStateHandle writes from background threads
- TransactionsViewModel.bindSavedState (3 collectors) and DashboardViewModel.bindSavedState write to the handle from `Dispatchers.IO`. Depending on the lifecycle version the handle's map is
  not synchronized. Values are small (Int, String, enum name), so the size limit is not an issue. A restored month offset is relative to today, so a restore a month later shows a different month.

### R5-10  Tests that do not test what is claimed
- NavigationRoutesTest and ControlSizingTest check source text with string search, not behaviour. The "no bare navigate" regex matches only
  `navController.navigate(AppRoutes.X)` in one exact form; another form passes. They protect against a revert, not against a wrong back stack.
- AccountSwitchGuardTest uses a fake store; the real wipe (`DELETE FROM preferences_table WHERE key NOT IN (...)`, seeding inside the transaction, the table list) never runs in any test. The column name `key` and the table names are right
  (I checked them against the entities), but a runtime SQL error would only appear on the phone.
- CaptureClaimTest runs the interface's default `claimForLogging` on a fake DAO, so the Room transaction is not exercised.
- I did not read TotalsParityTest and ParityFixture in full.

## Checked and found correct
- Compile and wiring by reading: every `R.string.*` used in main exists (scripted comparison of all references with all definitions); the format arguments of `clear_voided_counts`, `clear_voided_done`,
  `clear_voided_skipped` and `account_switch_pending` match their call sites; `SettingsViewModel` and `OnboardingViewModel` factories receive `accountSwitchGuard` from MainActivity;
  `startBackgroundWork` is a suspend lambda and `getSubscriptionReminderTimeSync` exists; AppNavHost imports `NavHostController`; the new helpers are in the package that MainActivity uses; no manifest or receiver/worker constructor changed in this range.
- Auth, first install: the email is saved after the guard allows it and before the request, `setAccount(Account(email, "com.google"))` is built, `NeedsConsent` returns a PendingIntent that the Activity launches, and the second request after consent returns a token. Background worker with no Activity: same request with `isForeground = false` posts the reconnect notification as before. Sign in after sign out with the same account is not questioned (last email).
- Account switch paths: first install, same email in another case, different email with data, cancel (nothing saved; Settings also signs the picker account out), failed wipe (work restarts, owner unchanged, message shown), kill during the wipe (one transaction including the seed, owner written after) behave as described. Kept preference keys match the real keys; per-account tables are all in the wipe list.
- rememberSaveable: all saved types are String, Boolean, String? or an enum (Serializable); dialogs and sheets that leave the composition start empty on the next open; the quick-log guard keeps unfinished manual input only for the same activity instance, so a new sheet starts clean.
- Navigation: `goBackOrDashboard` uses `previousBackStackEntry`, which skips the graph entry; `goToDashboard` pops to the existing Dashboard or clears the stack and starts at the Dashboard (Onboarding is not left below); external routes now enter through `navigateToRoute`; the transactions route is percent-encoded with `+` mapped to `%20`.
- Widgets and totals after clearing voided history: voided rows were already outside LivingBurn and the widget summary, so numbers do not move.

## Not covered
- Nothing was compiled or run; a compile error in a file only touched by an edit script can still exist.
- Widget, tile and shortcut entry points were not re-traced; none of their files changed in this range except QuickActionReceiver.
- TotalsParityTest, ParityFixture, DiscardIsFinalTest and WorkbookRecoveryTest contents were not read line by line.
- The WorkbookResolver change (a 403 or 404 on the stored workbook leads to a Drive search and then a fresh workbook) was read: a 403 caused by a disabled Sheets API is also treated as inaccessible, so the resolver would try to create a new workbook (which then also fails); I found no data loss path but did not test it.
