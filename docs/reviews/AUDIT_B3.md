# AUDIT_B3 - Worker B, round 3 (read-only)

Scope: shared checkout tip 1db64b0 (feat/ui-redesign), read with git archive / grep only. No edits, builds or installs.
No real amounts or payees appear below.

Coverage statement: I traced the account switch flow fully, the navigation graph and activity handoffs by grep and reading, and the dead handler scan. I did NOT fully trace every item in the request (see "Not covered"). Findings marked SUSPECTED were read in code but not executed.

## Findings, most severe first

### R3-1  HIGH, CONFIRMED  Sign out then sign in with a different Google account keeps the previous account's data and keeps syncing it
- Files: presentation/viewmodel/SettingsSyncCoordinator.kt (`onGoogleSignOut`, `onGoogleSignInSuccess`, fresh workbook path), data/remote/WorkbookResolver.kt (`resolveForYear` with a stored id, `createFreshWorkbook`).
- Scenario: user A signs out. `onGoogleSignOut` clears only the token cache, email, name, photo and avatar. The local database (transactions, people, trips, subscriptions, config), the sync queue, the per-year spreadsheet ids and the Config mirror state stay. User B signs in. `onGoogleSignInSuccess` runs `syncConfigUseCase.pull()`, then `autoProvisionMasterSheet()`, which calls `resolveForYear(year, forceValidateTabs = true)`. With a stored spreadsheet id, an error from tab validation returns immediately and never searches Drive for B's own workbook. If validation succeeds (B has edit rights to A's file) B writes into A's workbook. If it fails, the fresh workbook path creates a new file for B and then `mirrorToCloud` and `syncPendingTransactions` push A's local rows and queued items into B's sheet.
- Result: A's financial data is shown to B and uploaded into B's Drive, or B's new rows land in A's ledger.
- Fix: on sign out (or on sign in when the account email differs from the stored one) stop sync workers, clear the sync queue, the per-year spreadsheet id prefs and the Config dirty and mirror state, and either wipe the local ledger after confirmation or keep it keyed per account. Add a test: sign in A, add rows, sign out, sign in B, assert nothing of A is pushed and B's sheet id is resolved by Drive search.
- Checked: the sign-in success path calls `syncPendingTransactions` right after provisioning, so no gate protects the first push.

### R3-2  MEDIUM, CONFIRMED  Dashboard to Transactions route does not encode the category
- File: presentation/ui/AppNavHost.kt around 602-613 (route string built by concatenation).
- Scenario: a category whose name contains `/`, `?`, `&`, `%` or `#` (user defined categories allow free text) produces a malformed route; navigation throws or opens the list with a truncated filter.
- Fix: `Uri.encode` the argument when building the route and decode on read, or pass it as a navigation argument. Test with a category named with a slash and an ampersand.

### R3-3  MEDIUM, CONFIRMED  Deep link route handling bypasses the dashboard and review helpers, so the back stack stacks
- Files: presentation/ui/MainActivity.kt (`LaunchedEffect(pendingRoute)` navigates with `launchSingleTop` only), AppNavHost.kt (`goToDashboard()` and `goToNeedsReview()` at ~188-203 pop to the start destination first).
- Scenario: a notification tap to Needs Review while the user is deep inside Transactions pushes Needs Review on top of that stack. Back then walks through screens the user did not open from the notification. Several plain `navigate(...)` calls (~464, 613, 620, 623, 626, 700, 769) also lack `launchSingleTop`, so a double tap stacks two copies.
- Fix: route pending external routes through the same helpers (`popUpTo(start){saveState}`, `launchSingleTop`, `restoreState`), and add `launchSingleTop = true` to the plain navigates.

### R3-4  MEDIUM, SUSPECTED  Back arrows on some screens call `popBackStack()` with nothing below
- File: AppNavHost.kt ~823-844 (`onNavigateBack = { navController.popBackStack() }`).
- Scenario: when a screen is the first destination after an external launch (the route validator allows dashboard, needsReview and transactions only, so Transactions can be the only entry) the arrow pops the last entry and leaves a blank NavHost.
- Fix: fall back to `goToDashboard()` when `previousBackStackEntry == null`.
- Not executed; reasoning from the graph only.

### R3-5  LOW, CONFIRMED  Speed dial lists two entries that open the same INCOME flow
- File: AppNavHost.kt ~245 (speed dial list), `launchFlow` ~245-272.
- Scenario: two different labels lead to the same sheet, so one looks broken or duplicated.
- Fix: give each entry its own flow value (income versus refund) or remove one.

### R3-6  LOW, CONFIRMED  Display-only chips look tappable
- File: presentation/flows/TripModeFlowSheet.kt ~317 and ~332 (`onClick = {}` on the "You" chip and the companion chips in a read-only summary row).
- Result: a chip that responds to touch ripple but does nothing; for screen readers it is announced as a button.
- Fix: render as non-clickable labels (`enabled = false` or a plain surface) so they carry no click semantics.

### R3-7  LOW, CONFIRMED  Active trip card passes an empty reactivate handler
- File: presentation/screens/drawer/PeopleTripsScreen.kt:258 (`onReactivateTrip = {}`).
- Verified harmless today: the card only shows the reactivate button for inactive trips (line ~618 uses the handler, and the inactive list at ~244 wires a real one). Dead code that would silently do nothing if the card state changes.
- Fix: make the parameter nullable, hide the button when null.

### R3-8  INFO, CONFIRMED  Disabled remove button with `onClick = {}`
- PeopleTripsScreen.kt:763. Button is `enabled = false` and explained by the text above it. Fine. Only the user visible string is hard coded English ("Cannot remove ... Settle all balances first") instead of a string resource, so it is not translatable. LOW.

## Checked and found correct
- External intents: `MainRouteValidator` allows only dashboard, needsReview, transactions; `launchFlow` attaches an internal marker only for in-app launches.
- Modal sheet while locked loads nothing and shows the lock prompt (`ModalSheetLoadPolicy`, my earlier work).
- Widget with app lock on shows no figures.
- Reminder worker keeps a pending run when the time is unchanged.
- Config push serialization (test `concurrentPushesAreSerializedSoConfigRowsAreNotDuplicated`).
- Ledger date display uses Asia/Kolkata through one formatter, so 23:59/00:01 rows land on the same day on every screen that uses it.

## Not covered (what I tried, what is left)
- Totals consistency against LivingBurn for Transactions "Your share", CSV export, trip detail and People balances: not traced in this round. Earlier rounds covered Dashboard and Insights only.
- Process death and rotation of QuickLog state, token expiry mid-sync, two quick saves, fresh workbook with stale queue items: only the sign in path above was traced; the rest is unverified.
- Font scale 1.3/2.0 overflow, dark contrast, touch target sizes, content descriptions: not checked (needs a running UI).
- Quick tile, notification action and widget write paths: not traced.
