# iOS Backend v2.3.0 Sync Notes

Status: change report and reconciliation checklist. This is not a replacement for the v7.0.0 specification
in `PROJECT_PROPOSAL_AND_TECH_STACK.md`. It records what changed in the iOS reference system since the copy
mirrored in `docs/ios_reference/` (Apps Script `Code.gs` v2.0.0), and where the Android specification
and the iOS system now disagree.

Sources reviewed (iOS repository, read-only): `Code.gs` (v2.3.0), `README.md`, `AGENTS.md`, `TASKLIST.md`,
`docs/SPREADSHEET_SPECIFICATION.md`, `docs/SHORTCUTS_MASTER_REFERENCE.md`,
`docs/ARCHITECTURE_AND_PLAN.md`, `docs/ANDROID_APP_PLAN.md`, `Finance2026_Template.xlsx`.
Where a document and `Code.gs` disagree, this note follows `Code.gs` and flags the document.

Nothing in the Android source tree was changed as part of this note.

---

## 1. Summary of what changed in iOS (v2.0.0 to v2.3.0)

| Area | v2.0.0 (mirrored in `docs/ios_reference/`) | v2.3.0 (current) |
| :--- | :--- | :--- |
| Settlement | Single linked row, one decrement | Cascading waterfall: explicit rows first, then oldest-first across all month tabs |
| Surplus money | Not handled | Routed to the opposite table (see section 3), only when a real debt/payable was settled |
| Netting | None | Bilateral netting in both directions on every `log_debit` and `log_payable` |
| Shared rows | Substring/greedy | Exact token match, per-person quota (owed / N), settled person removed from the names cell |
| Idempotency | None | `request_id` or fallback fingerprint, 30-day key retention, `duplicate: true` response |
| Amount handling | Raw number | `parseAmount_`: strips currency text and commas, rejects zero/negative/non-numeric, rounds to 2 decimals |
| Text safety | None | Formula-injection guard (leading `=`, `+`, `-`, `@` prefixed with an apostrophe) |
| Date handling | Basic | Multi-format parser, native date objects at midnight, `dd/mm/yyyy` mask, script timezone `Asia/Kolkata` |
| Carry-forward | Previous month only | Searches back up to 12 months, bridges into the prior year workbook, follows carried rows through chains |
| Workbook resolution | One spreadsheet | Annual workbook `FinanceYYYY` resolved from the Drive folder, cached by year, with fallbacks |
| New actions | - | `get_pending_payables` |
| Locking | None | `LockService` script lock, 30 s, taken only after authentication |
| Dashboard | Flat queries | Grouped per-person settlement queries, gross outflow excludes carried rows, 10-category breakdown |
| Shortcut payload | No split mode | `split_mode` added to `log_debit`; multi-bill settlement picker; response `status` checked before queue fallback |

---

## 2. Backend contract as it stands (for parity, not for the Android transport)

The Android app does not call this backend (direct Drive/Sheets REST, no Apps Script, per the frozen decisions).
The contract still matters because the iOS shortcuts and the Android app may write to the same annual
workbook, and Android must produce rows that the iOS engine can still read and reconcile.

### 2.1 Actions and fields

| Action | Fields | Notes |
| :--- | :--- | :--- |
| `log_debit` | `date, account, item, category, total_amount, my_share, owed_to_me, status, companions, trip_tag, split_mode` | `split_mode` containing "equal" forces `my_share = total / (N + 1)` |
| `log_credit` | `date, account, amount, inflow_type, received_from, reason, linked_debit_row` | `linked_debit_row` may hold several `Sheet!RowN` references, comma separated |
| `log_payable` | `date, paid_by, what_for, amount_owed` | Nets against what the creditor owes first |
| `log_repayment` | `person, date_repaid, amount, linked_payable_row` | With `amount`: waterfall. Without: legacy mark-as-repaid |
| `sync_queue` | `queue: [...]` | Batch of the above; iOS flush does not use it |
| `get_pending_debts` | `person` (optional) | Returns `pendingDebts` titles plus `pendingDebtDetails` objects |
| `get_pending_payables` | `person` (optional) | New in v2.3.0 |
| `get_top_friends` | none | Up to 6 names, current plus previous month, historical fallback weighted 0.5 |
| `get_all_people` | none | All companions and creditors, sorted A to Z |
| `repair_sheets`, `inspect_dashboard`, `year_close` | none | Maintenance; `repair_sheets` touches `Template` and `Dashboard` only |

All requests carry `token` and `action` in the JSON body. The result is in the body (`status` of `success`,
`error` or `not_found`, plus a numeric `code`), not in the HTTP status.

### 2.2 Idempotency

- Key is `request_id` when supplied. Otherwise a fingerprint of action, timestamp, amount, item, person,
  account, companions and linked row, and only when the timestamp includes hours and minutes.
- A replay returns `status: success` with `duplicate: true` and writes nothing.
- Keys are held for 30 days.

### 2.3 Parsing and validation rules

- Amounts: `₹`, `Rs`, `INR` and commas are stripped. Zero, negative and non-numeric values are rejected with an error.
  Values are rounded to 2 decimals.
- `my_share` is clamped to the range 0 to total. `owed_to_me` is always recomputed as `total - my_share`.
- Category `Saving` is normalised to `Savings`. The category list is fixed at ten values:
  Food & Drink, Groceries, Transit, Subscriptions, Shopping, Trip/Travel, Health, Family, Savings, Other.
- Names are split on commas, `&` and `and`, trimmed, and matched case-insensitively and exactly.
  `Rohit` and `Rohit K` are different people.
- Debit status is derived, not trusted from the client: `Personal` when owed is zero, `Settled` when
  netting consumed the whole amount, otherwise `Pending`. A zero-owed row is never `Pending`.

---

## 3. Accounting behaviour that Android must reproduce or consciously diverge from

### 3.1 Waterfall settlement of debits (`log_credit`, Friend Settlement)

1. Explicitly selected rows are settled first, in the order selected. A shared row may be settled in full
   when it was explicitly selected and the payment covers the whole balance.
2. Remaining money cascades across the sender's other open debits, oldest month first. On shared rows the
   payment is capped at that person's share (owed / N).
3. When a person's share is covered, they are removed from the Companions cell. Other companions keep
   their balance.
4. Surplus becomes a Table 3 payable ("Excess settlement credit", status `Pending`) only if at least one
   real debt was settled. If nothing was settled (typo, stranger), the credit is recorded unlinked and
   the response carries `unmatched: true`.
5. Column T of the credit row records every row actually settled, comma separated.

### 3.2 Waterfall repayment of payables (`log_repayment` with `amount`)

1. An optional explicit payable row is repaid first, then the creditor's other open payables oldest first.
2. A shared payable is repaid only up to the creditor's share, and the creditor leaves "Paid By" once covered.
3. Status becomes `Partial` while a balance remains and `Repaid` at zero. Column AA gets the repayment date.
4. Surplus becomes a Table 1 row, only if at least one payable was repaid: item `[Carried Over] Excess repayment balance`,
   category `Other`, total equal to the surplus, my share 0, owed equal to the surplus, status `Pending`.

### 3.3 Bilateral netting

- New debit with companions: open payables owed to those companions are reduced first (cap per companion
  is `owed / N`), and the debit's owed amount is reduced by the amount offset.
- New payable: open debits owed by that creditor are settled first, oldest first.
- A payable fully absorbed by netting is still written, with amount 0, status `Repaid`, and a repaid date.

### 3.4 Carry-forward at month start

- Triggered lazily when the first transaction of a new month creates the tab (not pre-created ahead of time).
- Looks back up to 12 months for the most recent existing tab, including the prior year workbook.
- Table 1 rows with status `Pending` and owed above zero are copied with: original date and time, description
  prefixed `[Carried from MmmYY] `, **Total Amount = owed**, My Share = 0, Owed = owed, status `Pending`.
  The origin row status becomes `Carried Fwd`.
- Table 3 rows with status `Pending` or `Partial` are copied the same way (amount owed kept, status `Pending`)
  and the origin row becomes `Carried Fwd`.
- Later settlements follow `Carried Fwd` rows forward by matching the `[Carried from ...]` description tag,
  across any number of rollovers.
- `year_close` writes a `CarryFwd` tab and `carry_forward_YYYY.json` into the Drive folder.

### 3.5 Row references

Rows are addressed positionally as `Sheet!RowN` (for example `Nov26!Row4`). There are no stable row
identifiers in the iOS system. Manual insert, delete or sort in Google Sheets breaks references that are
already in flight.

---

## 4. Spreadsheet changes

| Item | iOS template v2.3.0 | Notes for Android |
| :--- | :--- | :--- |
| Columns | 28 (A to AB), two spacers L and U | Android adds hidden AC, AD, AE (UUIDs). iOS never writes them. |
| Header H | `Paid For / Companions` | Android spec calls it `Companions` |
| Header T | `Linked Debit Row` (text, `Sheet!RowN`) | Android uses `Linked Debit UUID` |
| Group labels (row 2) | `EXPENSES (MONEY OUT)`, `INFLOWS (MONEY IN)`, `I OWE PEOPLE` | Android spec uses `DEBITS / EXPENSES`, `CREDITS / INFLOWS`, `PAYABLES / LIABILITIES` |
| Banner (row 1) | `EXPENSE LEDGER - NOV 2026` (three-letter month, upper case) | Android spec says full month name |
| Currency mask | `"₹"#,##0` (no decimals) | Android spec says `₹#,##0.00` |
| Time cell | Written as a text string such as `04:30 PM` | Android spec writes a fractional time serial |
| Date cell | Native date at midnight, mask `dd/mm/yyyy` | Compatible with integer date serials |
| Tab names | `MmmYY`, plus `Dashboard`, `Template`, `CarryFwd` | Unchanged |
| Status values, Table 1 | `Personal`, `Pending`, `Settled`, `Carried Fwd`, `Void` | See conflicts below |
| Status values, Table 3 | `Pending`, `Partial`, `Repaid`, `Carried Fwd`, `Void` | Android uses `Pending` and `Repaid` only |

`Void` is documented in the iOS specification but `Code.gs` never writes it. In-place void is an
Android-side feature.

### 4.1 Dashboard formulas (iOS template)

- Current burn (`A5`) and previous month (`D5`): sum of My Share excluding categories `Savings` and `Transfer`.
- Owed to me (`G5`): sum of Owed where status is exactly `Pending`.
- I owe people (`J5`): payables with status `Pending` plus `Partial`.
- Monthly summary `U6:W17`: burn per month in `V`, gross outflow in `W`. Gross outflow **excludes rows whose
  description starts with `[Carried`** (`G` summed where `D` is not `[Carried*`).
- Category breakdown: header in `Y5`, ten categories in `Y6:Y15` including `Savings`, sums of My Share in `Z6:Z15`.
- Settlements, they owe me (`A27`): nested `QUERY` grouping open balances per person into Original (`G - I`),
  Paid Back (`G - I - J`), Net Owed (`J`), Oldest Date, Txn Count. Excludes `Personal`.
- Settlements, I owe (`H27`): grouped per creditor with count, total and oldest date, `Pending` or `Partial`.

`Finance2026_Template_v2.xlsx` in this repository still carries the older flat `A27` query and a plain
`SUM(G)` for gross outflow, and its category breakdown lists nine categories without `Savings`.

---

## 5. Conflicts between the Android specification and the iOS system

These need a decision before the Android app writes to a workbook that the iOS shortcuts also use.
Items 1 and 2 are accounting-impacting.

1. **Carry-forward Total Amount.** Android rule: carried row has `G = 0`. iOS: carried row has `G = owed`,
   and the Dashboard hides it from gross outflow by filtering `D` on `[Carried*`. With `G = 0` the iOS
   settlement query computes Original as `G - I = 0` and Paid Back as `G - I - J`, which goes negative.
   Options: (a) keep `G = 0` and update the Dashboard settlement and gross-outflow formulas to not depend on
   `G` for carried rows; (b) adopt the iOS convention (`G = owed`) and rely on the `[Carried*` filter;
   (c) keep the two clients on separate workbooks. Not decided here.
2. **Surplus rows.** The iOS repayment surplus row has `G = surplus`, which counts toward gross outflow.
   Android's "G is gross outflow" reading would treat it as a real expense.
3. **Status vocabulary.** iOS `Personal` (zero-owed) versus Android `Settled` for Just Me. iOS `Carried Fwd`
   versus Android `Carried`. iOS `Partial` on payables, which Android does not model. The Dashboard owed
   formula sums only exact `Pending`, so any new status value silently drops out of KPIs.
4. **Mixed rows.** iOS rows have no UUID in `AC` to `AE`. Android must tolerate blank UUIDs (the orphan
   scan and the column-scan fallback in the specification already allow for this) and must not assume it
   created every row.
5. **Lineage.** Android keeps lineage in Room and states that code never parses description prefixes. The
   iOS engine locates carried rows by parsing `[Carried from MmmYY]`. Android writes must keep that prefix
   on carried rows or iOS settlements will not find them. The prefix text must therefore not be localised.
6. **Linked reference in Table 2.** iOS stores `Sheet!RowN` text; Android stores a UUID. An iOS settlement
   of an Android-created debit needs a row reference, and the reverse needs a UUID lookup.
7. **Settle semantics.** Android decrements `J` on a debit linked by UUID. iOS also removes names from the
   Companions cell on per-share settlement and applies per-person quotas. Android's single-decrement model
   does not reproduce the per-person quota on shared rows.
8. **Burn exclusion.** iOS hardcodes `<>Savings` and `<>Transfer` in formulas. Android replaces this with a
   per-category flag and regenerates the formulas. Consistent in intent, but a Dashboard regenerated by
   Android will differ from what the iOS `repair_sheets` action writes, and `repair_sheets` will overwrite
   it with the hardcoded version if run.
9. **Dashboard category range.** `PROJECT.md` and `DashboardFormulaBuilder` target `Y5:Z14`. The iOS template
   uses `Y6:Z15` for the ten categories with the header in row 5.
10. **Number masks and time storage.** Mask and time-cell differences in section 4 do not break formulas but
    produce visibly different cells in one workbook.
11. **Backend contract in `docs/ANDROID_APP_PLAN.md` (iOS repo).** That plan assumes the Apps Script web
    app remains the system of record and the Android app is a thin client. The Android repository's frozen
    decision is direct REST with a local write buffer. The plan's lessons (idempotent writes, ISO dates,
    never trust HTTP status, fixed category list, exact name matching, no edit or delete) remain useful as
    behavioural requirements.

---

## 6. Known iOS limitations worth carrying into Android design

From the iOS Android-plan and review notes:

- No read API for transaction history or month summaries.
- No edit, delete or undo in the backend.
- Shared rows hold one owed amount, so shared payables can be overpaid by one person and understate the others.
- Rows written before v2.3.0 may have day and month swapped for days 1 to 12.
- Settlement and repayment scan every ledger tab and slow down as tabs accumulate.
- Dashboard KPIs read only the current month tab, so on the first of a month they show zero until the tab exists.
- Errors have no machine-readable code beyond `status`, `code` and a text `message`.

---

## 7. Stale files in this repository

| File | Problem |
| :--- | :--- |
| `docs/ios_reference/Code.gs` | v2.0.0 mirror. Missing waterfall, netting, idempotency, sanitising, `get_pending_payables`. |
| `docs/ios_reference/SHORTCUTS_MASTER_REFERENCE.md` | Missing `split_mode`, multi-bill settlement picker, the two-tier success check, external IP check. |
| `docs/PRE_RELEASE_AUDIT_REPORT.md` | Cites `Code.gs` line numbers from the v2.0.0 mirror for parity claims. |
| `TEST_INFRA.md`, `TEST_READY.md` | Still describe UTR deduplication (features 10 and 38, scenario S2) and a 43-feature, 316-test suite. Already contradicted by v7.0.0 and the audit report. |
| `PROJECT_PROPOSAL_AND_TECH_STACK.md` | Does not mention the iOS waterfall, netting, or the carry-forward `G = owed` convention. |
| `Finance2026_Template_v2.xlsx` | Dashboard formulas and category breakdown behind the iOS template (section 4.1). |

---

## 8. Suggested next steps

1. Decide conflicts 1 and 2 (carry-forward `G`, surplus rows). They change numbers on the Dashboard.
2. Refresh `docs/ios_reference/` from the iOS repository (`Code.gs` and the master reference).
3. Align `Finance2026_Template_v2.xlsx` Dashboard formulas and category list with the iOS template, then
   re-check `DashboardFormulaBuilder` ranges against it.
4. Add Android test scenarios for: shared-row quota settlement, waterfall with surplus, unmatched credit,
   bilateral netting in both directions, replayed request, and rows with blank UUIDs.
5. Update `TEST_INFRA.md` and `TEST_READY.md` to the v7.0.0 feature list.
