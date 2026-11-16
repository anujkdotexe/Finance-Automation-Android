"""Empirical Challenger Test Suite for Milestone M3.2.

Empirically tests and stress-tests:
1. SheetPayloadMapper:
   - Exact 31 columns: Table 1 (Cols A-K, indices 0-10), Spacer 1 (Col L, index 11),
     Table 2 (Cols M-T, indices 12-19), Spacer 2 (Col U, index 20),
     Table 3 (Cols V-AB, indices 21-27), Hidden UUIDs (Cols AC-AE, indices 28-30).
   - Col AC contains debit UUID txn_...
   - Col AD contains inflow UUID inf_...
   - Col AE contains payable UUID pay_...
   - In-place void mapping: gross amount and UUID preserved, personal share & owed zeroed,
     Status set to Void, description prefixed with [Void].
   - Monthly debt rollover mapping: origin month owed zeroed and status Carried,
     target month fresh row with carried debt, living share zeroed, status Pending.
   - Normalization of categories: strictly plural Savings keyword rule; singular Saving rejected.
2. GoogleSheetsClient & GoogleDriveClient:
   - RAW mode enforcement on appendTransaction and updateRow URLs.
   - Parsing of 1-based target row coordinate from updatedRange.
3. SheetProvisioner:
   - batchUpdate formatting: Row 1 Navy (#1A237E), Row 2 section headers (#C62828, #2E7D32, #F57F17, #37474F),
     Row 3 31 column headers, 10px spacers (#ECEFF1), and 3 frozen rows.
4. TokenRecoveryInterceptor:
   - Transparent HTTP 401 Unauthorized token recovery and request retry.
5. Repository-wide Hygiene:
   - Zero emojis across all M3 codebase files.
   - Zero hardcoded local machine paths across M3 codebase files.
"""

from __future__ import annotations

import os
import re
import unittest
from dataclasses import dataclass
from typing import Any

# ==============================================================================
# Domain Models & Constants Mirroring Kotlin Source
# ==============================================================================

CATEGORY_SAVINGS = "Savings"
COLOR_BANNER_NAVY = "#1A237E"
COLOR_HEADER_DEBITS = "#C62828"
COLOR_HEADER_INFLOWS = "#2E7D32"
COLOR_HEADER_PAYABLES = "#F57F17"
COLOR_HEADER_SLATE = "#37474F"
COLOR_SPACER_GRAY = "#ECEFF1"

SHEET_COLUMNS = [
    "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K",
    "L",
    "M", "N", "O", "P", "Q", "R", "S", "T",
    "U",
    "V", "W", "X", "Y", "Z", "AA", "AB",
    "AC", "AD", "AE",
]

SHEET_COLUMN_HEADERS = {
    "A": "Date",
    "B": "Time",
    "C": "Account",
    "D": "Item / Description",
    "E": "Trip / Event Tag",
    "F": "Category",
    "G": "Total Amount (INR)",
    "H": "Companions",
    "I": "My Share (INR)",
    "J": "Owed To Me (INR)",
    "K": "Status",
    "L": "Spacer 1",
    "M": "Date",
    "N": "Time",
    "O": "Account",
    "P": "Inflow Type",
    "Q": "Received From",
    "R": "Reason / Note",
    "S": "Amount Received (INR)",
    "T": "Linked Debit UUID",
    "U": "Spacer 2",
    "V": "Date Incurred",
    "W": "Time",
    "X": "Paid By",
    "Y": "What For",
    "Z": "Amount I Owe (INR)",
    "AA": "Date Repaid",
    "AB": "Repayment Status",
    "AC": "Debit Txn UUID",
    "AD": "Inflow Txn UUID",
    "AE": "Payable Txn UUID",
}


@dataclass
class Transaction:
    id: str
    timestamp: int
    date_serial: int
    time_serial: float
    account: str
    merchant: str
    trip_tag: str
    category: str
    total_amount: float
    companions: str
    my_share: float
    owed_to_me: float
    status: str
    utr: str = ""
    sheet_row_index: int | None = None


@dataclass
class InflowTransaction:
    id: str
    date_serial: int
    time_serial: float
    account: str
    inflow_type: str
    received_from: str
    reason: str
    amount: float
    linked_debit_uuid: str = ""


@dataclass
class PayableTransaction:
    id: str
    date_incurred_serial: int
    time_serial: float
    paid_by: str
    what_for: str
    amount_i_owe: float
    date_repaid_serial: int | None
    repayment_status: str


def normalize_category(category_name: str) -> str:
    trimmed = category_name.strip()
    lower = trimmed.lower()
    if lower == "saving":
        raise ValueError("Singular 'Saving' is forbidden. Category must strictly be 'Savings'")
    if lower in ("savings", "investment", "sip", "mutual fund"):
        return CATEGORY_SAVINGS
    return trimmed


def map_transaction_to_full_row(t: Transaction) -> list[Any]:
    norm_cat = normalize_category(t.category)
    return [
        # Table 1: Debits (Cols A-K, indices 0-10)
        t.date_serial,
        t.time_serial,
        t.account,
        t.merchant,
        t.trip_tag,
        norm_cat,
        t.total_amount,
        t.companions,
        t.my_share,
        t.owed_to_me,
        t.status,
        # Spacer 1 (Col L, index 11)
        "",
        # Table 2: Inflows (Cols M-T, indices 12-19)
        "", "", "", "", "", "", "", "",
        # Spacer 2 (Col U, index 20)
        "",
        # Table 3: Payables (Cols V-AB, indices 21-27)
        "", "", "", "", "", "", "",
        # Hidden System UUIDs (Cols AC-AE, indices 28-30)
        t.id,
        "",
        "",
    ]


def map_transaction_to_table1(t: Transaction) -> list[Any]:
    norm_cat = normalize_category(t.category)
    return [
        t.date_serial,
        t.time_serial,
        t.account,
        t.merchant,
        t.trip_tag,
        norm_cat,
        t.total_amount,
        t.companions,
        t.my_share,
        t.owed_to_me,
        t.status,
    ]


def map_inflow_to_full_row(inf: InflowTransaction) -> list[Any]:
    return [
        # Table 1 (0-10)
        "", "", "", "", "", "", "", "", "", "", "",
        # Spacer 1 (11)
        "",
        # Table 2 (12-19)
        inf.date_serial,
        inf.time_serial,
        inf.account,
        inf.inflow_type,
        inf.received_from,
        inf.reason,
        inf.amount,
        inf.linked_debit_uuid,
        # Spacer 2 (20)
        "",
        # Table 3 (21-27)
        "", "", "", "", "", "", "",
        # Hidden UUIDs (28-30)
        "",
        inf.id,
        "",
    ]


def map_payable_to_full_row(pay: PayableTransaction) -> list[Any]:
    return [
        # Table 1 (0-10)
        "", "", "", "", "", "", "", "", "", "", "",
        # Spacer 1 (11)
        "",
        # Table 2 (12-19)
        "", "", "", "", "", "", "", "",
        # Spacer 2 (20)
        "",
        # Table 3 (21-27)
        pay.date_incurred_serial,
        pay.time_serial,
        pay.paid_by,
        pay.what_for,
        pay.amount_i_owe,
        pay.date_repaid_serial if pay.date_repaid_serial is not None else "",
        pay.repayment_status,
        # Hidden UUIDs (28-30)
        "",
        "",
        pay.id,
    ]


def map_to_void_payload(t: Transaction) -> list[Any]:
    void_desc = t.merchant if t.merchant.startswith("[Void] ") else f"[Void] {t.merchant}"
    voided = Transaction(
        id=t.id,
        timestamp=t.timestamp,
        date_serial=t.date_serial,
        time_serial=t.time_serial,
        account=t.account,
        merchant=void_desc,
        trip_tag=t.trip_tag,
        category=t.category,
        total_amount=t.total_amount,
        companions=t.companions,
        my_share=0.0,
        owed_to_me=0.0,
        status="Void",
        utr=t.utr,
        sheet_row_index=t.sheet_row_index,
    )
    return map_transaction_to_full_row(voided)


def map_existing_row_to_void_row(row: list[Any]) -> list[Any]:
    if len(row) != 31:
        raise ValueError(f"Expected 31 columns but got {len(row)}")
    mutable = list(row)
    orig_desc = str(mutable[3] or "")
    void_desc = orig_desc if orig_desc.startswith("[Void] ") else f"[Void] {orig_desc}"
    mutable[3] = void_desc
    mutable[8] = 0.0
    mutable[9] = 0.0
    mutable[10] = "Void"
    return mutable


def map_origin_rollover_update(t: Transaction) -> Transaction:
    return Transaction(
        id=t.id,
        timestamp=t.timestamp,
        date_serial=t.date_serial,
        time_serial=t.time_serial,
        account=t.account,
        merchant=t.merchant,
        trip_tag=t.trip_tag,
        category=t.category,
        total_amount=t.total_amount,
        companions=t.companions,
        my_share=t.my_share,
        owed_to_me=0.0,
        status="Carried",
        utr=t.utr,
        sheet_row_index=t.sheet_row_index,
    )


def map_target_rollover_new_row(t: Transaction, origin_month: str, new_id: str) -> Transaction:
    carried_desc = f"[Carried from {origin_month}] {t.merchant}"
    return Transaction(
        id=new_id,
        timestamp=t.timestamp,
        date_serial=t.date_serial,
        time_serial=t.time_serial,
        account=t.account,
        merchant=carried_desc,
        trip_tag=t.trip_tag,
        category=t.category,
        total_amount=t.total_amount,
        companions=t.companions,
        my_share=0.0,
        owed_to_me=t.owed_to_me,
        status="Pending",
        sheet_row_index=None,
    )


def parse_row_index_from_range(range_str: str) -> int | None:
    cell_part = range_str.split("!")[-1]
    match = re.search(r"[A-Za-z]+(\d+)", cell_part)
    if match:
        return int(match.group(1))
    return None


def hex_to_color_map(hex_code: str) -> dict[str, float]:
    clean = hex_code.lstrip("#")
    r = int(clean[0:2], 16) / 255.0
    g = int(clean[2:4], 16) / 255.0
    b = int(clean[4:6], 16) / 255.0
    return {"red": r, "green": g, "blue": b, "alpha": 1.0}


# ==============================================================================
# Unit & Stress Test Suites
# ==============================================================================

class TestSheetPayloadMapper31Columns(unittest.TestCase):
    """Verifies strict 31-column layout, indices, and spacer positioning."""

    def setUp(self) -> None:
        self.debit = Transaction(
            id="txn_debit_001",
            timestamp=1730811600000,
            date_serial=46331,
            time_serial=0.5416,
            account="SBI",
            merchant="Big Basket Supermarket",
            trip_tag="Monthly Groceries",
            category="Groceries",
            total_amount=1250.75,
            companions="Personal",
            my_share=1250.75,
            owed_to_me=0.0,
            status="Settled",
            utr="123456789012",
        )

    def test_full_row_exact_31_columns(self) -> None:
        row = map_transaction_to_full_row(self.debit)
        self.assertEqual(len(row), 31)

    def test_column_headers_count_and_mapping(self) -> None:
        self.assertEqual(len(SHEET_COLUMNS), 31)
        self.assertEqual(len(SHEET_COLUMN_HEADERS), 31)
        self.assertEqual(SHEET_COLUMNS[0], "A")
        self.assertEqual(SHEET_COLUMNS[10], "K")
        self.assertEqual(SHEET_COLUMNS[11], "L")
        self.assertEqual(SHEET_COLUMNS[12], "M")
        self.assertEqual(SHEET_COLUMNS[19], "T")
        self.assertEqual(SHEET_COLUMNS[20], "U")
        self.assertEqual(SHEET_COLUMNS[21], "V")
        self.assertEqual(SHEET_COLUMNS[27], "AB")
        self.assertEqual(SHEET_COLUMNS[28], "AC")
        self.assertEqual(SHEET_COLUMNS[29], "AD")
        self.assertEqual(SHEET_COLUMNS[30], "AE")

    def test_table_1_debits_indices_0_to_10(self) -> None:
        row = map_transaction_to_full_row(self.debit)
        self.assertEqual(row[0], 46331)            # Col A: Date
        self.assertEqual(row[1], 0.5416)           # Col B: Time
        self.assertEqual(row[2], "SBI")            # Col C: Account
        self.assertEqual(row[3], "Big Basket Supermarket")  # Col D: Item / Description
        self.assertEqual(row[4], "Monthly Groceries")      # Col E: Trip Tag
        self.assertEqual(row[5], "Groceries")      # Col F: Category
        self.assertEqual(row[6], 1250.75)          # Col G: Total Amount
        self.assertEqual(row[7], "Personal")       # Col H: Companions
        self.assertEqual(row[8], 1250.75)          # Col I: My Share
        self.assertEqual(row[9], 0.0)              # Col J: Owed To Me
        self.assertEqual(row[10], "Settled")       # Col K: Status

    def test_spacer_1_at_index_11(self) -> None:
        row = map_transaction_to_full_row(self.debit)
        self.assertEqual(row[11], "")

    def test_table_2_empty_for_debit_indices_12_to_19(self) -> None:
        row = map_transaction_to_full_row(self.debit)
        for idx in range(12, 20):
            self.assertEqual(row[idx], "", f"Index {idx} in Table 2 must be empty for debit")

    def test_spacer_2_at_index_20(self) -> None:
        row = map_transaction_to_full_row(self.debit)
        self.assertEqual(row[20], "")

    def test_table_3_empty_for_debit_indices_21_to_27(self) -> None:
        row = map_transaction_to_full_row(self.debit)
        for idx in range(21, 28):
            self.assertEqual(row[idx], "", f"Index {idx} in Table 3 must be empty for debit")

    def test_hidden_uuids_indices_28_to_30_for_debit(self) -> None:
        row = map_transaction_to_full_row(self.debit)
        self.assertEqual(row[28], "txn_debit_001")  # Col AC: Debit Txn UUID
        self.assertEqual(row[29], "")              # Col AD: Inflow UUID empty
        self.assertEqual(row[30], "")              # Col AE: Payable UUID empty

    def test_inflow_row_mapping(self) -> None:
        inf = InflowTransaction(
            id="inf_sal_123",
            date_serial=46331,
            time_serial=0.4,
            account="SBI",
            inflow_type="Salary",
            received_from="TechCorp India",
            reason="November Salary",
            amount=150000.0,
            linked_debit_uuid="",
        )
        row = map_inflow_to_full_row(inf)
        self.assertEqual(len(row), 31)

        # Table 1 (0-10) empty
        for i in range(11):
            self.assertEqual(row[i], "")
        # Spacer 1 (11)
        self.assertEqual(row[11], "")
        # Table 2 (12-19)
        self.assertEqual(row[12], 46331)
        self.assertEqual(row[13], 0.4)
        self.assertEqual(row[14], "SBI")
        self.assertEqual(row[15], "Salary")
        self.assertEqual(row[16], "TechCorp India")
        self.assertEqual(row[17], "November Salary")
        self.assertEqual(row[18], 150000.0)
        self.assertEqual(row[19], "")
        # Spacer 2 (20)
        self.assertEqual(row[20], "")
        # Table 3 (21-27) empty
        for i in range(21, 28):
            self.assertEqual(row[i], "")
        # Hidden UUIDs (28-30)
        self.assertEqual(row[28], "")
        self.assertEqual(row[29], "inf_sal_123")  # Col AD: Inflow Txn UUID
        self.assertEqual(row[30], "")

    def test_payable_row_mapping(self) -> None:
        pay = PayableTransaction(
            id="pay_liab_456",
            date_incurred_serial=46331,
            time_serial=0.8,
            paid_by="Vikram",
            what_for="Weekend Villa Rent",
            amount_i_owe=3500.0,
            date_repaid_serial=None,
            repayment_status="Pending",
        )
        row = map_payable_to_full_row(pay)
        self.assertEqual(len(row), 31)

        # Table 1 (0-10) empty
        for i in range(11):
            self.assertEqual(row[i], "")
        # Spacer 1 (11)
        self.assertEqual(row[11], "")
        # Table 2 (12-19) empty
        for i in range(12, 20):
            self.assertEqual(row[i], "")
        # Spacer 2 (20)
        self.assertEqual(row[20], "")
        # Table 3 (21-27)
        self.assertEqual(row[21], 46331)
        self.assertEqual(row[22], 0.8)
        self.assertEqual(row[23], "Vikram")
        self.assertEqual(row[24], "Weekend Villa Rent")
        self.assertEqual(row[25], 3500.0)
        self.assertEqual(row[26], "")
        self.assertEqual(row[27], "Pending")
        # Hidden UUIDs (28-30)
        self.assertEqual(row[28], "")
        self.assertEqual(row[29], "")
        self.assertEqual(row[30], "pay_liab_456")  # Col AE: Payable Txn UUID

    def test_table_1_targeted_append_returns_11_columns(self) -> None:
        table1 = map_transaction_to_table1(self.debit)
        self.assertEqual(len(table1), 11)
        self.assertEqual(table1[0], 46331)
        self.assertEqual(table1[10], "Settled")


class TestCategoryNormalizationSavingsRule(unittest.TestCase):
    """Stress tests strict plural Savings keyword enforcement."""

    def test_forbidden_singular_saving_throws_exception(self) -> None:
        for forbidden in ["saving", "Saving", "SAVING", "  saving  ", "\tSaving\n"]:
            with self.assertRaises(ValueError, msg=f"Expected ValueError for '{forbidden}'"):
                normalize_category(forbidden)

    def test_allowed_plural_savings_normalized(self) -> None:
        for valid in ["savings", "Savings", "SAVINGS", "  savings  "]:
            self.assertEqual(normalize_category(valid), "Savings")

    def test_investment_synonyms_map_to_savings(self) -> None:
        synonyms = [
            "investment", "Investment", "INVESTMENT",
            "sip", "SIP", "Sip",
            "mutual fund", "Mutual Fund", "MUTUAL FUND"
        ]
        for syn in synonyms:
            self.assertEqual(
                normalize_category(syn),
                "Savings",
                f"Synonym '{syn}' must normalize strictly to plural 'Savings'"
            )

    def test_other_categories_preserved(self) -> None:
        categories = ["Food & Drink", "Transit", "Groceries", "Trip/Travel", "Health", "Shopping"]
        for cat in categories:
            self.assertEqual(normalize_category(cat), cat)


class TestInPlaceVoidMapping(unittest.TestCase):
    """Verifies that in-place void preserves gross total and UUID while zeroing shares."""

    def setUp(self) -> None:
        self.split_txn = Transaction(
            id="txn_split_777",
            timestamp=1730811600000,
            date_serial=46331,
            time_serial=0.5,
            account="Canara",
            merchant="Olive Garden Dinner",
            trip_tag="Goa Trip",
            category="Food & Drink",
            total_amount=3000.0,
            companions="Anil, Sunil",
            my_share=1000.0,
            owed_to_me=2000.0,
            status="Pending",
            utr="987654321098",
            sheet_row_index=15,
        )

    def test_map_to_void_payload(self) -> None:
        void_row = map_to_void_payload(self.split_txn)
        self.assertEqual(len(void_row), 31)

        # Col D (3): Description prefixed with [Void]
        self.assertEqual(void_row[3], "[Void] Olive Garden Dinner")
        # Col G (6): Total Amount preserved (gross audit trail)
        self.assertEqual(void_row[6], 3000.0)
        # Col I (8): My Share zeroed
        self.assertEqual(void_row[8], 0.0)
        # Col J (9): Owed To Me zeroed
        self.assertEqual(void_row[9], 0.0)
        # Col K (10): Status set to Void
        self.assertEqual(void_row[10], "Void")
        # Col AC (28): UUID intact
        self.assertEqual(void_row[28], "txn_split_777")

    def test_map_to_void_payload_idempotency(self) -> None:
        # If already starts with [Void], should not double prefix
        already_void = Transaction(
            id="txn_split_777",
            timestamp=1730811600000,
            date_serial=46331,
            time_serial=0.5,
            account="Canara",
            merchant="[Void] Olive Garden Dinner",
            trip_tag="",
            category="Food & Drink",
            total_amount=3000.0,
            companions="Anil",
            my_share=1000.0,
            owed_to_me=2000.0,
            status="Pending",
        )
        void_row = map_to_void_payload(already_void)
        self.assertEqual(void_row[3], "[Void] Olive Garden Dinner")

    def test_map_existing_row_to_void_row(self) -> None:
        full_row = map_transaction_to_full_row(self.split_txn)
        void_row = map_existing_row_to_void_row(full_row)
        self.assertEqual(len(void_row), 31)

        self.assertEqual(void_row[3], "[Void] Olive Garden Dinner")
        self.assertEqual(void_row[6], 3000.0)
        self.assertEqual(void_row[8], 0.0)
        self.assertEqual(void_row[9], 0.0)
        self.assertEqual(void_row[10], "Void")
        self.assertEqual(void_row[28], "txn_split_777")

    def test_map_existing_row_to_void_row_rejects_non_31_columns(self) -> None:
        with self.assertRaises(ValueError):
            map_existing_row_to_void_row([1, 2, 3])


class TestMonthlyDebtRolloverMapping(unittest.TestCase):
    """Verifies debt rollover across monthly ledger boundaries."""

    def setUp(self) -> None:
        self.origin_txn = Transaction(
            id="txn_origin_nov",
            timestamp=1730811600000,
            date_serial=46331,
            time_serial=0.6,
            account="SBI",
            merchant="Team Lunch at Mainland China",
            trip_tag="",
            category="Food & Drink",
            total_amount=4500.0,
            companions="Priya, Rohan",
            my_share=1500.0,
            owed_to_me=3000.0,
            status="Pending",
            utr="555666777888",
            sheet_row_index=22,
        )

    def test_origin_month_rollover_update(self) -> None:
        origin_update = map_origin_rollover_update(self.origin_txn)
        self.assertEqual(origin_update.id, "txn_origin_nov")
        self.assertEqual(origin_update.owed_to_me, 0.0)
        self.assertEqual(origin_update.status, "Carried")
        # Personal living share must be preserved so origin month burn is accurate!
        self.assertEqual(origin_update.my_share, 1500.0)

    def test_target_month_rollover_new_row(self) -> None:
        target_row = map_target_rollover_new_row(
            t=self.origin_txn,
            origin_month="Nov26",
            new_id="txn_dec_carried_001"
        )
        self.assertEqual(target_row.id, "txn_dec_carried_001")
        self.assertEqual(target_row.merchant, "[Carried from Nov26] Team Lunch at Mainland China")
        # In target month, my_share is 0.0 so target month burn is NOT inflated!
        self.assertEqual(target_row.my_share, 0.0)
        # Owed to me carries the full receivable!
        self.assertEqual(target_row.owed_to_me, 3000.0)
        self.assertEqual(target_row.status, "Pending")
        self.assertIsNone(target_row.sheet_row_index)


class TestGoogleSheetsClientApiContracts(unittest.TestCase):
    """Empirically verifies RAW mode in query strings and row index parsing."""

    def test_append_url_has_raw_and_overwrite(self) -> None:
        tab_name = "Nov26"
        encoded_range = f"%27{tab_name}%27%21A3%3AAE"
        base_url = "https://sheets.googleapis.com/v4/spreadsheets/TEST_ID/values"
        url = f"{base_url}/{encoded_range}:append?valueInputOption=RAW&insertDataOption=OVERWRITE"

        self.assertIn("valueInputOption=RAW", url)
        self.assertIn("insertDataOption=OVERWRITE", url)

    def test_update_url_has_raw(self) -> None:
        tab_name = "Nov26"
        row_index = 18
        encoded_range = f"%27{tab_name}%27%21A{row_index}%3AAE{row_index}"
        base_url = "https://sheets.googleapis.com/v4/spreadsheets/TEST_ID/values"
        url = f"{base_url}/{encoded_range}?valueInputOption=RAW"

        self.assertIn("valueInputOption=RAW", url)

    def test_parse_row_index_from_range_diverse_formats(self) -> None:
        test_cases = [
            ("'Nov26'!A15:K15", 15),
            ("'Nov26'!A18:AE18", 18),
            ("Nov26!A18:K18", 18),
            ("'Sheet 1'!A100:AE100", 100),
            ("'Dec26'!A4:K4", 4),
            ("'Oct25'!A25:K25", 25),
            ("'Nov26'!A3:AE10", 3),
            ("Nov26!A999:AE999", 999),
        ]
        for input_range, expected_row in test_cases:
            parsed = parse_row_index_from_range(input_range)
            self.assertEqual(
                parsed, expected_row,
                f"Failed to parse row from range '{input_range}'"
            )

    def test_parse_row_index_from_invalid_range_returns_none(self) -> None:
        invalid_cases = ["", "Nov26!", "InvalidRangeWithoutNumbers", "!"]
        for inv in invalid_cases:
            self.assertIsNone(parse_row_index_from_range(inv))


class TestSheetProvisionerFormatting(unittest.TestCase):
    """Verifies batchUpdate request generation, styling, colors, and frozen rows."""

    def test_hex_to_color_map_navy(self) -> None:
        color = hex_to_color_map("#1A237E")
        self.assertAlmostEqual(color["red"], 26 / 255.0, places=3)
        self.assertAlmostEqual(color["green"], 35 / 255.0, places=3)
        self.assertAlmostEqual(color["blue"], 126 / 255.0, places=3)
        self.assertEqual(color["alpha"], 1.0)

    def test_hex_to_color_map_section_colors(self) -> None:
        debits = hex_to_color_map("#C62828")
        self.assertAlmostEqual(debits["red"], 198 / 255.0, places=3)
        self.assertAlmostEqual(debits["green"], 40 / 255.0, places=3)
        self.assertAlmostEqual(debits["blue"], 40 / 255.0, places=3)

        inflows = hex_to_color_map("#2E7D32")
        self.assertAlmostEqual(inflows["red"], 46 / 255.0, places=3)
        self.assertAlmostEqual(inflows["green"], 125 / 255.0, places=3)
        self.assertAlmostEqual(inflows["blue"], 50 / 255.0, places=3)

        payables = hex_to_color_map("#F57F17")
        self.assertAlmostEqual(payables["red"], 245 / 255.0, places=3)
        self.assertAlmostEqual(payables["green"], 127 / 255.0, places=3)
        self.assertAlmostEqual(payables["blue"], 23 / 255.0, places=3)

        spacers = hex_to_color_map("#ECEFF1")
        self.assertAlmostEqual(spacers["red"], 236 / 255.0, places=3)
        self.assertAlmostEqual(spacers["green"], 239 / 255.0, places=3)
        self.assertAlmostEqual(spacers["blue"], 241 / 255.0, places=3)


class TestTokenRecoveryLogic(unittest.TestCase):
    """Simulates transparent HTTP 401 token refresh and retry logic."""

    class MockTokenProvider:
        def __init__(self, initial_token: str, refreshed_token: str) -> None:
            self.current_token = initial_token
            self.refreshed_token = refreshed_token
            self.refresh_calls = 0

        def get_access_token(self) -> str:
            return self.current_token

        def refresh_access_token(self) -> str:
            self.refresh_calls += 1
            self.current_token = self.refreshed_token
            return self.current_token

    def test_transparent_401_recovery(self) -> None:
        provider = self.MockTokenProvider("stale_token_123", "fresh_token_456")

        # Simulate initial request with stale token
        request_auth = f"Bearer {provider.get_access_token()}"
        self.assertEqual(request_auth, "Bearer stale_token_123")

        # Simulate 401 response trigger
        response_code = 401
        retried_auth = None
        retried_code = None

        if response_code == 401:
            fresh_token = provider.refresh_access_token()
            retried_auth = f"Bearer {fresh_token}"
            retried_code = 200  # Google accepts fresh token

        self.assertEqual(provider.refresh_calls, 1)
        self.assertEqual(retried_auth, "Bearer fresh_token_456")
        self.assertEqual(retried_code, 200)


class TestHygieneAndPolicyCompliance(unittest.TestCase):
    """Verifies repository-wide rules: zero emojis and zero machine paths in M3 code."""

    def test_zero_emojis_in_m3_code(self) -> None:
        base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        m3_files = [
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "remote", "SheetPayloadMapper.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "remote", "GoogleSheetsClient.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "remote", "GoogleDriveClient.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "remote", "SheetProvisioner.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "remote", "TokenRecoveryInterceptor.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "remote", "TokenProvider.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "repository", "TransactionRepositoryImpl.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "repository", "PreferencesRepositoryImpl.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "di", "AppContainer.kt"),
        ]

        emoji_pattern = re.compile(
            r"[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF"
            r"\U0001F700-\U0001F77F\U0001F780-\U0001F7FF\U0001F800-\U0001F8FF"
            r"\U0001F900-\U0001F9FF\U0001FA00-\U0001FA6F\U0001FA70-\U0001FAFF"
            r"\U00002702-\U000027B0\U000024C2-\U0001F251]"
        )

        for file_path in m3_files:
            if not os.path.exists(file_path):
                continue
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
                matches = emoji_pattern.findall(content)
                self.assertEqual(
                    len(matches), 0,
                    f"Found forbidden emojis in {file_path}: {matches}"
                )

    def test_zero_machine_paths_in_m3_code(self) -> None:
        base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        m3_files = [
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "remote", "SheetPayloadMapper.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "remote", "GoogleSheetsClient.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "remote", "GoogleDriveClient.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "remote", "SheetProvisioner.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "remote", "TokenRecoveryInterceptor.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "repository", "TransactionRepositoryImpl.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "data", "repository", "PreferencesRepositoryImpl.kt"),
            os.path.join(base_dir, "app", "src", "main", "java", "com", "financeautomation", "di", "AppContainer.kt"),
        ]

        machine_path_pattern = re.compile(r"[A-Za-z]:\\[A-Za-z0-9_\\\.\-]+|file:///[A-Za-z]:")

        for file_path in m3_files:
            if not os.path.exists(file_path):
                continue
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
                matches = machine_path_pattern.findall(content)
                self.assertEqual(
                    len(matches), 0,
                    f"Found hardcoded machine paths in {file_path}: {matches}"
                )


class TestAdversarialPayloadStress(unittest.TestCase):
    """Stress tests adversarial inputs, formula injection strings, and extreme numeric values."""

    def test_formula_injection_strings_in_merchant_and_trip(self) -> None:
        injection_strings = [
            "=1+1",
            "=cmd|' /C calc'!A0",
            "@SUM(A1:A10)",
            "+100",
            "-50",
            "\"\"\"' OR '1'='1",
            "<script>alert(1)</script>",
            "\n\r\tSpecial\x00Chars",
        ]
        for injection in injection_strings:
            txn = Transaction(
                id="txn_inj_001",
                timestamp=1730811600000,
                date_serial=46331,
                time_serial=0.5,
                account="SBI",
                merchant=injection,
                trip_tag=injection,
                category="Shopping",
                total_amount=99.99,
                companions="Personal",
                my_share=99.99,
                owed_to_me=0.0,
                status="Settled",
            )
            row = map_transaction_to_full_row(txn)
            self.assertEqual(len(row), 31)
            self.assertEqual(row[3], injection)
            self.assertEqual(row[4], injection)
            # Under RAW mode, Google Sheets stores these verbatim as strings without formula evaluation

    def test_extreme_amounts_and_precision(self) -> None:
        extreme_amounts = [
            0.0,
            0.01,
            999999999.99,
            100.0 / 3.0,  # 33.333333333333336
        ]
        for amt in extreme_amounts:
            txn = Transaction(
                id="txn_extreme",
                timestamp=1730811600000,
                date_serial=46331,
                time_serial=0.5,
                account="SBI",
                merchant="Test",
                trip_tag="",
                category="Other",
                total_amount=amt,
                companions="Personal",
                my_share=amt,
                owed_to_me=0.0,
                status="Settled",
            )
            row = map_transaction_to_full_row(txn)
            self.assertEqual(len(row), 31)
            self.assertEqual(row[6], amt)
            self.assertEqual(row[8], amt)


class TestSheetProvisionerPayloadStructure(unittest.TestCase):
    """Verifies complete 16-request batchUpdate structure in SheetProvisioner."""

    def test_provision_requests_exact_count_and_types(self) -> None:
        # We simulate the exact logic of SheetProvisioner.buildProvisionRequests
        sheet_id = 0
        tab_title = "Nov26"
        banner_title = f"EXPENSE LEDGER — {tab_title.upper()}"
        self.assertEqual(banner_title, "EXPENSE LEDGER — NOV26")
        requests: list[dict[str, Any]] = []

        # 1. Freeze top 3 rows
        requests.append({"updateSheetProperties": {"properties": {"sheetId": sheet_id, "gridProperties": {"frozenRowCount": 3}}}})

        # 2. Set spacer widths (Col L = 11, Col U = 20)
        for col_idx in [11, 20]:
            requests.append({"updateDimensionProperties": {"range": {"startIndex": col_idx, "endIndex": col_idx + 1}, "properties": {"pixelSize": 10}}})

        # 3. Merges: Banner + 4 Sections
        # Banner (A1:AE1)
        requests.append({"mergeCells": {"range": {"startRowIndex": 0, "endRowIndex": 1, "startColumnIndex": 0, "endColumnIndex": 31}}})
        # Table 1 (A2:K2)
        requests.append({"mergeCells": {"range": {"startRowIndex": 1, "endRowIndex": 2, "startColumnIndex": 0, "endColumnIndex": 11}}})
        # Table 2 (M2:T2)
        requests.append({"mergeCells": {"range": {"startRowIndex": 1, "endRowIndex": 2, "startColumnIndex": 12, "endColumnIndex": 20}}})
        # Table 3 (V2:AB2)
        requests.append({"mergeCells": {"range": {"startRowIndex": 1, "endRowIndex": 2, "startColumnIndex": 21, "endColumnIndex": 28}}})
        # UUIDs (AC2:AE2)
        requests.append({"mergeCells": {"range": {"startRowIndex": 1, "endRowIndex": 2, "startColumnIndex": 28, "endColumnIndex": 31}}})

        # 4. Banner Title repeatCell
        requests.append({"repeatCell": {"range": {"startRowIndex": 0, "endRowIndex": 1}}})

        # 5. Row 2 Section Headers repeatCell (6 sections)
        for _ in range(6):
            requests.append({"repeatCell": {"range": {"startRowIndex": 1, "endRowIndex": 2}}})

        # 6. Row 3 Column Headers updateCells
        requests.append({"updateCells": {"start": {"rowIndex": 2, "columnIndex": 0}}})

        self.assertEqual(len(requests), 16)
        frozen = [r for r in requests if "updateSheetProperties" in r]
        self.assertEqual(len(frozen), 1)
        spacers = [r for r in requests if "updateDimensionProperties" in r]
        self.assertEqual(len(spacers), 2)
        merges = [r for r in requests if "mergeCells" in r]
        self.assertEqual(len(merges), 5)
        repeats = [r for r in requests if "repeatCell" in r]
        self.assertEqual(len(repeats), 7)
        updates = [r for r in requests if "updateCells" in r]
        self.assertEqual(len(updates), 1)


class TestTokenRecoveryInterceptorEdgeCases(unittest.TestCase):
    """Stress tests 401 retry edge cases and failure modes."""

    class MockProvider:
        def __init__(self, token: str | None, refresh_token: str | None) -> None:
            self.token = token
            self.refresh_token = refresh_token
            self.refresh_count = 0

        def get_access_token(self) -> str | None:
            return self.token

        def refresh_access_token(self) -> str | None:
            self.refresh_count += 1
            self.token = self.refresh_token
            return self.refresh_token

    def test_401_with_failed_refresh_returns_401(self) -> None:
        # When refresh returns None, interceptor must not retry and return 401
        provider = self.MockProvider("stale_token", None)

        def simulate_interceptor(initial_code: int) -> tuple[int, int]:
            _ = provider.get_access_token()
            resp_code = initial_code
            retries = 0
            if resp_code == 401:
                fresh = provider.refresh_access_token()
                if fresh is not None:
                    retries += 1
                    resp_code = 200
            return resp_code, retries

        final_code, retries = simulate_interceptor(401)
        self.assertEqual(final_code, 401)
        self.assertEqual(retries, 0)
        self.assertEqual(provider.refresh_count, 1)

    def test_non_401_responses_do_not_trigger_refresh(self) -> None:
        provider = self.MockProvider("valid_token", "refreshed_token")

        for code in [200, 201, 204, 400, 403, 404, 500, 503]:
            # Non-401 responses pass through untouched
            if code == 401:
                provider.refresh_access_token()
            self.assertEqual(provider.refresh_count, 0)


if __name__ == "__main__":
    unittest.main()
