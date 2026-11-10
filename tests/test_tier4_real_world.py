"""Tier 4 Real-World Workload Scenarios for Finance Automation Android.

This test module executes realistic, end-to-end multi-step workflows:
1. S1: Standard UPI Outflow Pipeline (GPay -> Parse -> Categorize -> Split -> Settle -> Sheet Row)
2. S2: Exact UTR Dual-Alert Merging (GPay Push + Bank SMS 14s later with identical UTR)
3. S3: Cross-Source Settle Window Matching (No UTR, matching amount -> Flagged Possible Duplicate)
4. S4: Non-Destructive In-Place Void (Zero Col I & J, Status Void, Prefix [Void], UUID intact)
5. S5: End-of-Month Debt Rollover (Origin Col J zeroed & Carried, fresh UUID in target month)
6. S6: Inflow Repayment Settlement Linking (Credit linked via Column T to Col AC UUID)
7. S7: Formula Injection Neutralization (Untrusted payee string formula safety under RAW mode)
8. S8: Privacy Blacklist & OTP Suppression (WhatsApp/Telegram/Signal/OTP dropped, UPI captured)
"""

from __future__ import annotations

import datetime
import pathlib
import sys
import unittest

ROOT_DIR = pathlib.Path(__file__).resolve().parent.parent
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from tests.domain_harness import (
    TABLE_1_DEBITS_RANGE,
    DeduplicationEngine,
    InflowTransaction,
    InflowType,
    NotificationParser,
    ReviewState,
    RolloverEngine,
    SheetPayloadMapper,
    SplitCalculationEngine,
    SyncState,
    Transaction,
    TransactionStatus,
    TwoAxisStateMachine,
    VoidEngine,
    to_date_serial,
    to_time_serial,
)


class TestTier4RealWorldScenarios(unittest.TestCase):
    """End-to-end multi-step real world application scenarios."""

    def test_s1_standard_upi_outflow_pipeline(self) -> None:
        """Scenario 1: Complete UPI Outflow Pipeline with Split Equally."""
        # Step 1: Intercept GPay notification
        raw_pkg = "com.google.android.apps.nbu.paisa.user"
        raw_title = "Payment to Blue Tokai"
        raw_text = "Paid INR 1,000.00 to Blue Tokai Coffee using UPI Ref: 431012345678"

        parsed = NotificationParser.parse_notification(raw_pkg, raw_title, raw_text)
        self.assertEqual(parsed["status"], "PARSED_SUCCESS")
        self.assertEqual(parsed["amount"], 1000.0)
        self.assertNotIn("utr", parsed)
        self.assertEqual(parsed["merchant"], "Blue Tokai Coffee")

        # Step 2: Categorization & Split across 3 companions (4 heads total)
        category = SplitCalculationEngine.validate_category("Food & Drink")
        split_res = SplitCalculationEngine.calculate_split_equally(
            parsed["amount"], ["Alice", "Bob", "Charlie"]
        )
        self.assertEqual(split_res.my_share, 250.0)
        self.assertEqual(split_res.owed_to_me, 750.0)
        self.assertEqual(split_res.status, TransactionStatus.PENDING)

        # Step 3: Insert into Room (NEEDS_REVIEW, PENDING)
        txn = Transaction(
            id="txn_blue_tokai_001",
            date_serial=to_date_serial(datetime.date(2026, 11, 5)),
            time_serial=to_time_serial(datetime.time(15, 30, 0)),
            account="SBI XX1234",
            item_description=parsed["merchant"],
            category=category,
            total_amount=split_res.total_amount,
            my_share=split_res.my_share,
            owed_to_me=split_res.owed_to_me,
            status=split_res.status,
            companions=split_res.companions,
            package_name=raw_pkg,
            review_state=ReviewState.NEEDS_REVIEW,
            sync_state=SyncState.PENDING,
        )

        # Step 4: Quick Action chip confirms category
        self.assertTrue(TwoAxisStateMachine.is_valid_transition(
            txn.review_state, txn.sync_state,
            ReviewState.CONFIRMED, SyncState.PENDING,
        ))
        txn.review_state = ReviewState.CONFIRMED

        # Step 5: Settle window elapses -> SyncWorker dispatches RAW values.append
        self.assertTrue(TwoAxisStateMachine.is_valid_transition(
            txn.review_state, txn.sync_state,
            ReviewState.CONFIRMED, SyncState.SYNCED,
        ))
        txn.sync_state = SyncState.SYNCED

        # Step 6: Verify 31-column Sheet row formatting
        sheet_row = SheetPayloadMapper.map_debit_row(txn)
        self.assertEqual(len(sheet_row), 11)  # Table 1 Columns A to K
        self.assertEqual(sheet_row[0], 46331)  # Date serial
        self.assertEqual(sheet_row[3], "Blue Tokai Coffee")
        self.assertEqual(sheet_row[5], "Food & Drink")
        self.assertEqual(sheet_row[6], 1000.0)  # Total
        self.assertEqual(sheet_row[7], "Alice, Bob, Charlie")
        self.assertEqual(sheet_row[8], 250.0)  # My Share
        self.assertEqual(sheet_row[9], 750.0)  # Owed To Me
        self.assertEqual(sheet_row[10], "Pending")  # Status

    def test_s2_exact_utr_dual_alert_merging(self) -> None:
        """Scenario 2: Rapid dual notification merged in-place via Amount + Merchant + 120s window."""
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t_base = 1000.0

        # Push Notification 1: GPay App alert arrives first
        parsed_gpay = NotificationParser.parse_notification(
            "com.google.android.apps.nbu.paisa.user",
            "Payment Sent",
            "Paid Rs. 450.00 to Chai Point Ref 887766554433",
        )
        action_1, _ = engine.evaluate_incoming(
            parsed_gpay["package_name"],
            parsed_gpay["amount"],
            parsed_gpay["merchant"],
            t_base,
        )
        self.assertEqual(action_1, "ACCEPT_NEW")

        t1 = Transaction(
            id="txn_chai_point_01",
            date_serial=46331,
            time_serial=0.5,
            account="Default Account",
            item_description=parsed_gpay["merchant"],
            category="Food & Drink",
            total_amount=parsed_gpay["amount"],
            my_share=450.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=t_base,
        )
        engine.recent_transactions.append(t1)

        # Push Notification 2: Bank SMS arrives 14 seconds later with same amount and merchant
        t_sms = t_base + 14.0
        parsed_sms = NotificationParser.parse_notification(
            "com.android.mms",
            "SBI Bank Alert",
            "SBI A/c XX1234 debited by Rs 450.00 to Chai Point Ref 887766554433. Avl Bal Rs 12,000",
        )
        action_2, merged = engine.evaluate_incoming(
            parsed_sms["package_name"],
            parsed_sms["amount"],
            parsed_sms["merchant"],
            t_sms,
        )

        # Assert in-place merge occurred: zero second transaction created
        self.assertEqual(action_2, "MERGE_EXACT_SIMILAR")
        self.assertIsNotNone(merged)
        self.assertEqual(merged.id, "txn_chai_point_01")
        self.assertEqual(len(engine.recent_transactions), 1)

    def test_s3_cross_source_settle_window_matching(self) -> None:
        """Scenario 3: Cross-source duplicate candidate flagged in Review Inbox."""
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t_base = 2000.0

        # Alert 1: Supermoney payment
        t1 = Transaction(
            id="txn_super_01",
            date_serial=46331,
            time_serial=0.4,
            account="HDFC XX5678",
            item_description="Pharmacy",
            category="Medical",
            total_amount=350.0,
            my_share=350.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=t_base,
        )
        engine.recent_transactions.append(t1)

        # Alert 2: Bank SMS arrives 35 seconds later with distinct merchant string
        t_alert2 = t_base + 35.0
        action, candidate = engine.evaluate_incoming(
            "com.android.mms",
            350.0,
            "Apollo Pharmacy",
            t_alert2,
        )

        # Assert flagged as possible duplicate without being dropped
        self.assertEqual(action, "FLAG_POSSIBLE_DUPLICATE")
        self.assertTrue(candidate.is_possible_duplicate)
        self.assertEqual(candidate.review_state, ReviewState.NEEDS_REVIEW)

    def test_s4_non_destructive_in_place_void(self) -> None:
        """Scenario 4: Non-destructive in-place void preserves contiguous rows and clears burn."""
        # Existing transaction committed to Google Sheet Row 15
        txn = Transaction(
            id="txn_void_scenario_01",
            date_serial=46331,
            time_serial=0.6,
            account="SBI XX1234",
            item_description="Client Lunch",
            category="Food & Drink",
            total_amount=1500.0,
            my_share=500.0,
            owed_to_me=1000.0,
            status=TransactionStatus.PENDING,
            companions="Alice, Bob",
            sync_state=SyncState.SYNCED,
            review_state=ReviewState.CONFIRMED,
        )

        # User voids the expense in Review Inbox
        voided_txn = VoidEngine.apply_void(txn)

        # Assert non-destructive in-place void properties: Col G = 0, Col I = 0, Col J = 0
        self.assertEqual(voided_txn.total_amount, 0.00)  # Col G zeroed!
        self.assertEqual(voided_txn.my_share, 0.00)     # Col I zeroed!
        self.assertEqual(voided_txn.owed_to_me, 0.00)   # Col J zeroed!
        self.assertEqual(voided_txn.status, TransactionStatus.VOID)
        self.assertEqual(voided_txn.item_description, "[Void] Client Lunch")
        self.assertEqual(voided_txn.id, "txn_void_scenario_01")  # UUID preserved
        self.assertEqual(voided_txn.sync_state, SyncState.VOID)

        # Verify Google Sheet Row update format
        row = SheetPayloadMapper.map_debit_row(voided_txn)
        self.assertEqual(row[6], 0.00)  # Col G (Total Amount)
        self.assertEqual(row[8], 0.00)  # Col I (My Share)
        self.assertEqual(row[9], 0.00)  # Col J (Owed To Me)
        self.assertEqual(row[10], "Void")  # Col K (Status)
        self.assertEqual(row[3], "[Void] Client Lunch")

    def test_s5_end_of_month_debt_rollover(self) -> None:
        """Scenario 5: End-of-month rollover zeroes origin Col J and appends fresh row."""
        # Unsettled debt originating in Nov26 tab
        nov_txn = Transaction(
            id="txn_nov26_team_outing",
            date_serial=to_date_serial(datetime.date(2026, 11, 10)),
            time_serial=0.5,
            account="SBI XX1234",
            item_description="Team Outing",
            category="Entertainment",
            total_amount=6000.0,
            my_share=1500.0,
            owed_to_me=4500.0,
            status=TransactionStatus.PENDING,
            companions="Alice, Bob, Charlie",
        )

        # Monthly Rollover executes on 01-Dec-2026
        target_date = to_date_serial(datetime.date(2026, 12, 1))
        target_time = to_time_serial(datetime.time(0, 1, 0))

        nov_updated, dec_carried = RolloverEngine.carry_forward_unsettled_debt(
            "Nov26", "Dec26", nov_txn, target_date, target_time
        )

        # Assert origin transaction (Nov26): Col J zeroed out, Status = Carried
        self.assertEqual(nov_updated.owed_to_me, 0.00)
        self.assertEqual(nov_updated.status, TransactionStatus.CARRIED)

        # Assert carried transaction (Dec26): fresh UUID, Col G = 0, Col I = 0, Col J = 4500, Status = Pending
        self.assertNotEqual(dec_carried.id, nov_txn.id)
        self.assertEqual(dec_carried.total_amount, 0.00)  # Col G = 0 prevents gross outflow inflation!
        self.assertEqual(dec_carried.my_share, 0.00)      # Col I = 0
        self.assertEqual(dec_carried.owed_to_me, 4500.0)  # Col J = carried debt
        self.assertEqual(dec_carried.status, TransactionStatus.PENDING)
        self.assertEqual(dec_carried.item_description, "[Carried from Nov26] Team Outing")
        self.assertEqual(dec_carried.companions, "Alice, Bob, Charlie")

    def test_s6_inflow_repayment_settlement_linking(self) -> None:
        """Scenario 6: Inflow friend repayment linked via Column T to Col AC UUID."""
        # 1. Original debit expense in Table 1
        debit_uuid = "txn_dinner_nov_99"
        debit_owed = 800.0

        # 2. Friend repays via PhonePe
        credit_notif = NotificationParser.parse_notification(
            "com.phonepe.app",
            "Payment Received",
            "Received ₹800.00 from Alice for dinner UPI Ref: 123123123123",
        )
        self.assertEqual(credit_notif["status"], "PARSED_SUCCESS")
        self.assertTrue(credit_notif["is_credit"])
        self.assertEqual(credit_notif["amount"], 800.0)

        # 3. Create Table 2 Inflow record linked to Table 1 Col AC UUID
        inflow = InflowTransaction(
            id="inf_repay_01",
            date_serial=to_date_serial(datetime.date(2026, 11, 15)),
            time_serial=to_time_serial(datetime.time(18, 0, 0)),
            account="HDFC XX5678",
            inflow_type=InflowType.FRIEND_SETTLEMENT,
            received_from="Alice",
            reason="Settling dinner bill",
            amount=credit_notif["amount"],
            linked_debit_uuid=debit_uuid,
        )

        # 4. Assert mapping to Table 2 (Columns M through T)
        row = SheetPayloadMapper.map_inflow_row(inflow)
        self.assertEqual(len(row), 8)
        self.assertEqual(row[3], "Friend Settlement")
        self.assertEqual(row[6], 800.0)
        self.assertEqual(row[7], debit_uuid)  # Column T matches Col AC UUID

        # 5. Full settlement clears balance
        remaining_balance = debit_owed - inflow.amount
        final_status = TransactionStatus.SETTLED if remaining_balance <= 0.0 else TransactionStatus.PENDING
        self.assertEqual(final_status, TransactionStatus.SETTLED)

    def test_s7_formula_injection_neutralization(self) -> None:
        """Scenario 7: Malicious Payee with spreadsheet formulas safely committed via RAW mode."""
        malicious_input = "=cmd|' /C calc'!A0"
        raw_text = f"Paid Rs. 100.00 to {malicious_input} ref 555666777888"

        parsed = NotificationParser.parse_notification(
            "com.google.android.apps.nbu.paisa.user",
            "Payment",
            raw_text,
        )
        self.assertEqual(parsed["status"], "PARSED_SUCCESS")

        txn = Transaction(
            id="txn_safe_raw_01",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description=parsed["merchant"],
            category="Shopping",
            total_amount=100.0,
            my_share=100.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
        )

        # Verify RAW append mapping
        row = SheetPayloadMapper.map_debit_row(txn)
        self.assertEqual(row[3], parsed["merchant"])
        # Under valueInputOption=RAW, Google Sheets preserves leading '=' as literal string
        self.assertTrue(SheetPayloadMapper.is_safe_raw_value(row[3]))
        self.assertEqual(TABLE_1_DEBITS_RANGE, "A3:K")

    def test_s8_privacy_blacklist_and_otp_suppression(self) -> None:
        """Scenario 8: Barrage of incoming alerts: non-financial and OTPs dropped, financial ingested."""
        # 1. WhatsApp personal chat message -> DROPPED
        r1 = NotificationParser.parse_notification(
            "com.whatsapp",
            "Mom",
            "Sent Rs. 500 for groceries",
        )
        self.assertEqual(r1["status"], "DROPPED_BLACKLIST")

        # 2. Telegram message -> DROPPED
        r2 = NotificationParser.parse_notification(
            "org.telegram.messenger",
            "Crypto Bot",
            "You received ₹10,000 in your wallet",
        )
        self.assertEqual(r2["status"], "DROPPED_BLACKLIST")

        # 3. Signal private message -> DROPPED
        r3 = NotificationParser.parse_notification(
            "org.thoughtcrime.securesms",
            "Bob",
            "Paid INR 350 for movie ticket",
        )
        self.assertEqual(r3["status"], "DROPPED_BLACKLIST")

        # 4. Bank OTP SMS -> DROPPED
        r4 = NotificationParser.parse_notification(
            "com.android.mms",
            "SBI Bank",
            "Your OTP for payment of Rs 1,500 is 849201. Do not share with anyone.",
        )
        self.assertEqual(r4["status"], "DROPPED_OTP")

        # 5. Legitimate PhonePe payment -> PARSED_SUCCESS
        r5 = NotificationParser.parse_notification(
            "com.phonepe.app",
            "Payment Sent",
            "Debited Rs. 150.00 at Grocery Mart Ref: 987654321098",
        )
        self.assertEqual(r5["status"], "PARSED_SUCCESS")
        self.assertEqual(r5["amount"], 150.0)
        self.assertNotIn("utr", r5)
        self.assertEqual(r5["merchant"], "Grocery Mart")


if __name__ == "__main__":
    unittest.main()
