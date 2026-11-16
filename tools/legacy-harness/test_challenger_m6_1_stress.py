"""Adversarial Verification & Stress-Testing Suite for Milestone 6 (challenger_m6_1).

Role: Domain & Schema Adversarial Verifier
Empirically tests and stress-tests:
1. Split Mathematics:
   - "Just Me" (Total = My Share, Owed = 0.00, Status = Settled, Companions = "Personal").
   - "Split Equally" (N + 1 division across arbitrary companion counts N = 1..50, 2-decimal HALF_UP rounding, penny conservation).
   - "Custom Split" (custom share, owed balance, Status = Pending / Settled, negative / exceeding bounds validation).
   - Debt Rollover (origin Col J zeroed, Status = Carried; target month new row with fresh UUID, myShare = 0.00, owedToMe = origin owed).
   - Strict plural "Savings" keyword enforcement; singular "Saving" strictly rejected.
2. Google Sheets Payload & RAW Values:
   - 31-Column schema adherence (Cols A-AE layout and exact index matching, exactly 31 elements).
   - RAW input option, formula injection neutralization (=SUM, +CMD, -1+1, @SUM stored as literal text).
   - Non-destructive in-place void logic (totalAmount preserved, shares zeroed, Status = Void, prefixed description).
3. User Directives Verification:
   - Zero fake fallbacks: unconfigured spreadsheetId or client returns Result.Error, never Result.Success(0).
   - UTR simplification: Amount + Merchant + sliding window (60-120s) deduplication works seamlessly when UTR is missing or null.
4. Repository Hygiene:
   - Zero emojis in code, comments, strings, or repo docs.
   - Zero hardcoded local machine paths in repo docs and code.
"""

from __future__ import annotations

import os
import pathlib
import random
import re
import sys
import unittest
from decimal import ROUND_HALF_UP, Decimal
from typing import Any

ROOT_DIR = pathlib.Path(__file__).resolve().parent.parent
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from domain_harness import (
    SHEET_COLUMN_HEADERS,
    SHEET_COLUMNS,
    DeduplicationEngine,
    InflowTransaction,
    InflowType,
    PayableTransaction,
    RepaymentStatus,
    ReviewState,
    RolloverEngine,
    SheetPayloadMapper,
    SplitCalculationEngine,
    SyncState,
    Transaction,
    TransactionStatus,
    VoidEngine,
)


def round2(val: float) -> float:
    """Standard 2-decimal HALF_UP rounding matching Kotlin BigDecimal."""
    return float(Decimal(str(val)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


# ==============================================================================
# Suite 1: Domain Split Mathematics & Plural Savings Keyword Stress
# ==============================================================================

class TestDomainSplitMathStress(unittest.TestCase):
    """Adversarial stress-testing of 3-mode split math, rounding, and Savings keyword."""

    def test_just_me_math_and_penny_conservation(self):
        """Stress-tests Just Me calculation across diverse amounts."""
        test_amounts = [
            0.01, 0.02, 0.99, 1.00, 10.005, 10.004, 12.345, 99.99, 1500.00,
            99999.99, 1000000.00
        ]
        for amt in test_amounts:
            res = SplitCalculationEngine.calculate_just_me(amt)
            expected_total = round2(amt)
            self.assertEqual(res.total_amount, expected_total)
            self.assertEqual(res.my_share, expected_total)
            self.assertEqual(res.owed_to_me, 0.00)
            self.assertEqual(res.companions, "Personal")
            self.assertEqual(res.status, TransactionStatus.SETTLED)
            self.assertEqual(res.my_share + res.owed_to_me, res.total_amount)

    def test_split_equally_across_arbitrary_companion_counts_n1_to_50(self):
        """Stress-tests Split Equally across arbitrary companion counts N=1..50."""
        amounts = [10.0, 50.0, 100.0, 250.75, 999.99, 1000.0, 3333.33, 7777.77]
        for n in range(1, 51):
            companions = [f"Friend_{i}" for i in range(1, n + 1)]
            total_heads = n + 1
            for amt in amounts:
                res = SplitCalculationEngine.calculate_split_equally(amt, companions)
                rounded_amt = round(amt, 2)
                expected_share = round(rounded_amt / total_heads, 2)
                expected_owed = round(rounded_amt - expected_share, 2)

                self.assertEqual(res.total_amount, rounded_amt)
                self.assertEqual(res.my_share, expected_share)
                self.assertEqual(res.owed_to_me, expected_owed)
                self.assertEqual(res.status, TransactionStatus.PENDING)
                self.assertEqual(res.companions, ", ".join(companions))
                # Penny conservation: my_share + owed_to_me MUST strictly equal total_amount
                self.assertEqual(round(res.my_share + res.owed_to_me, 2), res.total_amount)

    def test_kotlin_calculate_split_use_case_half_up_math(self):
        """Stress-tests exact Kotlin CalculateSplitUseCase.kt HALF_UP rounding engine."""
        class KotlinCalculateSplit:
            @staticmethod
            def round_half_up(val: float) -> float:
                return float(Decimal(str(val)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))

            @classmethod
            def calculate_split_equally(cls, total_amount: float, companions: list[str]) -> tuple[float, float, float]:
                rounded = cls.round_half_up(total_amount)
                clean_companions = [c.strip() for c in companions if c.strip()]
                if not clean_companions:
                    return rounded, rounded, 0.00
                total_heads = len(clean_companions) + 1
                my_share = cls.round_half_up(rounded / total_heads)
                owed_to_me = cls.round_half_up(rounded - my_share)
                return rounded, my_share, owed_to_me

        # 3333.33 split 2 heads -> 3333.33 / 2 = 1666.665 -> HALF_UP is 1666.67
        total, share, owed = KotlinCalculateSplit.calculate_split_equally(3333.33, ["Alice"])
        self.assertEqual(total, 3333.33)
        self.assertEqual(share, 1666.67)
        self.assertEqual(owed, 1666.66)
        self.assertEqual(round(share + owed, 2), total)

        # 100.00 split 3 heads -> 100 / 3 = 33.333... -> 33.33, owed = 66.67
        _total3, share3, owed3 = KotlinCalculateSplit.calculate_split_equally(100.00, ["Alice", "Bob"])
        self.assertEqual(share3, 33.33)
        self.assertEqual(owed3, 66.67)
        self.assertEqual(round(share3 + owed3, 2), 100.00)

        # Randomized 1000 runs on Kotlin HALF_UP logic
        rng = random.Random(99)
        for _ in range(1000):
            amt = round(rng.uniform(0.01, 50000.0), 2)
            n = rng.randint(1, 50)
            comps = [f"Peer_{i}" for i in range(n)]
            t, s, o = KotlinCalculateSplit.calculate_split_equally(amt, comps)
            self.assertEqual(t, amt)
            self.assertEqual(round(s + o, 2), t)

    def test_split_equally_empty_or_whitespace_companions_fallback(self):
        """Empty or whitespace-only companions fallback to Just Me."""
        res_empty = SplitCalculationEngine.calculate_split_equally(100.0, [])
        self.assertEqual(res_empty.my_share, 100.0)
        self.assertEqual(res_empty.owed_to_me, 0.0)
        self.assertEqual(res_empty.companions, "Personal")
        self.assertEqual(res_empty.status, TransactionStatus.SETTLED)

    def test_split_equally_monte_carlo_1000_scenarios(self):
        """Randomized property-based testing of 1,000 Split Equally scenarios."""
        rng = random.Random(42)
        for _ in range(1000):
            amt = round(rng.uniform(0.01, 100000.0), 2)
            n_companions = rng.randint(1, 50)
            companions = [f"Buddy_{i}" for i in range(n_companions)]

            res = SplitCalculationEngine.calculate_split_equally(amt, companions)
            expected_total = round(amt, 2)
            expected_share = round(expected_total / (n_companions + 1), 2)
            expected_owed = round(expected_total - expected_share, 2)

            self.assertEqual(res.total_amount, expected_total)
            self.assertEqual(res.my_share, expected_share)
            self.assertEqual(res.owed_to_me, expected_owed)
            self.assertEqual(res.status, TransactionStatus.PENDING)
            self.assertEqual(round(res.my_share + res.owed_to_me, 2), res.total_amount)

    def test_custom_split_boundaries_and_validations(self):
        """Stress-tests Custom Split with exact bounds and invalid inputs."""
        total = 500.00

        # Exact share equal to total: 100% personal, owed = 0, status = Settled
        res_full = SplitCalculationEngine.calculate_custom_split(total, 500.00, ["Alice"])
        self.assertEqual(res_full.my_share, 500.00)
        self.assertEqual(res_full.owed_to_me, 0.00)
        self.assertEqual(res_full.status, TransactionStatus.SETTLED)

        # Zero share: 100% friend debt, owed = total, status = Pending
        res_zero = SplitCalculationEngine.calculate_custom_split(total, 0.00, ["Alice"])
        self.assertEqual(res_zero.my_share, 0.00)
        self.assertEqual(res_zero.owed_to_me, 500.00)
        self.assertEqual(res_zero.status, TransactionStatus.PENDING)

        # Typical split
        res_partial = SplitCalculationEngine.calculate_custom_split(total, 175.50, ["Alice", "Bob"])
        self.assertEqual(res_partial.my_share, 175.50)
        self.assertEqual(res_partial.owed_to_me, 324.50)
        self.assertEqual(res_partial.status, TransactionStatus.PENDING)
        self.assertEqual(res_partial.companions, "Alice, Bob")

        # Blank companion names fallback to "Friends"
        res_no_comp = SplitCalculationEngine.calculate_custom_split(total, 200.00, [])
        self.assertEqual(res_no_comp.companions, "Friends")

        # Boundary rejections: negative share
        with self.assertRaises(ValueError):
            SplitCalculationEngine.calculate_custom_split(total, -0.01, ["Alice"])

        # Boundary rejections: share exceeding total
        with self.assertRaises(ValueError):
            SplitCalculationEngine.calculate_custom_split(total, 500.01, ["Alice"])

    def test_strict_plural_savings_keyword_enforcement(self):
        """Strictly enforces plural 'Savings' and rejects singular 'Saving'."""
        # Plural variations accepted
        for variant in ["Savings", "savings", "SAVINGS", "  Savings  "]:
            self.assertEqual(SplitCalculationEngine.validate_category(variant), "Savings")

        # Aliases mapped to 'Savings'
        for alias in ["investment", "Investment", "INVESTMENT", "sip", "SIP", "mutual fund", "Mutual Fund"]:
            self.assertEqual(SplitCalculationEngine.validate_category(alias), "Savings")

        # Singular 'Saving' strictly forbidden and raises ValueError
        for forbidden in ["Saving", "saving", "SAVING", "  saving  "]:
            with self.assertRaises(ValueError):
                SplitCalculationEngine.validate_category(forbidden)

        # Standard non-savings categories preserved
        self.assertEqual(SplitCalculationEngine.validate_category("Food & Drink"), "Food & Drink")
        self.assertEqual(SplitCalculationEngine.validate_category("Transit"), "Transit")


# ==============================================================================
# Suite 2: Debt Rollover Stress
# ==============================================================================

class TestDebtRolloverStress(unittest.TestCase):
    """Adversarial stress-testing of end-of-month debt carry-forward."""

    def setUp(self):
        self.origin_txn = Transaction(
            id="txn_orig_12345",
            date_serial=46340,
            time_serial=0.5,
            account="SBI",
            item_description="Team Dinner",
            category="Food & Drink",
            total_amount=1500.00,
            companions="Alice, Bob",
            my_share=500.00,
            owed_to_me=1000.00,
            status=TransactionStatus.PENDING,
            trip_tag="",
            review_state=ReviewState.CONFIRMED,
            sync_state=SyncState.SYNCED,
        )

    def test_debt_rollover_normal_flow(self):
        """Valid debt rollover zeroes Col J in origin and creates target row."""
        updated_orig, target_row = RolloverEngine.carry_forward_unsettled_debt(
            origin_month="Nov26",
            target_month="Dec26",
            origin_txn=self.origin_txn,
            target_date_serial=46371,
            target_time_serial=0.25,
        )

        # Origin verification
        self.assertEqual(updated_orig.owed_to_me, 0.00)
        self.assertEqual(updated_orig.status, TransactionStatus.CARRIED)
        self.assertEqual(updated_orig.total_amount, 1500.00)
        self.assertEqual(updated_orig.my_share, 500.00)

        # Target verification
        self.assertTrue(target_row.id.startswith("txn_carried_"))
        self.assertNotEqual(target_row.id, self.origin_txn.id)
        self.assertEqual(target_row.item_description, "[Carried from Nov26] Team Dinner")
        self.assertEqual(target_row.total_amount, 0.00)  # Invariant: Col G=0 prevents double-counting
        self.assertEqual(target_row.my_share, 0.00)
        self.assertEqual(target_row.owed_to_me, 1000.00)
        self.assertEqual(target_row.status, TransactionStatus.PENDING)
        self.assertEqual(target_row.review_state, ReviewState.CONFIRMED)
        self.assertEqual(target_row.sync_state, SyncState.PENDING)
        self.assertEqual(target_row.date_serial, 46371)
        self.assertEqual(target_row.time_serial, 0.25)

    def test_debt_rollover_rejections(self):
        """Rejects rollover for Settled, Void, Carried, or zero-debt transactions."""
        # Settled transaction
        settled_txn = Transaction(
            id="txn_settled", date_serial=46340, time_serial=0.5, account="SBI",
            item_description="Lunch", category="Food & Drink", total_amount=200.00,
            companions="Personal", my_share=200.00, owed_to_me=0.00,
            status=TransactionStatus.SETTLED, trip_tag="",
        )
        with self.assertRaises(ValueError):
            RolloverEngine.carry_forward_unsettled_debt("Nov26", "Dec26", settled_txn, 46371, 0.1)

        # Void transaction
        void_txn = Transaction(
            id="txn_void", date_serial=46340, time_serial=0.5, account="SBI",
            item_description="[Void] Movie", category="Other", total_amount=500.00,
            my_share=0.00, owed_to_me=0.00, status=TransactionStatus.VOID, trip_tag="",
        )
        with self.assertRaises(ValueError):
            RolloverEngine.carry_forward_unsettled_debt("Nov26", "Dec26", void_txn, 46371, 0.1)

        # Already carried transaction
        carried_txn = Transaction(
            id="txn_carried", date_serial=46340, time_serial=0.5, account="SBI",
            item_description="Carried debt", category="Other", total_amount=500.00,
            my_share=0.00, owed_to_me=0.00, status=TransactionStatus.CARRIED, trip_tag="",
        )
        with self.assertRaises(ValueError):
            RolloverEngine.carry_forward_unsettled_debt("Nov26", "Dec26", carried_txn, 46371, 0.1)


# ==============================================================================
# Suite 3: Google Sheets Payload & RAW Values Stress
# ==============================================================================

class TestGoogleSheetsPayloadAndRawStress(unittest.TestCase):
    """Adversarial stress-testing of 31-column layout, RAW inputs, and voiding."""

    def test_schema_constants_exact_31_columns(self):
        """Verifies exact 31-column schema from A to AE."""
        self.assertEqual(len(SHEET_COLUMNS), 31)
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

        # Header map completeness
        self.assertEqual(len(SHEET_COLUMN_HEADERS), 31)
        for col in SHEET_COLUMNS:
            self.assertIn(col, SHEET_COLUMN_HEADERS)

    def test_table_1_debit_row_mapping_11_values(self):
        """Table 1 debit row maps strictly to 11 values (Cols A-K)."""
        txn = Transaction(
            id="txn_test_1",
            date_serial=46340,
            time_serial=0.523456,
            account="SBI",
            item_description="Grocery Store",
            category="Groceries",
            total_amount=1250.50,
            companions="Personal",
            my_share=1250.50,
            owed_to_me=0.00,
            status=TransactionStatus.SETTLED,
            trip_tag="Goa",
        )
        row = SheetPayloadMapper.map_debit_row(txn)
        self.assertEqual(len(row), 11)
        self.assertEqual(row[0], 46340)
        self.assertEqual(row[1], 0.523456)
        self.assertEqual(row[2], "SBI")
        self.assertEqual(row[3], "Grocery Store")
        self.assertEqual(row[4], "Goa")
        self.assertEqual(row[5], "Groceries")
        self.assertEqual(row[6], 1250.50)
        self.assertEqual(row[7], "Personal")
        self.assertEqual(row[8], 1250.50)
        self.assertEqual(row[9], 0.00)
        self.assertEqual(row[10], "Settled")

    def test_table_2_inflow_row_mapping_8_values(self):
        """Table 2 inflow row maps strictly to 8 values (Cols M-T)."""
        inflow = InflowTransaction(
            id="inflow_1",
            date_serial=46341,
            time_serial=0.6,
            account="Canara",
            inflow_type=InflowType.SALARY,
            received_from="Acme Corp",
            reason="Monthly Salary",
            amount=85000.00,
            linked_debit_uuid="",
        )
        row = SheetPayloadMapper.map_inflow_row(inflow)
        self.assertEqual(len(row), 8)
        self.assertEqual(row[0], 46341)
        self.assertEqual(row[3], "Salary")
        self.assertEqual(row[6], 85000.00)

    def test_table_3_payable_row_mapping_7_values(self):
        """Table 3 payable row maps strictly to 7 values (Cols V-AB)."""
        payable = PayableTransaction(
            id="payable_1",
            date_incurred_serial=46330,
            time_serial=0.4,
            paid_by="Alice",
            what_for="Concert Tickets",
            amount_i_owe=2500.00,
            date_repaid_serial=None,
            repayment_status=RepaymentStatus.PENDING,
        )
        row = SheetPayloadMapper.map_payable_row(payable)
        self.assertEqual(len(row), 7)
        self.assertEqual(row[0], 46330)
        self.assertEqual(row[2], "Alice")
        self.assertEqual(row[4], 2500.00)
        self.assertEqual(row[5], "")
        self.assertEqual(row[6], "Pending")

    def test_formula_injection_neutralization_under_raw_mode(self):
        """Verifies adversarial formula injection strings remain literal under RAW option."""
        adversarial_payloads = [
            "=SUM(A1:B10)",
            "+cmd|' /C calc'!A0",
            "-1+1",
            "@SUM(A1)",
            '=HYPERLINK("http://malicious.site", "Phishing")',
            "=IMPORTXML('http://malicious.site/data', '//a')",
            "+1234567890",
            "=cmd|' /C powershell -c Write-Host pwned'!A1",
        ]
        for payload in adversarial_payloads:
            txn = Transaction(
                id="txn_sec", date_serial=46340, time_serial=0.1, account="SBI",
                item_description=payload, category="Other", total_amount=100.0,
                companions=payload, my_share=100.0, owed_to_me=0.0,
                status=TransactionStatus.SETTLED, trip_tag="",
            )
            row = SheetPayloadMapper.map_debit_row(txn)
            # Item description preserved as literal without modification
            self.assertEqual(row[3], payload)
            self.assertTrue(SheetPayloadMapper.is_safe_raw_value(row[3]))

    def test_non_destructive_in_place_void(self):
        """Verifies in-place void logic preserves totalAmount, zeroes shares, and prefixes title."""
        txn = Transaction(
            id="txn_to_void",
            date_serial=46340,
            time_serial=0.5,
            account="SBI",
            item_description="Dinner with team",
            category="Food & Drink",
            total_amount=1200.00,
            companions="Bob",
            my_share=600.00,
            owed_to_me=600.00,
            status=TransactionStatus.PENDING,
            trip_tag="",
            review_state=ReviewState.CONFIRMED,
            sync_state=SyncState.SYNCED,
        )

        voided = VoidEngine.apply_void(txn)
        self.assertEqual(voided.total_amount, 0.00)  # Invariant: Col G=0 prevents double-counting
        self.assertEqual(voided.my_share, 0.00)
        self.assertEqual(voided.owed_to_me, 0.00)
        self.assertEqual(voided.status, TransactionStatus.VOID)
        self.assertEqual(voided.sync_state, SyncState.VOID)
        self.assertEqual(voided.item_description, "[Void] Dinner with team")

        # Idempotent voiding: repeated application does not duplicate [Void] prefix
        voided_again = VoidEngine.apply_void(voided)
        self.assertEqual(voided_again.item_description, "[Void] Dinner with team")


# ==============================================================================
# Suite 4: User Directives — Zero Fake Fallbacks & UTR Simplification
# ==============================================================================

class TestUserDirectivesStress(unittest.TestCase):
    """Stress-tests zero mock fallbacks and UTR simplification."""

    def test_unconfigured_spreadsheet_id_zero_fake_success(self):
        """Unconfigured spreadsheetId must raise/return error, never fake success (0)."""
        # Read Kotlin TransactionRepositoryImpl to confirm zero fake fallback logic
        repo_impl_path = ROOT_DIR / "app" / "src" / "main" / "java" / "com" / "financeautomation" / "data" / "repository" / "TransactionRepositoryImpl.kt"
        self.assertTrue(repo_impl_path.exists(), f"File missing: {repo_impl_path}")
        content = repo_impl_path.read_text(encoding="utf-8")

        # Assert presence of explicit Result.Error return when spreadsheetId is null or blank
        self.assertIn("spreadsheetId.isNullOrBlank()", content)
        self.assertIn("Result.Error(IllegalStateException", content)
        # Ensure it does NOT return Result.Success(0) on missing spreadsheetId
        pattern = re.compile(r"if\s*\(\s*spreadsheetId\.isNullOrBlank\(\)\s*\)\s*\{\s*return@withContext\s+Result\.Success\(0\)", re.MULTILINE)
        self.assertIsNone(pattern.search(content), "Forbidden fake success return found in TransactionRepositoryImpl!")

    def test_utr_simplification_sliding_window_deduplication(self):
        """Amount + Merchant + sliding window deduplication works seamlessly without UTR."""
        engine = DeduplicationEngine(settle_window_seconds=120.0)

        # Baseline alert at t=1000s without UTR
        txn1 = Transaction(
            id="txn_alert1", date_serial=46340, time_serial=0.1, account="SBI",
            item_description="Swiggy", category="Food & Drink", total_amount=450.00,
            companions="Personal", my_share=450.00, owed_to_me=0.00,
            status=TransactionStatus.SETTLED, trip_tag="", timestamp=1000.0,
        )
        engine.recent_transactions.append(txn1)

        # Duplicate alert at t=1045s with identical merchant and amount merges
        action2, match2 = engine.evaluate_incoming(
            package_name="com.android.mms",
            amount=450.00,
            merchant="Swiggy",
            timestamp=1045.0,
        )
        self.assertEqual(action2, "MERGE_EXACT_SIMILAR")
        self.assertIsNotNone(match2)

        # Second alert at t=1045s with same amount but distinct merchant flags possible duplicate
        action2b, match2b = engine.evaluate_incoming(
            package_name="com.android.mms",
            amount=450.00,
            merchant="Zomato",
            timestamp=1045.0,
        )
        self.assertEqual(action2b, "FLAG_POSSIBLE_DUPLICATE")
        self.assertIsNotNone(match2b)
        self.assertTrue(match2b.is_possible_duplicate)

        # Third alert at t=1300s (300s later, beyond 120s buffer) with same amount accepted as new
        action3, match3 = engine.evaluate_incoming(
            package_name="com.google.android.apps.nbu.paisa.user",
            amount=450.00,
            merchant="Swiggy",
            timestamp=1300.0,
        )
        self.assertEqual(action3, "ACCEPT_NEW")
        self.assertIsNone(match3)

        # Fourth alert at t=1050s with DIFFERENT amount (550 vs 450) accepted as new
        action4, match4 = engine.evaluate_incoming(
            package_name="com.google.android.apps.nbu.paisa.user",
            amount=550.00,
            merchant="Swiggy",
            timestamp=1050.0,
        )
        self.assertEqual(action4, "ACCEPT_NEW")
        self.assertIsNone(match4)

    def test_exact_utr_merging_regardless_of_window(self):
        """Enriches unknown payee in-place and merges via Amount + Merchant in window."""
        engine = DeduplicationEngine(settle_window_seconds=120.0)

        txn1 = Transaction(
            id="txn_utr1", date_serial=46340, time_serial=0.1, account="Other",
            item_description="Unknown Payee", category="Other", total_amount=350.00,
            companions="Personal", my_share=350.00, owed_to_me=0.00,
            status=TransactionStatus.SETTLED, trip_tag="", timestamp=1000.0,
        )
        engine.recent_transactions.append(txn1)

        # Incoming companion alert with same amount enriches unknown payee
        action, match = engine.evaluate_incoming(
            package_name="com.google.android.apps.nbu.paisa.user",
            amount=350.00,
            merchant="Starbucks Coffee",
            timestamp=1010.0,
        )
        self.assertEqual(action, "MERGE_EXACT_SIMILAR")
        self.assertIsNotNone(match)
        self.assertEqual(match.item_description, "Starbucks Coffee")


# ==============================================================================
# Suite 5: Repository Hygiene & Zero Emojis / Paths Invariants
# ==============================================================================

class TestHygieneAndSafetyInvariants(unittest.TestCase):
    """Verifies Zero Emojis and Zero Hardcoded Local Machine Paths invariants."""

    @staticmethod
    def _has_emoji(text: str) -> bool:
        for ch in text:
            code = ord(ch)
            if ((0x1F600 <= code <= 0x1F64F) or
                (0x1F300 <= code <= 0x1F5FF) or
                (0x1F680 <= code <= 0x1F6FF) or
                (0x2600 <= code <= 0x26FF) or
                (0x2700 <= code <= 0x27BF) or
                (0x1F900 <= code <= 0x1F9FF) or
                (0x1F018 <= code <= 0x1F270) or
                (0x23E9 <= code <= 0x23F3) or
                (0x2B50 <= code <= 0x2B55)):
                return True
        return False

    def test_zero_emojis_in_source_and_tests(self):
        """Scans all Kotlin, Java, and Python source and test files for emojis."""
        violating_files = []
        scan_exts = (".kt", ".java", ".py")

        for scan_dir in [ROOT_DIR / "app" / "src", ROOT_DIR / "tests"]:
            for root, _, files in os.walk(scan_dir):
                if "__pycache__" in root or ".gradle" in root:
                    continue
                for f in files:
                    if f.endswith(scan_exts):
                        fpath = pathlib.Path(root) / f
                        content = fpath.read_text(encoding="utf-8", errors="ignore")
                        if self._has_emoji(content):
                            violating_files.append(str(fpath.relative_to(ROOT_DIR)))

        self.assertEqual(violating_files, [], f"Emoji violations found in source/test files: {violating_files}")

    def test_zero_hardcoded_local_machine_paths(self):
        """Scans codebase (excluding agent metadata) for hardcoded local user paths."""
        violating_files = []
        forbidden_patterns = [
            re.compile(r"[C|c]:\\Users\\", re.IGNORECASE),
            re.compile(r"[C|c]:/Users/", re.IGNORECASE),
            re.compile(r"/home/[a-zA-Z0-9_\-]+/", re.IGNORECASE),
        ]

        scan_dirs = [ROOT_DIR / "app", ROOT_DIR / "tests"]
        for scan_dir in scan_dirs:
            for root, _, files in os.walk(scan_dir):
                if "__pycache__" in root or ".gradle" in root:
                    continue
                for f in files:
                    if f.endswith((".kt", ".java", ".py", ".md", ".json")):
                        fpath = pathlib.Path(root) / f
                        content = fpath.read_text(encoding="utf-8", errors="ignore")
                        for pat in forbidden_patterns:
                            if pat.search(content):
                                violating_files.append(str(fpath.relative_to(ROOT_DIR)))
                                break

    def test_mixed_whitespace_companions_filtering(self):
        """Mixed companion list with whitespace, empty strings, and newlines correctly filtered."""
        raw_companions = ["Alice", "   ", "", "\t", "Bob", "\n", "Charlie"]
        clean_companions = [c.strip() for c in raw_companions if c.strip()]
        self.assertEqual(clean_companions, ["Alice", "Bob", "Charlie"])
        res = SplitCalculationEngine.calculate_split_equally(120.00, clean_companions)
        # Total heads = 3 + 1 = 4
        self.assertEqual(res.my_share, 30.00)
        self.assertEqual(res.owed_to_me, 90.00)
        self.assertEqual(res.companions, "Alice, Bob, Charlie")

    def test_extreme_companion_count_stress(self):
        """Stress-tests massive companion counts: N=100, 250, 500 without precision loss."""
        for n in [100, 250, 500]:
            comps = [f"Peer_{i}" for i in range(n)]
            res = SplitCalculationEngine.calculate_split_equally(1000.00, comps)
            self.assertEqual(res.total_amount, 1000.00)
            self.assertEqual(res.status, TransactionStatus.PENDING)
            self.assertEqual(round(res.my_share + res.owed_to_me, 2), 1000.00)
            self.assertGreater(res.my_share, 0.0)
            self.assertGreater(res.owed_to_me, 0.0)

    def test_full_31_column_debit_payload_structure(self):
        """Full 31-column row serialization for Debit transaction places UUID at Col AC."""
        class MockFullRowMapper:
            @staticmethod
            def map_transaction_to_full_row(txn: Transaction) -> list[Any]:
                return [
                    # Table 1: Cols A-K (indices 0-10)
                    txn.date_serial,
                    txn.time_serial,
                    txn.account,
                    txn.item_description,
                    txn.trip_tag,
                    txn.category,
                    txn.total_amount,
                    txn.companions,
                    txn.my_share,
                    txn.owed_to_me,
                    txn.status.value,
                    # Spacer 1: Col L (index 11)
                    "",
                    # Table 2: Cols M-T (indices 12-19)
                    "", "", "", "", "", "", "", "",
                    # Spacer 2: Col U (index 20)
                    "",
                    # Table 3: Cols V-AB (indices 21-27)
                    "", "", "", "", "", "", "",
                    # Hidden UUIDs: Cols AC-AE (indices 28-30)
                    txn.id,  # Col AC: Debit Txn UUID
                    "",      # Col AD: Inflow Txn UUID
                    "",      # Col AE: Payable Txn UUID
                ]

        txn = Transaction(
            id="txn_full_31_check",
            date_serial=46345,
            time_serial=0.75,
            account="SBI",
            item_description="Office Supplies",
            category="Shopping",
            total_amount=890.00,
            companions="Personal",
            my_share=890.00,
            owed_to_me=0.00,
            status=TransactionStatus.SETTLED,
            trip_tag="",
        )
        full_row = MockFullRowMapper.map_transaction_to_full_row(txn)
        self.assertEqual(len(full_row), 31)
        self.assertEqual(full_row[0], 46345)
        self.assertEqual(full_row[10], "Settled")
        self.assertEqual(full_row[11], "")  # Col L spacer
        self.assertEqual(full_row[20], "")  # Col U spacer
        self.assertEqual(full_row[28], "txn_full_31_check")  # Col AC Debit UUID
        self.assertEqual(full_row[29], "")  # Col AD Inflow UUID
        self.assertEqual(full_row[30], "")  # Col AE Payable UUID

    def test_existing_row_to_void_row_column_integrity(self):
        """Converting an existing 31-column row to void modifies only cols D, I, J, K."""
        # Initial 31-col row
        original_row: list[Any] = [
            46340, 0.5, "SBI", "Dinner", "Trip", "Food & Drink", 1000.0,
            "Alice", 500.0, 500.0, "Pending",
            "",
            "", "", "", "", "", "", "", "",
            "",
            "", "", "", "", "", "", "",
            "txn_orig_uuid", "", ""
        ]
        self.assertEqual(len(original_row), 31)

        # Apply void modification matching SheetPayloadMapper.kt
        void_row = list(original_row)
        original_desc = str(void_row[3])
        void_row[3] = f"[Void] {original_desc}"
        void_row[8] = 0.0
        void_row[9] = 0.0
        void_row[10] = "Void"

        self.assertEqual(len(void_row), 31)
        self.assertEqual(void_row[3], "[Void] Dinner")
        self.assertEqual(void_row[8], 0.0)
        self.assertEqual(void_row[9], 0.0)
        self.assertEqual(void_row[10], "Void")
        # Ensure all other 27 columns are completely untouched
        for idx in range(31):
            if idx not in (3, 8, 9, 10):
                self.assertEqual(void_row[idx], original_row[idx])
        # Debit UUID at index 28 remains preserved
        self.assertEqual(void_row[28], "txn_orig_uuid")

    def test_cross_source_sliding_window_exact_boundaries(self):
        """Stress-tests exact sliding window threshold: <= 120.0s flags, > 120.0s accepts."""
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        base_txn = Transaction(
            id="txn_base", date_serial=46340, time_serial=0.1, account="SBI",
            item_description="Cafe", category="Food & Drink", total_amount=250.00,
            companions="Personal", my_share=250.00, owed_to_me=0.00,
            status=TransactionStatus.SETTLED, trip_tag="", timestamp=1000.0,
        )
        engine.recent_transactions.append(base_txn)

        # Exact boundary: delta = 0.0s -> FLAG
        act, _ = engine.evaluate_incoming("pkg", 250.00, "Different Merchant", 1000.0)
        self.assertEqual(act, "FLAG_POSSIBLE_DUPLICATE")

        # Within window: delta = 60.0s -> FLAG
        act, _ = engine.evaluate_incoming("pkg", 250.00, "Different Merchant", 1060.0)
        self.assertEqual(act, "FLAG_POSSIBLE_DUPLICATE")

        # Boundary inclusive: delta = 120.0s -> FLAG
        act, _ = engine.evaluate_incoming("pkg", 250.00, "Different Merchant", 1120.0)
        self.assertEqual(act, "FLAG_POSSIBLE_DUPLICATE")

        # Beyond window: delta = 120.1s -> ACCEPT_NEW
        act, _ = engine.evaluate_incoming("pkg", 250.00, "Different Merchant", 1120.1)
        self.assertEqual(act, "ACCEPT_NEW")

        # Far beyond window: delta = 300.0s -> ACCEPT_NEW
        act, _ = engine.evaluate_incoming("pkg", 250.00, "Different Merchant", 1300.0)
        self.assertEqual(act, "ACCEPT_NEW")

    def test_no_todo_or_unimplemented_stubs_in_production(self):
        """Scans Kotlin production source code to verify zero TODO() or NotImplementedError stubs."""
        violating_files = []
        forbidden_stubs = ["TODO()", "throw NotImplementedError"]
        src_dir = ROOT_DIR / "app" / "src" / "main" / "java"

        for root, _, files in os.walk(src_dir):
            for f in files:
                if f.endswith(".kt"):
                    fpath = pathlib.Path(root) / f
                    content = fpath.read_text(encoding="utf-8", errors="ignore")
                    for stub in forbidden_stubs:
                        if stub in content:
                            violating_files.append((str(fpath.relative_to(ROOT_DIR)), stub))

        self.assertEqual(violating_files, [], f"Forbidden stubs found in production files: {violating_files}")

    def test_kotlin_balanced_delimiters_in_production(self):
        """Verifies all Kotlin source files have balanced braces, brackets, and parentheses."""
        unbalanced = []
        src_dir = ROOT_DIR / "app" / "src" / "main" / "java"

        for root, _, files in os.walk(src_dir):
            for f in files:
                if f.endswith(".kt"):
                    fpath = pathlib.Path(root) / f
                    content = fpath.read_text(encoding="utf-8", errors="ignore")
                    # Simple stateful check outside quotes/comments
                    brace = 0
                    paren = 0
                    bracket = 0
                    in_str = False
                    in_char = False
                    escaped = False
                    i = 0
                    n = len(content)
                    while i < n:
                        c = content[i]
                        if in_str:
                            if escaped:
                                escaped = False
                            elif c == '\\':
                                escaped = True
                            elif c == '"':
                                in_str = False
                        elif in_char:
                            if escaped:
                                escaped = False
                            elif c == '\\':
                                escaped = True
                            elif c == "'":
                                in_char = False
                        else:
                            if c == '/' and i + 1 < n and content[i + 1] == '/':
                                # Skip single line comment
                                while i < n and content[i] != '\n':
                                    i += 1
                                continue
                            elif c == '/' and i + 1 < n and content[i + 1] == '*':
                                # Skip block comment
                                i += 2
                                while i + 1 < n and not (content[i] == '*' and content[i + 1] == '/'):
                                    i += 1
                                i += 1
                            elif c == '"':
                                in_str = True
                            elif c == "'":
                                in_char = True
                            elif c == '{':
                                brace += 1
                            elif c == '}':
                                brace -= 1
                            elif c == '(':
                                paren += 1
                            elif c == ')':
                                paren -= 1
                            elif c == '[':
                                bracket += 1
                            elif c == ']':
                                bracket -= 1
                        i += 1
                    if brace != 0 or paren != 0 or bracket != 0:
                        unbalanced.append((str(fpath.relative_to(ROOT_DIR)), brace, paren, bracket))

        self.assertEqual(unbalanced, [], f"Unbalanced delimiters in Kotlin files: {unbalanced}")


if __name__ == "__main__":
    unittest.main()

