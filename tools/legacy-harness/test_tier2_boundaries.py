"""Tier 2 Boundary Value Analysis (BVA) Tests for Finance Automation Android.

This test module asserts numeric boundaries, extreme limits, zero/negative values,
UTR length boundaries, date/time serial extremes, formula injection edge cases,
and settle window timing boundaries.
"""

from __future__ import annotations

import datetime
import pathlib
import sys
import unittest

ROOT_DIR = pathlib.Path(__file__).resolve().parent.parent
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from domain_harness import (
    AMOUNT_REGEX,
    DeduplicationEngine,
    NotificationParser,
    ReviewState,
    RolloverEngine,
    SheetPayloadMapper,
    SplitCalculationEngine,
    SyncState,
    Transaction,
    TransactionStatus,
    TwoAxisStateMachine,
    from_date_serial,
    to_date_serial,
    to_time_serial,
)


class TestTier2AmountBoundaries(unittest.TestCase):
    """BVA on Amount Extraction and Calculation."""

    def test_bva_amount_zero_triggers_fallback(self) -> None:
        res = NotificationParser.parse_notification(
            "com.google.android.apps.nbu.paisa.user",
            "Payment",
            "Paid Rs. 0.00 to Merchant",
        )
        self.assertEqual(res["status"], "FALLBACK_PROMPT")

    def test_bva_amount_minimum_positive_unit_one_paisa(self) -> None:
        match = AMOUNT_REGEX.search("Paid Rs. 0.01 via UPI")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1), "0.01")

    def test_bva_amount_large_hundred_million(self) -> None:
        match = AMOUNT_REGEX.search("Transferred INR 100,000,000.00 to Escrow")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1), "100,000,000.00")

    def test_bva_sub_paisa_fractional_rounding_up(self) -> None:
        res = SplitCalculationEngine.calculate_just_me(10.556)
        self.assertEqual(res.my_share, 10.56)

    def test_bva_sub_paisa_fractional_rounding_down(self) -> None:
        res = SplitCalculationEngine.calculate_just_me(10.554)
        self.assertEqual(res.my_share, 10.55)

    def test_bva_missing_amount_triggers_fallback_prompt(self) -> None:
        res = NotificationParser.parse_notification(
            "com.phonepe.app",
            "Payment Successful",
            "Payment to merchant completed",
        )
        self.assertEqual(res["status"], "FALLBACK_PROMPT")


class TestTier2UtrLengthBoundaries(unittest.TestCase):
    """BVA on Deduplication Sliding Window and String Boundaries."""

    def test_bva_sliding_window_at_119_seconds_is_inside(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Store",
            category="Shopping",
            total_amount=300.0,
            my_share=300.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=1000.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming("com.android.mms", 300.0, "Store", 1119.0)
        self.assertEqual(action, "MERGE_EXACT_SIMILAR")

    def test_bva_sliding_window_at_120_seconds_is_inside(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Store",
            category="Shopping",
            total_amount=300.0,
            my_share=300.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=1000.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming("com.android.mms", 300.0, "Store", 1120.0)
        self.assertEqual(action, "MERGE_EXACT_SIMILAR")

    def test_bva_sliding_window_at_121_seconds_is_outside(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Store",
            category="Shopping",
            total_amount=300.0,
            my_share=300.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=1000.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming("com.android.mms", 300.0, "Store", 1121.0)
        self.assertEqual(action, "ACCEPT_NEW")

    def test_bva_merchant_string_max_length_boundary(self) -> None:
        long_merchant = "A" * 255
        txn = Transaction(
            id="t_long",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description=long_merchant,
            category="Shopping",
            total_amount=100.0,
            my_share=100.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
        )
        row = SheetPayloadMapper.map_debit_row(txn)
        self.assertEqual(len(row[3]), 255)

    def test_bva_merchant_empty_string_falls_back_to_unknown(self) -> None:
        res = NotificationParser.parse_notification("com.phonepe.app", "Alert", "Debited Rs 100")
        self.assertEqual(res["merchant"], "Unknown Payee")

    def test_bva_zero_utr_attribute_in_transaction(self) -> None:
        txn = Transaction(
            id="t_no_utr",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Store",
            category="Shopping",
            total_amount=50.0,
            my_share=50.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
        )
        self.assertFalse(hasattr(txn, "utr"))


class TestTier2DateSerialBoundaries(unittest.TestCase):
    """BVA on Date Serials and Epoch Boundaries."""

    def test_bva_epoch_day_zero(self) -> None:
        d = datetime.date(1899, 12, 30)
        serial = to_date_serial(d)
        self.assertEqual(serial, 0)
        self.assertEqual(from_date_serial(serial), d)

    def test_bva_epoch_day_one(self) -> None:
        d = datetime.date(1899, 12, 31)
        serial = to_date_serial(d)
        self.assertEqual(serial, 1)

    def test_bva_epoch_day_two(self) -> None:
        d = datetime.date(1900, 1, 1)
        serial = to_date_serial(d)
        self.assertEqual(serial, 2)

    def test_bva_leap_year_february_29(self) -> None:
        d = datetime.date(2028, 2, 29)
        serial = to_date_serial(d)
        self.assertEqual(from_date_serial(serial), d)

    def test_bva_far_future_year_2099(self) -> None:
        d = datetime.date(2099, 12, 31)
        serial = to_date_serial(d)
        self.assertEqual(from_date_serial(serial), d)


class TestTier2TimeSerialBoundaries(unittest.TestCase):
    """BVA on Time Serials and Second-Precision Tolerances."""

    def test_bva_midnight_zero(self) -> None:
        t = datetime.time(0, 0, 0)
        self.assertEqual(to_time_serial(t), 0.0)

    def test_bva_end_of_day_last_second(self) -> None:
        t = datetime.time(23, 59, 59)
        serial = to_time_serial(t)
        self.assertLess(serial, 1.0)
        self.assertGreater(serial, 0.9999)

    def test_bva_one_second_past_midnight(self) -> None:
        t = datetime.time(0, 0, 1)
        serial = to_time_serial(t)
        self.assertAlmostEqual(serial, 1.0 / 86400.0, places=7)

    def test_bva_exact_half_day_noon(self) -> None:
        t = datetime.time(12, 0, 0)
        self.assertEqual(to_time_serial(t), 0.5)

    def test_bva_second_precision_tolerance_half_second(self) -> None:
        t1 = datetime.time(12, 0, 0, 0)
        t2 = datetime.time(12, 0, 0, 500000)
        s1 = to_time_serial(t1)
        s2 = to_time_serial(t2)
        diff_seconds = abs(s1 - s2) * 86400.0
        self.assertAlmostEqual(diff_seconds, 0.5, places=3)


class TestTier2CompanionCountBoundaries(unittest.TestCase):
    """BVA on Companion Counts and Split Rounding."""

    def test_bva_zero_companions_falls_back_to_just_me(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(150.0, [])
        self.assertEqual(res.my_share, 150.0)
        self.assertEqual(res.owed_to_me, 0.0)
        self.assertEqual(res.status, TransactionStatus.SETTLED)

    def test_bva_one_companion_halves_amount(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(100.0, ["Alice"])
        self.assertEqual(res.my_share, 50.0)
        self.assertEqual(res.owed_to_me, 50.0)

    def test_bva_uneven_three_way_split_cents_conservation(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(10.0, ["A", "B"])
        self.assertEqual(res.my_share, 3.33)
        self.assertEqual(res.owed_to_me, 6.67)
        self.assertAlmostEqual(res.my_share + res.owed_to_me, 10.0, places=2)

    def test_bva_seven_companions_eight_heads_division(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(100.0, ["c1", "c2", "c3", "c4", "c5", "c6", "c7"])
        self.assertEqual(res.my_share, 12.50)
        self.assertEqual(res.owed_to_me, 87.50)

    def test_bva_large_group_hundred_heads(self) -> None:
        companions = [f"Friend_{i}" for i in range(99)]
        res = SplitCalculationEngine.calculate_split_equally(1000.0, companions)
        self.assertEqual(res.my_share, 10.0)
        self.assertEqual(res.owed_to_me, 990.0)


class TestTier2CustomSplitBoundaries(unittest.TestCase):
    """BVA on Custom Split Share Limits."""

    def test_bva_custom_split_zero_personal_share(self) -> None:
        res = SplitCalculationEngine.calculate_custom_split(500.0, 0.0, ["Alice"])
        self.assertEqual(res.my_share, 0.0)
        self.assertEqual(res.owed_to_me, 500.0)
        self.assertEqual(res.status, TransactionStatus.PENDING)

    def test_bva_custom_split_full_personal_share(self) -> None:
        res = SplitCalculationEngine.calculate_custom_split(500.0, 500.0, ["Alice"])
        self.assertEqual(res.my_share, 500.0)
        self.assertEqual(res.owed_to_me, 0.0)
        self.assertEqual(res.status, TransactionStatus.SETTLED)

    def test_bva_custom_split_share_exceeds_by_one_paisa(self) -> None:
        with self.assertRaises(ValueError):
            SplitCalculationEngine.calculate_custom_split(500.0, 500.01, ["Alice"])

    def test_bva_custom_split_negative_share_by_one_paisa(self) -> None:
        with self.assertRaises(ValueError):
            SplitCalculationEngine.calculate_custom_split(500.0, -0.01, ["Alice"])


class TestTier2PayloadAndFormulaInjectionBoundaries(unittest.TestCase):
    """BVA on Malicious Payees, Special Characters and RAW Formula Safety."""

    def test_bva_formula_prefix_equals_handled_safely(self) -> None:
        malicious = "=cmd|' /C calc'!A0"
        self.assertTrue(SheetPayloadMapper.is_safe_raw_value(malicious))

    def test_bva_formula_prefix_plus_handled_safely(self) -> None:
        malicious = "+123456789"
        self.assertTrue(SheetPayloadMapper.is_safe_raw_value(malicious))

    def test_bva_formula_prefix_minus_handled_safely(self) -> None:
        malicious = "-SUM(A1:A10)"
        self.assertTrue(SheetPayloadMapper.is_safe_raw_value(malicious))

    def test_bva_formula_prefix_at_handled_safely(self) -> None:
        malicious = "@SOMETHING"
        self.assertTrue(SheetPayloadMapper.is_safe_raw_value(malicious))

    def test_bva_very_long_merchant_string_five_hundred_chars(self) -> None:
        long_merchant = "A" * 500
        txn = Transaction(
            id="t_long",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description=long_merchant,
            category="Shopping",
            total_amount=100.0,
            my_share=100.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
        )
        row = SheetPayloadMapper.map_debit_row(txn)
        self.assertEqual(len(row[3]), 500)

    def test_bva_special_characters_quotes_and_ampersand(self) -> None:
        merchant = 'Barnes & Noble "Downtown" Store'
        txn = Transaction(
            id="t_quotes",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description=merchant,
            category="Books",
            total_amount=250.0,
            my_share=250.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
        )
        row = SheetPayloadMapper.map_debit_row(txn)
        self.assertEqual(row[3], merchant)


class TestTier2SettleWindowTimeBoundaries(unittest.TestCase):
    """BVA on Settle Window Durations (60s to 120s)."""

    def setUp(self) -> None:
        self.engine = DeduplicationEngine(settle_window_seconds=120.0)
        self.t0 = Transaction(
            id="t_base",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Store",
            category="Shopping",
            total_amount=300.0,
            my_share=300.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=1000.0,
        )
        self.engine.recent_transactions.append(self.t0)

    def test_bva_settle_window_inside_at_59_seconds(self) -> None:
        action, _ = self.engine.evaluate_incoming("com.android.mms", 300.0, "Store", 1059.0)
        self.assertEqual(action, "MERGE_EXACT_SIMILAR")

    def test_bva_settle_window_inside_at_60_seconds(self) -> None:
        action, _ = self.engine.evaluate_incoming("com.android.mms", 300.0, "Other Store", 1060.0)
        self.assertEqual(action, "FLAG_POSSIBLE_DUPLICATE")

    def test_bva_settle_window_inside_at_90_seconds_exact(self) -> None:
        action, _ = self.engine.evaluate_incoming("com.android.mms", 300.0, "Store", 1090.0)
        self.assertEqual(action, "MERGE_EXACT_SIMILAR")

    def test_bva_settle_window_outside_at_91_seconds(self) -> None:
        # At 120s window, 91s is inside!
        action, _ = self.engine.evaluate_incoming("com.android.mms", 300.0, "Store", 1091.0)
        self.assertEqual(action, "MERGE_EXACT_SIMILAR")

    def test_bva_settle_window_outside_at_121_seconds(self) -> None:
        action, _ = self.engine.evaluate_incoming("com.android.mms", 300.0, "Store", 1121.0)
        self.assertEqual(action, "ACCEPT_NEW")


class TestTier2DebtRolloverBoundaries(unittest.TestCase):
    """BVA on Debt Rollover and Carried Balance Invariants."""

    def test_bva_rollover_minimum_owed_one_paisa(self) -> None:
        origin = Transaction(
            id="t_orig_min",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Split Tea",
            category="Food & Drink",
            total_amount=0.02,
            my_share=0.01,
            owed_to_me=0.01,
            status=TransactionStatus.PENDING,
        )
        orig_up, carried = RolloverEngine.carry_forward_unsettled_debt(
            "Nov26", "Dec26", origin, 46361, 0.1
        )
        self.assertEqual(orig_up.owed_to_me, 0.0)
        self.assertEqual(orig_up.status, TransactionStatus.CARRIED)
        self.assertEqual(carried.total_amount, 0.0)  # Col G = 0!
        self.assertEqual(carried.owed_to_me, 0.01)

    def test_bva_rollover_zero_owed_rejected(self) -> None:
        origin = Transaction(
            id="t_orig_zero",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Personal Snack",
            category="Food & Drink",
            total_amount=50.0,
            my_share=50.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
        )
        with self.assertRaises(ValueError):
            RolloverEngine.carry_forward_unsettled_debt("Nov26", "Dec26", origin, 46361, 0.1)

    def test_bva_rollover_already_voided_rejected(self) -> None:
        origin = Transaction(
            id="t_orig_void",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Void Item",
            category="Food & Drink",
            total_amount=100.0,
            my_share=0.0,
            owed_to_me=0.0,
            status=TransactionStatus.VOID,
        )
        with self.assertRaises(ValueError):
            RolloverEngine.carry_forward_unsettled_debt("Nov26", "Dec26", origin, 46361, 0.1)


class TestTier2CategoryValidationBoundaries(unittest.TestCase):
    """BVA on Savings Keyword Enforcement and Case Normalization."""

    def test_bva_category_exact_plural_savings(self) -> None:
        self.assertEqual(SplitCalculationEngine.validate_category("Savings"), "Savings")

    def test_bva_category_all_lowercase_savings(self) -> None:
        self.assertEqual(SplitCalculationEngine.validate_category("savings"), "Savings")

    def test_bva_category_all_uppercase_savings(self) -> None:
        self.assertEqual(SplitCalculationEngine.validate_category("SAVINGS"), "Savings")

    def test_bva_category_singular_saving_rejected(self) -> None:
        with self.assertRaises(ValueError):
            SplitCalculationEngine.validate_category("Saving")

    def test_bva_category_singular_saving_with_whitespace_rejected(self) -> None:
        with self.assertRaises(ValueError):
            SplitCalculationEngine.validate_category("  saving  ")

    def test_bva_category_sip_normalized(self) -> None:
        self.assertEqual(SplitCalculationEngine.validate_category("sip"), "Savings")

    def test_bva_category_mutual_fund_normalized(self) -> None:
        self.assertEqual(SplitCalculationEngine.validate_category("Mutual Fund"), "Savings")


class TestTier2StateMachineBoundaries(unittest.TestCase):
    """BVA on State Machine Terminal States and Invalid Loops."""

    def test_bva_void_state_cannot_transition_to_synced(self) -> None:
        valid = TwoAxisStateMachine.is_valid_transition(
            ReviewState.CONFIRMED, SyncState.VOID,
            ReviewState.CONFIRMED, SyncState.SYNCED,
        )
        self.assertFalse(valid)

    def test_bva_void_state_cannot_transition_to_pending(self) -> None:
        valid = TwoAxisStateMachine.is_valid_transition(
            ReviewState.NEEDS_REVIEW, SyncState.VOID,
            ReviewState.NEEDS_REVIEW, SyncState.PENDING,
        )
        self.assertFalse(valid)

    def test_bva_cannot_unconfirm_confirmed_transaction(self) -> None:
        valid = TwoAxisStateMachine.is_valid_transition(
            ReviewState.CONFIRMED, SyncState.PENDING,
            ReviewState.NEEDS_REVIEW, SyncState.PENDING,
        )
        self.assertFalse(valid)

    def test_bva_synced_can_transition_to_void(self) -> None:
        valid = TwoAxisStateMachine.is_valid_transition(
            ReviewState.CONFIRMED, SyncState.SYNCED,
            ReviewState.CONFIRMED, SyncState.VOID,
        )
        self.assertTrue(valid)


if __name__ == "__main__":
    unittest.main()
