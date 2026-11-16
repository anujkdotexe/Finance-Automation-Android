# AUDIT_B: independent read-only audit of feat/ui-redesign

Scope: the checkout of branch feat/ui-redesign (the HEAD file says feat/ui-redesign; I did not run git log, so the exact commit was not recorded).
Method: read and grep only. Nothing was built, run, edited or installed in the shared checkout. No real amounts or payees were read or recorded.
Paths are repository-relative. Line numbers are from the files as read; they drift if the branch moves.

Legend: CONFIRMED = I traced the code path end to end. SUSPECTED = the code reads as defective but I did not prove the runtime behaviour (platform behaviour, data-dependent, or I did not read every caller).

Summary of severities (finding IDs in sections 1 to 8): 2 blocker, 8 high, 22 medium, 17 low. The table below lists the blockers and the most serious highs; the high finding D-2 is in section 3. IDs in the table (B-1, H-1 and so on) map to section IDs as: B-1 = S-1, B-2 = D-1, H-1 = S-2, H-2 = S-3, H-3 = S-4, H-4 = A-1, H-5 = A-3, H-6 = C-1, H-7 = C-2.

---

## 0. Top findings (read these first)

| # | Sev | Area | One line |
|---|-----|------|----------|
| B-1 | blocker | Sync | An expense saved from the sheet or overlay ("Saved, will sync") never triggers a sync, and no periodic sync job is scheduled except in BootReceiver. A fresh install can sit unsynced indefinitely. |
| B-2 | blocker | Database | The in-memory fallback database used when the Keystore is unavailable is cached as the process singleton, accepts writes, and nothing in the app reads the "storage unavailable" flag. Expenses are shown as saved and are lost. |
| H-1 | high | Sync | HTTP 429 (and 403 rate limit, 408) is classified as a permanent failure: the queue item is set to FAILED with retry count 999 and the transaction is pushed to Needs Review. |
| H-2 | high | Sync | Token failures (401 after refresh fails) are also permanent: every queued item is marked failed in one pass instead of pausing the queue. |
| H-3 | high | Sync | reconcileWithSheet runs after every sync that had no transport failure and overwrites local rows with sheet values, including rows with an unsynced local edit or a permanently failed update. Local edits are silently lost. |
| H-4 | high | Accounting | Undo on the result pill for Repaid and Friend-paid does nothing (not found), and Undo on Income after the (immediate) sync leaves the sheet row and the settled debts in place. |
| H-5 | high | Accounting | Carry-forward is skipped whenever any "[Carried from ...]" row already exists in the target tab, but the rollover worker runs daily from day 25. Debts incurred on days 25 to 31 after the first run are never carried. |
| H-6 | high | Capture | Non-SMS whitelisted apps (Google Pay, PhonePe, Paytm and so on) are parsed without requiring success wording. A promotional notification that contains a rupee amount becomes a debit capture. |
| H-7 | high | Capture / privacy | The OTP regex includes "do not share". Many real bank debit SMS carry "do not share OTP" boilerplate, so real expenses are dropped silently. |

---

## 1. Accounting

### Findings

**A-1 (high, CONFIRMED) Undo does not work for payable and repayment, and is incomplete for income**
File: data/repository/TransactionRepositoryImpl.kt:446-484 (undoRecentSave), presentation/overlay/OverlayActionDispatcher.kt:59, 97, 136.
Problem: undoRecentSave looks the id up in transactions, then inflows, then returns Error("Transaction not found"). logPayable (id pay_*) and logRepayment (id rep_*) create neither a transaction nor an inflow row, so Undo always fails. The pill still shows an Undo and the queue item still syncs.
For income (inflow), the Undo branch deletes queued rows and marks the local inflow VOID but never enqueues OP_VOID_INFLOW. logCredit calls onTriggerSync immediately, so inside the 8 second window the credit has normally been written to the sheet already (inflow row plus the settlement edits that marked friend debts Settled). The sheet row and the settled debts stay; only the local copy is voided.
Scenario: user records a friend repayment of an amount, taps Undo within 8 seconds. Sheet keeps the inflow and the debts stay Settled. Dashboard (local) shows the inflow void.
Fix: in undoRecentSave, if the queue item for the id is already gone (synced) enqueue OP_VOID_INFLOW via InflowRepository.voidInflow (which also needs to reverse the settlement edits, see A-2). For payable and repayment, either remove the Undo affordance or implement delete-from-queue when pending and a reversing op when synced. Take syncMutex around the check-and-delete so a sync in flight cannot race.

**A-2 (medium, SUSPECTED) Void of an income row does not reverse the settlement it caused**
File: data/remote/AccountingSyncExecutor.kt:150-170 (executeVoidInflow).
Problem: it zeroes the inflow amount and prefixes the reason, nothing else. The debts that logCredit settled (J set to remaining, K set to Settled, H edited) are not reopened.
Scenario: friend pays 500 against two debts; user voids the income as a mistake; the sheet shows the debts Settled and no inflow.
Fix: store the SettledRef list on the inflow (linkedDebitRefs is already written in the row) and restore the previous J, K, H values on void, or document that void of a settlement is not supported and block it in the UI.

**A-3 (high, CONFIRMED) Carry-forward drops items after the first run**
File: domain/usecase/CarryForwardUseCase.kt:123-126, data/remote/AccountingSyncExecutor.kt:320-322, service/worker/MonthlyRolloverWorker.kt (day >= 25).
Problem: if any target-tab debit or payable starts with the "[Carried from ...]" prefix the whole carry is skipped (skippedAlreadyCarried). The worker enqueues a carry every day from the 25th. The first run carries what is open then; anything incurred on days 25 to 31 is never carried. The same skip applies when the first transaction of the new month creates the tab and enqueues a carry (TransactionRepositoryImpl.kt:850-868).
Scenario: friend owes on the 28th, first rollover ran on the 25th. At month end the debt is still Pending in the old tab and absent from the new tab; the new month's "owed to me" under-reports.
Fix: make carry-forward per-row idempotent (skip only the source rows that already have a lineage record), not per-tab. Run the final carry on the first sync of the new month, not from day 25.

**A-4 (medium, CONFIRMED) Date serial and time serial use the device time zone, not Asia/Kolkata**
Files: domain/usecase/LogExpenseUseCase.kt:58-59, domain/usecase/ParseNotificationUseCase.kt:262-263, data/remote/AccountingSyncExecutor.kt:104-105, 182-183, 246-247, core/utils/DateSerialUtils.kt:78-100 (default zone = ZoneId.systemDefault()).
Problem: the month tab (formatMonthTabName), the year selection, the dashboard window and living burn all use Asia/Kolkata, but the date and time serial written to the sheet use the device zone. A phone set to another zone (travel, a wrong manual setting, an emulator) writes a serial for a different calendar day than the tab it lands in. Rows near midnight show the wrong date.
Fix: default DateSerialUtils zone parameters to a single constant (Asia/Kolkata) and remove systemDefault from the signatures.

**A-5 (medium, SUSPECTED) Custom split with no companions and a share below the total creates a phantom debtor named "Friends"**
File: domain/usecase/CalculateSplitUseCase.kt:90-96.
Problem: with no companions and owed above 0 the companion text becomes the literal "Friends", status Pending, owed greater than 0. The Dashboard then lists "Friends" as a person who owes money, and the settlement waterfall can never match a settler.
Scenario: LogExpenseUseCase called with CUSTOM, a share below the total and an empty companion list (any caller that does not enforce a companion selection). I did not trace the Compose sheet to see whether it prevents this.
Fix: reject (Result.Error) a custom split with owed above 0 and no companions.

**A-6 (medium, CONFIRMED) Per-person receivables assume an equal split and the Dashboard and Settings use different keys**
Files: presentation/viewmodel/DashboardViewModel.kt:486-488 (key = raw name), presentation/viewmodel/SettingsCustomizationCoordinator.kt:64-69 (key = lowercased name).
Problem: owedToMe is divided by the number of companions regardless of the split mode; a custom split with unequal shares shows wrong per-person balances. The Dashboard keeps "Alice" and "alice" as two people, Settings merges them.
Fix: store per-companion shares (or recompute from the plan) and use one normalising key function shared by both screens.

**A-7 (medium, SUSPECTED) Refunds are income, not a reduction of living burn**
Files: domain/usecase/LivingBurn.kt:47-49, presentation/viewmodel/DashboardViewModel.kt:444-458, data/remote/DashboardFormulaBuilder.kt (no refund or net term).
Problem: "Refund / Return" inflows are counted as Income and never netted against the category they reverse. The brief says "net burn calculation rules from the iOS app". I could not compare with the iOS formulas. If iOS nets refunds, the app burn is overstated and differs from the sheet Dashboard.
Fix: confirm the iOS rule; if netting is required add a refund-category link and subtract it in LivingBurn.

**A-8 (medium, CONFIRMED) Living burn counts rows that are not on the sheet**
Files: domain/usecase/LivingBurn.kt:47-49 (no reviewState check), data/repository/TransactionRepositoryImpl.kt:770 (sync skips NEEDS_REVIEW), TransactionRepositoryImpl.kt:786, 824 and similar (failed items are set NEEDS_REVIEW).
Problem: transactions in NEEDS_REVIEW (expired captures converted at 24 hours with category Other, and rows whose sync permanently failed) count toward the app's burn but are not on the sheet. App and sheet disagree until the user resolves them.
Fix: either count them with a visible "unconfirmed" total or exclude them from LivingBurn and show them only in the Needs Review count.

**A-9 (low, CONFIRMED) Repository entry points do not validate amounts**
File: data/repository/TransactionRepositoryImpl.kt:256-361 (logCredit, logPayable, logRepayment).
Problem: no check for amount above 0, finite, or a sane maximum. Validation relies on each Compose sheet. A zero or negative amount would be queued and written; SettlementWaterfallUseCase appends the inflow with that amount.
Fix: validate in the repository (require amount.isFinite() and amount > 0).

**A-10 (low, SUSPECTED) FIFO order of the settlement waterfall depends on tab order**
File: domain/usecase/SettlementWaterfallUseCase.kt:95-120.
Problem: the "oldest first" waterfall iterates workingDebits in snapshot order, not by date. If the snapshot returns tabs in sheet order and a tab was reordered or created out of order, an older debt may be settled after a newer one.
Fix: sort by (date serial, time serial) before the loop.

### Checked and found correct (Accounting)
- Just Me: My share = Total, Owed 0, Companions "Personal", Status Personal (CalculateSplitUseCase.kt:31-41, resolveStatus 117-135 gives Settled only when netting offset is positive).
- Split Equally: share = round2(total / (N + 1)), owed = total - share; the two always sum to the total (47-69). N is computed from tokenised, trimmed names, so "A and B" counts as 2.
- Custom split: the share is clamped to [0, total] and owed is computed as the remainder, so the parts always sum (76-108).
- Rounding: AccountingUtils.round2 uses BigDecimal.valueOf with HALF_UP; NaN and Infinity throw and are caught in LogExpenseUseCase (Result.Error), so they cannot be persisted.
- Category "Savings": CategoryConstants.normalizeCategory throws on "Saving" and maps savings, investment, sip, mutual fund to "Savings"; LogExpenseUseCase and the sheet mapper (SheetPayloadMapper.kt) both normalise.
- Void: calculateInPlaceVoid zeroes total, share and owed; LivingBurn.counts excludes VOID; the CSV export and Dashboard income exclude VOID.
- Future-date guard: present in LogExpenseUseCase, insertTransactionWith, updateTransaction, logCredit, logPayable, logRepayment and QuickLogViewModel. Paths without it are system-generated (sheet reconcile import, carry-forward rows, expired captures which are always at least 24 hours old).
- Living burn is one definition (LivingBurn) shared by Dashboard, budgets and widget; month windows use Asia/Kolkata with exclusive end.

---

## 2. Sync and sheets

**S-1 (blocker, CONFIRMED) Saving an expense does not start a sync, and the periodic job is only scheduled on boot**
Files: data/repository/TransactionRepositoryImpl.kt:203-232 (insertTransactionWith never calls onTriggerSync), service/BootReceiver.kt:45 (the only call to SyncTransactionsWorker.enqueuePeriodic), service/worker/SyncTransactionsWorker.kt:116-150, presentation/viewmodel/QuickLogViewModel.kt (message "Saved ..., will sync", no sync call), FinanceApplication.kt (schedules rollover, listener health, pipeline health, subscription reminder; not the sync worker).
Problem: grep for every caller of syncPendingTransactions and enqueueExpedited shows: manual Sync now (Dashboard, Settings, Transactions, Review), credit/payable/repayment/carry/void-inflow (via onTriggerSync), the notification quick-action receiver, the subscription receiver and the monthly rollover worker. A normal expense saved from the sheet or overlay is only queued. The periodic job is registered on BOOT_COMPLETED or MY_PACKAGE_REPLACED, so a freshly installed app has no scheduled sync until the phone reboots or the app is updated.
Scenario: install, onboard, log expenses all day, never tap Sync now. The sheet stays empty. Process death also loses the in-process coroutine that onTriggerSync launches (no WorkManager, no retry).
Fix: call SyncTransactionsWorker.enqueueExpedited after every queue insert (insertTransactionWith, updateTransaction, voidTransaction, logX) and schedule enqueuePeriodic in FinanceApplication.onCreate with ExistingPeriodicWorkPolicy.KEEP. Replace the in-process onTriggerSync coroutine with the same worker so retries survive process death.

**S-2 (high, CONFIRMED) Rate limit responses are treated as permanent failures**
Files: data/remote/GoogleSheetsClient.kt:47-61 (createHttpException: 429 falls into the else branch as GoogleSheetsApiException(429)), data/repository/TransactionRepositoryImpl.kt:545-557 (isTransportOrServerError accepts only 500..599 for that exception).
Problem: 429, 403 with reason rateLimitExceeded or userRateLimitExceeded, and 408 are retryable but are classified non-transport. The queue item is set FAILED with retryCount 999 and the transaction goes to NEEDS_REVIEW (for example TransactionRepositoryImpl.kt:1014-1016).
Scenario: a burst of syncs or the full-snapshot reads described in S-8 hit the per-minute quota; every remaining item in that run is permanently failed and the user must retry each by hand.
Fix: classify 408, 429 and 403 rate-limit reasons as transport (retry with backoff, honour Retry-After).

**S-3 (high, CONFIRMED) An expired or revoked token fails every queued item**
Files: data/remote/GoogleSheetsClient.kt:56 (401 gives AuthExpiredException), TransactionRepositoryImpl.kt:552-554 (AuthExpiredException is explicitly non-transport) and the per-item branches (778-788, 816-826).
Problem: when refresh fails the loop does not break. Each item gets FAILED/999 and its transaction becomes NEEDS_REVIEW, so a temporary sign-in problem turns the whole queue into manual-retry items. A reconnect notification is posted by the token provider, but the data state is already damaged.
Fix: on AuthExpiredException stop the run (like a transport failure), leave items PENDING, surface a reconnect banner, and resume after sign-in.

**S-4 (high, CONFIRMED) Reconcile can overwrite local edits and queued changes**
File: data/repository/TransactionRepositoryImpl.kt:1133-1138 (reconcile runs unless a transport failure occurred), 1178-1241.
Problem: reconcileWithSheet copies sheet values (amount, merchant, category, share, owed, status, companions) onto the local row when they differ, and sets syncState to SYNCED. It does not look at pending or failed queue items for that id. Items that failed non-transport (permanent) or were skipped (NEEDS_REVIEW) still have local changes.
Scenario: user edits a category; the update fails permanently (row not found); the next sync's reconcile restores the old sheet value and marks the row SYNCED, so the edit and the failure marker both disappear.
Fix: skip reconcile for any id that has a queue row, or only reconcile ids whose local row is syncState SYNCED with no queue entry.

**S-5 (medium, SUSPECTED) Reconcile can merge two different transactions by row number**
File: data/repository/TransactionRepositoryImpl.kt:1174-1176 and 1188-1217.
Problem: when the sheet UUID is unknown locally the code falls back to getByTabAndSheetRowIndex and, if found, replaces the local id with the sheet UUID, deletes the old row and re-points queue items (updateTransactionId, updatePayloadJsonId, updatePlanJsonId). After a row insert or delete in the sheet (or a second device) the cached row index of transaction A can be the row of transaction B.
Scenario: a pending update for A is re-pointed to B's id and applied to B.
Fix: only fall back to row matching when the sheet UUID is blank; never when it is a non-blank unknown UUID (import it as new instead).

**S-6 (medium, SUSPECTED) Orphan row adoption can claim a manual sheet row**
File: data/repository/TransactionRepositoryImpl.kt:899-922.
Problem: after the UUID lookup fails the code searches for a row with the same date serial, time serial and amount and a blank UUID cell, writes its own UUID there and treats the insert as done. A manually typed row with the same date, time and amount (blank AC) is adopted, and the app's own row is never written.
Fix: also require equal description and account, or only adopt orphans created by an earlier attempt of the same queue item (store a marker in the planJson).

**S-7 (medium, CONFIRMED) One bad item blocks newer items**
File: data/repository/TransactionRepositoryImpl.kt:611-1131 (break on transport failure), data/local/dao/SyncQueueDao.kt:45-52 (FIFO).
Problem: the first transport-class failure ends the loop, so a single item that always errors (for example an unclassified IOException for one payload) holds back everything behind it until it reaches 8 retries. WorkManager itself gives up after 3 attempts (SyncTransactionsWorker.kt:63, 84-92), and with S-1 nothing re-enqueues, so the retry is driven only by user actions.
Fix: continue to the next item after incrementing the failing item's retry count; keep ordering only for items that touch the same transaction.

**S-8 (medium, CONFIRMED) Every sync and every reconcile reads the entire ledger**
File: data/repository/TransactionRepositoryImpl.kt:600, 1136, 1161 (readLedgerSnapshot reads all month tabs) plus openPayables gather at 936-956 per shared expense.
Problem: a 15 minute periodic worker (or each manual sync) downloads every tab. Quota pressure feeds S-2; battery and data cost grow with the year.
Fix: reconcile only the current and previous month, and only when the sheet's modified time changed (Drive revision).

**S-9 (low, CONFIRMED) applyPlan appends after a non-transport existence-check error**
File: data/repository/TransactionRepositoryImpl.kt:1352-1361.
Problem: if findRowByUuid returns a non-transport error the code falls through to append. A false negative (for example a 403 on a read) can create a duplicate row.
Fix: return the error for any failure of the existence check.

**S-10 (low, SUSPECTED) Stale stored plans can overwrite newer sheet values**
File: data/remote/AccountingSyncExecutor.kt:99-134, 177-231 (plan saved on first attempt, re-applied on retry with absolute cell values).
Problem: a retry hours later writes the J, K and H values computed from the first snapshot. If the user or another device settled the same debt in between, it is overwritten.
Fix: store only intent and recompute edits on retry, or compare current cell values before writing.

### Checked and found correct (Sync)
- Idempotency of debit append: OP_INSERT checks the UUID in column AC first (874-885) and applyPlan checks each append by UUID (1352-1358), so a retry after a lost response does not create a second debit row. The plan is persisted before any write (996-998).
- 31 column layout: SheetPayloadMapper maps A-K debits, L spacer, M-T inflows, U spacer, V-AB payables, AC-AE UUIDs; updates write only A-K (mapTransactionToTable1); writes use RAW, so merchant text starting with "=" is stored as text, not a formula.
- Month tab: tab name and year come from the transaction timestamp in Asia/Kolkata (formatMonthTabName, year selection at 774 and 642); new tabs are created through ensureMonthTabExists before the append.
- Single flight: syncMutex wraps the whole run, so two triggers cannot interleave.
- NEEDS_REVIEW rows are skipped (not silently dropped) and stay in the queue (770-772).
- Token refresh: TokenRecoveryInterceptor retries once with a fresh token on 401 inside synchronized(this).

---

## 3. Database

**D-1 (blocker, CONFIRMED) Silent in-memory database**
Files: data/local/FinanceDatabase.kt:165-181 (if the passphrase cannot be read after one retry the code returns createInMemory and sets isStorageTemporarilyUnavailable), the instance is stored in INSTANCE (getInstance, around line 140); grep shows isStorageTemporarilyUnavailable is never read anywhere else.
Problem: on a transient Keystore failure the app runs on an in-memory Room database for the rest of the process. Every save succeeds and says "Saved", nothing is persisted, and the cached instance is never replaced when the Keystore recovers.
Scenario: Keystore hiccup right after boot (a known Samsung and Xiaomi behaviour) while the listener captures a payment; the user confirms three expenses; the process is killed; all gone.
Fix: do not cache the fallback; throw or show a blocking "Storage unavailable, retry" screen; retry the Keystore on the next access; never write to the in-memory database.

**D-2 (high, SUSPECTED) A thrown canary error leaves callers to crash**
File: data/local/FinanceDatabase.kt:225-240 (rethrow after one retry of a transient canary error), di/AppContainer.kt (databaseReady is a single async; awaitDatabase rethrows the same exception on every call).
Problem: a failed async is not retried, and ViewModels call awaitDatabase inside viewModelScope.launch, so every screen open throws until the process restarts. Only FinanceApplication catches it.
Fix: wrap databaseReady in a retriable provider and show an error state instead of crashing.

**D-3 (medium, CONFIRMED) Key invalidation silently discards the data**
Files: data/local/DatabaseKeyProvider.kt:60-72, data/local/FinanceDatabase.kt:200-224.
Problem: when the Keystore alias is gone or invalid the passphrase file is deleted and regenerated, the old database fails the canary and is renamed to finance_automation.db.unreadable_<timestamp>. Unsynced queue rows in that file are unrecoverable. The user is not told. The renamed files are never deleted (unbounded growth, and they remain readable only with the lost key).
Fix: surface a one-time "local data was reset, unsynced items lost" message; cap the number of kept files; prefer a Keystore key that survives (no auth binding) and keep a plain export of the pending queue before any move-aside.

**D-4 (medium, CONFIRMED) Migrations are never executed in tests**
Files: app/src/test/java/com/financeautomation/data/local/DatabaseMigrationTest.kt, no androidTest directory.
Problem: the migration tests compare createSql text from the exported schema JSON with SQL strings. Nothing runs migrations 2 to 14 against a real SQLite (MigrationTestHelper). A data migration such as 7_8 (DELETE on companions, UPDATE on trip_tags and preferences) is unverified.
Fix: add androidTest MigrationTestHelper cases for every step and a full 2 to 14 chain.

**D-5 (low, CONFIRMED) No migration path from version 1 or 2**
File: data/local/DatabaseMigrations.kt (ALL_MIGRATIONS starts at 2_3).
Problem: a version 1 or 2 database throws IllegalStateException (destructive fallback is correctly not enabled). Irrelevant for new installs; recorded for completeness.

### Checked and found correct (Database)
- Migrations 11_12, 12_13, 13_14 create exactly the tables, defaults and indices in 12.json, 13.json and 14.json (subscriptions, subscription_cycles, inflows with four indices, budgets).
- No fallbackToDestructiveMigration anywhere; all migrations are registered in ALL_MIGRATIONS and in both the file and in-memory builders.
- SQLCipher key: 32 random bytes wrapped by an AES-GCM Keystore key, stored only encrypted, formatted as a raw hex key; transient Keystore errors do not delete the key file (only invalidation or a missing alias does).
- Plaintext to encrypted migration keeps a moved-aside copy on failure rather than deleting.
- Backups: allowBackup is false; data extraction rules and backup rules exclude database, files and shared preferences.

---

## 4. Capture pipeline

**C-1 (high, CONFIRMED) Promotional notifications become debits**
File: domain/usecase/ParseNotificationUseCase.kt:212-253.
Problem: for non-SMS whitelisted packages the only wording gate is "failure". There is no requirement for success wording or for a debit verb. Any notification from a whitelisted payment app that contains a rupee amount (cashback offers, reminders, "your bill of Rs 500 is due") produces a WAITING debit capture, a popup and eventually a NEEDS_REVIEW transaction at 24 hours. isDebit is defined as isDebit || !isCredit (222-223), so unknown direction defaults to debit.
Fix: require SUCCESS wording for payment apps as for SMS, treat unknown direction as ignore, and add a deny list for promo words (cashback, offer, reward, due).

**C-2 (high, SUSPECTED) "Do not share" in the OTP regex drops real debit SMS**
File: core/constants/PackageWhitelist.kt (OTP_SUPPRESSION_REGEX includes do[- ]not[- ]share and security code), applied at ParseNotificationUseCase.kt:184-190 and NotificationProcessor.
Problem: several Indian bank debit and UPI alerts end with "do not share OTP/PIN". They are classified as OTP messages and dropped without any trace the user can see. The data suggests missed expenses with no count.
Fix: only treat a message as OTP when it contains a numeric code pattern next to the word OTP, and do not drop messages that also contain a debit verb and amount; count dropped-as-OTP messages in diagnostics (without text).

**C-3 (medium, CONFIRMED) Every SMS package notification is read**
Files: service/NotificationListenerServiceImpl.kt:51-74, core/constants/PackageWhitelist.kt (SMS packages are whitelisted).
Problem: for the default SMS apps the listener extracts title and text of every message before the personal-sender and financial keyword filters run in ParseNotificationUseCase.kt:195-209. The text is kept in memory only and only hashed (content hash) and not stored, which matches the stated privacy rule, but the filter order means personal and OTP SMS text is read first. The filter result for personal senders depends on isPersonalSender(title) heuristics I did not audit.
Fix: run the sender and keyword filter on the title and a truncated text before building the full content; document the heuristic and test it with names that look like short codes.

**C-4 (medium, CONFIRMED) Cross-source dedupe only merges different packages**
File: service/NotificationProcessor.kt (findSimilarByAmountAndTime filtered by sourcePackage inequality, 90 second window).
Problem: the same payment announced twice by the same app (for example processing then success under different keys) is handled by the key logic, but two alerts for the same payment from the same SMS app and the same bank app are merged only if packages differ; a payment alert plus its bank SMS from a package that is also whitelisted and equal (two SMS apps) is not merged. Conversely two real payments of the same amount to different payees within 90 seconds through two different apps are merged into one.
Fix: include the merchant text similarity or reference id in the dedupe key.

**C-5 (medium, CONFIRMED) Android 12 notification trampoline blocks the subscription "Yes" for a varying amount**
File: service/SubscriptionActionReceiver.kt (handle: ACTION_YES with NeedsAmount calls context.startActivity from a BroadcastReceiver fired by a notification action).
Problem: targeting API 31 or higher, starting an activity from a receiver or service launched by a notification action is blocked. The notification is cancelled before the call, the capture row is created in SHOWN state, and nothing opens. The reminder then repeats next evening and leaves orphan captures (reset to WAITING at next start).
Fix: use PendingIntent.getActivity directly for the Yes action when the amount varies (the earlier design) or open the app from the notification content intent.

**C-6 (low, CONFIRMED) Exported entry activity opens flows for any caller**
Files: AndroidManifest.xml (EntryActivity exported=true), presentation/ui/EntryActivity.kt:97-120, presentation/overlay/FlowAuthGate.kt.
Problem: EntryActivity validates the flow name against a whitelist but accepts it from any app, then (in the no-overlay path) creates the "internal auth" PendingIntent itself, so ModalBottomSheetActivity's hasAuthToken check is always true for that route. In the overlay path there is no caller check at all. The impact is limited to showing an input sheet (the app lock check on the overlay is present, OverlayController.kt:202, 361), but the gate in FlowAuthGate does not protect anything for this path.
Fix: for external callers (no calling package equal to ours) force the plain expense or the choose-action screen; or make EntryActivity non-exported and use a separate exported shortcut target.

**C-7 (low, SUSPECTED) The foreground watcher loop lives in an app-scope coroutine**
File: service/ForegroundWatcher.kt:194-260.
Problem: polling for the payment app to leave the foreground runs in a plain coroutine with no foreground service. If the process is killed the loop stops; captures wait until the next process start. This is a design limit, not a bug, but it explains missed popups on aggressive OEMs. Usage access queries are limited to whitelisted packages (isInForeground line 139) which is good.

### Checked and found correct (Capture)
- The listener drops non-whitelisted and blacklisted packages before reading extras (NotificationListenerServiceImpl.kt:56-62), and the processor re-checks blacklist and whitelist (NotificationProcessor.kt).
- WhatsApp, Telegram, Signal, Messenger, Instagram, Discord, Outlook and Gmail packages are blacklisted (prefix match).
- Debug package ids (shell, test) are accepted only when BuildConfig.DEBUG.
- The pending capture stores payee, amount, account and flags; the raw text is not stored. Content hash is a truncated SHA-256 kept in memory and in the handled-notification table for 24 hours then pruned.
- No Log call or Diagnostics.record writes notification text, amounts or payees (grep of Log.* and Diagnostics.record arguments).
- Stale WAITING captures expire at 24 hours into NEEDS_REVIEW with category Other instead of being dropped.

---

## 5. Security

**X-1 (medium, CONFIRMED) Release build can be signed with the debug key**
File: app/build.gradle.kts:31-43.
Problem: if keystore.properties is missing, the releaseSigning config does initWith(debug). A release or beta APK built on a machine without the file is silently debug-signed. Beta is a clone of release with the simulator and discovery scan enabled (60-80).
Fix: fail the build for the release and beta types when keystore.properties is missing; keep debug signing only for debug.

**X-2 (medium, CONFIRMED) Deep link and route validation allow high-impact destinations**
File: presentation/ui/MainRouteValidator.kt (ALLOWED_ROUTES includes DIAGNOSTICS, SYNC_BACKUP, PREFERENCES, ONBOARDING and the error screens).
Problem: any app or web page can open financeautomation://onboarding or diagnostics. The app lock is not part of the validator; I did not verify that MainActivity gates the navigation behind the lock state for deep links that arrive while locked.
Fix: restrict the deep link to a short list (dashboard, needs review, transactions) and require unlock for the rest.

**X-3 (low, CONFIRMED) CSV export leaves files in cache until the next export**
File: data/export/CsvExportWriter.kt:14-32 (purge runs only inside write).
Problem: the one hour retention only triggers on the next export, so a month of financial data sits in the app cache indefinitely. The CSV text itself is protected against formula injection (leading =, +, -, @, tab, CR are quoted) and uses a non-exported FileProvider limited to cache/exports.
Fix: purge on app start and on share completion.

**X-4 (low, CONFIRMED) Diagnostics share puts the account email and spreadsheet id in plain text**
File: presentation/screens/drawer/DiagnosticsScreen.kt:185-189.
Developer mode only, user initiated, but the text goes to any app the user picks. Mask the email and the id.

**X-5 (low, SUSPECTED) The subscription action receiver trusts the cycle id only**
File: service/SubscriptionActionReceiver.kt. The receiver is not exported and the PendingIntents are immutable, so only the app (and the system on its behalf) can send the intent. Cycle ids are random 64 bit values. No finding beyond noting that a Yes performs a ledger write with no unlock step; the app lock is not consulted for notification actions.

### Checked and found correct (Security)
- Manifest: only MainActivity (launcher and deep link), the alias, EntryActivity, the listener (permission protected), the QS tile (permission protected) and the two widget providers are exported. Receivers for quick actions, subscription actions, boot and the FileProvider are not exported.
- allowBackup false; cleartext traffic disabled; network security config trusts only system roots.
- Secrets: OAuth uses the Play Services authorization client; no keystore, client secret or token is tracked in git (git ls-files shows only .env.example); google-services.json exists on disk but is untracked.
- Recents and screenshot protection: FLAG_SECURE is applied through SecureWindow and the overlay uses the same helper with a "hide preview" preference.
- App lock: BiometricPrompt with strong biometrics or device credential; the overlay checks isLocked before showing existing data; ModalBottomSheetActivity does not load an existing transaction while locked (ModalBottomSheetActivity.kt:293-302).
- Clipboard: no ClipboardManager writes found.
- Logging: no sensitive content in Log or Diagnostics calls; the Sheets error text in Diagnostics contains only API messages.

---

## 6. Robustness

**R-1 (medium, CONFIRMED) runBlocking inside the token provider**
File: data/remote/GoogleAuthTokenProvider.kt:117-146.
Problem: getAccessToken and refreshAccessToken block the calling thread with runBlocking. They run inside OkHttp interceptors and request builders on IO, which is fine, but any call that happens to reach them on the main thread (a ViewModel using Dispatchers.Main, a composable) ANRs. The token fields are plain vars read and written from several threads, and a failed refresh posts a reconnect notification each time (notification spam under load).
Fix: mark the cached fields volatile or guard with a Mutex, deduplicate concurrent refreshes, and rate-limit the reconnect notification.

**R-2 (medium, CONFIRMED) Compose "maxLines = 1" with ellipsis on money**
Files: presentation/components/AmountText.kt:50-58 (maxLines 1, softWrap false, Ellipsis), presentation/components/KpiCard.kt:75-85 (shrinks to a floor then ellipsises).
Problem: at large font scale a long rupee figure is cut with "..." which hides digits. A truncated amount is worse than a wrapped one.
Fix: never ellipsise money; shrink then fall back to the compact form (KpiCard partly does) and allow AmountText to scale down or wrap.

**R-3 (low, CONFIRMED) Retention**
- Moved-aside unreadable databases are never pruned (D-3).
- CSV cache (X-3).
- Pipeline events and handled notifications are pruned (handled at 24 hours); the pipeline event table has an index on time but I did not find a prune; confirm PipelineEventDao has one.

**R-4 (low, SUSPECTED) Division by token count**
Files: presentation/viewmodel/DashboardViewModel.kt:486, SettingsCustomizationCoordinator.kt:64 both guard with tokens.isNotEmpty(); NettingUseCase.kt:50 guards list.isEmpty(). No unguarded division by zero found.

### Checked and found correct (Robustness)
- Disk and network I/O: repositories wrap work in withContext(ioDispatcher); the only Thread.sleep calls are in the database builder (background) guarded by a main-looper check.
- Only 5 non-null assertions in main code; no GlobalScope.
- Overlay windows: every addView has a matching removeView path (OverlayController.kt:322, 392; ResultPillOverlay.kt:164, 190).
- Service scope is cancelled in NotificationListenerServiceImpl.onDestroy.

---

## 7. UI correctness

**U-1 (low, CONFIRMED) Hard-coded colour**
File: presentation/screens/drawer/AddCategorySheet.kt:147 uses Color.White for a tint instead of a theme token (contrast against light category colours is not guaranteed in dark theme).

**U-2 (low, SUSPECTED) Single-line truncation in 46 places**
The codebase uses maxLines = 1 in 46 places. I did not render at font scale 1.3. FontScaleOverflowAuditTest measures string length in strings.xml, not layout (see T-2).

**U-3 (low, CONFIRMED) Sample data and simulator**
SelfTestSampleData and the Simulate Payment card exist only behind ENABLE_SIMULATOR (false in release, true in debug and beta); NotificationProcessor.kt:405 rechecks the flag and developer mode. No fake amounts or mock fallbacks were found in release paths.

### Checked and found correct (UI)
- Compose Material 3 theme tokens are used almost everywhere (one literal Color found in presentation outside theme).
- BackHandler and predictive back are handled in AppNavHost; the manifest enables enableOnBackInvokedCallback.
- Strings come from resources; only previews contain literal text.

---

## 8. Tests

**T-1 (medium, CONFIRMED) A security test asserts a function production never calls**
File: app/src/test/java/com/financeautomation/presentation/EntryActivitySecurityTest.kt:56-80 tests EntryIntentValidator.sanitizeExtraKeys, which is defined in presentation/ui/EntryActivity.kt:57-70 and has no production caller (grep: only the test uses it). The test passes regardless of what EntryActivity really forwards.
Fix: test buildFallbackIntent and the real routing, or delete the dead function and the test.

**T-2 (medium, CONFIRMED) Source-text tests stand in for behaviour tests**
Files: presentation/screens/drawer/CategoriesTapTargetsTest.kt (regexes over Kotlin source and the manifest), presentation/components/FontScaleOverflowAuditTest.kt (string length in strings.xml), presentation/screens/BudgetWiringAndStringsTest.kt (source wiring). They break on refactors and prove nothing about layout or touch behaviour.
Fix: replace with Compose UI tests (semantics, font scale) or accept them as lint-like checks and label them.

**T-3 (medium, CONFIRMED) Critical paths with no runtime test**
- Room migrations 2 to 14 (D-4).
- Listener to processor to capture on a device (NotificationListenerServiceImpl, ForegroundWatcher loop, overlay timing).
- SyncTransactionsWorker retry mapping beyond the small pure function; BootReceiver scheduling; the missing schedule in S-1 is not detected by any test (a test that FinanceApplication or an expense save schedules a sync would have caught it).
- DatabaseKeyProvider, FinanceDatabase canary and move-aside, in-memory fallback (D-1).
- Undo for payable and repayment (A-1): PillUndoTest exists but I did not see a repository-level test for undoRecentSave on these ids.
I only listed the test directory (93 Kotlin files); this list is from names, not from reading every test.

**T-4 (low, CONFIRMED) AppContainerTest hard-codes a count**
File: app/src/test/java/com/financeautomation/di/AppContainerTest.kt asserts its own list has 33 entries and resolves each. It does not fail when a new container property is added without being listed, and the real container now has more than 33 properties.

**T-5 (low, SUSPECTED) Tests built on private fakes or proxies**
TransactionRepositorySyncTest and others drive the real repository against hand-written DAOs and a mocked Sheets client; they exercise the real sync loop (good), but because the fakes accept every call they cannot show S-1 (nothing triggers a sync) or S-4 (reconcile vs queue).

### Checked and found correct (Tests)
- Accounting golden tests, split calculation tests, parse and SMS fixture tests, config round trip tests and the future-date guard tests exercise production classes directly.
- The sync repository tests execute the real syncPendingTransactions with the idempotent debit path.

---

## Coverage statement

Read in full or in the relevant part: CalculateSplitUseCase, LogExpenseUseCase, AccountingUtils, DateSerialUtils, SettlementWaterfallUseCase, LivingBurn, NettingUseCase (head), TransactionRepositoryImpl (insert, update, credit, payable, repayment, void, undo, discard, sync loop, reconcile, applyPlan), AccountingSyncExecutor (credit, payable, repayment head, void inflow, carry-forward, year close, applyAccountingPlan), SyncTransactionsWorker, BootReceiver, FinanceApplication (greps), ParseNotificationUseCase, NotificationProcessor (earlier version), NotificationListenerServiceImpl, ForegroundWatcher (foreground detection and loop), EntryActivity, MainRouteValidator, ModalBottomSheetActivity (intent handling), FlowAuthGate, AndroidManifest, network and backup XML, FinanceDatabase, DatabaseMigrations, DatabaseKeyProvider (key handling), schemas 12 to 14 versus migrations, GoogleSheetsClient (error mapping), TokenRecoveryInterceptor, GoogleAuthTokenProvider (token methods), ExportMonthCsvUseCase, CsvExportWriter, MonthCsvExporter, DashboardViewModel (burn, receivables), SheetPayloadMapper, build.gradle.kts (build types, signing), selected tests.

Not read, so not covered: GoogleSheetsClient append, find and snapshot logic beyond the error mapping; SheetProvisioner and the repair and fresh workbook path; WorkbookResolver; DashboardFormulaBuilder formulas; ConfigSheetManager; SyncConfigUseCase beyond the subscription rows; most Compose screens; OverlayController internals beyond the lock check; PeopleTrips, Insights and Budget logic; RegexPatterns (amount, merchant and personal-sender heuristics). The "repair/fresh workbook" and "31-column mapping on read" parts of the brief therefore remain unaudited.

Notes on my own work (for transparency): C-5 and C-6 touch code I wrote or changed in the T2 subscription round (the Yes action for varying amounts and the earlier EntryActivity extra). The current shared checkout already replaced the EntryActivity extra with a receiver call, which introduced C-5.
