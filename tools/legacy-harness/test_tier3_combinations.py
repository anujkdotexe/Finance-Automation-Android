"""Tier 3 Pairwise Combinatorial Tests for Finance Automation Android.

This test module systematically tests pairwise and n-way combinations across:
1. SplitMode x ReviewState x SyncState
2. PackageSource x PaymentDirection x CurrencyFormat
3. Category x CompanionCount x SettlementStatus
4. Settle Window State x UTR Occurrence x Deduplication Action
5. InflowType x LinkedUUID Status x AccountType
"""

from __future__ import annotations

import pathlib
import sys
import unittest

ROOT_DIR = pathlib.Path(__file__).resolve().parent.parent
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from domain_harness import (
    DeduplicationEngine,
    InflowTransaction,
    InflowType,
    NotificationParser,
    ReviewState,
    SheetPayloadMapper,
    SplitCalculationEngine,
    SyncState,
    Transaction,
    TransactionStatus,
    TwoAxisStateMachine,
    VoidEngine,
)


class TestTier3SplitModeStateCombinations(unittest.TestCase):
    """Pairwise combinations of SplitMode x ReviewState x SyncState."""

    def test_comb_just_me_needs_review_pending(self) -> None:
        res = SplitCalculationEngine.calculate_just_me(150.0)
        txn = Transaction(
            id="t_comb_1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Coffee",
            category="Food & Drink",
            total_amount=res.total_amount,
            my_share=res.my_share,
            owed_to_me=res.owed_to_me,
            status=res.status,
            review_state=ReviewState.NEEDS_REVIEW,
            sync_state=SyncState.PENDING,
        )
        self.assertEqual(txn.status, TransactionStatus.SETTLED)
        self.assertEqual(txn.review_state, ReviewState.NEEDS_REVIEW)
        self.assertEqual(txn.sync_state, SyncState.PENDING)

    def test_comb_just_me_confirmed_synced(self) -> None:
        res = SplitCalculationEngine.calculate_just_me(200.0)
        txn = Transaction(
            id="t_comb_2",
            date_serial=46331,
            time_serial=0.5,
            account="HDFC",
            item_description="Fuel",
            category="Transport",
            total_amount=res.total_amount,
            my_share=res.my_share,
            owed_to_me=res.owed_to_me,
            status=res.status,
            review_state=ReviewState.CONFIRMED,
            sync_state=SyncState.SYNCED,
        )
        row = SheetPayloadMapper.map_debit_row(txn)
        self.assertEqual(row[10], "Settled")
        self.assertEqual(row[8], 200.0)
        self.assertEqual(row[9], 0.0)

    def test_comb_split_equally_needs_review_pending(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(300.0, ["Alice", "Bob"])
        txn = Transaction(
            id="t_comb_3",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Team Lunch",
            category="Food & Drink",
            total_amount=res.total_amount,
            my_share=res.my_share,
            owed_to_me=res.owed_to_me,
            status=res.status,
            companions=res.companions,
            review_state=ReviewState.NEEDS_REVIEW,
            sync_state=SyncState.PENDING,
        )
        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertEqual(txn.my_share, 100.0)
        self.assertEqual(txn.owed_to_me, 200.0)

    def test_comb_split_equally_confirmed_synced(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(500.0, ["Charlie"])
        txn = Transaction(
            id="t_comb_4",
            date_serial=46331,
            time_serial=0.5,
            account="Canara",
            item_description="Groceries",
            category="Groceries",
            total_amount=res.total_amount,
            my_share=res.my_share,
            owed_to_me=res.owed_to_me,
            status=res.status,
            companions=res.companions,
            review_state=ReviewState.CONFIRMED,
            sync_state=SyncState.SYNCED,
        )
        self.assertEqual(txn.my_share, 250.0)
        self.assertEqual(txn.owed_to_me, 250.0)
        self.assertTrue(TwoAxisStateMachine.is_valid_transition(
            ReviewState.CONFIRMED, SyncState.SYNCED,
            ReviewState.CONFIRMED, SyncState.VOID,
        ))

    def test_comb_custom_split_needs_review_pending(self) -> None:
        res = SplitCalculationEngine.calculate_custom_split(1000.0, 300.0, ["Alice"])
        txn = Transaction(
            id="t_comb_5",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Dinner",
            category="Food & Drink",
            total_amount=res.total_amount,
            my_share=res.my_share,
            owed_to_me=res.owed_to_me,
            status=res.status,
            review_state=ReviewState.NEEDS_REVIEW,
            sync_state=SyncState.PENDING,
        )
        self.assertEqual(txn.my_share, 300.0)
        self.assertEqual(txn.owed_to_me, 700.0)

    def test_comb_custom_split_confirmed_void(self) -> None:
        res = SplitCalculationEngine.calculate_custom_split(800.0, 200.0, ["Bob"])
        txn = Transaction(
            id="t_comb_6",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Hotel",
            category="Travel",
            total_amount=res.total_amount,
            my_share=res.my_share,
            owed_to_me=res.owed_to_me,
            status=res.status,
            review_state=ReviewState.CONFIRMED,
            sync_state=SyncState.SYNCED,
        )
        voided = VoidEngine.apply_void(txn)
        self.assertEqual(voided.my_share, 0.0)
        self.assertEqual(voided.owed_to_me, 0.0)
        self.assertEqual(voided.status, TransactionStatus.VOID)
        self.assertEqual(voided.sync_state, SyncState.VOID)


class TestTier3SourceAndDirectionCombinations(unittest.TestCase):
    """Pairwise combinations of PackageSource x CurrencyFormat x PaymentDirection."""

    def test_comb_gpay_inr_debit(self) -> None:
        text = "Paid INR 450 to Chai Point ref 112233445566"
        res = NotificationParser.parse_notification("com.google.android.apps.nbu.paisa.user", "Payment", text)
        self.assertEqual(res["status"], "PARSED_SUCCESS")
        self.assertEqual(res["amount"], 450.0)
        self.assertTrue(res["is_debit"])

    def test_comb_gpay_rupee_credit(self) -> None:
        text = "Received ₹1,200 from Dave on UPI ref 998877665544"
        res = NotificationParser.parse_notification("com.google.android.apps.nbu.paisa.user", "Received", text)
        self.assertEqual(res["status"], "PARSED_SUCCESS")
        self.assertEqual(res["amount"], 1200.0)
        self.assertTrue(res["is_credit"])

    def test_comb_phonepe_rs_dot_debit(self) -> None:
        text = "Debited Rs. 99.50 at Supermarket using UPI Ref 445566778899"
        res = NotificationParser.parse_notification("com.phonepe.app", "Alert", text)
        self.assertEqual(res["status"], "PARSED_SUCCESS")
        self.assertEqual(res["amount"], 99.50)
        self.assertTrue(res["is_debit"])

    def test_comb_paytm_rs_space_debit(self) -> None:
        text = "Paid Rs 350 to Metro Store Txn ID: 778899001122"
        res = NotificationParser.parse_notification("net.one97.paytm", "Sent", text)
        self.assertEqual(res["status"], "PARSED_SUCCESS")
        self.assertEqual(res["amount"], 350.0)
        self.assertTrue(res["is_debit"])

    def test_comb_bhim_inr_debit(self) -> None:
        text = "Account debited INR 50.00 for payment to Tea Stall UTR: 121212121212"
        res = NotificationParser.parse_notification("in.org.npci.upiapp", "Alert", text)
        self.assertEqual(res["status"], "PARSED_SUCCESS")
        self.assertEqual(res["amount"], 50.0)

    def test_comb_supermoney_rupee_debit(self) -> None:
        text = "Spent ₹650 at Pharmacy ref 343434343434"
        res = NotificationParser.parse_notification("com.supermoney.app", "Spent", text)
        self.assertEqual(res["status"], "PARSED_SUCCESS")
        self.assertEqual(res["amount"], 650.0)

    def test_comb_system_sms_sbi_credit(self) -> None:
        text = "Your SBI A/c XX1234 is credited with Rs 45,000.00 on 05-Nov Ref 565656565656"
        res = NotificationParser.parse_notification("com.google.android.apps.messaging", "SBI", text)
        self.assertEqual(res["status"], "PARSED_SUCCESS")
        self.assertEqual(res["amount"], 45000.0)
        self.assertTrue(res["is_credit"])

    def test_comb_system_sms_hdfc_debit(self) -> None:
        text = "HDFC Bank A/c ending 5678 debited by Rs. 2,500.00 to Amazon Ref 909090909090"
        res = NotificationParser.parse_notification("com.android.mms", "HDFC Bank", text)
        self.assertEqual(res["status"], "PARSED_SUCCESS")
        self.assertEqual(res["amount"], 2500.0)
        self.assertTrue(res["is_debit"])


class TestTier3CategoryAndSplitCombinations(unittest.TestCase):
    """Pairwise combinations of Category x CompanionCount x SettlementStatus."""

    def test_comb_category_food_personal_settled(self) -> None:
        res = SplitCalculationEngine.calculate_just_me(350.0)
        cat = SplitCalculationEngine.validate_category("Food & Drink")
        self.assertEqual(cat, "Food & Drink")
        self.assertEqual(res.status, TransactionStatus.SETTLED)

    def test_comb_category_savings_solo_settled(self) -> None:
        res = SplitCalculationEngine.calculate_just_me(5000.0)
        cat = SplitCalculationEngine.validate_category("Savings")
        self.assertEqual(cat, "Savings")
        self.assertEqual(res.owed_to_me, 0.0)
        self.assertEqual(res.status, TransactionStatus.SETTLED)

    def test_comb_category_travel_group_pending(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(4000.0, ["Alice", "Bob", "Charlie"])
        cat = SplitCalculationEngine.validate_category("Travel")
        self.assertEqual(cat, "Travel")
        self.assertEqual(res.my_share, 1000.0)
        self.assertEqual(res.owed_to_me, 3000.0)
        self.assertEqual(res.status, TransactionStatus.PENDING)

    def test_comb_category_utilities_two_people_pending(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(1200.0, ["Roommate"])
        cat = SplitCalculationEngine.validate_category("Utilities")
        self.assertEqual(cat, "Utilities")
        self.assertEqual(res.my_share, 600.0)
        self.assertEqual(res.owed_to_me, 600.0)

    def test_comb_category_shopping_custom_pending(self) -> None:
        res = SplitCalculationEngine.calculate_custom_split(1500.0, 500.0, ["Alice"])
        cat = SplitCalculationEngine.validate_category("Shopping")
        self.assertEqual(cat, "Shopping")
        self.assertEqual(res.owed_to_me, 1000.0)
        self.assertEqual(res.status, TransactionStatus.PENDING)


class TestTier3SettleWindowAndDeduplicationCombinations(unittest.TestCase):
    """Combinations of Settle Window Timing x Amount/Merchant Matching x Merge Behavior."""

    def test_comb_settle_window_same_amount_and_merchant_within_window(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="",
            category="Food & Drink",
            total_amount=200.0,
            my_share=200.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=100.0,
        )
        engine.recent_transactions.append(t1)
        action, merged = engine.evaluate_incoming(
            "com.android.mms", 200.0, "Starbucks", 120.0
        )
        self.assertEqual(action, "MERGE_EXACT_SIMILAR")
        self.assertEqual(merged.item_description, "Starbucks")

    def test_comb_settle_window_same_amount_and_merchant_outside_window(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Starbucks",
            category="Food & Drink",
            total_amount=200.0,
            my_share=200.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=100.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming(
            "com.android.mms", 200.0, "Starbucks", 250.0
        )
        self.assertEqual(action, "ACCEPT_NEW")

    def test_comb_settle_window_different_merchant_within_window_possible_dup(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Cafe",
            category="Food & Drink",
            total_amount=150.0,
            my_share=150.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=100.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming("com.android.mms", 150.0, "Bakery", 130.0)
        self.assertEqual(action, "FLAG_POSSIBLE_DUPLICATE")

    def test_comb_settle_window_different_amount_within_window_accept_new(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Cafe",
            category="Food & Drink",
            total_amount=150.0,
            my_share=150.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=100.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming("com.android.mms", 250.0, "Cafe", 130.0)
        self.assertEqual(action, "ACCEPT_NEW")


class TestTier3InflowTypeAndRepaymentCombinations(unittest.TestCase):
    """Combinations of InflowType x LinkedUUID Status x AccountType."""

    def test_comb_inflow_salary_unlinked_sbi(self) -> None:
        inflow = InflowTransaction(
            id="inf_sal_1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI XX1234",
            inflow_type=InflowType.SALARY,
            received_from="TechCorp",
            reason="Monthly Salary",
            amount=80000.0,
            linked_debit_uuid="",
        )
        row = SheetPayloadMapper.map_inflow_row(inflow)
        self.assertEqual(row[3], "Salary")
        self.assertEqual(row[7], "")

    def test_comb_inflow_friend_settlement_linked_hdfc(self) -> None:
        inflow = InflowTransaction(
            id="inf_fs_1",
            date_serial=46331,
            time_serial=0.5,
            account="HDFC XX5678",
            inflow_type=InflowType.FRIEND_SETTLEMENT,
            received_from="Alice",
            reason="Lunch settle",
            amount=250.0,
            linked_debit_uuid="txn_nov26_lunch",
        )
        row = SheetPayloadMapper.map_inflow_row(inflow)
        self.assertEqual(row[3], "Friend Settlement")
        self.assertEqual(row[7], "txn_nov26_lunch")

    def test_comb_inflow_allowance_unlinked_canara(self) -> None:
        inflow = InflowTransaction(
            id="inf_all_1",
            date_serial=46331,
            time_serial=0.5,
            account="Canara XX4321",
            inflow_type=InflowType.ALLOWANCE,
            received_from="Family",
            reason="Monthly Allowance",
            amount=10000.0,
            linked_debit_uuid="",
        )
        row = SheetPayloadMapper.map_inflow_row(inflow)
        self.assertEqual(row[3], "Allowance")

    def test_comb_inflow_other_linked_cash(self) -> None:
        inflow = InflowTransaction(
            id="inf_oth_1",
            date_serial=46331,
            time_serial=0.5,
            account="Cash",
            inflow_type=InflowType.OTHER,
            received_from="Bob",
            reason="Reimbursement",
            amount=500.0,
            linked_debit_uuid="txn_nov26_fuel",
        )
        row = SheetPayloadMapper.map_inflow_row(inflow)
        self.assertEqual(row[3], "Other")
        self.assertEqual(row[7], "txn_nov26_fuel")


class TestTier3TwoAxisStateTransitionsPairwise(unittest.TestCase):
    """Pairwise verification of two-axis ReviewState x SyncState transitions."""

    def test_comb_state_transition_needs_review_to_confirmed_pending(self) -> None:
        self.assertTrue(TwoAxisStateMachine.is_valid_transition(
            ReviewState.NEEDS_REVIEW, SyncState.PENDING,
            ReviewState.CONFIRMED, SyncState.PENDING,
        ))

    def test_comb_state_transition_needs_review_to_synced_unconfirmed(self) -> None:
        self.assertTrue(TwoAxisStateMachine.is_valid_transition(
            ReviewState.NEEDS_REVIEW, SyncState.PENDING,
            ReviewState.NEEDS_REVIEW, SyncState.SYNCED,
        ))

    def test_comb_state_transition_confirmed_pending_to_synced(self) -> None:
        self.assertTrue(TwoAxisStateMachine.is_valid_transition(
            ReviewState.CONFIRMED, SyncState.PENDING,
            ReviewState.CONFIRMED, SyncState.SYNCED,
        ))

    def test_comb_state_transition_synced_unconfirmed_to_confirmed_synced(self) -> None:
        self.assertTrue(TwoAxisStateMachine.is_valid_transition(
            ReviewState.NEEDS_REVIEW, SyncState.SYNCED,
            ReviewState.CONFIRMED, SyncState.SYNCED,
        ))

    def test_comb_state_transition_needs_review_to_void(self) -> None:
        self.assertTrue(TwoAxisStateMachine.is_valid_transition(
            ReviewState.NEEDS_REVIEW, SyncState.PENDING,
            ReviewState.NEEDS_REVIEW, SyncState.VOID,
        ))

    def test_comb_state_transition_confirmed_to_void(self) -> None:
        self.assertTrue(TwoAxisStateMachine.is_valid_transition(
            ReviewState.CONFIRMED, SyncState.PENDING,
            ReviewState.CONFIRMED, SyncState.VOID,
        ))

    def test_comb_state_transition_synced_unconfirmed_to_void(self) -> None:
        self.assertTrue(TwoAxisStateMachine.is_valid_transition(
            ReviewState.NEEDS_REVIEW, SyncState.SYNCED,
            ReviewState.NEEDS_REVIEW, SyncState.VOID,
        ))

    def test_comb_custom_split_fully_fronted_companions_pending(self) -> None:
        res = SplitCalculationEngine.calculate_custom_split(1200.0, 0.0, ["Alice", "Bob"])
        self.assertEqual(res.my_share, 0.0)
        self.assertEqual(res.owed_to_me, 1200.0)
        self.assertEqual(res.status, TransactionStatus.PENDING)

    def test_comb_inflow_repayment_partial_balance_pending(self) -> None:
        owed = 1000.0
        repaid = 400.0
        remaining = owed - repaid
        status = TransactionStatus.SETTLED if remaining <= 0.0 else TransactionStatus.PENDING
        self.assertEqual(status, TransactionStatus.PENDING)
        self.assertEqual(remaining, 600.0)

    def test_comb_category_allowance_inflow_with_zero_burn(self) -> None:
        inflow = InflowTransaction(
            id="inf_comb_all",
            date_serial=46331,
            time_serial=0.25,
            account="SBI XX1234",
            inflow_type=InflowType.ALLOWANCE,
            received_from="Grandparents",
            reason="Gift",
            amount=2000.0,
        )
        row = SheetPayloadMapper.map_inflow_row(inflow)
        self.assertEqual(row[6], 2000.0)


if __name__ == "__main__":
    unittest.main()
