> ARCHIVED COPY, restored for comparison. This is the Draft v7.0.0 specification. It is superseded by
> `docs/ANDROID_V8_PRODUCT_AND_ARCHITECTURE_SPEC.md` and `docs/ANDROID_V8_ENGINEERING_AND_TEST_PLAN.md`. Where it
> disagrees with those, they win. Do not build from this file.

# Project Technical Architecture & Specification: Finance Automation Android

## Document Metadata
* **Project Name:** Finance Automation Android (Native Companion & Standalone Ledger Client)
* **Author:** Mobile Systems & Infrastructure Engineering Team
* **Target Audience:** Engineering Leads, System Architects, Security Reviewers, and Developers
* **Status:** Draft v7.0.0
* **Target Platforms:** Android 9.0 (API 28) through Android 16 (API 36)
* **Document Version:** 7.0.0

---

## Document Revision Changelog

* **v1.0.0:** Initial proposal with clean architecture, bottom sheet overlay, and Google Sheets integration.
* **v2.0.0:** Replaced Credential Manager with Google Identity AuthorizationClient; converted overlay to notification-first heads-up interaction; introduced table-scoped range writes; added basic deduplication and idempotency.
* **v3.0.0:** Added Column AC for transaction UUID; updated atomic values.append; added 3-button notification limits; auto-save as NEEDS_REVIEW; UTR deduplication; self-healing listener rebinds.
* **v4.0.0:** Allocated dedicated hidden ID columns per table (AC for Debits, AD for Inflows, AE for Payables); added Spike 3; corrected integer date serials; defined native JSON provisioning.
* **v5.0.0:** Switched to RAW writes; added in-place Void; 60–120s settle delay; Drive files.create with parents; second-precision time; two-axis state model; restored inflows and fallback.
* **v6.0.0:** Harmonized sync settle window; zeroed out carried receivables in origin month; standardized Column K status values; integer-second orphan matching.
* **v7.0.0 (Current):**
  * **Complete UTR Purge (A1, A2):** Removed UTR entirely across all parsers, schema, dedupe, tests, and documentation. Extraction restricted strictly to: Amount, Merchant ("paid to"), Source Package, and Timestamp. Raw notification text and transaction reference numbers are never stored. Logs are redacted. `allowBackup=false`. No analytics or telemetry.
  * **Refined Deduplication Tiers (A3, A4):** (1) Ignore own package; (2) same `sbn.key` + content hash + state transition (pending to success is a valid transition, not a duplicate); (3) cross-source matching (same amount, close in time, different source package, merchant not required to match) flags "Possible Duplicate" without auto-dropping. Settle delay fixed at 120s. Resolving as duplicate Voids the synced row.
  * **System Features Retained (A5, A6):** Catch-up scan of `getActiveNotifications()` in `onListenerConnected()`, fallback "Payment detected, tap to enter amount", and Quick Settings manual quick-add tile. Inflows (credit alerts) parsed with identical privacy rules; Payables (Table 3) remain manual entry in v1.
  * **Master 31-Column Schema Hardening (B1–B8):** Columns A–AB unchanged. Hidden ID columns: `AC` Debit UUID, `AD` Inflow UUID, `AE` Payable UUID. Column `T` renamed **"Linked Debit UUID"**. Time format standardized to **`hh:mm am/pm`**. Writes use `valueInputOption=RAW` and `insertDataOption=OVERWRITE` on `{Tab}!A3:K`, `M3:T`, `V3:AB`. Nothing may sit below the tables.
  * **In-Place Void Invariants (B5):** Set `K="Void"`, `G=0`, `I=0`, `J=0`, and prefix `D` with `[Void] `. Keep UUID. Never blank or delete a row (G=0 ensures Dashboard Total Outflow remains accurate).
  * **Debt Settlement Mathematics (B6):** Repayment linked by UUID in `T`. Decrements `J` on the debit row by repaid amount. Sets `K="Settled"` only when `J` reaches 0 (Dashboard computes Paid Back as `G - I - J`).
  * **Carry-Forward Accounting & Lineage (B7, B8):** Original row gets `J=0`, `K="Carried"`. New-month row gets fresh UUID, `G=0`, `I=0`, `J=owed`, `K="Pending"`, and `D` prefixed with `[Carried from MmmYY] `. Lineage (`carried_from_uuid`, `root_uuid`) maintained in Room; code does not parse description prefixes. Payables also carry forward when Pending.
  * **Workbook Provisioning & Tab Pre-Creation (B9–B11):** Drive `files.create` with `parents` and `appProperties`, then `batchUpdate` from JSON in APK. Spreadsheet locale (`en_IN`) and timeZone (`Asia/Kolkata`) explicitly set. Next month's tab created ahead of time (from day 25). Grid provisioned with ~1000 rows, expanding via `appendDimension` before write if needed.
  * **Config-Driven Architecture (C1–C10):** Author's setup ships only as a selectable example preset, not hardcoded default. Tier 1 lists (accounts, categories, people, trips). Tier 2 behavior (burn exclusion flag replaces hardcoded strings, currency symbol, locale/timezone, monitored apps, settle delay). Tier 3 regex rules with length caps and timeout guards. Config stored in Room and mirrored to a hidden `Config` sheet in the workbook (no financial data). Default unconfirmed category is `Other`.
  * **Robustness & Error Sealed Results (D1–D4):** Sealed network results (`Success`, `SheetNotFound`, `AuthExpired`, `Network`, `Malformed`). Tolerant DTOs with recorded error response fixtures. Negative test suite. Two-axis state model (`review_state`, `sync_state`, `is_voided`).

---

## 1. Executive Summary & Vision

### 1.1 The Problem Space
Personal finance tracking fails consistently in the consumer market due to three structural friction points:
1. **Manual Entry Fatigue:** Standard expense apps require users to manually unlock their device, open an app, wait for loading screens, type a numeric value, drill down through category pickers, and confirm submissions after every transaction. Empirical tracking shows users abandon manual logging within 10 to 14 days.
2. **Disconnected Group Splitting:** Third-party debt tools (e.g., Splitwise) maintain isolated debt ledgers. They do not feed into the user's primary monthly accounting workbook, making it difficult to compute true net personal burn without tedious manual reconciliation.
3. **Cloud Lock-In & Privacy Risks:** Commercial aggregators demand direct access to bank credentials or scrape transactional SMS onto proprietary cloud databases, monetizing transaction histories and subjecting users to subscription paywalls.

### 1.2 The Solution: Notification-First Native Automation
**Finance Automation Android** is an open, sovereign, native Android client engineered to eliminate manual logging friction entirely.

The application operates as a system-level listener on Android. When a user completes a UPI payment (Google Pay, PhonePe, Paytm, BHIM, Supermoney) or receives a bank debit alert, the app intercepts the notification locally, extracts the merchant and amount via pre-compiled regex, and executes a fail-safe capture:
* **Immediate Zero-Loss Persistence:** Every parsed transaction is immediately committed to the local Room database with review state `NEEDS_REVIEW` and sync state `PENDING`, defaulting to the historically learned category or `Other`. Swiping away or ignoring the notification never loses an expense.
* **120-Second Settle Delay & Sync Policy:** Transactions undergo a fixed 120-second settle window on-device to allow cross-source deduplication (e.g. bank SMS arriving 15 seconds after a Google Pay alert) to resolve before writing to Google Sheets. After the settle delay, transactions sync with their predicted category. When confirmed or edited by the user, the existing sheet row is updated in-place via its stable UUID.
* **Actionable Heads-Up Notification (3 Action Limit):** A heads-up notification displays the expense with at most three Android action buttons:
  * Two learned / top category chips (e.g. `[Food & Drink]` and `[Groceries]`). Tapping a chip upgrades the review state to `CONFIRMED` via a lightweight `BroadcastReceiver` using `goAsync()`.
  * One expansion action `[Details / Split]` which launches the Jetpack Compose `ModalBottomSheetActivity` as a direct user-initiated foreground transition.
* **Review Inbox & Fallback:** Any notification that was dismissed or unconfirmed remains in the in-app `Needs Review` inbox. Notifications from whitelisted packages that fail regex extraction surface a fallback prompt (*"Payment detected, tap to enter amount"*).
* **Direct Cloud Sync:** Transactions are committed directly from device to the user's private Google Sheet via the Google Sheets v4 REST API using `valueInputOption=RAW`. No intermediate backend servers, proxies, or webhooks exist.

---

## 2. Frozen Architectural Decisions

The following technical decisions are frozen and non-negotiable:
* **Platform:** Native Kotlin 2.1+ and Jetpack Compose with Material 3. Cross-platform frameworks (Flutter, React Native) are strictly excluded.
* **Authorization:** Google Identity `AuthorizationClient` requesting the minimal `https://www.googleapis.com/auth/drive.file` scope only. Distribution via GitHub Releases or direct sideload. Google Play Services dependency accepted.
* **Networking:** Direct REST execution over OkHttp 5.0+ connection pools. Monolithic Google API Java client libraries (`google-api-services-sheets`, `google-api-client-android`) are strictly excluded.
* **User Experience:** Notification-first UX: high-priority heads-up notification with at most 3 action buttons. The bottom sheet dialog opens strictly from a deliberate user tap, never via illegal background activity launches.
* **Storage Boundary:** The local Room SQLite database is a **write buffer and offline queue**, not a two-way synchronization mirror of manual spreadsheet edits.
* **Audience:** Designed as a general-purpose product for all users. The author's iOS setup serves solely as the visual reference and initial selectable preset.

---

## 3. Ledger Architecture & Master Schema Specification

### 3.1 Complete 31-Column Sheet Layout
Every monthly ledger tab (`Nov26`, `Dec26`, `Jan27`) lays out three business tables side-by-side separated by visual 10px gray spacer columns (`Col L` and `Col U`), followed by three hidden ID columns (`Col AC`, `Col AD`, `Col AE`):

| Col | Field Name | Table Section | Data Type | Number Format | Alignment | Description |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **A** | Date | Table 1: Debits | Date Serial | `dd/mm/yyyy` | Center | Integer day serial since 30/12/1899 |
| **B** | Time | Table 1: Debits | Time Serial | `hh:mm am/pm`| Center | Fractional 24h day serial with seconds |
| **C** | Account | Table 1: Debits | Text | `@` | Center | Funding account (dropdown from Config) |
| **D** | Item / Description | Table 1: Debits | Text | `@` | Left | Merchant name or description |
| **E** | Trip / Event Tag | Table 1: Debits | Text | `@` | Center | Optional trip identifier |
| **F** | Category | Table 1: Debits | Text | `@` | Center | Category name (dropdown from Config) |
| **G** | Total Amount (₹) | Table 1: Debits | Currency | `₹#,##0.00` | Right | Gross amount deducted from account |
| **H** | Companions | Table 1: Debits | Text | `@` | Left | Friend names or `Personal` |
| **I** | My Share (₹) | Table 1: Debits | Currency | `₹#,##0.00` | Right | Net personal living burn |
| **J** | Owed To Me (₹) | Table 1: Debits | Currency | `₹#,##0.00` | Right | Receivables balance (`Total - My Share`) |
| **K** | Status | Table 1: Debits | Text | `@` | Center | `Settled`, `Pending`, `Void`, or `Carried` |
| **L** | Spacer 1 | Spacer Column | None | Width: 10px | None | Background fill `#ECEFF1` |
| **M** | Date | Table 2: Inflows | Date Serial | `dd/mm/yyyy` | Center | Credit transaction date |
| **N** | Time | Table 2: Inflows | Time Serial | `hh:mm am/pm`| Center | Credit transaction time |
| **O** | Account | Table 2: Inflows | Text | `@` | Center | Receiving account |
| **P** | Inflow Type | Table 2: Inflows | Text | `@` | Center | `Salary`, `Friend Settlement`, `Allowance`, `Other` |
| **Q** | Received From | Table 2: Inflows | Text | `@` | Left | Payer name |
| **R** | Reason / Note | Table 2: Inflows | Text | `@` | Left | Inflow description |
| **S** | Amount Received (₹)| Table 2: Inflows | Currency | `₹#,##0.00` | Right | Inflow amount |
| **T** | Linked Debit UUID | Table 2: Inflows | Text | `@` | Center | Permanent `Debit Txn UUID` of settled row |
| **U** | Spacer 2 | Spacer Column | None | Width: 10px | None | Background fill `#ECEFF1` |
| **V** | Date Incurred | Table 3: Payables| Date Serial | `dd/mm/yyyy` | Center | Date debt was incurred |
| **W** | Time | Table 3: Payables| Time Serial | `hh:mm am/pm`| Center | Time incurred |
| **X** | Paid By | Table 3: Payables| Text | `@` | Left | Creditor friend name |
| **Y** | What For | Table 3: Payables| Text | `@` | Left | Description of covered item |
| **Z** | Amount I Owe (₹) | Table 3: Payables| Currency | `₹#,##0.00` | Right | Liability amount |
| **AA**| Date Repaid | Table 3: Payables| Date Serial | `dd/mm/yyyy` | Center | Repayment date (blank until repaid) |
| **AB**| Repayment Status | Table 3: Payables| Text | `@` | Center | `Pending` or `Repaid` |
| **AC**| Debit Txn UUID | System ID (Table 1)| Text (Hidden)| `@` | Left | Permanent UUID for Table 1 row (`txn_...`) |
| **AD**| Inflow Txn UUID | System ID (Table 2)| Text (Hidden)| `@` | Left | Permanent UUID for Table 2 row (`inf_...`) |
| **AE**| Payable Txn UUID| System ID (Table 3)| Text (Hidden)| `@` | Left | Permanent UUID for Table 3 row (`pay_...`) |

### 3.2 Row Structure & Header Boundaries
* **Row 1:** Merged Title Banner across `A1:AE1`, background `#1A237E` (Navy Blue), bold white text: `EXPENSE LEDGER — [MONTH] [YEAR]`.
* **Row 2:** Section Group Headers:
  * `A2:K2`: `DEBITS / EXPENSES` (Crimson Red `#C62828`, bold white, centered)
  * `L2`: Spacer `#ECEFF1`
  * `M2:T2`: `CREDITS / INFLOWS` (Forest Green `#2E7D32`, bold white, centered)
  * `U2`: Spacer `#ECEFF1`
  * `V2:AB2`: `PAYABLES / LIABILITIES` (Amber Orange `#F57F17`, bold white, centered)
  * `AC2:AE2`: `SYSTEM IDENTIFIERS` (Slate `#37474F`, hidden)
* **Row 3:** Column Field Header Row. Slate Gray `#37474F`, bold white text, size 9pt, frozen top 3 rows.
* **Row 4+:** Contiguous transaction data rows.
* **Target Append Ranges:** `A3:K` for Debits, `M3:T` for Inflows, `V3:AB` for Payables.
* **Boundary Rule:** **Nothing may ever sit below the tables.** Placing formulas, totals, or notes below any table breaks `values.append` contiguous range detection.

### 3.3 Core Mathematical Rules & Ledger Transitions
1. **Net Personal Burn Isolation:**
   Only Column I (`My Share`) contributes to monthly living expense analytics. Receivables fronted for companions (Column J: `Owed To Me`) are isolated balance-sheet assets and never distort monthly burn calculations.
2. **Category Standardization:**
   All asset allocations, mutual funds, investments, and deposits must use the exact plural category string **`Savings`** (or categories configured with the living-burn-exclusion flag).
3. **Split Mathematics:**
   * **Just Me:** $\text{Total} = \text{Amount}$, $\text{My Share} = \text{Total}$, $\text{Owed To Me} = 0$, $\text{Companions} = \text{"Personal"}$, $\text{Status} = \text{"Settled"}$.
   * **Split Equally ($N$ friends):** $\text{Total Heads} = N + 1$. $\text{My Share} = \text{Total} / (N + 1)$, $\text{Owed To Me} = \text{Total} - \text{My Share}$, $\text{Status} = \text{"Pending"}$.
   * **Custom Split ($X$ personal share):** $\text{My Share} = X$, $\text{Owed To Me} = \text{Total} - X$, $\text{Status} = \text{"Pending"}$.
4. **In-Place Void Semantics (B5):**
   * Rows are **never blanked out or physically removed**. Blank rows in Table 1 break contiguous table detection in `values.append`, causing subsequent appends to overwrite rows below the gap.
   * When a transaction is voided:
     * `Total Amount (G)` = `0.00` (Crucial: Dashboard "Total Outflow" sums Column G; setting G=0 eliminates the outflow)
     * `My Share (I)` = `0.00`
     * `Owed To Me (J)` = `0.00`
     * `Status (K)` = `"Void"`
     * `Item / Description (D)` = `"[Void] " + original_item`
     * `Debit Txn UUID (AC)` remains intact.
5. **Debt Settlement Mathematics (B6):**
   * When an inflow settlement arrives, Column T records the target `Debit Txn UUID`.
   * The sync engine locates the debit row via Column AC and **decrements Column J (`Owed To Me`) by the repaid amount**.
   * If Column J reaches `0.00`, Column K (`Status`) transitions to `"Settled"`.
   * Because the Dashboard "They Owe Me" table computes "Paid Back" as `G - I - J`, decrementing J reflects the true repayment without falsifying the original bill amount G.
6. **Carry-Forward Rollover Mechanics (B7, B8):**
   When unsettled debts roll over to a new month:
   * **Originating row in preceding month:** Column J (`Owed To Me`) is set to `0.00` and `Status` is set to `"Carried"`.
   * **New-month appended row:** Receives a **fresh unique UUID** in Column AC, `Total Amount (G) = 0.00`, `My Share (I) = 0.00`, `Owed To Me (J) = original_owed`, `Status = "Pending"`, and Column D is prefixed with `"[Carried from MmmYY] " + original_item`.
   * Lineage (`carried_from_uuid`, `root_uuid`) is recorded in Room. The application code strictly never parses description strings to reconstruct lineage.
   * Table 3 Payables also carry forward when still `Pending`, because Dashboard KPI formulas look only at the active month tab.

---

## 4. Privacy, Security & Ingestion Architecture

### 4.1 Strict Privacy & Zero UTR Rule (A1, A2)
* **Complete Removal of UTR:** The application strictly never extracts, matches, stores, or logs any UPI Transaction Reference (UTR) number or bank reference string.
* **Extracted Attributes Only:** Extraction is strictly limited to four primitive fields:
  1. `Amount` (numeric currency value)
  2. `Merchant / Paid To` (payee string)
  3. `Source Package` (e.g. `com.phonepe.app`)
  4. `Timestamp` (epoch millisecond)
* **Zero Raw Notification Text Storage:** Raw notification title, body, subtext, and extras are processed in memory and discarded immediately. They are never written to Room, shared preferences, logcat, or disk files.
* **System Privacy Configuration:** `android:allowBackup="false"` is enforced on the application manifest to prevent ADB or cloud backup leakage. No analytics, telemetry, or tracking SDKs are bundled.

### 4.2 Multi-Tier Deduplication (A3, A4)
Deduplication is executed in three strict tiers:
* **Tier 1 (Self-Package Filter):** The listener immediately discards any notification where `sbn.packageName == context.packageName`.
* **Tier 2 (Key + Content Hash + State Transition):** Identical `sbn.key` events are dropped unless the notification content transitions from a pending state to a success state. A pending-to-success transition is treated as an update, not a duplicate.
* **Tier 3 (Cross-Source Settle Window):**
  * Settle delay is a **fixed 120 seconds** (exceeding the 90-second cross-source match window).
  * If two notifications arrive within the match window sharing the **same Amount** from **different source packages** (e.g. Google Pay notification followed by bank SMS), merchant name is not required to match.
  * The secondary alert is flagged as **`Possible Duplicate`** in the Review Inbox and is **never automatically dropped**.
  * If the user confirms the secondary transaction as a duplicate, the synced row in Google Sheets is updated in-place to **`Void`**.

### 4.3 Inflow Processing & Manual Payables (A6)
* Credit notifications (e.g. salary, friend UPI credit transfers) are parsed under the same strict privacy rules and mapped to Table 2 (Credits / Inflows).
* Table 3 Payables ("Friend Paid For Me") are managed via manual entry in v1, as banking notifications do not fire when someone else pays on your behalf.

---

## 5. Google Drive & Sheets Integration Engine

### 5.1 Google Authorization & Error Recovery (B12)
* Uses `Identity.getAuthorizationClient(activity)` from `com.google.android.gms:play-services-auth`.
* Requests minimal `https://www.googleapis.com/auth/drive.file` scope only.
* Access tokens are requested on-demand through `AuthorizationClient`. Play Services manages in-memory token validity internally.
* **Auth Error Recovery Protocol:**
  If an API request returns HTTP 401 Unauthorized:
  1. Call `AuthorizationClient.clearToken(staleToken)`.
  2. Attempt a single silent re-authorization request.
  3. If silent re-authorization fails (user revoked consent or changed password), surface a high-priority system notification: *"Finance Automation: Tap to reconnect Google Drive sync"*.
* All sheet writes are funneled through one **unique serialized WorkManager queue** (`ExistingWorkPolicy.APPEND_OR_REPLACE`).

### 5.2 Atomic Sheet Appends with `valueInputOption=RAW` (B3, B4, B11)
* Appends use `insertDataOption=OVERWRITE` and `valueInputOption=RAW`:
  * Debits: `range={MonthTab}!A3:K`
  * Inflows: `range={MonthTab}!M3:T`
  * Payables: `range={MonthTab}!V3:AB`
* `valueInputOption=RAW` ensures user payee strings (e.g. `=IMPORTXML(...)`) are treated strictly as text and never executed as formulas.
* **Grid Bounds Protection:** Generous initial row capacity (~1000 rows) is provisioned. If a write would exceed the grid, the app issues an `appendDimension` request to expand the sheet before appending.
* **Integer-Second Orphan Reconciliation:** If an append succeeds but writing Column AC UUID fails due to process termination, retry logic searches Table 1 for an orphan row matching integer date serial, integer second serial (`hour * 3600 + minute * 60 + second`), and amount before inserting a new row.
* **Pre-Update Verification:** Before updating an existing row (marking `Settled` or `Void`), the client verifies `AC{cachedRow}`. If the UUID matches, it updates in-place; if it differs (due to user row reordering), it executes a column scan over AC to locate the true row.

### 5.3 Native Provisioning & Pre-Creation (B9, B10, B13)
* The workbook is created inside the `Personal Finance` folder via Drive v3 `files.create`:
  ```json
  POST https://www.googleapis.com/drive/v3/files
  {
    "name": "Finance2026",
    "mimeType": "application/vnd.google-apps.spreadsheet",
    "parents": ["<Personal_Finance_Folder_ID>"],
    "appProperties": {
      "app": "FinanceAutomation",
      "fiscalYear": "2026"
    }
  }
  ```
* Applied via `spreadsheets.batchUpdate` using the bundled JSON specification.
* **Locale & Timezone:** Spreadsheet `locale` (`en_IN`) and `timeZone` (`Asia/Kolkata`) (or user-configured values) are explicitly set.
* **Advance Tab Creation:** To ensure dynamic Dashboard formulas (`TEXT(TODAY(),"mmm")`) resolve seamlessly across month boundaries, next month's tab is pre-created on or after the 25th of the active month. Duplication is idempotent; a "sheet name already exists" response is handled as success.
* **Legacy Import:** Legacy iOS workbooks are imported from CSV or XLSX via the Storage Access Framework (SAF), reading local file bytes without sensitive Drive permissions.

---

## 6. Config-Driven Customization Architecture (C1–C10)

Nothing user-specific is hardcoded into the application codebase. The reference setup (SBI, Canara, Cash, Other; categories including Family, Savings; people) is packaged solely as a selectable example preset.

```
+---------------------------------------------------------------------------------------------------+
|                                  CONFIG-DRIVEN CUSTOMIZATION TIERS                                |
+-------------------+-------------------------------------------------------------------------------+
| Configuration Tier| User-Configurable Capabilities                                                |
+-------------------+-------------------------------------------------------------------------------+
| Tier 1: Lists     | Accounts, Categories, People, Trip Tags. Add, rename, reorder, archive.       |
| Tier 2: Behavior  | Default funding account, per-category burn exclusion flag, currency symbol,   |
|                   | sheet locale and timezone, package whitelist, and settle delay.               |
| Tier 3: Parsers   | Per-app amount and merchant regex rules, editable in-app with interactive     |
|                   | testing box. Protected by length caps, execution timeouts, and sanity guards. |
+-------------------+-------------------------------------------------------------------------------+
```

### 6.1 Configuration Persistence & Dashboard Regeneration
* Configuration is persisted in Room and mirrored to a hidden **`Config` sheet** in the user's Google Sheet workbook. On app reinstall, configuration is restored automatically from this tab. The `Config` sheet contains **zero financial transaction data**.
* **Dashboard Regeneration:** When a user adds, renames, or archives categories, the app issues a `batchUpdate` regenerating:
  1. The Dashboard Category Breakdown table (`Y5:Z14`).
  2. Data validation dropdowns on Column C (`Account`) and Column F (`Category`).
  3. The living-burn exclusion formulas (`A5`, `D5`, `V5:V17`), substituting categories flagged as "excluded from living burn".
* Renames apply to future transactions; an optional migration utility updates historical rows on request.
* **Default Category:** Unconfirmed transactions default to **`Other`** (or a category guaranteed to exist in the Dashboard breakdown).

---

## 7. Robustness, Error Handling & Data States (D1–D4)

### 7.1 Sealed Result Hierarchy
Every network and repository call returns an explicit sealed result type:
```kotlin
sealed interface DataResult<out T> {
    data class Success<out T>(val data: T) : DataResult<T>
    data class SheetNotFound(val spreadsheetId: String) : DataResult<Nothing>
    data class AuthExpired(val message: String) : DataResult<Nothing>
    data class Network(val exception: Throwable) : DataResult<Nothing>
    data class Malformed(val details: String) : DataResult<Nothing>
}
```
* `SheetNotFound` triggers automated workbook search and provisioning before retrying, never an unhandled exception or crash.
* Zero fake success returns: unconfigured states or missing IDs strictly return explicit error results.

### 7.2 Two-Axis State Model
In Room SQLite (`transactions` table), state is decoupled into two independent dimensions plus a void flag:
* `review_state`: `NEEDS_REVIEW` vs `CONFIRMED`.
* `sync_state`: `PENDING`, `SYNCED`, `FAILED`.
* `is_voided`: Boolean flag.

---

## 8. Definition of Done & Quality Standards

Before the system is eligible for final team review:
1. **Implementation:** All specifications in Sections 3, 4, 5, 6, and 7 are fully implemented.
2. **Null Safety:** Zero `!!` operators. Build must pass strict lint/detekt rules.
3. **Privacy:** Zero real user financial data, tokens, or live notification texts in repository source, tests, or documentation.
4. **Spike 3 Verification Matrix:** Recorded pass/fail results across all 9 test matrix cases (empty month, asymmetric tables, void row in middle, totals row below table, active filter/sort, protected range, back-to-back appends, user blank gap, full grid).
5. **Real Redacted Corpus:** Spike 2 verified against real notification samples provided by the user, properly anonymized.
6. **Documentation Hygiene:** Title remains **Draft v7.0.0**. No self-certifying language.
