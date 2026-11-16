"""Empirical Challenge & Stress Test Harness for Milestone M2.

Stress-tests split calculations, boundary value conditions, cents conservation,
in-place non-destructive voiding, carry-forward debt rollover mechanics, and
strictly plural 'Savings' category normalization rules.
"""

from __future__ import annotations

import random
import unittest

from domain_harness import (
    RolloverEngine,
    SplitCalculationEngine,
    SyncState,
    Transaction,
    TransactionStatus,
    VoidEngine,
)


class TestEmpiricalSplitEqually(unittest.TestCase):
    """Stress testing equal split across varying head counts and penny conservation."""

    def test_zero_companions_falls_back_to_just_me(self) -> None:
        """0 companions must fall back to Just Me calculation."""
        res = SplitCalculationEngine.calculate_split_equally(250.75, [])
        self.assertEqual(res.total_amount, 250.75)
        self.assertEqual(res.my_share, 250.75)
        self.assertEqual(res.owed_to_me, 0.00)
        self.assertEqual(res.companions, "Personal")
        self.assertEqual(res.status, TransactionStatus.SETTLED)

    def test_split_equally_across_two_friends_three_heads(self) -> None:
        """100 split across 2 friends (3 heads): 33.33 + 66.67 = 100.00."""
        res = SplitCalculationEngine.calculate_split_equally(100.00, ["Alice", "Bob"])
        self.assertEqual(res.total_amount, 100.00)
        self.assertEqual(res.my_share, 33.33)
        self.assertEqual(res.owed_to_me, 66.67)
        self.assertEqual(res.status, TransactionStatus.PENDING)
        self.assertEqual(round(res.my_share + res.owed_to_me, 2), 100.00)

    def test_split_equally_across_three_friends_four_heads(self) -> None:
        """100 split across 3 friends (4 heads): 25.00 + 75.00 = 100.00."""
        res = SplitCalculationEngine.calculate_split_equally(100.00, ["Alice", "Bob", "Charlie"])
        self.assertEqual(res.total_amount, 100.00)
        self.assertEqual(res.my_share, 25.00)
        self.assertEqual(res.owed_to_me, 75.00)
        self.assertEqual(res.status, TransactionStatus.PENDING)
        self.assertEqual(round(res.my_share + res.owed_to_me, 2), 100.00)

    def test_split_equally_across_five_friends_six_heads(self) -> None:
        """100 split across 5 friends (6 heads): 16.67 + 83.33 = 100.00."""
        companions = ["A", "B", "C", "D", "E"]
        res = SplitCalculationEngine.calculate_split_equally(100.00, companions)
        self.assertEqual(res.total_amount, 100.00)
        self.assertEqual(res.my_share, 16.67)
        self.assertEqual(res.owed_to_me, 83.33)
        self.assertEqual(res.status, TransactionStatus.PENDING)
        self.assertEqual(round(res.my_share + res.owed_to_me, 2), 100.00)

    def test_split_equally_across_ten_friends_eleven_heads(self) -> None:
        """100 split across 10 friends (11 heads): 9.09 + 90.91 = 100.00."""
        companions = [f"Friend_{i}" for i in range(10)]
        res = SplitCalculationEngine.calculate_split_equally(100.00, companions)
        self.assertEqual(res.total_amount, 100.00)
        self.assertEqual(res.my_share, 9.09)
        self.assertEqual(res.owed_to_me, 90.91)
        self.assertEqual(res.status, TransactionStatus.PENDING)
        self.assertEqual(round(res.my_share + res.owed_to_me, 2), 100.00)

    def test_fuzz_cents_conservation_ten_thousand_cases(self) -> None:
        """Fuzz testing: sum of parts strictly equals rounded total across 10,000 cases."""
        rng = random.Random(42)
        for _ in range(10000):
            amount = round(rng.uniform(0.01, 100000.0), 2)
            num_friends = rng.randint(1, 50)
            companions = [f"F_{i}" for i in range(num_friends)]
            res = SplitCalculationEngine.calculate_split_equally(amount, companions)
            sum_parts = round(res.my_share + res.owed_to_me, 2)
            self.assertEqual(
                sum_parts,
                amount,
                f"Conservation failed for amount={amount}, heads={num_friends+1}: {res.my_share} + {res.owed_to_me} != {amount}",
            )


class TestEmpiricalCustomSplitBoundaries(unittest.TestCase):
    """Stress testing Custom Split boundary conditions."""

    def test_custom_split_boundary_my_share_zero(self) -> None:
        """Boundary: myShare = 0.00 (100% fronted debt for others)."""
        res = SplitCalculationEngine.calculate_custom_split(750.50, 0.00, ["Alice", "Bob"])
        self.assertEqual(res.total_amount, 750.50)
        self.assertEqual(res.my_share, 0.00)
        self.assertEqual(res.owed_to_me, 750.50)
        self.assertEqual(res.status, TransactionStatus.PENDING)

    def test_custom_split_boundary_my_share_equals_total(self) -> None:
        """Boundary: myShare = totalAmount (100% personal living burn)."""
        res = SplitCalculationEngine.calculate_custom_split(750.50, 750.50, ["Alice"])
        self.assertEqual(res.total_amount, 750.50)
        self.assertEqual(res.my_share, 750.50)
        self.assertEqual(res.owed_to_me, 0.00)
        self.assertEqual(res.status, TransactionStatus.SETTLED)

    def test_custom_split_rejects_my_share_exceeding_total(self) -> None:
        """Boundary: myShare > totalAmount must throw/reject."""
        with self.assertRaises(ValueError):
            SplitCalculationEngine.calculate_custom_split(500.00, 500.01, ["Alice"])

        with self.assertRaises(ValueError):
            SplitCalculationEngine.calculate_custom_split(100.00, 200.00, ["Bob"])

    def test_custom_split_rejects_negative_share(self) -> None:
        """Boundary: negative share must throw/reject."""
        with self.assertRaises(ValueError):
            SplitCalculationEngine.calculate_custom_split(500.00, -0.01, ["Alice"])

        with self.assertRaises(ValueError):
            SplitCalculationEngine.calculate_custom_split(100.00, -50.00, ["Bob"])

    def test_custom_split_empty_companions_defaults_to_friends(self) -> None:
        """Empty companions in custom split defaults to 'Friends' label."""
        res = SplitCalculationEngine.calculate_custom_split(300.00, 100.00, [])
        self.assertEqual(res.companions, "Friends")
        self.assertEqual(res.owed_to_me, 200.00)


class TestEmpiricalDebtRollover(unittest.TestCase):
    """Stress testing carry-forward debt rollover mechanics."""

    def setUp(self) -> None:
        self.origin = Transaction(
            id="txn_origin_nov26",
            date_serial=46331,
            time_serial=0.5,
            account="SBI XX1234",
            item_description="Team Dinner",
            category="Food & Drink",
            total_amount=3000.00,
            my_share=1000.00,
            owed_to_me=2000.00,
            status=TransactionStatus.PENDING,
            companions="Alice, Bob",
        )

    def test_origin_row_owed_to_me_zeroed_and_status_carried(self) -> None:
        """Origin row Col J must be zeroed to 0.00 and status set to Carried."""
        orig_updated, _ = RolloverEngine.carry_forward_unsettled_debt(
            "Nov26", "Dec26", self.origin, 46361, 0.000694
        )
        self.assertEqual(orig_updated.owed_to_me, 0.00)
        self.assertEqual(orig_updated.status, TransactionStatus.CARRIED)
        self.assertEqual(orig_updated.my_share, 1000.00)
        self.assertEqual(orig_updated.total_amount, 3000.00)

    def test_target_row_structure_and_debt_carry(self) -> None:
        """Target row has fresh UUID, [Carried from MMMYY] prefix, Col I=0, Col J=owed."""
        _, carried = RolloverEngine.carry_forward_unsettled_debt(
            "Nov26", "Dec26", self.origin, 46361, 0.000694
        )
        self.assertNotEqual(carried.id, self.origin.id)
        self.assertTrue(carried.id.startswith("txn_carried_"))
        self.assertEqual(carried.item_description, "[Carried from Nov26] Team Dinner")
        self.assertEqual(carried.my_share, 0.00)
        self.assertEqual(carried.owed_to_me, 2000.00)
        self.assertEqual(carried.status, TransactionStatus.PENDING)

    def test_rollover_rejected_for_settled_or_void_or_carried_debts(self) -> None:
        """Only transactions with Pending status and positive owed_to_me can be rolled over."""
        # Settled
        self.origin.status = TransactionStatus.SETTLED
        with self.assertRaises(ValueError):
            RolloverEngine.carry_forward_unsettled_debt("Nov26", "Dec26", self.origin, 46361, 0.0)

        # Void
        self.origin.status = TransactionStatus.VOID
        with self.assertRaises(ValueError):
            RolloverEngine.carry_forward_unsettled_debt("Nov26", "Dec26", self.origin, 46361, 0.0)

        # Carried
        self.origin.status = TransactionStatus.CARRIED
        with self.assertRaises(ValueError):
            RolloverEngine.carry_forward_unsettled_debt("Nov26", "Dec26", self.origin, 46361, 0.0)

        # Zero owed
        self.origin.status = TransactionStatus.PENDING
        self.origin.owed_to_me = 0.00
        with self.assertRaises(ValueError):
            RolloverEngine.carry_forward_unsettled_debt("Nov26", "Dec26", self.origin, 46361, 0.0)


class TestEmpiricalInPlaceVoid(unittest.TestCase):
    """Stress testing non-destructive in-place void logic."""

    def test_in_place_void_zeroes_shares_and_prefixes_description(self) -> None:
        """In-place void zeroes shares, sets status Void, and prefixes [Void]."""
        txn = Transaction(
            id="txn_to_void",
            date_serial=46331,
            time_serial=0.5,
            account="SBI XX1234",
            item_description="Accidental Purchase",
            category="Shopping",
            total_amount=1200.00,
            my_share=400.00,
            owed_to_me=800.00,
            status=TransactionStatus.PENDING,
            companions="Alice",
        )
        voided = VoidEngine.apply_void(txn)
        self.assertEqual(voided.id, "txn_to_void")
        self.assertEqual(voided.my_share, 0.00)
        self.assertEqual(voided.owed_to_me, 0.00)
        self.assertEqual(voided.status, TransactionStatus.VOID)
        self.assertEqual(voided.sync_state, SyncState.VOID)
        self.assertEqual(voided.item_description, "[Void] Accidental Purchase")

    def test_in_place_void_idempotency(self) -> None:
        """Voiding an already voided transaction does not duplicate [Void] prefix."""
        txn = Transaction(
            id="txn_already_void",
            date_serial=46331,
            time_serial=0.5,
            account="SBI XX1234",
            item_description="[Void] Double Void Item",
            category="Shopping",
            total_amount=500.00,
            my_share=0.00,
            owed_to_me=0.00,
            status=TransactionStatus.VOID,
        )
        voided = VoidEngine.apply_void(txn)
        self.assertEqual(voided.item_description, "[Void] Double Void Item")


class TestEmpiricalCategoryNormalization(unittest.TestCase):
    """Stress testing strict plural 'Savings' category keyword enforcement."""

    def test_singular_saving_strictly_forbidden(self) -> None:
        """Singular 'Saving' must throw ValueError / IllegalArgumentException."""
        with self.assertRaises(ValueError):
            SplitCalculationEngine.validate_category("Saving")

        with self.assertRaises(ValueError):
            SplitCalculationEngine.validate_category("saving")

        with self.assertRaises(ValueError):
            SplitCalculationEngine.validate_category("  Saving  ")

    def test_plural_savings_and_asset_keywords_mapped_to_savings(self) -> None:
        """Plural 'Savings', 'investment', 'sip', 'mutual fund' mapped to 'Savings'."""
        self.assertEqual(SplitCalculationEngine.validate_category("Savings"), "Savings")
        self.assertEqual(SplitCalculationEngine.validate_category("savings"), "Savings")
        self.assertEqual(SplitCalculationEngine.validate_category("SAVINGS"), "Savings")
        self.assertEqual(SplitCalculationEngine.validate_category("investment"), "Savings")
        self.assertEqual(SplitCalculationEngine.validate_category("Investment"), "Savings")
        self.assertEqual(SplitCalculationEngine.validate_category("SIP"), "Savings")
        self.assertEqual(SplitCalculationEngine.validate_category("sip"), "Savings")
        self.assertEqual(SplitCalculationEngine.validate_category("mutual fund"), "Savings")

    def test_other_standard_categories_preserved(self) -> None:
        """Standard categories pass through normalized without distortion."""
        self.assertEqual(SplitCalculationEngine.validate_category("Food & Drink"), "Food & Drink")
        self.assertEqual(SplitCalculationEngine.validate_category("Groceries"), "Groceries")
        self.assertEqual(SplitCalculationEngine.validate_category("Transit"), "Transit")
        self.assertEqual(SplitCalculationEngine.validate_category("Subscriptions"), "Subscriptions")


if __name__ == "__main__":
    unittest.main()
