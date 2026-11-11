# Finance Automation Android - Phase Guide for Build Agents

Status: Draft v8.1.0, detailed edition. Work the phases in order. Each action is written as Do, How, Watch out and
Check, so that nothing has to be guessed. Phases 0 to 7 are finished and merged into `main`. Phase 8 (UI and UX), Phase 9
(beta tooling and failure visibility) and Phase 10 (MVP closure) remain.

Source documents (read them first, they define the behaviour):

- [ANDROID_V8_PRODUCT_AND_ARCHITECTURE_SPEC.md](./ANDROID_V8_PRODUCT_AND_ARCHITECTURE_SPEC.md) - what the app does
- [ANDROID_V8_ENGINEERING_AND_TEST_PLAN.md](./ANDROID_V8_ENGINEERING_AND_TEST_PLAN.md) - devices, permissions, audit
- [IOS_BACKEND_V2_3_0_SYNC_NOTES.md](./IOS_BACKEND_V2_3_0_SYNC_NOTES.md) - accounting reference
- `docs/ios_reference/Code.gs` (v2.3.0) and `docs/ios_reference/Finance2026_Template.xlsx` - the verified iOS
  engine and the master workbook. Port behaviour from these, do not reinvent it.
- `AGENTS.md` - repository rules
- [ANDROID_V8_UI_UX_DESIGN.md](./ANDROID_V8_UI_UX_DESIGN.md) - look, components, screens and copy (Phase 8)
- [ANDROID_V8_BETA_AND_RELEASE_PLAN.md](./ANDROID_V8_BETA_AND_RELEASE_PLAN.md) - beta distribution, failure visibility and the
  owner checklist (Phase 9 and the owner action items)

---

## 0. Working rules (apply to every phase)

1. Read each file completely before editing it. List a directory before assuming a path. Confirm every finding in the
   code before fixing it.
2. Testing is deferred until the MVP is in place. Do not write or edit tests and do not report test results. Note in
   your report which existing test files reference names you changed. A phase is done when `.\gradlew.bat assembleDebug`
   succeeds and you have traced the behaviour through the code by hand.
3. AGENTS.md rules: no commit or push, no emojis anywhere, no `!!`, no secrets or keys in source, no local machine paths
   in documentation, no mock or stub Google clients, `Savings` always plural.
4. Layering: `domain` is pure Kotlin with no Android imports. Network and sheet code lives in `data`. UI in
   `presentation`. Services and receivers in `service`.
5. No disk or network work on the main thread. Use `Dispatchers.IO`. Every network call returns the sealed result type
   already in use; never throw across layers.
6. User data written to the sheet is always `RAW`. Numbers and date or time serials stay JSON numbers. Never prefix text
   with an apostrophe. The only exception is formulas the app itself builds (Dashboard and similar): write those as
   `formulaValue` in an `updateCells` request, because `RAW` would store them as plain text.
7. Reads that compare values must use `valueRenderOption=UNFORMATTED_VALUE` and `dateTimeRenderOption=SERIAL_NUMBER`.
   The default returns formatted text and breaks comparisons.
8. Do not edit the spec documents. You may update the status line at the top of a phase in this file.
9. If the code and the spec disagree, follow the spec and list the disagreement in your report. If the spec is silent or
   ambiguous, stop and ask. Do not guess.
10. Keep changes scoped to the phase. Do not refactor unrelated code.
11. No destructive database fallback. Every Room entity change needs a version bump and an explicit `Migration` that
    never drops the sync queue. Follow the version plan in rule 12.
12. Room version plan, so migrations never collide: v3 = Phase 2.4, v4 = Phase 3, v5 = Phase 4, v6 = Phase 5. If a number
    is already used, take the next free one and say so. New columns need `@ColumnInfo(defaultValue = "...")` that exactly
    matches the `ALTER TABLE ... DEFAULT` in the migration, or Room rejects the schema.
13. Evidence rule. Before using any library or Android API method, confirm it exists in the bundled library version by
    inspecting the dependency (for example `javap` on the AAR classes) and check its minimum API level against
    `minSdk 28`. Wrap newer APIs in `Build.VERSION.SDK_INT` checks. Earlier failures came from assuming (`execSQL` for a
    statement that returns a row, a `clearToken` method that did not exist).
14. Error paths. Every error branch must do one of: classify and retry, mark needs attention, or surface to the UI. No
    empty `catch`, no `// Ignored`. Log only status codes and exception class names, never bodies, tokens or amounts.
15. Self-review before reporting. Re-read every file you changed from top to bottom. Search the changed files for `!!`,
    `TODO`, `Log.`, `catch (_`, and hardcoded ids. Confirm every new class is wired in (registered in the manifest or
    container, or called from somewhere).
16. Never claim behaviour the code does not have. If something could not be checked without a device, say
    "not verified on a device".
17. Replacing a flow means deleting the old one. When a phase introduces a new path for something that already had a path
    (for example captures instead of direct transaction inserts), remove the old path in the same phase, then prove with a
    search (`grep -n` of its symbols) that nothing reachable still uses it. Check the production wiring too: read the place
    where the class is constructed in production and list the arguments it really receives, not the ones tests pass.
18. Traces and evidence must be copied from the tools, not recalled. Every file, line number, class and function you cite in a
    report must come from a command you ran (`grep -n`, a file read) in this round. Paste the raw output next to the claim. A
    trace that names code that does not exist is a failed report even if the code itself is right.
19. Done-when traces include a negative check. For each flow, also show what must NOT happen (for example "no transaction row
    exists before Confirm and Save") and the search or call path that proves it.
20. Tests that no longer compile are not left behind. When a change breaks an existing test file, update it in the same
    commit or delete it with a note in the report; never leave test sources that fail to compile.

### Report format after each phase

- Files changed, one line each.
- What was confirmed wrong in the code versus what was already fine.
- Each claim with its evidence (file and method, or the inspection command used).
- Anything left undone or deferred, and why.
- Risks not verified on a device.
- Any question that needs a decision.

---

## 0b. Code quality standards (apply to every line you write or touch)

These are binding in the same way as the working rules. They exist so the code stays readable, safe and easy to test
later. Where existing code breaks a standard, fix it when you touch that function; do not rewrite untouched code.

### Structure and size

1. One responsibility per class and per function. A function should fit on one screen (about 50 lines); if it needs a
   comment to separate sections, split it into named functions instead.
2. New files stay under about 400 lines. `SheetProvisioner` and `GoogleSheetsClient` are already far larger. When you
   change one of them, put the new part in a new file (for example `SheetStyleBuilder`, `ChartRequestBuilder`,
   `DashboardRequestBuilder`, `LedgerSnapshotReader`) and have the old class call it, instead of making the big file bigger.
3. One top-level class per file, named after the file. Package by layer as in AGENTS.md (`domain`, `data`, `service`,
   `presentation`). The `domain` package imports nothing from `android.*` or `androidx.*`.
4. Keep nesting shallow (three levels at most). Use early returns and small helper functions instead of deep `if` chains.
5. No dead code, no commented-out code, no unused parameters or imports. A `TODO` must name the phase that will resolve it.

### Kotlin

6. Follow the official Kotlin coding conventions: four-space indent, `camelCase` functions, `PascalCase` types,
   `SCREAMING_SNAKE_CASE` for `const val`, lines under about 120 characters, trailing commas allowed.
7. Prefer `val` and immutable data (`data class`, `List`, `Map`). Use `copy` to change a value. Mutable state is private
   and has a clear owner; shared mutable state needs a `Mutex`, `@Volatile` or an atomic type, and a comment saying why.
8. Null safety: no `!!` (already a hard rule). Use `?.`, `?:`, early return on null, or `requireNotNull(x) { "reason" }`
   only for programmer-error invariants, never for data from the network, the sheet, a notification or the user.
9. Model outcomes with sealed types and write `when` over them without an `else` branch, so the compiler flags a missing case.
   Use enums for closed sets and keep their sheet text in one `value` property.
10. Name things for what they mean (`remainingPayment`, not `x`). Booleans read as questions (`isShared`, `hasUuid`).
    Money and ids use named types or clearly named parameters; never pass two `Double` amounts in an easily swapped order.
11. No magic numbers or strings: put tolerances, limits, delays, column letters, tab names and sheet words in named constants
    in one place (`SheetSchemaConstants`, a `Tolerances` object, theme `dimens`). The accounting tolerances are 0.009,
    0.05 and 0.01 and must live in one object.
12. Money: all arithmetic results pass through `round2` (BigDecimal, `HALF_UP`, built with `BigDecimal.valueOf`). Never compare
    two `Double` amounts with `==`; use the tolerance helpers. Never format money by string concatenation in domain code.
13. Time: use `java.time` only (`Instant`, `ZonedDateTime`, `LocalDate`, `ZoneId`). No `SimpleDateFormat`, `Date`,
    `Calendar`. The sheet zone is one constant. Domain code receives time from an injected `Clock` or a parameter; it never calls
    `System.currentTimeMillis()` or `Instant.now()` directly (this also keeps it testable later).
14. Collections: do not mutate a list while iterating it; build the result and return it. Cap any list that can grow without
    bound (diagnostics, handled-notification records).

### Coroutines and threading

15. Structured concurrency: no `GlobalScope`. Every coroutine has an owner scope (a `ViewModel`, the listener, an
    application scope) that is cancelled when it should stop. Inject dispatchers; never hardcode `Dispatchers.IO` in a class that
    will be tested later.
16. Cancellation is not an error. In a suspend function, `catch (e: Exception)` also catches `CancellationException` and breaks
    cancellation. Either catch specific exceptions, or write `catch (e: CancellationException) { throw e }` first, or use one shared
    helper such as `suspend inline fun <T> safeCall(block: suspend () -> T): Result<T>` that rethrows cancellation. Use it for all new
    code, and fix the existing handlers in any function you touch. There are many in `GoogleSheetsClient`.
17. `runBlocking` is allowed only at the OkHttp interceptor and token boundary that cannot suspend, never on the main thread, and
    always with a comment. Never block the main thread with `Thread.sleep`, network, disk or database work.
18. UI state comes from `StateFlow` and is collected in Compose with `collectAsStateWithLifecycle`. A `ViewModel` exposes one
    immutable `UiState` class plus one-off events through a `SharedFlow` or `Channel`.

### Error handling and logging

19. Convert exceptions to the sealed result type at the data boundary (HTTP, JSON, database). Above that boundary, code handles
    results, not exceptions. Never return a fake success.
20. User-visible messages are plain sentences with no stack traces and no raw server text. Put them in `strings.xml` (so the app can
    be translated), with the domain returning a code plus arguments where the message depends on data.
21. Logging goes through one small `AppLog` wrapper that is a no-op in release builds and accepts only a tag, a short event name and
    a status code or exception class name. No other file calls `android.util.Log` directly. Never log tokens, response bodies, amounts,
    payees, names, notification text or file contents.
22. Each failure has exactly one owner that decides retry, needs-attention or user message. Do not catch an error, log it and carry on as
    if nothing happened.

### Android specifics

23. Singletons and services hold the application context, never an `Activity` or `View`. Unregister every receiver and listener you
    register. Use WorkManager for deferrable work and respect background limits; do not start a service from the background.
24. Every `PendingIntent` is `FLAG_IMMUTABLE` unless a mutable one is required, with a unique request code. Components are
    `exported="false"` unless another app must reach them, and exported ones validate every extra they read. Never put financial data in
    intent extras that another app could read.
25. Guard APIs newer than API 28 with `Build.VERSION.SDK_INT` and provide a working fallback. Prefer AndroidX compatibility classes
    (`ContextCompat`, `NotificationManagerCompat`, `ShortcutManagerCompat`).
26. Do no more than a few milliseconds of work in a system callback (`onNotificationPosted`, `onReceive`, widget update). Persist,
    then hand off to a coroutine or WorkManager.
27. Resources: user-visible text in `strings.xml`, sizes in `dimens.xml`, colours and shapes from the theme tokens in `ui/theme/`. No
    hardcoded colours or `dp` values in composables (AGENTS.md).

### Compose

28. Composables are stateless where possible: state goes down, events go up (state hoisting). Side effects live in `LaunchedEffect`,
    `DisposableEffect` or `rememberCoroutineScope`, never directly in the composition body.
29. Use `remember` and `derivedStateOf` for computed values, give list items stable keys, and pass only the data a composable needs
    (small parameters recompose less).
30. Accessibility: every icon-only button has a `contentDescription`; touch targets are at least 48 dp; text uses `sp` and scales with
    the system font size; colours keep enough contrast in light and dark themes; important state is not shown by colour alone.

### Data, network and sheet code

31. Room: DAO calls are `suspend` or return `Flow`; a multi-statement change is one `@Transaction`; queries on a lookup column have an
    `@Index`; every entity change has a migration (working rules 11 and 12).
32. Networking: set timeouts, wrap every response in `use`, parse with a tolerant JSON reader (`ignoreUnknownKeys`), and never trust a
    field that may be missing. Retry only operations that are safe to repeat (everything here is made safe by UUIDs). Batch reads and
    writes; never read a range once per row (no N+1 calls).
33. Sheet code: build requests with small typed builder functions, not long inline `mapOf` trees; share the colour, range and
    format helpers; keep one place that knows column letters and indexes. Write ranges in zero-based `GridRange` form only inside the
    builders.
34. Security: no secret, key, token, id or credential in source, tests, docs or logs. Keys live in the Android Keystore. User text is
    always `RAW`. Treat everything from notifications, the sheet and intents as untrusted input and validate it.

### Documentation and comments

35. Comments explain why, not what. Public classes and functions in `domain` and `data` get a short KDoc stating the contract, the
    units (rupees, serials, seconds) and the failure cases. Do not restate the code.
36. When behaviour is ported from `Code.gs`, name the original function in the KDoc (for example "Port of
    `updateLinkedDebitSettlement`") so a reviewer can compare.

### Dependencies and build hygiene

37. Add a library only if the platform has no reasonable equivalent. Put versions in `libs.versions.toml`, pin them, and say in the
    report what the dependency costs in size. Remove dependencies you no longer use.
38. Do not introduce new compiler warnings. Fix unused variables, deprecated calls you touch, and unchecked casts. A `@Suppress` needs
    a one-line reason.
39. Keep diffs focused: no reformatting of untouched code, no renames unrelated to the phase, no moving files without a reason stated
    in the report.

### Pre-report checklist (run it and say so in the report)

- Every changed function is under about 50 lines or explained.
- No `!!`, no empty `catch`, no `catch (_`, no `GlobalScope`, no `Thread.sleep` on the main thread, no direct `Log.` outside `AppLog`.
- No magic number or string left in new code.
- `domain` has no Android imports.
- Every `catch (e: Exception)` in a suspend function rethrows cancellation.
- New files are under about 400 lines and each class is wired in.
- Strings, dimensions and colours come from resources and theme tokens.
- No stray non-English text or garbled identifiers in code. Search every file you wrote or touched for characters in the
  CJK ranges (U+3000 to U+9FFF and U+AC00 to U+D7AF) and for any identifier that is not plain ASCII. The only allowed
  non-ASCII characters are the rupee sign and the dash and bullet characters already used in sheet text. A function that
  is meant to override a framework method (for example `onReceive`) must carry the exact framework name; the build
  reports "overrides nothing" otherwise.
- Imports are at the top of every file; regular expressions are compiled once as top-level or companion constants, never
  inside functions or loops.
- No global mutable state for request-scoped data. Independent suspend calls that do not depend on each other run together
  with `async` and `awaitAll`, not one after another.
- Databases and their settings are initialised once; do not write transient data to disk just to read it back, pass models in
  memory.
- Move, copy and rename files with `git mv` or native commands; never regenerate a file's contents to relocate it. Do not
  overwrite an existing major file wholesale: edit it in place or add a new file.
- Comments only where the intent is not obvious; keep documentation current with the code.
- Zero-cost static checks before every report: `assembleDebug`, `compileDebugUnitTestSources` and `lintDebug`; paste the lint
  summary counts. Do not run tests unless the owner asks.
- Pull requests are opened and then left open. The reviewer inspects the pull request on GitHub and tells the agent when to
  squash-merge. Do not merge on your own unless the instruction says so.

---

## Completed work (as built, requirements that must stay true)

Phase 0 (fixes 1 to 4 and gaps), Phase 1 (authentication and onboarding), and Phase 1.5 (database encryption) are done.
Keep these true in all later work:

- Sheet writes are `RAW`; appends target `A3:K`, `M3:T`, `V3:AB`; the UUID is written in a second step to
  `AC{row}` (debit), `AD` (inflow), `AE` (payable); an existing UUID or an orphan row is checked before appending.
- The sync queue is first in, first out, stops at the first transport failure, retries up to 8 times, then shows the item
  in Needs Review. Logical errors go to Needs Review at once.
- Workbooks are per year (`Finance{year}`, found by Drive `appProperties`), resolved by `WorkbookResolver.resolveForYear`.
  Month tabs are created on demand in the `Asia/Kolkata` zone.
- Sign-in uses the Google Identity authorization API with `drive.file`; no client id or token is in source; a 401 clears
  the stale token (`GoogleAuthUtil.clearToken`, because `AuthorizationClient` has no `clearToken` in this version).
- For an existing workbook the configuration is pulled first and the Dashboard regenerated only after a successful pull.
- The database is SQLCipher with a Keystore-wrapped key in the `x'<hex>'` raw form everywhere. A key problem never
  crashes the app: an unreadable database is moved aside and rebuilt from the sheet; a failed plain-to-encrypted
  migration copies the queue and the transactions it references into the new database.
- `NotificationSimulatorReceiver` exists only in `app/src/debug`.
- Release builds package only `arm64-v8a` and `armeabi-v7a`.

---

## Phase 2 - Sheet gateway, workbook design and settings sync

Status: Complete (steps 2.1, 2.1b, 2.2, 2.3, and 2.4 built, verified, and passing assembleDebug). Goal: the workbook the app creates looks and behaves
like the iOS master template plus the hidden UUID columns, the gateway can do the batched reads and writes the accounting
engine needs, and settings follow the user between devices.

### 2.0 Preparation (do this before editing)

1. List `data/remote`. Read completely: `SheetProvisioner.kt`, `DashboardFormulaBuilder.kt`,
   `RegenerateDashboardUseCase.kt`, `GoogleSheetsClient.kt`, `SheetSchemaConstants.kt`, `Enums.kt`.
2. Write a throwaway script outside the repository (for example in the scratchpad) that opens
   `docs/ios_reference/Finance2026_Template.xlsx` with openpyxl and prints, for the `Dashboard` and `Template` tabs:
   - Column widths: iterate `ws.column_dimensions.items()` and print `dim.min`, `dim.max`, `dim.width`. Entries are
     grouped, so expand each `min..max` range; otherwise columns look missing.
   - Row heights from `ws.row_dimensions[r].height` (points).
   - Merged ranges from `ws.merged_cells.ranges`.
   - For every styled cell used in spec 8.3: fill rgb, font name, size, bold, colour, alignment, number format, borders.
   - Formula text: for normal cells `cell.value`; for array formulas (`G5`, `J5`, `M5`) use `cell.value.text`.
   - Chart anchors and size from `xl/drawings/drawing1.xml` inside the xlsx (zip file): `from.col`, `from.row` (zero-based),
     `colOff`, `rowOff`, `ext cx`, `ext cy` in EMU. Divide EMU by 9525 for pixels.
3. Keep the script output in the scratchpad. Do not commit the script or the output.

Known values from the reference file (verify them with your script, do not trust this list over the file):
chart 1 anchors at `A7`, offset about 17 px down, about 520 by 266 px; chart 2 anchors at `K7`, offset about 1 px right
and 16 px down, about 491 by 266 px; the two charts are a clustered column chart and a doughnut chart.

### 2.1 Match the iOS template layout

Do: make `SheetProvisioner` produce the iOS layout plus hidden `AC`, `AD`, `AE`.

How:

1. Spreadsheet properties first, once per workbook: `updateSpreadsheetProperties` with `locale = "en_GB"` and
   `timeZone = "Asia/Kolkata"`, `fields = "locale,timeZone"`, executed in an isolated `batchUpdate` before tab creation.
   On-device verification confirmed that `locale = "en_GB"` with `timeZone = "Asia/Kolkata"` is accepted by Google Sheets API without error (0 failures logged in Diagnostics or logcat for the locale batch). We use `en_GB` to ensure English month names in `TEXT(TODAY(), "mmm")`, `DD/MM/YYYY` date parsing, and comma formula argument separators, while `timeZone = "Asia/Kolkata"` anchors date functions to IST. All rupee values use explicit custom number formats (`"₹"#,##0`), rendering currency display independent of sheet locale defaults. If the cosmetic properties batch ever fails, it is recorded in Diagnostics and tab provisioning continues uninterrupted.
2. Ledger tabs (`Template` and every `MmmYY` tab), all in one `batchUpdate`:
   - Grid size: 1000 rows by 31 columns (`updateSheetProperties` with `gridProperties.rowCount` and `columnCount`, fields
     `gridProperties.rowCount,gridProperties.columnCount`).
   - Frozen rows: `gridProperties.frozenRowCount = 3`, fields `gridProperties.frozenRowCount`.
   - Row 1: merge `A1:AB1`, text `EXPENSE LEDGER - MMM YYYY` (three-letter upper-case month, year).
   - Row 2: merges `A2:K2` ("EXPENSES (MONEY OUT)"), `M2:T2` ("INFLOWS (MONEY IN)"), `V2:AB2` ("I OWE PEOPLE").
   - Row 3 headers, A to AB as in the iOS template (`Date`, `Time`, `Account`, `Item / Description`, `Trip / Event Tag`,
     `Category`, `Total Amount (₹)`, `Paid For / Companions`, `My Share (₹)`, `Owed To Me (₹)`, `Status`, blank spacer,
     `Date`, `Time`, `Account`, `Inflow Type`, `Received From`, `Reason / Note`, `Amount Received (₹)`, `Linked Debit Row`,
     blank spacer, `Date Incurred`, `Time`, `Paid By`, `What For`, `Amount I Owe (₹)`, `Date Repaid`, `Repayment Status`),
     then `AC3:AE3` = `Debit Txn UUID`, `Inflow Txn UUID`, `Payable Txn UUID`.
   - Hidden columns: `updateDimensionProperties` with `range.dimension = "COLUMNS"`, `startIndex = 28`, `endIndex = 31`,
     `properties.hiddenByUser = true`, `fields = "hiddenByUser"`.
   - Spacers `L` (index 11) and `U` (index 20): `pixelSize = 10`, fill `#ECEFF1` for the whole column.
3. Writing header or banner text: use `updateCells` with `userEnteredValue.stringValue`. Text beginning with `=`, `+`, `-`
   or `@` never appears here, so there is no injection risk. Formulas use `formulaValue`.

Watch out:

- Index rules for `GridRange`: `startRowIndex`, `startColumnIndex` are zero-based and inclusive; `endRowIndex`,
  `endColumnIndex` are exclusive. `A1:K2` is rows 0 to 2, columns 0 to 11. The common mistake is an off-by-one that
  shifts a whole table.
- Do not put anything below row 3 on a ledger tab except data. Nothing may sit below a table.
- Re-running provisioning must not duplicate anything (merges, charts, validations are idempotent; charts are not, see
  2.1b).

Check: for each element in the list, find the request that produces it, and state its range in zero-based form in your
report.

### 2.1b Make the workbook look like iOS

Do: apply the styles in spec section 8.3 to every ledger tab and the Dashboard.

How:

1. Colours: write a helper `hexToColor("#19227E")` returning `{red, green, blue}` as floats between 0 and 1 (each
   channel divided by 255). Use it everywhere; never type floats by hand.
2. Text and fill: `repeatCell` with `cell.userEnteredFormat` containing `backgroundColor`, `textFormat`
   (`foregroundColor`, `bold`, `fontSize`, `fontFamily`), `horizontalAlignment`, `verticalAlignment`, and the matching
   `fields` mask, for example
   `userEnteredFormat(backgroundColor,textFormat,horizontalAlignment,verticalAlignment)`.
   The `fields` mask must list every property you set or the API ignores it silently.
3. Number formats: `userEnteredFormat.numberFormat = {type: "NUMBER", pattern: "\"₹\"#,##0"}` for currency columns
   (`G`, `I`, `J`, `S`, `Z`), `{type: "DATE", pattern: "dd/mm/yyyy"}` for date columns, `{type: "TIME", pattern: "hh:mm am/pm"}`
   for time columns, `{type: "TEXT", pattern: "@"}` for text columns. Add `numberFormat` to the `fields` mask.
4. Borders: `updateBorders` with `style = "SOLID"`, `width = 1`, colour `#E5EAEF` on ledger cells and `#DFE6E9` on
   settlement tables, `innerHorizontal` and `innerVertical` plus the four outer sides.
5. Column widths: convert the iOS width in characters to pixels with `pixels = round(chars * 7 + 5)`, then
   `updateDimensionProperties` with `pixelSize`. Row heights: points times 4/3 gives pixels. Spacers stay 10 px.
6. Dashboard cells, from spec 8.3 and the script output of 2.0: title (`A1:T1` merged), last updated (`A2:T2`), section strip
   (`A3:R3`), six KPI cards each merged over rows 4, 5 and 6 across three columns (`A:C`, `D:F`, `G:I`, `J:L`, `M:O`, `P:R`),
   monthly table `U5:W17`, category table `Y5:Z15`, settlement banner and tables rows 24 to 40.
7. Formulas: copy the exact text from the reference file (step 2.0.2). Replace only the burn-exclusion part by the
   configured exclusion list. Write with `updateCells` and `userEnteredValue.formulaValue`.
8. Two settlement query cells (`A27`, `H27`) spill their results below and to the right. Keep `A27:F40` and `H27:K40`
   empty of other content or the result is `#REF!`.
9. Charts, with `addChart`:
   - Column chart: `spec.title = "Full Year Spending by Month (Personal Burn vs Total Outflow)"`,
     `basicChart.chartType = "COLUMN"`, `headerCount = 1`, `domains[0].domain.sourceRange.sources = [U5:U17]`,
     `series[0] = V5:V17` with `colorStyle.rgbColor` `#1465BF`, `series[1] = W5:W17` with `#90CAF8`, both on the left
     axis. Include the header row (row 5) in each range because `headerCount` takes the series name from it.
   - Doughnut: `spec.title = "Current Month Spend by Category"`, `pieChart.domain = Y6:Y15`, `pieChart.series = Z6:Z15`,
     `pieChart.pieHole = 0.5`, `legendPosition = "RIGHT_LEGEND"`. Per-slice colours are not configurable through the
     API; the default palette equals the reference file's colours.
   - Position: `overlayPosition` with `anchorCell {sheetId, rowIndex = 6, columnIndex = 0 or 10}`, offsets and
     `widthPixels`, `heightPixels` from the script output.
10. Idempotency for charts: before adding, read `spreadsheets.get?fields=sheets(properties.sheetId,charts(chartId,spec.title))`.
    If a chart with the same title exists on the Dashboard, delete it with `deleteEmbeddedObject` (by `objectId` =
    `chartId`) and add again, or skip. Never add a second copy.
11. Data validation: `setDataValidation` with `rule.condition.type = "ONE_OF_LIST"` and `values` of
    `{userEnteredValue: name}`, `showCustomUi = true`, `strict = false`, on `C4:C1000`, `O4:O1000` (accounts), `F4:F1000`
    (categories), `P4:P1000` (inflow types: Friend Settlement, Salary/Stipend, Allowance, Other).
12. Month tabs created later (rollover, advance creation, first transaction of a month) use the same builder as
    `Template`. Prefer `duplicateSheet` from `Template` (`sourceSheetId`, `newSheetName`, `insertSheetIndex`), then clear
    data rows and set the banner text, because it copies every style, validation and column width exactly. If `Template`
    is missing, build from the same request list.

Watch out:

- The category table range is `Y5:Z15`: header in row 5, ten categories in rows 6 to 15. The iOS chart stops at row 14 and
  omits `Other`; do not copy that.
- The `fields` mask for `repeatCell` must match what you set. A missing `numberFormat` in the mask leaves the cell
  unformatted without any error.
- Merged ranges: set the value and format on the top-left cell of the merge.
- Chart ranges use `GridRange` with the Dashboard `sheetId`, zero-based, end exclusive.

Check: list each styled range from spec 8.3 with the request and its fields mask, and state that the Dashboard has no
`#REF!`, `#VALUE!` or `#NAME?` once a month tab exists (the formulas reference `MmmYY` tabs by name, so a Dashboard
created before the first month tab shows errors; provisioning therefore creates the current month tab first).

### 2.2 Status vocabulary

Do: use exactly these words in the sheet: debit status `Personal`, `Pending`, `Settled`, `Carried Fwd`, `Void`; payable
status `Pending`, `Partial`, `Repaid`, `Carried Fwd`, `Void`.

How:

1. Search all main source for the literal strings `"Settled"`, `"Carried"`, `"Pending"`, `"Void"`, `"Repaid"` and the enum
   names that carry them.
2. Inspect how Room stores the status (a column of the enum name or of the value string, and any `TypeConverter`). Decide
   the least risky change:
   - If Room stores the Kotlin enum name, keep the existing constant names and change only the sheet `value` strings, and
     add the new constants (`PERSONAL`, `CARRIED_FWD`, `PARTIAL`). No migration is needed.
   - If Room stores the value string, rows already stored with an old word need a data migration (for example
     `UPDATE transactions SET status = 'Personal' WHERE status = 'Settled' AND owedToMe = 0`) in the Phase 2.4 migration
     (v3). State which case applies.
3. Update `CalculateSplitUseCase` and `LogExpenseUseCase`: Just Me gives `Personal`; a row with owed 0 is never `Pending`.
4. Update `SheetProvisioner` and `DashboardFormulaBuilder` so formulas use the same words.

Watch out: the Dashboard "owed to me" card sums rows whose status is exactly `Pending`. Any other word silently drops out
of the KPI.

Check: list every file changed for this and the Room storage case you found.

### 2.3 Gateway capabilities

Do: add to `GoogleSheetsClient` (or a new `SheetGateway` wrapping it) the reads and writes the engine needs.

How:

1. `batchGetValues(spreadsheetId, ranges)`: `GET /v4/spreadsheets/{id}/values:batchGet` with one `ranges` query parameter per
   range (each range URL-encoded separately, tab names quoted: `'Nov26'!A4:K1000`), plus `majorDimension=ROWS`,
   `valueRenderOption=UNFORMATTED_VALUE`, `dateTimeRenderOption=SERIAL_NUMBER`. Send at most 10 ranges per call.
2. Parse `valueRanges[i].values`. Sheets omits trailing empty rows and trailing empty cells in each row, and empty rows in
   the middle come back as `[]`. Therefore: derive the row number from the position (`startRow + index`), pad each row to
   the full column count with `null`, and treat both `null` and `""` as blank. Numbers arrive as JSON numbers: read with
   `doubleOrNull`; read text with `content`.
3. `batchUpdateValues(spreadsheetId, updates)`: `POST /v4/spreadsheets/{id}/values:batchUpdate` with body
   `{"valueInputOption":"RAW","data":[{"range":"'Nov26'!J12","majorDimension":"ROWS","values":[[150]]}]}`. To clear a
   cell write `""`.
4. Grid growth: after each append, read the end row from the response `updatedRange`. If it is within 20 rows of the tab's
   `rowCount`, send `appendDimension` (`{"appendDimension":{"sheetId":id,"dimension":"ROWS","length":500}}`) in a
   `spreadsheets.batchUpdate`. This uses data you already have and costs no extra read.
5. Tab list: `GET spreadsheets/{id}?fields=sheets(properties(sheetId,title,gridProperties(rowCount)))`. Month tabs are the
   titles matching `^[A-Z][a-z]{2}\d{2}$` whose first three letters are a valid month. Sort by `2000 + yy` then month.
6. `LedgerSnapshotReader.read(workbookIds)`: for each workbook, list month tabs, then batchGet per tab
   `'Tab'!A4:K{rowCount}`, `'Tab'!V4:AB{rowCount}`, `'Tab'!AC4:AC{rowCount}`, `'Tab'!AE4:AE{rowCount}`. Return typed rows:
   - `LedgerDebit(tab, row, uuid, dateSerial, timeSerial, account, item, tripTag, category, total, companions, myShare, owed, status)`
   - `LedgerPayable(tab, row, uuid, dateIncurredSerial, timeSerial, paidBy, whatFor, amountOwed, dateRepaidSerial, status)`
   Skip rows where every cell of the table is blank. Rows are returned in chronological tab order, then row order.
   Workbooks are returned oldest year first. The prior year's workbook is read only if it already exists; add
   `WorkbookResolver.findExisting(year)` that never creates anything.
7. Domain models for the rows live in `domain/model` with no Android types. The reader (in `data`) maps to them.

Watch out:

- Never use the formatted text of a cell to decide anything. Formatted dates arrive as text and fail numeric comparison.
- A 404 on a range usually means the tab does not exist; return the typed `SheetNotFound`.
- Do not read a whole sheet when only one table is needed.

Check: for each function, say which Sheets endpoint it calls and show how a row with trailing blank cells is padded.

### 2.4 Settings sync across devices (Room version 3)

Spec section 12 is authoritative. Do this after the review of 2.1 to 2.3.

Findings to confirm in the current code first:

- `ConfigSheetManager.mirrorConfig` overwrites `Config!A1:P50` as a whole, without timestamps or deletions.
- Each list is capped at 49 rows by the fixed grid.
- `SyncConfigUseCase.mirrorToCloud` mirrors only the workbook id, default account and settle delay as preferences.
- `restoreFromCloud` never removes anything.
- The workbook id is mirrored into the sheet, which is pointless.

Do and How:

1. Config tab layout (new), row 1 header: `Type | Key | Value1 | Value2 | Value3 | Value4 | UpdatedAt | UpdatedBy | Deleted`.
   Row 2 is metadata: `meta | schemaVersion | 1`. Items follow, one per row:
   - `account`: Key name; Value1 lastFour, Value2 isDefault, Value3 isArchived, Value4 displayOrder.
   - `category`: Key name; Value1 excludedFromBurn, Value2 isDefault, Value3 isArchived, Value4 displayOrder.
   - `person`: Key name; Value1 order, Value2 isArchived.
   - `trip`: Key name; Value1 isActive, Value2 isArchived.
   - `pref`: Key preference key; Value1 value.
   `UpdatedAt` is an ISO-8601 UTC string (`2026-11-10T09:30:00Z`); `UpdatedBy` the device id; `Deleted` is `true` or blank.
   Booleans are the strings `true` and `false`. The tab stays hidden, protected by nothing, and contains no financial data.
2. Model and repository: `ConfigRow(type, key, values: List<String>, updatedAt: Instant, updatedBy, deleted)` in `domain`;
   `ConfigRepository` in `data` with `readAll()` (one `values.get` of `'Config'!A1:I` unformatted),
   `upsert(rows)` and `tombstone(type, key)`. Build a map from `"type|key"` to sheet row on read. Update an existing row
   with `batchUpdateValues`; add a new row with `values.append` (RAW) to `'Config'!A:I`.
3. Device id: create a random UUID on first run, store it under preference key `device_id`. Define a constant set
   `LOCAL_ONLY_PREF_KEYS` (`device_id`, signed-in account email, all spreadsheet ids, permission and health state, widget
   state); anything in the set is never pushed or pulled.
4. Room migration v2 to v3, in `Migration(2, 3)`: for accounts, categories, companions, trip tags and the preference
   table add `updatedAt INTEGER NOT NULL DEFAULT 0` and `dirty INTEGER NOT NULL DEFAULT 0`; add `isArchived` where a table
   lacks it (companions, trip tags). Read the real table names from each entity's `tableName` (the preference table name may
   differ from the one used in the migrator's table list). Mirror each default in the entity with
   `@ColumnInfo(defaultValue = "0")`. If 2.2 needs a status data fix, put it in the same migration. Keep `sync_queue`
   untouched.
5. Every write path for these entities sets `updatedAt = now` and `dirty = 1`. Deleting an item archives it (or for a
   preference removes it) and writes a tombstone on push.
6. `SyncConfigUseCase.pull()`: read all rows; for each remote row find the local item by (type, key): none and not
   deleted, insert it with the remote `updatedAt`; none and deleted, skip; present, the later `updatedAt` wins; remote
   deleted and newer, archive locally (preference: delete). Unknown types and columns are ignored and left in the sheet.
   If the sheet `schemaVersion` is higher than the app knows, pull but never push, and flag "update the app".
7. `push()`: collect `dirty = 1` items, write only those rows, then clear `dirty` for exactly the items written. Push
   runs through the serialised queue: add operation type `OP_CONFIG_PUSH`, keep at most one pending push operation, and
   debounce edits by about 3 seconds in an application-scoped coroutine (`ConfigSyncCoordinator`).
8. Triggers: pull after sign-in, on app start, and on foreground at most once every 10 minutes (keep the last pull time in
   memory). Push after any settings change.
9. Setup order for an existing workbook: resolve, pull, then regenerate Dashboard dropdowns and the category breakdown.
   For an empty `Config` tab push local defaults once. Never regenerate before a successful pull.
10. Remove the workbook id from the mirrored preferences and delete the old fixed-grid code path only after the new one is
    in use; keep reading the old grid once if the new layout is absent, so an old workbook migrates.

Watch out:

- Writing the same item from two devices: the later `updatedAt` wins; devices with wrong clocks are accepted as a known
  limit.
- Room rejects a migration whose columns or defaults differ from the entity. Compare against the exported schema JSON.
- Do not push on every keystroke; the 3 second debounce is required.

Check: trace a second device signing in, pulling, and only then regenerating the Dashboard; trace an edit on one device
reaching the other without overwriting unrelated rows.

Done when (Phase 2): the build passes and the checks in 2.1 to 2.4 are written in your report.

---

## Phase 3 - Accounting engine (Room version 4)

Status: Complete (merged into main). Goal: the iOS waterfall, netting, carry-forward and void as pure Kotlin in `domain`, applied through
the gateway. Port from `docs/ios_reference/Code.gs`. Spec section 9 is authoritative.

### 3.0 Preparation

Read completely: `Code.gs` functions `handleLogDebit`, `handleLogCredit`, `handleLogPayable`, `handleLogRepayment`,
`reconcileDebitsWaterfall`, `updateLinkedDebitSettlement`, `reconcilePayablesWaterfall`,
`applyRepaymentToPayableRow_`, `offsetDebitsAgainstPayables`, `offsetPayablesAgainstDebits`, `carryForwardUnsettled`,
`resolveCarriedRow_`, `extractAllRowReferences`, `handleYearClose`. Then the existing `CalculateSplitUseCase`,
`LogExpenseUseCase`, `SheetPayloadMapper`.

### 3.1 Structure

1. Domain models (`domain/model`, no Android types):
   - `LedgerDebit`, `LedgerPayable` (from 2.3), `RowKey(tab, row)`.
   - `CellEdit(tab, row, column: String, value: Any?)` where `column` is the sheet letter (`J`, `K`, `H`, `Z`, `AB`, ...).
   - `NewRow(table: DEBIT|INFLOW|PAYABLE, tab, uuid, cells: List<Any?>)` with the cell list for that table only
     (11, 8 or 7 values).
   - `SettledRef(tab, row, uuid, applied, remaining)`.
   - `AccountingPlan(edits, appends, settled, excess, unmatched, message)`.
2. Pure helpers in one file, used everywhere:
   - `tokens(s)`: replace `\s+(&|and)\s+` (case-insensitive) with `, `, split on `,`, trim, drop blanks.
   - `round2(x)`: `BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_UP).toDouble()`. Never `BigDecimal(x)`.
   - `eq(a, b)` for names: `a.trim().lowercase() == b.trim().lowercase()`.
   - Tolerances (from `Code.gs`): 0.009 for "money left", 0.05 for "share fully covered", 0.01 for "payment covers whole
     balance".
3. Use cases, each `operator fun invoke(snapshot, request): AccountingPlan` and none does I/O:
   `CalculateSplitUseCase`, `SettlementWaterfallUseCase`, `RepaymentWaterfallUseCase`, `NettingUseCase`,
   `CarryForwardUseCase`, `VoidTransactionUseCase`.
4. Work on a mutable copy of the snapshot rows. Each step updates the copy so later steps see earlier changes. At the end,
   collapse edits so each cell has only its final value.
5. Two-phase application (this is what makes retries safe):
   - The queue item stores the request (`payloadJson`) first. Add a nullable `planJson` column to `sync_queue`
     (migration v4).
   - At sync time: read a fresh snapshot, compute the plan, **store the plan in `planJson` before writing anything**,
     then apply it.
   - On any retry, re-apply the stored plan. Never recompute after a partial application, because the sheet has already
     changed and the recomputed plan would be wrong (the payment's remainder would become a surplus).
   - Plan edits are absolute values, so applying them twice is harmless. Appends check for an existing row with the new
     UUID before adding (as for debit appends).
6. Apply order: append new rows (with UUID in the second step), then apply edits with `batchUpdateValues`, then mark the
   item done. For carry-forward the order is different (3.5).
7. Row addressing at apply time: read the UUID columns of each tab involved once, build a `uuid -> row` map, and use it
   for rows that have a UUID. For a row without a UUID (written by iOS) verify that date, time and total still match the
   snapshot, otherwise treat as a logical error (needs attention).

### 3.2 Log expense (debit) with split and netting

Port of `handleLogDebit`.

1. Validate: `total > 0` (amounts already parsed to 2 decimals); otherwise a logical error.
2. `myShare = clamp(requestedMyShare ?: total, 0, total)`; `owed = round2(total - myShare)`.
3. Companions: `Personal` if blank. Otherwise normalise with `tokens` and join with `, `.
4. Split Equally: if `tokens.size >= 1` and mode is Equal: `heads = n + 1`, `myShare = round2(total / heads)`,
   `owed = round2(total - myShare)`. (Code.gs applies the equal rule when there are two or more companions or the mode text
   contains "equal"; the Android UI always sends the mode, so apply it for any companion count in Equal mode.)
5. Safety: if `abs(myShare + owed - total) > 0.01`, set `owed = round2(total - myShare)`.
6. Netting: if `owed > 0` and companions is not `Personal`, run `nettingOnDebit` (3.4) and use its `netOwed` as the row's
   `owed`.
7. Status (port exactly): if `owed > 0.009` then the requested status unless it is `Settled` or `Personal`, in which case
   `Pending`; else if `offsetTotal > 0` then `Settled`; else if requested `Settled` then `Settled`; else `Personal`.
8. Category: `saving` (any case) becomes `Savings`. Build the Table 1 cells in order A to K and a new UUID `txn_<12 hex>`.
9. Message: `Debit logged successfully` plus ` (₹X offset against existing payables to NAMES)` when netting happened.

Watch out: step 6 changes `owed` after the safety check, so `total != myShare + owed` on netted rows is expected and not a
bug. The Dashboard "Paid Back" column uses `G - I - J`, which then counts netted amounts as paid back.

### 3.3 Settlement waterfall (Income / Return, Friend Settlement)

Port of `reconcileDebitsWaterfall` and `updateLinkedDebitSettlement`.

`settleOneDebt(row, paid, settler, allowFull)`:

1. If the row's status is `Carried Fwd`, resolve to the live row (3.5). If it cannot be resolved, return "not usable".
2. If status is not `Pending` or `owed <= 0`, return `applied = 0`, `excess = round2(paid)`.
3. `list = tokens(row.companions)`. `matchIdx` = index of the settler in `list` (case-insensitive) when the settler is
   not blank and `list.size > 1`, else none. `isShared = matchIdx exists`. `share = isShared ? round2(owed / list.size) : owed`.
4. `applied = min(paid, owed)`. If `isShared` and (`!allowFull` or `paid < owed - 0.01`), `applied = min(applied, share)`.
   `applied = round2(applied)`.
5. If `isShared`: if `applied >= owed - 0.05`, set the companions cell to the settler's name; else if
   `applied >= share - 0.05`, remove the settler from `list` and write `list.joinToString(", ")`.
6. `remaining = max(0, round2(owed - applied))`; `excess = max(0, round2(paid - applied))`.
7. Edits: `J = remaining`; `K = if (remaining == 0.0) "Settled" else "Pending"`.

`settlementWaterfall(settler, amount, explicitRefs, creditDate)`:

1. `remaining = round2(amount)`; `settled = []`; `handled = {}`.
2. For each explicit reference in the order given: stop if `remaining <= 0.009`; `res = settleOneDebt(row, remaining,
   settler, allowFull = true)`; skip unresolvable rows; add the live row to `handled`; if `res.applied > 0` append to
   `settled` and set `remaining = res.excess`.
   References are UUIDs from the picker, or `Sheet!RowN` text (regex `(?:'([^']+)'|([A-Za-z0-9_]+))!(?:Row)?(\d+)`).
   The "Auto-Settle (All / Oldest First)" entry yields no reference.
3. If `remaining > 0.009` and the settler is not blank: walk all debits oldest first. Skip handled rows, rows not
   `Pending`, rows with `owed <= 0`, and rows whose companions do not contain the settler. For each, call
   `settleOneDebt(..., allowFull = false)`; if `applied > 0` record and set `remaining = res.excess`. Stop when
   `remaining <= 0.009`.
4. `unmatched = remaining > 0.009 && settled.isEmpty()`.
5. If `remaining > 0.009` and the settler is not blank and `settled` is not empty: append a Table 3 row in the credit date's
   tab: date, time, `paidBy = settler`, `whatFor = "Excess settlement credit"`, `amountOwed = remaining`, repaid date
   blank, status `Pending`, new UUID `pay_<12 hex>`.
6. The credit row (Table 2): date, time, account, inflow type, received from, reason, amount, and the linked column set to
   the settled rows joined by `, `: use the debit's UUID if it has one, otherwise `Tab!RowN`.
7. Message: `Credit logged successfully`, plus ` (N debt(s) reconciled)` when N > 0, plus
   ` (Excess of ₹X logged to Payables Row R)` when a surplus row was added, plus
   ` (No open debts found for NAME; credit left unlinked)` when unmatched.

### 3.4 Repayment waterfall and netting

`applyRepaymentToPayable(row, creditor, remaining, date)`:

1. Status must be `Pending` or `Partial` and `amountOwed > 0`, else return null.
2. `list = tokens(row.paidBy)`; `matchIdx` = index of the creditor if the creditor is not blank; if the creditor is not blank and
   there is no match, return null.
3. `isShared = list.size > 1 && matchIdx exists`; `share = isShared ? round2(owed / list.size) : owed`.
4. `applied = round2(min(remaining, share, owed))`; if `applied <= 0` return null; `newOwed = max(0, round2(owed - applied))`.
5. Edits: `AA` = date serial; if `isShared && applied >= share - 0.05`, `paidBy` = list without the creditor, joined;
   `Z = newOwed`; `AB = if (newOwed > 0) "Partial" else "Repaid"`.

`repaymentWaterfall(creditor, amount, explicitRef?, date)`:

1. `remaining = round2(amount)`. If an explicit payable is given, resolve a carried row (3.5), apply, record, and subtract.
2. While `remaining > 0.009` and the creditor is not blank: walk payables oldest first, skip handled rows, skip statuses other
   than `Pending` or `Partial`, skip rows whose `paidBy` tokens do not contain the creditor, apply, record, subtract.
3. If `remaining > 0.009` and at least one payable was repaid: append a Table 1 row in the repayment date's tab:
   item `[Carried Over] Excess repayment balance`, category `Other`, total `remaining`, companions = creditor, my share `0`,
   owed `remaining`, status `Pending`, new UUID `txn_<12 hex>`.
4. If nothing was repaid and there is no surplus row: return a "not found" result with message
   `No pending payable found for NAME`; write nothing.
5. Message: `Repaid N payable(s)`, plus ` (Overpayment of ₹X logged to Debits Row R)` when a surplus row was added.
   The Android flow always supplies an amount, so the iOS no-amount mode is not ported.

`nettingOnDebit(companions, owed, date)` (port of `offsetDebitsAgainstPayables`):

1. `list = tokens(companions)`. If empty or `owed <= 0` return `netOwed = owed`, `offset = 0`.
2. `cap = round2(owed / list.size)`.
3. For each companion: `capLeft = cap`. Walk payables oldest first; stop when `capLeft <= 0.009` or `owedLeft <= 0.009`;
   skip statuses other than `Pending`/`Partial`; skip rows whose `paidBy` tokens do not contain the companion; call
   `applyRepaymentToPayable(row, companion, min(capLeft, owedLeft), date)`; if it returns a result subtract `applied` from
   `capLeft` and `owedLeft` and add it to `offsetTotal`.
4. Return `netOwed = owedLeft`, `offsetTotal`.

`nettingOnPayable(creditor, amount, date)` (port of `offsetPayablesAgainstDebits`):

1. If the creditor is blank or `amount <= 0` return `net = amount`, `offset = 0`.
2. Walk debits oldest first; skip rows not `Pending` or with `owed <= 0` or whose companions do not contain the creditor;
   call `settleOneDebt(row, min(owed, remaining), creditor, allowFull = false)`; subtract `applied` from `remaining` and add
   to `offsetTotal`; stop at `remaining <= 0.009`.
3. The new payable row: amount `net`; status `Repaid` with the date as `dateRepaid` when `net <= 0`, else `Pending` and blank.
   The row is written even when `net` is 0. Message ` (₹X offset against existing debits owed by NAME)` when offset > 0.

### 3.5 Carry-forward, carried-row resolution, void, year close

Resolution of a `Carried Fwd` row (`resolveCarried`): up to 12 hops. First use the lineage table (3.6): find the row whose
`carried_from_uuid` equals this row's UUID (or `Tab!RowN` key for rows without a UUID). If the lineage table has nothing,
fall back to the description tag: find a row in another tab whose description equals `[Carried from <this tab>] <this
description>`. Stop at the first row that is not `Carried Fwd`.

`carryForward(targetTab)`:

1. Preconditions: the target tab exists (create and style it first, 2.1b). Check the target tab for any row whose
   description starts `[Carried from `; if one exists for the same source tab, stop (already done).
2. Find the source tab: look back up to 12 months from the target for the most recent existing month tab, including the
   previous year's workbook if it exists (read-only, `findExisting`).
3. For each source debit with status `Pending` and `owed > 0`: new row with the original date serial and time serial,
   account, description `[Carried from <SourceTab>] <item>`, trip tag, category, `total = owed`, companions, `myShare = 0`,
   `owed = owed`, status `Pending`, new UUID. Record lineage (3.6). Queue an edit setting the source row status to
   `Carried Fwd` (leave `owed` unchanged for audit).
4. For each source payable with status `Pending` or `Partial` and `amountOwed > 0`: new row with the original dates,
   `paidBy`, `whatFor = [Carried from <SourceTab>] <whatFor>`, `amountOwed`, repaid date blank, status `Pending`, new UUID.
   Queue an edit setting the source status to `Carried Fwd`.
5. Apply order: **append all new rows first, then apply the source status edits**. A crash in between then leaves a
   harmless duplicate that step 1's guard detects, never a lost debt.
6. Triggers: (a) when the first transaction of a month needs its tab; (b) a daily periodic WorkManager job that, on or after
   day 25, creates next month's tab and runs `carryForward` for it. Both are safe to repeat.

`voidDebit(row)`: edits `D = "[Void] " + item` (only if not already prefixed), `G = 0`, `I = 0`, `J = 0`, `K = "Void"`. Keep the
UUID. Never delete or blank a row. `voidPayable(row)`: description prefix, `Z = 0`, `AB = "Void"`.

Year close (`handleYearClose`): collect all `Pending` debts with owed above 0 and all `Pending`/`Partial` payables across the
year's tabs; write a `CarryFwd` tab (title line, generated timestamp, two sections with headers) and upload
`carry_forward_<year>.json` to the Drive folder (create or replace the same file name). Triggered manually from Settings.

### 3.6 Lineage table (Room version 4)

`Migration(3, 4)`: create table `lineage(uuid TEXT PRIMARY KEY NOT NULL, carried_from_uuid TEXT NOT NULL, root_uuid TEXT NOT
NULL, created_at INTEGER NOT NULL)` and add `planJson TEXT` to `sync_queue` (nullable, no default). Match the entity
annotations exactly. The code must not parse description prefixes to find lineage, except the fallback in `resolveCarried`.

### 3.7 Worked examples (trace each by hand through your code and write the result in the report)

1. Equal split: total 600, two companions. Heads 3, my share 200, owed 400, status `Pending`.
2. Just Me: total 120. my share 120, owed 0, companions `Personal`, status `Personal`.
3. Waterfall with surplus: Raghav owes 250 on row A (single). Row B has owed 300 with companions Raghav, Rohit. Raghav pays 500
   and selects row A. Row A: applied 250, owed 0, `Settled`; remaining 250. FIFO reaches row B: share 150, applied
   min(250, 300) capped to 150. Raghav is removed from the companions, row B owed becomes 150, status stays `Pending`.
   Remaining 100; one debt settled, so a payable of 100 "Excess settlement credit" is added. Message: 2 debts reconciled
   plus the excess note.
4. Unmatched: 200 from a name with no open debts. Credit row only; no edits; `unmatched = true`.
5. Netting on a debit: payable of 100 owed to Rohit. New debit total 600 with companion Rohit, split equally: my share 300, owed
   300. Cap 300; the payable is applied 100: `Z = 0`, `AB = Repaid`. Debit owed becomes 200, status `Pending`.
6. Netting on a payable: Rohit owes 250 on a debit. A new payable from Rohit of 100: debit applied 100, owed 150, status
   `Pending`; payable amount 0, status `Repaid`.
7. Carry-forward: November row owed 400, `Pending`. December tab is created. December gets total 400, my share 0, owed 400,
   description `[Carried from Nov26] ...`, status `Pending`. November status becomes `Carried Fwd`. Dashboard gross outflow for
   December excludes it because the description starts with `[Carried`.
8. Retry safety: a settlement plan is stored, two of five edits are applied, the process dies. The retry re-applies the stored
   plan without reading a new snapshot; the final sheet equals the one-shot result.

Done when: the build passes, the `domain` package has no Android imports, and each example traces correctly.

---

## Phase 4 - Capture pipeline (Room version 5)

Status: Complete (merged into main). Goal: parse in the background, keep a pending capture, and show the prompt when the payment app is
closed. Spec section 4. Audit items 6, 7, 8, 11.

### 4.0 Preparation

Read completely: `NotificationListenerServiceImpl.kt`, `NotificationProcessor` (inside it), `ParseNotificationUseCase.kt`,
`RegexPatterns.kt`, `PackageWhitelist.kt`, `QuickActionReceiver.kt`, `SettleWindowWorker.kt`, the debug simulator.

### 4.1 Pending captures and handled notifications

Do: stop launching UI from the listener; write a pending capture instead.

How:

1. `Migration(4, 5)`: create `pending_captures(id TEXT PRIMARY KEY NOT NULL, sourcePackage TEXT NOT NULL, amount REAL,
   payee TEXT NOT NULL, inferredAccount TEXT, timestamp INTEGER NOT NULL, kind TEXT NOT NULL, flags TEXT NOT NULL DEFAULT '',
   state TEXT NOT NULL, notificationKey TEXT, contentHash TEXT, updatedAt INTEGER NOT NULL)` and
   `handled_notifications(key TEXT NOT NULL, postTime INTEGER NOT NULL, contentHash TEXT NOT NULL, handledAt INTEGER NOT NULL,
   PRIMARY KEY(key, postTime))`. Add DAOs, entities with exactly matching `@ColumnInfo` defaults.
   `kind` is `DEBIT` or `CREDIT`. `state` is `WAITING`, `AWAITING_SUCCESS`, `SHOWN`, `DONE`, `DISMISSED`. `flags` is a
   comma list of `NEEDS_AMOUNT`, `POSSIBLE_DUPLICATE`. `amount` is null when unreadable.
2. `contentHash` is a SHA-256 of the lower-cased title plus text, truncated to 16 hex characters, computed in memory. It is
   the only thing derived from the alert text that is stored. Raw title and text are never stored or logged.
3. Processing order inside `NotificationProcessor` (keep the existing checks, in this order): own package, blacklist,
   whitelist, OTP, handled-notification check, parse, decide capture. A payment alert must only create or update a
   `PendingCapture`; `NotificationProcessor` does not receive a `TransactionRepository` in production and never inserts a
   transaction, shows an overlay or posts a prompt itself. Delete any existing direct transaction insert, the outcome type
   that carried it and the listener branch that handled it. No transaction row exists until the user confirms in the sheet
   (Phase 5) or taps a category button in the fallback notification.
4. Handled-notification check: look up `(sbn.key, postTime)`. If present and the hash is identical, drop (repeat or reconnect
   replay). If the key exists with a different hash, it is an update of the same payment (4.2). Prune rows older than 24 hours
   whenever a row is added.
5. Credits: remove the "non-debit ignored" branch. A credit alert creates a capture with `kind = CREDIT`.
6. Unreadable alert (spec 4.5): a whitelisted alert that passes privacy checks but yields no amount creates a capture with
   `amount = null`, payee `Unknown Payee`, `flags = NEEDS_AMOUNT`.
7. Failure alerts: an alert whose wording says the payment failed, was declined or reversed creates no capture. If the parser
   already drops these, keep that and say so.
8. Inferred account: compare the alert text (in memory, at parse time only) with each configured account: match the account
   name as a whole word (case-insensitive) or the last four digits (`XX1234`, `x1234`, `ending 1234`). The first match wins; no
   match gives the default account. Store only the account name.

### 4.2 Pending-to-success and duplicate merging

1. Wording classes in the domain parser: processing (`processing`, `pending`, `initiated`, `in progress`), success (`paid`,
   `successful`, `debited`, `sent`, `credited`, `received`), failure (`failed`, `declined`, `unsuccessful`, `reversed`).
   Keep the lists as constants so they are easy to extend.
2. A processing alert creates a capture in `AWAITING_SUCCESS` (not shown). A later alert with the same `sbn.key` and success
   wording updates that capture (amount, payee, state to `WAITING`). A failure alert with the same key marks it `DISMISSED`.
3. Cross-source merge: a new capture with the same amount, a different source package and a timestamp within 90 seconds of an
   existing `WAITING` or `AWAITING_SUCCESS` capture is merged into it (fill missing payee or account; do not create a second
   capture). If the existing capture was already `SHOWN` or `DONE`, create the new one with `POSSIBLE_DUPLICATE`.
   Never drop silently.
4. Marking a possible duplicate as a duplicate: if its transaction is not yet synced, delete it locally and queue nothing; if it
   is synced, queue a void (Phase 3 `voidDebit`).

### 4.3 Foreground watcher and trigger rule

Do: show the prompt when the source payment app is not in the foreground. No timeout.

How:

1. Manifest: `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions"/>`
   (declare the `tools` namespace). Remove the unused `FOREGROUND_SERVICE` permissions unless a service is added.
2. `hasUsageAccess(context)`: `AppOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)`
   equals `MODE_ALLOWED` (use `checkOpNoThrow` below API 29).
3. `ForegroundWatcher` (in `service/`, owned by the listener's coroutine scope, not a separate service):
   - `isInForeground(pkg)`: call `UsageStatsManager.queryEvents(now - 10 * 60_000, now)`, iterate events, and keep per package
     the time of the last resume event (`ACTIVITY_RESUMED` on API 29 and later, `MOVE_TO_FOREGROUND` before) and the last
     pause event (`ACTIVITY_PAUSED` or `MOVE_TO_BACKGROUND`). The package is in the foreground when its last resume is later
     than its last pause. No events means background.
   - Run only while at least one capture is `WAITING`: a coroutine that polls every 700 ms and cancels itself when none is
     left. Require two consecutive polls showing "not in foreground" before showing, because events can arrive late.
4. Trigger rule for a capture in `WAITING`: if the source package is not in the foreground, show now. Otherwise keep polling.
   If `hasUsageAccess` is false, show immediately (spec 4.3 fallback).
5. Screen and lock state: if `PowerManager.isInteractive` is false or `KeyguardManager.isKeyguardLocked` is true, wait. Register a
   receiver for `ACTION_SCREEN_ON` and `ACTION_USER_PRESENT` while a capture is waiting and re-check when they fire.
6. When showing, set the capture to `SHOWN` and hand it to the overlay (Phase 5). A capture is finished only by Confirm and
   Save (`DONE`) or an explicit dismissal (`DISMISSED`, which also puts a Needs Review entry in the inbox). Nothing times out.
7. On process start (listener connected, boot), reload all `WAITING` captures and resume the rule above. This is what makes the
   prompt survive a killed process.

Watch out:

- The listener's callbacks run on the main thread; do all work on `Dispatchers.IO`, and persist the capture before anything is
  shown.
- Usage events lag on some phones; the two-poll rule exists for that reason. Do not shorten it.
- Do not hold a wake lock.

### 4.4 Listener resilience

1. `BootReceiver` (manifest: `exported="false"`, actions `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`): on receive, run the
   rebind routine, resume waiting captures, and re-enqueue the periodic jobs.
2. Rebind routine: if `NotificationManagerCompat.getEnabledListenerPackages(context)` contains the package but the listener is
   not connected (a `@Volatile` flag set in `onListenerConnected` and cleared in `onListenerDisconnected`), call
   `PackageManager.setComponentEnabledSetting` with `COMPONENT_ENABLED_STATE_DISABLED` then `..._ENABLED` (flag
   `DONT_KILL_APP`) and `NotificationListenerService.requestRebind(componentName)`.
3. Periodic health worker, every 6 hours, network not required: run the checks in 4.4.2 and, if the user must act (access
   revoked), post one low-priority notification (a single id, never repeated while visible, and only if notifications are allowed).
4. Catch-up: `onListenerConnected` reads `getActiveNotifications()` posted in the last 5 minutes and sends them through the same
   processor; the handled-notification check (4.1.4) prevents duplicates.
5. Replace every `catch (_: Exception)` in the listener with a call to `Diagnostics.record(reason)` that stores a short
   redacted reason (exception class and place, never content) in a small capped Room or file list shown in Settings. A failure
   must be visible.
6. Delete `SettleWindowWorker` and its scheduling (the capture rule replaces the settle window), and remove the heads-up
   category buttons' hardcoded categories (Phase 5 replaces them with predicted ones).

### 4.5 Debug hook

The debug-only simulator in `app/src/debug` sends its synthetic alerts through the same `NotificationProcessor` entry as the real
listener. Debug builds additionally allow the shell package and a test companion package in the whitelist through a
`BuildConfig.DEBUG` guard in one place; release builds must not.

Done when: the build passes and your trace shows (a) alert to pending capture, (b) the foreground wait with two-poll
confirmation, (c) credit capture, (d) processing then success on one key, (e) reconnect replay not duplicating, (f) an unreadable
alert opening with the amount missing, and (g) a negative check: after an alert is processed, no transaction exists (show that
`NotificationProcessor` has no repository dependency and that nothing in the listener path calls an insert).

---

## Phase 5 - Overlay and flows (Room version 6)

Status: Complete (merged into main). Goal: the iOS-Shortcuts-style overlay and every flow behind it. Spec section 3. Audit items 12, 13.

### 5.0 Preparation

Read completely: `ModalBottomSheetActivity.kt`, `AppleExpensePromptSheet.kt`, `ExpenseBottomSheet.kt`, `ExpenseViewModel.kt`,
`QuickActionReceiver.kt`, `QuickSettingsTileService.kt`, theme files, and `docs/ios_reference/SHORTCUTS_MASTER_REFERENCE.md`.

### 5.1 Overlay controller (no started service)

Reason: a background service start is restricted on current Android; the listener process is already alive when a capture is
ready, so the overlay is hosted in the application process. The engineering plan calls the component `OverlayService`; build
`OverlayController` and report the rename.

How:

1. `OverlayController` is an application-scoped object created in `AppContainer`. It owns one overlay window at a time and has
   `show(request)`, `update(request)`, `dismiss()`.
2. `canDrawOverlays = Settings.canDrawOverlays(appContext)`. If false, do not add a window; use the fallback in 5.5.
3. Window: `WindowManager.LayoutParams(MATCH_PARENT, MATCH_PARENT, TYPE_APPLICATION_OVERLAY, FLAG_NOT_FOCUSABLE or
   FLAG_LAYOUT_IN_SCREEN or FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT)`, `softInputMode = SOFT_INPUT_ADJUST_RESIZE`.
   The full-screen container draws the scrim and dismisses on a tap outside the sheet; the sheet sits at the bottom with a
   large corner radius.
4. Compose in a window: `ComposeView` needs three tree owners before it is attached. Create one helper class that implements
   `LifecycleOwner`, `SavedStateRegistryOwner` and `ViewModelStoreOwner`, with a `LifecycleRegistry`, a
   `SavedStateRegistryController` and a `ViewModelStore`. Before `addView`: `savedStateController.performAttach()`,
   `performRestore(null)`, then move the lifecycle to `CREATED`, `STARTED`, `RESUMED`. Then
   `ViewTreeLifecycleOwner.set(view, owner)`, `ViewTreeSavedStateRegistryOwner.set(view, owner)`,
   `ViewTreeViewModelStoreOwner.set(view, owner)`. Use `ViewCompositionStrategy.DisposeOnDetachedFromWindow`. On `dismiss`, move
   the lifecycle to `DESTROYED`, clear the `ViewModelStore`, and `removeView`.
5. Keyboard: the window starts with `FLAG_NOT_FOCUSABLE`. When a text field gains focus, clear that flag and call
   `windowManager.updateViewLayout`, then show the keyboard; when the field loses focus or the sheet closes, restore the flag.
   If `FLAG_NOT_FOCUSABLE` stays set, the keyboard never opens.
6. Blur: on Android 12 and later, if `windowManager.isCrossWindowBlurEnabled`, set `FLAG_BLUR_BEHIND` and `blurBehindRadius`
   (about 24 px) on the layout params, and listen with `addCrossWindowBlurEnabledListener` so it turns off when the system
   disables blur. Below Android 12 or with blur disabled use a translucent scrim colour from the theme.
7. Insets and cutouts: pad the sheet with system bars and display cutout insets so it never hides under a notch or the
   navigation bar. Do not resize the app underneath.
8. Look: spring animation in and out, a haptic tick on confirm (`HapticFeedbackConstants.CONFIRM` on 30 and later, else
   `VIRTUAL_KEY`), colours, shapes and type from `ui/theme`, light and dark themes (remove the forced light theme in
   `MainActivity`).
9. Never enter Recents: a window is not an activity. Do not start any activity to show it.

Watch out: do not use an Activity context for the window; use the application context.

### 5.2 Entry points

1. `EntryActivity` (new, transparent, `excludeFromRecents`, `noHistory`, `taskAffinity=""`, theme with no animation): started by the
   widget, the Quick Settings tile, a pinned shortcut and notification taps. In `onCreate` it asks `OverlayController` to show
   the Choose Action menu (or a given pending capture) and calls `finish()` immediately. If overlay permission is missing it
   shows the same sheet inside the activity (5.5) instead.
2. Capture ready (Phase 4): the listener's coroutine calls `OverlayController.show` directly, with no activity.
3. `ModalBottomSheetActivity` becomes the fallback host of the same composable (5.5).

### 5.3 Quick Log sheet

1. State holder `QuickLogViewModel` (scoped to the overlay's `ViewModelStore`), fed by a `PendingCapture` or by empty defaults.
2. Fields and controls, top to bottom: amount (focus requested when `NEEDS_AMOUNT`), payee, account chips (inferred account
   pre-selected), category chips (5.4), split control (Just Me, Split Equally, Custom Split), companion chips from the people list
   with `+ Add New Friend` (name entry inline), custom share field, trip tag chip (only when a trip is active, with start and end
   actions), Confirm and Save.
3. Split maths on the screen uses the same domain `CalculateSplitUseCase` as Phase 3, so the preview equals what is saved.
4. Confirm and Save: validate amount > 0; write a queue item with the request (Phase 3 two-phase flow); mark the capture `DONE`;
   update `category_stats` (5.4); close the sheet; show the result pill ("Saved, will sync" when offline, otherwise the plan's
   message when the sync result returns).
5. The result pill is a second small overlay (top centre, capsule shape, auto-hides after about 3 seconds, tap to dismiss).
6. Dismissal (outside tap, swipe, Close) sets the capture `DISMISSED` and shows it in Needs Review (spec 4.2 point 4).

### 5.4 Category and account prediction (Room version 6)

1. `Migration(5, 6)`: create `category_stats(payeeKey TEXT NOT NULL, category TEXT NOT NULL, count INTEGER NOT NULL,
   lastUsed INTEGER NOT NULL, PRIMARY KEY(payeeKey, category))`.
2. `payeeKey(payee)`: lower-case, remove everything except letters, digits and spaces, collapse spaces, truncate to 40
   characters.
3. Prediction: categories for the payee ordered by `count` desc then `lastUsed` desc; then global totals for the rest. Fallback
   `Other`. The chips show this order; the first is pre-selected.
4. On confirm: increment the row for (payeeKey, chosen category) and set `lastUsed`.
5. The heads-up fallback notification (5.5) uses the top two predicted categories, not fixed ones.

### 5.5 Fallbacks

1. No overlay permission: `ModalBottomSheetActivity` hosts the sheet as a translucent activity (`excludeFromRecents`, `noHistory`),
   started only by a user action.
2. A capture ready while overlay permission is missing: post a heads-up notification (existing channel) with two predicted
   category buttons and a Details button (opens `EntryActivity`). The two category buttons call the existing quick-action receiver
   with the capture id and category, which queues the item.

### 5.6 Choose Action menu and flows

Menu entries: Expense, Income / Return, Friend Paid For Me, Repaid a Friend, Trip Mode. Port prompt wording from the iOS reference.
Every flow produces a queue request; Phase 3 turns it into a plan.

1. Expense: the Quick Log sheet with empty fields.
2. Income / Return: amount, account, inflow type (Friend Settlement, Salary/Stipend, Allowance, Other), who sent it (people chips or
   typed), reason. For Friend Settlement: read the snapshot (Phase 2.3) for that person's open debts (status `Pending`, owed above
   0, person in companions) and show them as a multi-select list, with "Auto-Settle (All / Oldest First)" first and every row
   labelled like `Dinner (₹500 - Rohit) [Nov26]`. An empty list skips the picker. Offline: skip the picker and use auto-settle,
   because the waterfall handles it.
3. Friend Paid For Me: amount owed, who paid (people chips or typed), what for.
4. Repaid a Friend: person, amount, date (default today). The amount is mandatory.
5. Trip Mode: if a trip is active, offer End Trip and Keep Active; otherwise ask for a tag and start it. One active tag at a
   time, stored in the trip tags table and pushed by settings sync (Phase 2.4).
6. Results: the plan's `message` is shown in the pill. A logical error (unknown reference, invalid amount) lands in Needs Review
   with the plain message.

### 5.7 People list

Rebuild from the snapshot: names from companions (Table 1) and `paidBy` (Table 3), normalised with `tokens`, ranked by frequency in
the current and previous month, then older rows at half weight, up to the six top names for chips and all names for search. Merge
into the local people table without removing manually added names. Refresh in the background on app start and after a sync; never
block the sheet on it.

Done when: the build passes and your trace covers: capture to overlay to queue item; each of the five flows to a queue request; the
fallback paths; the pill; and the predicted chip order.

---

## Phase 6 - Launcher widget and shortcut

Status: Complete (merged into main). Spec section 5. Audit item 14.

### 6.0 Preparation

Read completely: `QuickLogWidgetProvider.kt`, `res/layout/widget_quick_log.xml`, `res/xml/quick_log_widget_info.xml`, the drawables
for the widget, `QuickSettingsTileService.kt`, the manifest.

### 6.1 Widget size handling

Goal: the widget looks like a normal app icon at whatever size the user's launcher gives it.

1. Keep `targetCellWidth = 1`, `targetCellHeight = 1`, `resizeMode = none`, plus `minWidth` and `minHeight` (40dp is fine for
   older launchers).
2. Read the size on every `onUpdate` and every `onAppWidgetOptionsChanged`:
   - Android 12 and later: `options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES)` returns a list of `SizeF` in dp;
     use the smallest width and height.
   - Earlier: `OPTION_APPWIDGET_MIN_WIDTH` and `OPTION_APPWIDGET_MIN_HEIGHT` in dp (use portrait/landscape correctly: the
     minimum is the safe size).
   Convert dp to pixels with `TypedValue.applyDimension(COMPLEX_UNIT_DIP, ..., displayMetrics)`.
3. Draw the icon as a bitmap of side `min(widthPx, heightPx)`: an ARGB_8888 canvas, a rounded rectangle in the theme's icon
   background colour with radius equal to the system widget radius where available
   (`context.resources.getDimension(android.R.dimen.system_app_widget_background_radius)` on API 31 and later), otherwise
   `0.22 * side`; then the app symbol (vector drawable rendered into the same canvas) centred at about 56% of the side. Set it with
   `RemoteViews.setImageViewBitmap`. Do this on every API level so behaviour is identical.
4. The layout contains only a root `FrameLayout` and a `match_parent` `ImageView` with `scaleType="fitCenter"`. It must contain no
   fixed `dp` or `px` dimensions and no padding. All sizing comes from the bitmap.
5. Colours follow the system theme: use the app's themed colour resources with `values-night` variants, and on Android 12 and
   later may use the dynamic colour resources if the user's wallpaper theme is on.

Watch out: very large bitmaps in `RemoteViews` crash the launcher host. Never exceed the display size; the icon side is small, so
this is fine, but cap it at 512 px to be safe.

### 6.2 Tap behaviour

The click `PendingIntent` is `PendingIntent.getActivity` for `EntryActivity` with `FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT` and a
unique request code per widget id. It must never open the dashboard (`MainActivity`).

### 6.3 Pinned shortcut and static shortcut

1. Settings gets a row "Add as app icon". It calls `ShortcutManagerCompat.isRequestPinShortcutSupported(context)`; when true, build
   `ShortcutInfoCompat.Builder(context, "quick_log")` with a short label, an icon from the adaptive launcher icon
   (`IconCompat.createWithAdaptiveBitmap` of the rendered icon, or `createWithResource`), and an intent for `EntryActivity` with
   `Intent.ACTION_VIEW`; then `ShortcutManagerCompat.requestPinShortcut(context, info, null)`. When not supported, show a short
   explanation.
2. Add one static shortcut (`res/xml/shortcuts.xml`, referenced by `<meta-data android:name="android.app.shortcuts">` on the
   launcher activity) so a long press on the app icon shows "Quick log".

### 6.4 App icon

Replace the default system icon with an adaptive icon: `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` with
`background`, `foreground` and (Android 13 and later) `monochrome` layers, and reference them from the manifest.

### 6.5 Quick Settings tile

`onClick` starts `EntryActivity`: on API 34 and later use `startActivityAndCollapse(PendingIntent)`, below that
`startActivityAndCollapse(Intent)` (deprecated but valid). Label "Quick log". Unlock handling: if the device is locked, use
`unlockAndRun`.

Done when: the build passes, the widget layout has no fixed dimensions, and your trace shows the sizing path for API 28, 30 and 31
and later.

---

## Phase 7 - Setup health screen and OEM hardening

Status: Complete (merged into main). Engineering plan sections 2 and 3.

### 7.0 Preparation

Read completely: the manifest, `OnboardingViewModel`, the listener, the Phase 4 `Diagnostics` list and watcher.

### 7.1 Health checks

Create `SetupHealthViewModel` with one check function per row, each returning `OK`, `MISSING` or `UNKNOWN`, re-run on every screen
resume and after returning from a settings screen:

1. Notification access: `NotificationManagerCompat.getEnabledListenerPackages(context).contains(packageName)`; also show whether
   the listener is currently connected (the flag from Phase 4).
2. Display over other apps: `Settings.canDrawOverlays(context)`.
3. Usage access: `hasUsageAccess(context)` (Phase 4).
4. Post notifications (Android 13 and later): `ContextCompat.checkSelfPermission(POST_NOTIFICATIONS)`; request at runtime.
5. Battery optimisation: `PowerManager.isIgnoringBatteryOptimizations(packageName)`.
6. OEM background settings: cannot be read; show as `UNKNOWN` with a "Done" toggle the user sets. Store the toggle locally (not
   synced).
7. Google connection: signed in and a workbook id for the current year exists.

### 7.2 Fix actions (each button opens the exact screen)

- Notification access: on API 30 and later `Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` with
  `Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME`; otherwise `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.
- Overlay: `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` with data `package:<name>`.
- Usage access: `Settings.ACTION_USAGE_ACCESS_SETTINGS` (with the package data on API 29 and later).
- Battery: add `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to the manifest; use `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with the package
  data, and fall back to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.
- Notifications: the runtime permission request.
- Every button first checks `intent.resolveActivity(packageManager) != null`; if not, it opens
  `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the app and shows plain instructions.

### 7.3 OEM handling

Detect `Build.MANUFACTURER` (lower case) and `Build.BRAND`. The brand only chooses which instructions and which candidate intents
to show; it never changes app logic.

| Brand | Instructions to show | Candidate intents (try in order, each must resolve) |
| :--- | :--- | :--- |
| Samsung | Remove the app from sleeping and deep-sleeping apps; set battery to Unrestricted | `com.samsung.android.lool` battery activities; battery settings |
| Xiaomi, Redmi, Poco | Turn on Autostart; allow "Display pop-up windows while running in the background"; battery saver No restrictions; lock the app in Recents | `com.miui.securitycenter` autostart management; `miui.intent.action.APP_PERM_EDITOR` with `extra_pkgname` |
| Oppo, Realme | Allow auto-start and background activity; allow in the battery manager | `com.oplus.battery`, `com.oplus.securitypermission`, `com.oplus.safecenter` activities; `com.coloros.safecenter` and `com.oppo.safe` startup lists; battery settings |
| Vivo | Allow auto-start; allow background pop-up; turn off high background power consumption restriction | `com.vivo.permissionmanager` background start-up manager; `com.iqoo.secure` power pages |
| OnePlus | Battery optimisation set to Don't optimise; allow auto-launch | `com.oplus` activities; `com.oneplus.security` launch list; battery optimisation settings |
| Pixel and others | Standard permissions only | none |

These component names are widely reported but firmware-dependent. Realme candidates are verified on a physical Realme RMX3998 on Android 16; Samsung, Xiaomi and Vivo lists are unverified until tested on those phones. Treat them as candidates: resolve each before use, never crash on a missing one, and never depend on one. Put the instruction text in `strings.xml`.

### 7.4 Self-test and warnings

1. Self-test button: calls `OverlayController.showSelfTest()`, which shows the real Quick Log sheet with sample data and a
   "Test" banner. It does not save, does not create a capture and does not use any whitelisted package, so it works in release
   builds. Report pass or fail by whether `addView` succeeded.
2. Show the force-stop warning text on the screen: "If you force-stop the app, Android turns capture off until you open the app".
3. When notification access is missing, show a persistent low-priority notification "Capture is off - tap to fix" (one id).
4. Run all checks at app start and after boot; badge the Settings tab when any row is `MISSING`.
5. Show the Phase 4 diagnostics list (capped) under an "Advanced" section.

Done when: the build passes and your trace shows each row's check and fix action, the resolve-then-fallback for OEM intents, and
the release-safe self-test.

---

## Phase 8 - UI and UX (design system, home screen, copy)

Status: not started. Authority: [ANDROID_V8_UI_UX_DESIGN.md](./ANDROID_V8_UI_UX_DESIGN.md). Goal: the app looks and reads like a
calm utility, every screen uses one token set and one component set, and a later redesign only touches `theme/` and
`components/`. Open a pull request for this phase and leave it open for review (rule in section 0b).

### 8.0 Preparation

Read the design guide completely, then `theme/Color.kt`, `Theme.kt`, `Type.kt`, `DashboardScreen.kt`, `ReviewInboxScreen.kt`,
`SettingsScreen.kt`, `OnboardingScreen.kt`, `QuickLogSheet.kt`, the flows and the overlay files, and `strings.xml`. The setup health
card still has some hard-coded titles and subtitles; they move to `strings.xml` in this phase.

### 8.1 Tokens

1. Add `FinanceColors` (light and dark values from design guide 3.1) provided through `LocalFinanceColors`; keep the existing
   semantic constants as the source.
2. Add the type roles of 3.2 (amount styles use tabular figures), the spacing scale, the shape set and the motion constants of 3.3
   in `theme/`.
3. Add `formatRupees` (Indian grouping, `en-IN`) in one utility and make it the only money formatter.

### 8.2 Components

Build the components of design guide section 4 in `presentation/components/` as stateless composables with previews for light,
dark and 200 percent font scale. One `SheetScaffold` replaces `AppleExpensePromptSheet` and `ExpenseBottomSheet`; the overlay and
`ModalBottomSheetActivity` both use it. Delete the replaced files and the placeholder files under `presentation/ui/components` and
`presentation/ui/screens`, and show by search that nothing references them (rule 17).

### 8.3 Home screen

Rebuild `DashboardScreen` per design guide 5.1: month selector, sync status line, hero living-burn card with the daily average,
Owed to me and I owe cards with the top three names, the needs-review chip, a category list with proportional bars, recent
transactions, savings and income below the fold, pull to refresh and the empty state. The ViewModel exposes one immutable UI state;
no formatting in the composable except through `formatRupees` and `AmountText`.

### 8.4 Other screens and the sheet

Apply the components to Review, Settings (grouped rows), Setup health, Onboarding and every overlay step. Copy comes from design
guide section 6; the widget, tile and shortcut labels become "Add expense". Remove the words Apple, Island, Shortcuts and
Quick log from user-visible text. Settings is large (about 800 lines): split it into one file per group while you restyle it.

### 8.5 String review list

Write every user-visible string of the app into the report, grouped by screen (key and text), so the owner can review the copy once.

Done when: the build passes and compiles test sources; a search shows no colour literal, no `sp` or corner-radius literal and no
user-visible string literal outside `theme/` and `strings.xml` in `presentation/`; no money is formatted outside `formatRupees`; the
report lists each screen with the components it uses. Negative check: no reference to a deleted component remains.

## Phase 9 - Beta tooling and failure visibility

Status: not started. Authority: [ANDROID_V8_BETA_AND_RELEASE_PLAN.md](./ANDROID_V8_BETA_AND_RELEASE_PLAN.md) sections 3 to 5. Goal:
the owner can see where the system breaks on friends' phones (silent pipeline failures, brand-specific behaviour and crashes)
without seeing any financial data.

### 9.0 Preparation

Read the plan, the `build.gradle.kts` files, `OemHardeningHelper.kt`, `Diagnostics.kt`, `SetupHealthViewModel.kt`,
`FinanceApplication.kt`, `NotificationListenerServiceImpl.kt`, `ForegroundWatcher.kt`, `OverlayController.kt` and `AGENTS.md`.

### 9.1 Build types

Add `beta` (signed from `keystore.properties` if present, otherwise the debug key; never commit that file) next to `debug` and
`release`. Same `applicationId`. `QUERY_ALL_PACKAGES` only in the `debug` and `beta` manifests. `BuildConfig` flags for the discovery
scan and the simulator. Apply the Google services and Crashlytics plugins only if `app/google-services.json` exists, so the project
builds without it. Add `keystore.properties`, `google-services.json` and keystores to `.gitignore` if they are missing.

### 9.2 Routes as data

Move the candidate lists into `assets/oem_routes.json` (plan section 5) with a repository that parses and validates it and falls
back to the generic battery settings. `OemHardeningHelper` reads the routes; instruction text stays in `strings.xml`. Every route
stays resolve-then-fallback and every launch stays guarded.

### 9.3 Local crash and exit record

An uncaught exception handler that stores class, our own frames, time and version (never the message) and chains to the previous
handler; at start, read the last five `ApplicationExitInfo` entries (API 30 and later). Store both locally (capped) and expose them
to the report.

### 9.3b Pipeline trail (Room version 7)

Add the capped event trail of plan section 4.5: `Migration(6, 7)` creating `pipeline_events(id INTEGER PRIMARY KEY AUTOINCREMENT NOT
NULL, time INTEGER NOT NULL, stage TEXT NOT NULL, code TEXT NOT NULL, a INTEGER, b INTEGER)` with an index on `time`, exported
`7.json`, an in-memory buffered writer that flushes in batches on `Dispatchers.IO` (never one disk write per event on a hot path),
and pruning to 500 rows or 7 days. One small `PipelineTrail.record(stage, code, a, b)` API in `core`, used at every stage of the
capture pipeline (listener, `NotificationProcessor`, `ForegroundWatcher`, `OverlayController`, the heads-up helper, the sync loop).
Codes are enums, never strings built from alert content; the type system must not allow text. Record the device snapshot at app
start and every six hours (WorkManager). Add a local "Capture health" view in Settings, Advanced: the daily funnel and the last 50
events. Also record a listener heartbeat time (last callback of any notification, no package name, no content). Rule: nothing from a
non-whitelisted app is recorded except the heartbeat time and a counter.

### 9.4 Compatibility report

A builder in `domain` (pure formatting) and a data collector, a preview screen and the share action (`ACTION_SEND`). Content per plan
4.3; the discovery scan per 4.4 only when the build flag is on. Add the per-candidate "Did this open the right screen? Yes / No"
feedback to the OEM fix flow and include the answers. Include the daily funnel, the last 30 minutes of the pipeline trail and the
device snapshot. Add the "A payment did not show a prompt" button (home screen and setup health) that opens this report pre-filled for
the last 30 minutes, with one optional note.

### 9.5 Crashlytics, opt in

Manifest meta-data disables collection; a Settings switch "Help improve reliability" with the disclosure sentence enables it at
runtime; allow-listed custom keys only; non-fatal recording with class and place only; a test-crash button in `debug` and `beta`
builds. The build must succeed when `google-services.json` is absent (the integration code is then inert). Add the one-sentence
exception to `AGENTS.md` section 2B (crash reports without financial data may go to Firebase Crashlytics when the user has opted in).

Done when: all three build types assemble; the release manifest does not contain `QUERY_ALL_PACKAGES` (show the merged-manifest
search); collection is disabled by default (show the manifest line and the code that enables it only after the switch); no free text
reaches a custom key (show the key allow-list); the report preview shows exactly what is shared; the routes file drives
`OemHardeningHelper` (trace); the trail has no field that can hold alert text (show the types); negative checks for amounts, payees
and message texts in the report builder (show the fields it can read).

## Phase 10 - MVP closure

Status: Audit and closure items complete. PR opened against main for reviewer approval.

1. Remove dead code: the `presentation/ui/*` placeholder files, `SettleWindowWorker` if still present, unused mappers and
   constants, the old fixed-grid config code once the new one is in use, unused imports. Confirm each deletion has no remaining
   reference by search.
2. Hygiene by search, and paste the search commands and counts in the report: `!!` (expect 0), emoji characters (expect 0), the
   strings `googleusercontent`, `client_secret`, `Bearer ` in logs (expect 0), `Log.` lines that print tokens, bodies, amounts or
   notification text (expect 0), local machine paths in docs (expect 0), `catch (_` (expect 0).
3. Release build checks: `assembleRelease` succeeds, the release manifest has no simulator and no debug-only whitelist entry,
   `allowBackup=false`, backup and extraction rules exclude the database and key file, ABI filters are `arm64-v8a` and
   `armeabi-v7a`, record the APK size.
4. Confirm every audit item in section 12 of the engineering plan is fixed or consciously deferred, and write the result at the end
   of this file.
5. `assembleBeta` succeeds and installs over a previous beta build; record the beta APK size.
6. Confirm the owner checklist in [ANDROID_V8_BETA_AND_RELEASE_PLAN.md](./ANDROID_V8_BETA_AND_RELEASE_PLAN.md) section 2 matches
   reality: tell the owner which file names the build expects (`keystore.properties`, `app/google-services.json`) and what each
   build type needs.
7. Then, and only then, the test phase begins (engineering plan section 8). Do not start it from this guide.

---

## Owner action items after development (not for build agents)

The owner action items now live in [ANDROID_V8_BETA_AND_RELEASE_PLAN.md](./ANDROID_V8_BETA_AND_RELEASE_PLAN.md), section 2, as an
ordered checklist of twelve steps with the evidence for each (release keystore, `keystore.properties`, release SHA-1, second OAuth
client, consent screen status, Firebase project, `google-services.json`, tester group, first beta install, crash test, distribution,
tidy-up). They are done after Phase 10 and before the live device test. Build agents must not start them.

---

## Decisions in force

| # | Decision | Value |
| :--- | :--- | :--- |
| D1 | Workbook shared with the iOS system | No. The app creates its own workbook |
| D2 | Dismissed captures | Wait in Needs Review, never auto-saved |
| D3 | No overlay permission | Heads-up notification fallback |
| D4 | Widget and pinned shortcut | Offer both |
| D5 | Target SDK | 35 per `AGENTS.md`; the project currently targets 36, ask before changing |
| D6 | Carry-forward convention | Total = owed on carried rows, excluded from gross outflow by the `[Carried` filter |
| D7 | OAuth scope | `drive.file` first; add `spreadsheets` only if the Sheets API requires it |
| D8 | Local database encryption | Yes, SQLCipher with a Keystore-wrapped key |
| D9 | Cross-platform workbook | Keep the contract in spec section 13 true; do not add Android-only conventions to the sheet |
| D10 | Overlay host | `OverlayController` in the application process, entered through `EntryActivity`; no started service |
| D11 | Look and copy | Calm utility, workbook palette, iOS prompt wording where it exists (`ANDROID_V8_UI_UX_DESIGN.md`) |
| D12 | Beta audience and distribution | At most ten friends; Firebase App Distribution by console upload; no paid account |
| D13 | Failure visibility | Crashlytics opt in, a pipeline trail and a user-shared compatibility report |
| D14 | Phone-brand routes | Data file in assets, resolve-then-fallback, discovery scan in debug and beta only |
| D15 | Build types | debug, beta, release; same application id |

---

## Section 12 Engineering Plan Audit Status

Status of all fifteen audit items from Section 12 of [ANDROID_V8_ENGINEERING_AND_TEST_PLAN.md](./ANDROID_V8_ENGINEERING_AND_TEST_PLAN.md):

| # | Area | Description | Status | Evidence / Implementation Reference |
| :--- | :--- | :--- | :--- | :--- |
| 1 | Sheet writes | Appends must use `valueInputOption=RAW` to prevent formula injection (`=IMPORTXML(...)`) | Fixed | `GoogleSheetsClient.kt` sets `valueInputOption=RAW` on append (lines 203, 210), cell update (lines 319, 324), batch update (lines 721, 739), and void update (line 777). |
| 2 | Offline queue | Failed items stranded; `getAllPending` only queried `PENDING`; repository returned success on all failures | Fixed | `SyncTransactionsWorker.kt` (lines 86, 95, 101) applies `Result.retry()` on transient network failures; `SyncQueueDao.kt` provides `getPendingAndFailedItems()` with retry limits; `TransactionRepositoryImpl.kt` coordinates error propagation. |
| 3 | Month tabs | Dynamic month tab creation (`MMM26`), carry forward from previous month, dynamic year workbook (`FinanceYYYY`) | Fixed | `SheetProvisioner.kt` creates monthly tabs with 31 columns; `WorkbookResolver.kt` resolves dynamic year workbooks; `MonthlyRolloverWorker.kt` carries forward balances; `SettingsSyncCoordinator.kt` names workbook `FinanceYYYY`. |
| 4 | UUIDs and row safety | Hidden UUID columns V-AB never populated; row mutation by index corrupts on manual sheet sort | Fixed | `SheetPayloadMapper.kt` writes UUIDs into columns K and AB; `LedgerSnapshotMapper.kt` reads typed rows with UUIDs; `AccountingSyncExecutor.kt` matches transactions by UUID before updating in-place cells. |
| 5 | Accounting engine | Waterfall, repayments, netting, quotas, surplus routing, inflows/payables never executed | Fixed | `CalculateSplitUseCase.kt`, `LogExpenseUseCase.kt`, `SettlementWaterfallUseCase.kt`, `RepaymentWaterfallUseCase.kt`, and `AccountingSyncExecutor.kt` implement Code.gs v2.3.0 parity. |
| 6 | Capture timing | Immediate launch during payment PIN entry; overlay exemption fragile on Android 14/15 | Fixed | `NotificationProcessor.kt` uses `ForegroundWatcher.kt` to defer prompt until payment app exits; `ForegroundHeadsUpHelper.kt` provides heads-up notification fallback; `ModalBottomSheetActivity.kt` handles user interaction. |
| 7 | Credits | Credit alerts dropped by direction filter; Income / Return never captured | Fixed | `ParseNotificationUseCase.kt` detects credit patterns; `NotificationProcessor.kt` captures income and returns; `LogExpenseUseCase.kt` logs inflows. |
| 8 | Duplicates | On listener reconnect, 5-minute alerts reprocessed but dedup window only 120 seconds | Fixed | `NotificationProcessor.kt` maintains in-memory deduplication hash set; `HandledNotificationDao.kt` persists processed notification keys to prevent duplicate captures across service rebinds. |
| 9 | Auth | Deprecated `GoogleSignIn` / blocking token call; committed OAuth client ID; token logged in logcat | Fixed | `GoogleAuthTokenProvider.kt` uses `Identity.getAuthorizationClient` (AuthorizationClient) and `GoogleAuthUtil.clearToken` for cache invalidation with `drive.file` scope. Token logging removed. First-run onboarding in `OnboardingScreen.kt`. |
| 10 | Test hook in release | `NotificationSimulatorReceiver` exported and registered in all build types | Fixed | `NotificationSimulatorReceiver` moved to `app/src/debug/AndroidManifest.xml`. Release merged manifest search verifies 0 occurrences. |
| 11 | Listener resilience | Only `requestRebind` on disconnect; no boot receiver, toggle rebind, or setup health screen | Fixed | `BootReceiver.kt` handles rebind on device boot; `NotificationListenerHealthWorker.kt` runs periodic health checks; `SetupHealthCard.kt` provides OEM permission status; `oem_routes.json` guides vendor battery settings. |
| 12 | Overlay look | Translucent activity with solid surface; no blur; Android default icon; hardcoded category chips | Fixed | `ModalBottomSheetActivity.kt` renders Material 3 bottom sheet with dim backdrop; `Theme.kt` supplies dynamic colors; `QuickLogCategoryRanker.kt` ranks categories dynamically based on usage recency. |
| 13 | Menu and flows | No Choose Action menu; missing Income, Friend Paid For Me, Repaid flows; no category prediction or account inference | Fixed | `EntryActivity.kt` provides Choose Action menu with Income, Friend Paid, and Repaid flows; `PredictCategoryUseCase.kt` and `QuickLogCategoryRanker.kt` predict categories; account inference handled in `NotificationProcessor.kt`. |
| 14 | Widget | 1x1 widget dynamic size only Android 12+; opens translucent sheet instead of menu | Fixed | `QuickLogWidgetProvider.kt` implements 1x1 widget using `AppWidgetProvider` and RemoteViews; layout declared in `quick_log_widget_info.xml` with dimension tokens in `dimens.xml`. |
| 15 | Minor | Target SDK 36 vs 35; settle window mismatch; redundant settle worker; OTP regex over-matching; OEM SMS package coverage | Partially Fixed / Consciously Deferred | `targetSdk` is 36 in `build.gradle.kts`, consciously deferred to owner and reviewer per Decision D5. Settle window unified to 120s in `ConfigConstants.kt`; redundant settle worker eliminated; OTP regex tightened in `RegexPatterns.kt`; OEM SMS packages expanded in `PackageWhitelist.kt`. |

---

## Known Large Files

The following five files exceed 400 lines in the codebase. Per reviewer instructions and guide guidance, they are intentionally kept intact without splitting in this round:

1. `app/src/main/java/com/financeautomation/data/remote/GoogleSheetsClient.kt` (1375 lines): Central low-level Google Sheets API client implementing raw append, batch dimension formatting, cell updates, and error handling. Pre-existing foundational client permitted by the guide.
2. `app/src/main/java/com/financeautomation/data/remote/SheetProvisioner.kt` (1303 lines): Implements 28-column master sheet provisioning, formula generation, styling requests, and multi-table layouts. Pre-existing foundational provisioner permitted by the guide.
3. `app/src/main/java/com/financeautomation/data/repository/TransactionRepositoryImpl.kt` (968 lines): Implements offline queue management, netting logs, Room persistence, and remote synchronization coordination. Pre-existing repository permitted by the guide.
4. `app/src/main/java/com/financeautomation/data/remote/AccountingSyncExecutor.kt` (422 lines): Coordinates split execution, repayment waterfalls, and remote row mutations. Kept intact to preserve coherent transactional semantics across accounting steps.
5. `app/src/main/java/com/financeautomation/di/AppContainer.kt` (407 lines): Manual dependency injection container providing singletons across database, repositories, use cases, and coordinators. Kept in a single container for direct wiring visibility.

