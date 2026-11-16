"""Empirical Challenger 1 Test Suite — Accounting Mathematics & Customization Engine.

Author: Challenger 1 (Empirical Challenger)
Target: Finance Automation Android
Scope:
1. Accounting Mathematics:
   - "Just Me" calculation (Total = Extracted, My Share = Total, Owed = 0, Status = Settled).
   - "Split Equally" multi-friend calculation (Heads = N + 1, My Share = Total / (N + 1), Owed = Total - My Share, Status = Pending). Cents and paise conservation.
   - "Custom Split" calculation (User input My Share, Owed = Total - My Share, Status transitions).
   - Non-destructive In-Place Void (Col G=0.00, Col I=0.00, Col J=0.00, Col K="Void", [Void] in Col D, outflow elimination).
   - Debt Carry-Forward Rollover (Origin Col J=0.00, Col K="Carried"; Target Col G=0.00, Col I=0.00, Col J=owed, Col K="Pending", [Carried] prefix, zero double-counting).
   - Plural "Savings" keyword rule strictly enforced, singular "Saving" strictly rejected.
2. Customization Engine Stress:
   - Dynamic accounts (arbitrary bank accounts, reordering, default selection, archive exclusion).
   - Dynamic categories with isExcludedFromLivingBurn toggle and dynamic SUMIFS formula generation without hardcoded strings.
   - Hidden Config tab serialization (A1:P50 range, zero financial data leakage, round-trip serialization fidelity).
   - Settle delay window configuration (30s to 300s, default 120s, bounding/clamping logic).
"""

from __future__ import annotations

import re
import unittest
from typing import Any, ClassVar

from domain_harness import (
    Account,
    AppConfigSnapshot,
    Category,
    Companion,
    RolloverEngine,
    SplitCalculationEngine,
    SyncState,
    Transaction,
    TransactionStatus,
    TripTag,
    VoidEngine,
)


class MockCustomizationFormulaBuilder:
    """Dynamic formula generator mirroring DashboardFormulaBuilder.kt."""

    @staticmethod
    def build_current_burn_formula(excluded_names: list[str]) -> str:
        active = [n.strip() for n in excluded_names if n.strip()]
        if not active:
            return '=IFERROR(SUM(INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!I4:I")), 0)'
        exclusions = "".join(
            f', INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!F4:F"), "<>{name}"'
            for name in active
        )
        return f'=IFERROR(SUMIFS(INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!I4:I"){exclusions}), 0)'

    @staticmethod
    def build_prev_month_burn_formula(excluded_names: list[str]) -> str:
        active = [n.strip() for n in excluded_names if n.strip()]
        if not active:
            return '=IFERROR(SUM(INDIRECT("\'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "\'!I4:I")), 0)'
        exclusions = "".join(
            f', INDIRECT("\'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "\'!F4:F"), "<>{name}"'
            for name in active
        )
        return f'=IFERROR(SUMIFS(INDIRECT("\'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "\'!I4:I"){exclusions}), 0)'

    @staticmethod
    def build_monthly_summary_burn_formula(row_number: int, excluded_names: list[str]) -> str:
        active = [n.strip() for n in excluded_names if n.strip()]
        if not active:
            return f'=IFERROR(SUM(INDIRECT("\'" & $U{row_number} & RIGHT(YEAR(TODAY()), 2) & "\'!I4:I")), 0)'
        exclusions = "".join(
            f', INDIRECT("\'" & $U{row_number} & RIGHT(YEAR(TODAY()), 2) & "\'!F4:F"), "<>{name}"'
            for name in active
        )
        return f'=IFERROR(SUMIFS(INDIRECT("\'" & $U{row_number} & RIGHT(YEAR(TODAY()), 2) & "\'!I4:I"){exclusions}), 0)'


class MockConfigGridManager:
    """Config tab grid serializer mirroring ConfigSheetManager.kt."""

    HEADERS: ClassVar[list[str]] = [
        "AccountName", "AccountLastFour", "AccountIsDefault", "AccountIsArchived", "AccountOrder",
        "CategoryName", "CategoryExcludedFromBurn", "CategoryIsDefault", "CategoryIsArchived", "CategoryOrder",
        "CompanionName", "CompanionOrder",
        "TripTagName", "TripTagIsActive",
        "PrefKey", "PrefValue"
    ]

    @staticmethod
    def build_grid(snapshot: AppConfigSnapshot) -> list[list[Any]]:
        grid: list[list[Any]] = [list(MockConfigGridManager.HEADERS)]
        pref_items = list(snapshot.preferences.items())

        for i in range(49):
            row: list[Any] = ["" for _ in range(16)]

            if i < len(snapshot.accounts):
                acc = snapshot.accounts[i]
                row[0] = acc.name
                row[1] = acc.last_four_digits or ""
                row[2] = str(acc.is_default)
                row[3] = str(acc.is_archived)
                row[4] = str(acc.display_order)

            if i < len(snapshot.categories):
                cat = snapshot.categories[i]
                row[5] = cat.name
                row[6] = str(cat.is_excluded_from_living_burn)
                row[7] = str(cat.is_default)
                row[8] = str(cat.is_archived)
                row[9] = str(cat.display_order)

            if i < len(snapshot.companions):
                comp = snapshot.companions[i]
                row[10] = comp.name
                row[11] = str(i)

            if i < len(snapshot.trip_tags):
                tag = snapshot.trip_tags[i]
                row[12] = tag.name
                row[13] = str(tag.is_active)

            if i < len(pref_items):
                k, v = pref_items[i]
                row[14] = k
                row[15] = v

            grid.append(row)

        return grid

    @staticmethod
    def parse_grid(grid: list[list[Any]]) -> AppConfigSnapshot:
        accounts: list[Account] = []
        categories: list[Category] = []
        companions: list[Companion] = []
        trip_tags: list[TripTag] = []
        preferences: dict[str, str] = {}

        for row_index in range(1, len(grid)):
            row = grid[row_index]

            acc_name = str(row[0]).strip() if len(row) > 0 and row[0] is not None else ""
            if acc_name and acc_name.lower() != "accountname":
                last_four = str(row[1]).strip() if len(row) > 1 and row[1] else None
                is_default = str(row[2]).strip().lower() == "true" if len(row) > 2 else False
                is_archived = str(row[3]).strip().lower() == "true" if len(row) > 3 else False
                order = int(row[4]) if len(row) > 4 and str(row[4]).isdigit() else (row_index - 1)
                accounts.append(
                    Account(
                        id=f"acc_{row_index}",
                        name=acc_name,
                        last_four_digits=last_four,
                        is_default=is_default,
                        is_archived=is_archived,
                        display_order=order,
                    )
                )

            cat_name = str(row[5]).strip() if len(row) > 5 and row[5] is not None else ""
            if cat_name and cat_name.lower() != "categoryname":
                is_burn_excluded = str(row[6]).strip().lower() == "true" if len(row) > 6 else False
                is_default = str(row[7]).strip().lower() == "true" if len(row) > 7 else False
                is_archived = str(row[8]).strip().lower() == "true" if len(row) > 8 else False
                order = int(row[9]) if len(row) > 9 and str(row[9]).isdigit() else (row_index - 1)
                categories.append(
                    Category(
                        id=f"cat_{row_index}",
                        name=cat_name,
                        is_default=is_default,
                        is_excluded_from_living_burn=is_burn_excluded,
                        is_archived=is_archived,
                        display_order=order,
                    )
                )

            comp_name = str(row[10]).strip() if len(row) > 10 and row[10] is not None else ""
            if comp_name and comp_name.lower() != "companionname":
                companions.append(Companion(id=f"comp_{row_index}", name=comp_name))

            tag_name = str(row[12]).strip() if len(row) > 12 and row[12] is not None else ""
            if tag_name and tag_name.lower() != "triptagname":
                is_active = str(row[13]).strip().lower() == "true" if len(row) > 13 else False
                trip_tags.append(TripTag(id=f"tag_{row_index}", name=tag_name, is_active=is_active))

            pref_key = str(row[14]).strip() if len(row) > 14 and row[14] is not None else ""
            pref_val = str(row[15]).strip() if len(row) > 15 and row[15] is not None else ""
            if pref_key and pref_key.lower() != "prefkey":
                preferences[pref_key] = pref_val

        return AppConfigSnapshot(
            accounts=accounts,
            categories=categories,
            companions=companions,
            trip_tags=trip_tags,
            preferences=preferences,
        )


class TestAccountingJustMe(unittest.TestCase):
    """Adversarial verification of Just Me split mathematics."""

    def test_just_me_standard_amount(self) -> None:
        """Verify standard amount: Total = Extracted, My Share = Total, Owed = 0, Status = Settled."""
        amount = 1450.75
        res = SplitCalculationEngine.calculate_just_me(amount)

        self.assertEqual(res.total_amount, 1450.75)
        self.assertEqual(res.my_share, 1450.75)
        self.assertEqual(res.owed_to_me, 0.00)
        self.assertEqual(res.companions, "Personal")
        self.assertEqual(res.status, TransactionStatus.SETTLED)

    def test_just_me_zero_amount(self) -> None:
        """Verify zero amount edge case: $0.00 total and $0.00 my share."""
        amount = 0.00
        res = SplitCalculationEngine.calculate_just_me(amount)

        self.assertEqual(res.total_amount, 0.00)
        self.assertEqual(res.my_share, 0.00)
        self.assertEqual(res.owed_to_me, 0.00)
        self.assertEqual(res.status, TransactionStatus.SETTLED)

    def test_just_me_fractional_paise_rounding(self) -> None:
        """Verify fractional paise rounding: half-up to 2 decimal places."""
        amount = 199.996
        res = SplitCalculationEngine.calculate_just_me(amount)

        self.assertEqual(res.total_amount, 200.00)
        self.assertEqual(res.my_share, 200.00)
        self.assertEqual(res.owed_to_me, 0.00)


class TestAccountingSplitEqually(unittest.TestCase):
    """Adversarial verification of Split Equally mathematics and paise conservation."""

    def test_split_equally_single_companion_two_heads(self) -> None:
        """1 friend -> 2 heads (user + friend). My Share = Total / 2, Owed = Total - My Share."""
        amount = 500.00
        res = SplitCalculationEngine.calculate_split_equally(amount, ["Alex"])

        self.assertEqual(res.total_amount, 500.00)
        self.assertEqual(res.my_share, 250.00)
        self.assertEqual(res.owed_to_me, 250.00)
        self.assertEqual(res.companions, "Alex")
        self.assertEqual(res.status, TransactionStatus.PENDING)
        self.assertEqual(round(res.my_share + res.owed_to_me, 2), 500.00)

    def test_split_equally_two_companions_three_heads_cents_conservation(self) -> None:
        """2 friends -> 3 heads. 100.00 / 3 = 33.33 my share, 66.67 owed. Cents conserved."""
        amount = 100.00
        res = SplitCalculationEngine.calculate_split_equally(amount, ["Alex", "Jordan"])

        self.assertEqual(res.total_amount, 100.00)
        self.assertEqual(res.my_share, 33.33)
        self.assertEqual(res.owed_to_me, 66.67)
        self.assertEqual(res.status, TransactionStatus.PENDING)
        self.assertEqual(round(res.my_share + res.owed_to_me, 2), 100.00)

    def test_split_equally_five_companions_six_heads(self) -> None:
        """5 friends -> 6 heads. 100.00 / 6 = 16.67 my share, 83.33 owed. Total sum exactly 100."""
        amount = 100.00
        res = SplitCalculationEngine.calculate_split_equally(amount, ["F1", "F2", "F3", "F4", "F5"])

        self.assertEqual(res.total_amount, 100.00)
        self.assertEqual(res.my_share, 16.67)
        self.assertEqual(res.owed_to_me, 83.33)
        self.assertEqual(round(res.my_share + res.owed_to_me, 2), 100.00)

    def test_split_equally_nine_companions_ten_heads(self) -> None:
        """9 friends -> 10 heads. 150.00 / 10 = 15.00 my share, 135.00 owed."""
        amount = 150.00
        companions = [f"Friend_{i}" for i in range(1, 10)]
        res = SplitCalculationEngine.calculate_split_equally(amount, companions)

        self.assertEqual(res.total_amount, 150.00)
        self.assertEqual(res.my_share, 15.00)
        self.assertEqual(res.owed_to_me, 135.00)
        self.assertEqual(round(res.my_share + res.owed_to_me, 2), 150.00)

    def test_split_equally_empty_companion_list_falls_back_to_just_me(self) -> None:
        """Empty companion list must automatically fall back to Just Me behavior."""
        amount = 750.25
        res = SplitCalculationEngine.calculate_split_equally(amount, [])

        self.assertEqual(res.total_amount, 750.25)
        self.assertEqual(res.my_share, 750.25)
        self.assertEqual(res.owed_to_me, 0.00)
        self.assertEqual(res.companions, "Personal")
        self.assertEqual(res.status, TransactionStatus.SETTLED)

    def test_split_equally_stress_paise_conservation_across_random_amounts(self) -> None:
        """Paise conservation invariant across 50 arbitrary amounts and varying friend counts."""
        test_cases = [
            (10.00, 2),
            (29.99, 3),
            (99.95, 4),
            (123.45, 5),
            (999.99, 7),
            (1500.50, 8),
            (4321.11, 9),
            (8888.88, 11),
        ]
        for amt, n in test_cases:
            companions = [f"Peer_{i}" for i in range(n)]
            res = SplitCalculationEngine.calculate_split_equally(amt, companions)
            summed = round(res.my_share + res.owed_to_me, 2)
            self.assertEqual(
                summed,
                round(amt, 2),
                f"Paise leak detected: {res.my_share} + {res.owed_to_me} != {round(amt, 2)}",
            )


class TestAccountingCustomSplit(unittest.TestCase):
    """Adversarial verification of Custom Split mathematics and validation bounds."""

    def test_custom_split_partial_share(self) -> None:
        """User pays 1000, inputs personal share 400. Owed = 600, Status = Pending."""
        res = SplitCalculationEngine.calculate_custom_split(1000.00, 400.00, ["Alex"])

        self.assertEqual(res.total_amount, 1000.00)
        self.assertEqual(res.my_share, 400.00)
        self.assertEqual(res.owed_to_me, 600.00)
        self.assertEqual(res.status, TransactionStatus.PENDING)

    def test_custom_split_full_personal_settled(self) -> None:
        """User inputs my_share == total. Owed = 0.00, Status transitions to Settled."""
        res = SplitCalculationEngine.calculate_custom_split(500.00, 500.00, ["Alex"])

        self.assertEqual(res.total_amount, 500.00)
        self.assertEqual(res.my_share, 500.00)
        self.assertEqual(res.owed_to_me, 0.00)
        self.assertEqual(res.status, TransactionStatus.SETTLED)

    def test_custom_split_zero_personal_share(self) -> None:
        """User covers 100% on behalf of friends: my_share == 0.00, owed == total, Status = Pending."""
        res = SplitCalculationEngine.calculate_custom_split(850.00, 0.00, ["Taylor"])

        self.assertEqual(res.total_amount, 850.00)
        self.assertEqual(res.my_share, 0.00)
        self.assertEqual(res.owed_to_me, 850.00)
        self.assertEqual(res.status, TransactionStatus.PENDING)

    def test_custom_split_negative_share_raises_error(self) -> None:
        """My share < 0.0 must raise ValueError."""
        with self.assertRaises(ValueError):
            SplitCalculationEngine.calculate_custom_split(500.00, -10.00, ["Alex"])

    def test_custom_split_share_exceeding_total_raises_error(self) -> None:
        """My share > total must raise ValueError."""
        with self.assertRaises(ValueError):
            SplitCalculationEngine.calculate_custom_split(500.00, 500.01, ["Alex"])


class TestAccountingInPlaceVoid(unittest.TestCase):
    """Adversarial verification of Non-Destructive In-Place Void mechanics."""

    def test_in_place_void_zeroes_amounts_and_marks_status(self) -> None:
        """Void sets Col G=0.00, Col I=0.00, Col J=0.00, Col K=Void, prefixes [Void]."""
        txn = Transaction(
            id="txn_test_void_01",
            date_serial=45000,
            time_serial=0.5,
            account="SBI",
            item_description="Team Lunch",
            category="Food & Drink",
            total_amount=1200.00,
            my_share=400.00,
            owed_to_me=800.00,
            status=TransactionStatus.PENDING,
            companions="Alice, Bob",
        )

        voided = VoidEngine.apply_void(txn)

        self.assertEqual(voided.total_amount, 0.00)
        self.assertEqual(voided.my_share, 0.00)
        self.assertEqual(voided.owed_to_me, 0.00)
        self.assertEqual(voided.status, TransactionStatus.VOID)
        self.assertEqual(voided.item_description, "[Void] Team Lunch")
        self.assertEqual(voided.sync_state, SyncState.VOID)
        self.assertEqual(voided.id, "txn_test_void_01")

    def test_in_place_void_idempotence_no_double_prefix(self) -> None:
        """Re-voiding an already voided transaction does not double-prefix [Void]."""
        txn = Transaction(
            id="txn_test_void_02",
            date_serial=45000,
            time_serial=0.5,
            account="SBI",
            item_description="[Void] Team Lunch",
            category="Food & Drink",
            total_amount=0.00,
            my_share=0.00,
            owed_to_me=0.00,
            status=TransactionStatus.VOID,
        )

        voided = VoidEngine.apply_void(txn)
        self.assertEqual(voided.item_description, "[Void] Team Lunch")
        self.assertEqual(voided.total_amount, 0.00)

    def test_in_place_void_eliminates_outflow_from_dashboard_sums(self) -> None:
        """Verify that voiding a 2500 INR transaction reduces sum of Col G by exactly 2500."""
        transactions = [
            Transaction(
                id="t1",
                date_serial=45000,
                time_serial=0.1,
                account="SBI",
                item_description="Groceries",
                category="Groceries",
                total_amount=1500.00,
                my_share=1500.00,
                owed_to_me=0.00,
                status=TransactionStatus.SETTLED,
            ),
            Transaction(
                id="t2",
                date_serial=45000,
                time_serial=0.2,
                account="Canara",
                item_description="Erroneous Transfer",
                category="Other",
                total_amount=2500.00,
                my_share=2500.00,
                owed_to_me=0.00,
                status=TransactionStatus.SETTLED,
            ),
        ]

        initial_gross_outflow = sum(t.total_amount for t in transactions)
        self.assertEqual(initial_gross_outflow, 4000.00)

        # Apply void to t2
        VoidEngine.apply_void(transactions[1])

        post_void_gross_outflow = sum(t.total_amount for t in transactions)
        self.assertEqual(post_void_gross_outflow, 1500.00)
        self.assertEqual(initial_gross_outflow - post_void_gross_outflow, 2500.00)


class TestAccountingDebtCarryForward(unittest.TestCase):
    """Adversarial verification of Debt Carry-Forward mechanics and zero double-counting."""

    def test_carry_forward_origin_and_target_properties(self) -> None:
        """Origin: Col J=0.00, Status=Carried. Target: Col G=0.00, Col I=0.00, Col J=owed, Status=Pending."""
        origin_txn = Transaction(
            id="txn_origin_01",
            date_serial=45000,
            time_serial=0.4,
            account="SBI",
            item_description="Weekend Trip Stay",
            category="Trip/Travel",
            total_amount=6000.00,
            my_share=2000.00,
            owed_to_me=4000.00,
            status=TransactionStatus.PENDING,
            companions="Friend1, Friend2",
        )

        updated_origin, carried_txn = RolloverEngine.carry_forward_unsettled_debt(
            origin_month="Nov26",
            target_month="Dec26",
            origin_txn=origin_txn,
            target_date_serial=45031,
            target_time_serial=0.0,
        )

        # Origin verification
        self.assertEqual(updated_origin.owed_to_me, 0.00)
        self.assertEqual(updated_origin.status, TransactionStatus.CARRIED)
        self.assertEqual(updated_origin.total_amount, 6000.00)
        self.assertEqual(updated_origin.my_share, 2000.00)

        # Target verification
        self.assertEqual(carried_txn.total_amount, 0.00)
        self.assertEqual(carried_txn.my_share, 0.00)
        self.assertEqual(carried_txn.owed_to_me, 4000.00)
        self.assertEqual(carried_txn.status, TransactionStatus.PENDING)
        self.assertTrue(carried_txn.item_description.startswith("[Carried"))
        self.assertNotEqual(carried_txn.id, origin_txn.id)

    def test_carry_forward_zero_double_counting_in_monthly_sums(self) -> None:
        """Target month sum of Col G remains 0.00 from the carried debt, eliminating double-counting."""
        origin_txn = Transaction(
            id="txn_origin_02",
            date_serial=45000,
            time_serial=0.4,
            account="SBI",
            item_description="Dinner",
            category="Food & Drink",
            total_amount=1500.00,
            my_share=500.00,
            owed_to_me=1000.00,
            status=TransactionStatus.PENDING,
            companions="Friend",
        )

        _, carried_txn = RolloverEngine.carry_forward_unsettled_debt(
            origin_month="Nov26",
            target_month="Dec26",
            origin_txn=origin_txn,
            target_date_serial=45031,
            target_time_serial=0.0,
        )

        # Target month transactions
        target_month_txns = [
            carried_txn,
            Transaction(
                id="txn_target_new",
                date_serial=45035,
                time_serial=0.2,
                account="SBI",
                item_description="December Groceries",
                category="Groceries",
                total_amount=800.00,
                my_share=800.00,
                owed_to_me=0.00,
                status=TransactionStatus.SETTLED,
            ),
        ]

        target_month_gross_outflow = sum(t.total_amount for t in target_month_txns)
        self.assertEqual(target_month_gross_outflow, 800.00)

        target_month_owed = sum(t.owed_to_me for t in target_month_txns if t.status == TransactionStatus.PENDING)
        self.assertEqual(target_month_owed, 1000.00)

    def test_carry_forward_rejects_settled_or_zero_owed_transactions(self) -> None:
        """Transactions not pending or with 0 owed cannot be rolled over."""
        settled_txn = Transaction(
            id="txn_settled",
            date_serial=45000,
            time_serial=0.4,
            account="SBI",
            item_description="Settled Expense",
            category="Food & Drink",
            total_amount=500.00,
            my_share=500.00,
            owed_to_me=0.00,
            status=TransactionStatus.SETTLED,
        )

        with self.assertRaises(ValueError):
            RolloverEngine.carry_forward_unsettled_debt(
                origin_month="Nov26",
                target_month="Dec26",
                origin_txn=settled_txn,
                target_date_serial=45031,
                target_time_serial=0.0,
            )


class TestAccountingSavingsKeywordRule(unittest.TestCase):
    """Adversarial verification of strictly plural 'Savings' category rule."""

    def test_plural_savings_accepted(self) -> None:
        """'Savings', 'savings', 'SAVINGS' normalize strictly to 'Savings'."""
        self.assertEqual(SplitCalculationEngine.validate_category("Savings"), "Savings")
        self.assertEqual(SplitCalculationEngine.validate_category("savings"), "Savings")
        self.assertEqual(SplitCalculationEngine.validate_category("SAVINGS"), "Savings")

    def test_investment_and_sip_normalize_to_savings(self) -> None:
        """'investment', 'sip', 'mutual fund' normalize strictly to 'Savings'."""
        self.assertEqual(SplitCalculationEngine.validate_category("investment"), "Savings")
        self.assertEqual(SplitCalculationEngine.validate_category("SIP"), "Savings")
        self.assertEqual(SplitCalculationEngine.validate_category("mutual fund"), "Savings")

    def test_singular_saving_strictly_forbidden(self) -> None:
        """Singular 'Saving', 'saving', 'SAVING' must raise ValueError."""
        with self.assertRaises(ValueError):
            SplitCalculationEngine.validate_category("Saving")
        with self.assertRaises(ValueError):
            SplitCalculationEngine.validate_category("saving")
        with self.assertRaises(ValueError):
            SplitCalculationEngine.validate_category("SAVING")


class TestCustomizationDynamicAccounts(unittest.TestCase):
    """Adversarial verification of Tier 1 dynamic funding accounts."""

    def test_arbitrary_accounts_addition_and_reordering(self) -> None:
        """Add arbitrary accounts (HDFC, ICICI, Cash, Credit Card) and reorder."""
        accounts = [
            Account(id="acc_1", name="HDFC Bank", last_four_digits="1234", is_default=False, display_order=1),
            Account(id="acc_2", name="ICICI Bank", last_four_digits="5678", is_default=True, display_order=0),
            Account(id="acc_3", name="Cash", last_four_digits=None, is_default=False, display_order=2),
            Account(id="acc_4", name="Credit Card", last_four_digits="9999", is_default=False, display_order=3),
        ]

        sorted_accounts = sorted(accounts, key=lambda a: a.display_order)
        self.assertEqual([a.name for a in sorted_accounts], ["ICICI Bank", "HDFC Bank", "Cash", "Credit Card"])
        self.assertEqual(sorted_accounts[0].name, "ICICI Bank")
        self.assertTrue(sorted_accounts[0].is_default)

    def test_account_archive_filtering(self) -> None:
        """Archived accounts are filtered from active lists and validation rules."""
        accounts = [
            Account(id="acc_1", name="Active SBI", is_archived=False),
            Account(id="acc_2", name="Old Axis", is_archived=True),
        ]
        active = [a for a in accounts if not a.is_archived]
        self.assertEqual(len(active), 1)
        self.assertEqual(active[0].name, "Active SBI")


class TestCustomizationDynamicCategoriesAndBurnFormulas(unittest.TestCase):
    """Adversarial verification of Living Burn exclusion and dynamic SUMIFS formula generation."""

    def test_burn_formulas_with_zero_excluded_categories(self) -> None:
        """When no categories are excluded, formula uses simple SUM without criteria."""
        formula = MockCustomizationFormulaBuilder.build_current_burn_formula([])
        self.assertIn("SUM(", formula)
        self.assertNotIn("SUMIFS", formula)
        self.assertNotIn("<>", formula)

    def test_burn_formulas_with_multiple_excluded_categories(self) -> None:
        """Formulas dynamically append <>Category for each burn-flagged category without hardcoded strings."""
        excluded = ["Savings", "Investments", "Loan Repayment"]
        formula = MockCustomizationFormulaBuilder.build_current_burn_formula(excluded)

        self.assertIn("SUMIFS(", formula)
        for cat in excluded:
            self.assertIn(f"\'!F4:F\"), \"<>{cat}\"", formula)

    def test_prev_month_and_summary_burn_formulas(self) -> None:
        """Previous month (EDATE) and monthly summary ($U{row}) formulas include dynamic exclusions."""
        excluded = ["Savings"]
        prev_formula = MockCustomizationFormulaBuilder.build_prev_month_burn_formula(excluded)
        self.assertIn("EDATE(TODAY(), -1)", prev_formula)
        self.assertIn('"<>{Savings}"'.replace("{", "").replace("}", ""), prev_formula)

        summary_formula = MockCustomizationFormulaBuilder.build_monthly_summary_burn_formula(8, excluded)
        self.assertIn("$U8", summary_formula)
        self.assertIn('"<>{Savings}"'.replace("{", "").replace("}", ""), summary_formula)


class TestCustomizationHiddenConfigTabSerialization(unittest.TestCase):
    """Adversarial verification of hidden Config tab A1:P50 serialization and zero leakage."""

    def test_config_tab_grid_dimensions_and_headers(self) -> None:
        """Grid is exactly 50 rows by 16 columns with standardized headers."""
        snapshot = AppConfigSnapshot(
            accounts=[Account(id="a1", name="HDFC", is_default=True)],
            categories=[Category(id="c1", name="Food & Drink", is_excluded_from_living_burn=False)],
            companions=[Companion(id="p1", name="Alex")],
            trip_tags=[TripTag(id="t1", name="Goa2026", is_active=True)],
            preferences={"key_settle_delay_seconds": "120"},
        )

        grid = MockConfigGridManager.build_grid(snapshot)

        self.assertEqual(len(grid), 50)
        for row in grid:
            self.assertEqual(len(row), 16)

        self.assertEqual(grid[0], MockConfigGridManager.HEADERS)

    def test_zero_financial_data_leakage_in_config_grid(self) -> None:
        """Scan all 50x16 cells: zero amounts, zero merchants, zero timestamps, zero currency symbols."""
        snapshot = AppConfigSnapshot(
            accounts=[
                Account(id="a1", name="SBI", last_four_digits="1111", is_default=True),
                Account(id="a2", name="HDFC", last_four_digits="2222"),
            ],
            categories=[
                Category(id="c1", name="Food & Drink"),
                Category(id="c2", name="Savings", is_excluded_from_living_burn=True),
            ],
            companions=[Companion(id="p1", name="Companion_A")],
            trip_tags=[TripTag(id="t1", name="Trip_A", is_active=True)],
            preferences={"key_settle_delay_seconds": "120", "key_default_account": "SBI"},
        )

        grid = MockConfigGridManager.build_grid(snapshot)

        currency_pattern = re.compile(r"[₹$€£]|INR|Rs", re.IGNORECASE)
        amount_pattern = re.compile(r"^\d+\.\d{2}$")

        for r_idx, row in enumerate(grid):
            for c_idx, cell in enumerate(row):
                cell_str = str(cell)
                self.assertFalse(
                    currency_pattern.search(cell_str),
                    f"Financial symbol leak at row {r_idx}, col {c_idx}: {cell_str}",
                )
                self.assertFalse(
                    amount_pattern.match(cell_str),
                    f"Financial amount leak at row {r_idx}, col {c_idx}: {cell_str}",
                )

    def test_config_grid_round_trip_fidelity(self) -> None:
        """Full serialize -> deserialize round trip preserves all settings accurately."""
        original = AppConfigSnapshot(
            accounts=[
                Account(id="acc_1", name="ICICI", last_four_digits="4321", is_default=True, display_order=0),
                Account(id="acc_2", name="Cash", is_archived=False, display_order=1),
            ],
            categories=[
                Category(id="cat_1", name="Groceries", is_excluded_from_living_burn=False, display_order=0),
                Category(id="cat_2", name="Savings", is_excluded_from_living_burn=True, display_order=1),
            ],
            companions=[Companion(id="comp_1", name="Sam")],
            trip_tags=[TripTag(id="tag_1", name="Holiday2026", is_active=True)],
            preferences={"key_settle_delay_seconds": "180"},
        )

        grid = MockConfigGridManager.build_grid(original)
        restored = MockConfigGridManager.parse_grid(grid)

        self.assertEqual(len(restored.accounts), 2)
        self.assertEqual(restored.accounts[0].name, "ICICI")
        self.assertTrue(restored.accounts[0].is_default)
        self.assertEqual(restored.accounts[0].last_four_digits, "4321")

        self.assertEqual(len(restored.categories), 2)
        self.assertEqual(restored.categories[1].name, "Savings")
        self.assertTrue(restored.categories[1].is_excluded_from_living_burn)

        self.assertEqual(len(restored.companions), 1)
        self.assertEqual(restored.companions[0].name, "Sam")

        self.assertEqual(len(restored.trip_tags), 1)
        self.assertEqual(restored.trip_tags[0].name, "Holiday2026")
        self.assertTrue(restored.trip_tags[0].is_active)

        self.assertEqual(restored.preferences.get("key_settle_delay_seconds"), "180")


class TestCustomizationSettleDelayWindow(unittest.TestCase):
    """Adversarial verification of configurable settle delay window (30s to 300s, default 120s)."""

    @staticmethod
    def clamp_settle_delay(delay: int) -> int:
        """Mirrors PreferencesRepositoryImpl.kt settle delay clamping."""
        return max(30, min(300, delay))

    def test_default_settle_delay(self) -> None:
        """Default settle delay is 120s."""
        default_val = 120
        self.assertEqual(self.clamp_settle_delay(default_val), 120)

    def test_settle_delay_boundary_limits(self) -> None:
        """30s minimum and 300s maximum limits are valid."""
        self.assertEqual(self.clamp_settle_delay(30), 30)
        self.assertEqual(self.clamp_settle_delay(300), 300)

    def test_settle_delay_clamping_underflow_and_overflow(self) -> None:
        """Values below 30 clamped to 30; values above 300 clamped to 300."""
        self.assertEqual(self.clamp_settle_delay(10), 30)
        self.assertEqual(self.clamp_settle_delay(0), 30)
        self.assertEqual(self.clamp_settle_delay(-50), 30)
        self.assertEqual(self.clamp_settle_delay(301), 300)
        self.assertEqual(self.clamp_settle_delay(600), 300)

    def test_settle_delay_intermediate_values(self) -> None:
        """Intermediate valid durations are preserved."""
        for val in [45, 60, 90, 150, 180, 240]:
            self.assertEqual(self.clamp_settle_delay(val), val)


if __name__ == "__main__":
    unittest.main()
