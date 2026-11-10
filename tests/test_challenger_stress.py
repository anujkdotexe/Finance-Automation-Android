"""Adversarial Stress Test Suite for Finance Automation Android.

Author: teamwork_challenger_1 (EMPIRICAL CHALLENGER)
Mission: Empirically stress-test Requirements R1 through R4 implementations.

Stress-test and verify:
1. Split accounting formulas (Just Me, Split Equally with 0/1/many companions, Custom Split, Savings normalization).
2. Deduplication logic: 120-second sliding window, same amount vs different merchant, cross-source flagging.
3. Notification parser regexes: amounts with decimals, various bank and UPI formats, merchant extraction, OTP suppression.
4. Sync queue state transitions and operations (INSERT, UPDATE, VOID; PENDING, IN_PROGRESS, FAILED, COMPLETED).
5. Invariants: zero double-bangs, zero emojis, zero UTR/raw notification storage, zero local machine paths.
"""

from __future__ import annotations

import decimal
import pathlib
import re
import unittest

ROOT_DIR = pathlib.Path(__file__).resolve().parent.parent

from tests.domain_harness import (
    AMOUNT_REGEX,
    CREDIT_FLAG_REGEX,
    DEBIT_FLAG_REGEX,
    OTP_REGEX,
    DeduplicationEngine,
    Transaction,
    TransactionStatus,
    VoidEngine,
)

VPA_REGEX = re.compile(r"\b([a-zA-Z0-9.\-_]+@[a-zA-Z0-9]+)\b")

MERCHANT_REGEX = re.compile(
    r"(?:to|at|paid to|vpa|transferred to)\s+([A-Za-z0-9\s&._\-@]+?)(?:\s+on|\.(?!\d)|$|\s+ref|\s+using|\s+via|\s+through|\s+utr|\s+a/c|\s+avl|\s+bal|\b\d{12}\b)",
    re.IGNORECASE,
)

BANK_SMS_AMOUNT_REGEX = re.compile(
    r"(?i)(?:debited\s+(?:by|for)?|spent|paid|withdrawn|transferred\s+to|vpa)\s+(?:Rs\.?|INR|₹)?\s*([\d,]+(?:\.\d{1,2})?)"
)


class TestChallengerSplitFormulas(unittest.TestCase):
    """Stress tests for split calculation accounting mathematics and category rules."""

    def _round2(self, val: float) -> float:
        d = decimal.Decimal(str(val))
        return float(d.quantize(decimal.Decimal("0.01"), rounding=decimal.ROUND_HALF_UP))

    def _calc_just_me(self, total: float) -> tuple[float, float, str, str]:
        r = self._round2(total)
        return r, 0.00, "Personal", "Settled"

    def _calc_split_equally(self, total: float, companions: list[str]) -> tuple[float, float, str, str]:
        r = self._round2(total)
        clean = [c.strip() for c in companions if c.strip()]
        if not clean:
            return self._calc_just_me(r)
        heads = len(clean) + 1
        my_share = self._round2(r / heads)
        owed_to_me = self._round2(r - my_share)
        return my_share, owed_to_me, ", ".join(clean), "Pending"

    def _calc_custom_split(self, total: float, my_share: float, companions: list[str]) -> tuple[float, float, str, str]:
        r_total = self._round2(total)
        r_my_share = self._round2(my_share)
        if r_my_share < 0.0:
            raise ValueError("My share cannot be negative")
        if r_my_share > r_total:
            raise ValueError("My share cannot exceed total amount")
        owed = self._round2(r_total - r_my_share)
        clean = [c.strip() for c in companions if c.strip()]
        comp_text = ", ".join(clean) if clean else "Friends"
        status = "Settled" if owed == 0.00 else "Pending"
        return r_my_share, owed, comp_text, status

    def _normalize_category(self, cat: str) -> str:
        trimmed = cat.strip()
        lower = trimmed.lower()
        if lower == "saving":
            raise ValueError("Singular 'Saving' is forbidden. Category must strictly be 'Savings'")
        if lower in ("savings", "investment", "sip", "mutual fund"):
            return "Savings"
        return trimmed

    def test_just_me_splits(self) -> None:
        """Verify Just Me: My Share == Total, Owed == 0, Companions == Personal, Status == Settled."""
        test_amounts = [0.00, 0.01, 49.99, 100.00, 349.50, 12500.00, 999999.99]
        for amt in test_amounts:
            my_share, owed, comp, status = self._calc_just_me(amt)
            self.assertEqual(my_share, amt)
            self.assertEqual(owed, 0.00)
            self.assertEqual(comp, "Personal")
            self.assertEqual(status, "Settled")

    def test_split_equally_with_zero_or_blank_companions_falls_back_to_just_me(self) -> None:
        """Verify 0 companions or empty strings fall back to Just Me."""
        empty_cases: list[list[str]] = [[], [""], ["   "], ["\t", "\n"]]
        for companions in empty_cases:
            my_share, owed, comp, status = self._calc_split_equally(300.00, companions)
            self.assertEqual(my_share, 300.00)
            self.assertEqual(owed, 0.00)
            self.assertEqual(comp, "Personal")
            self.assertEqual(status, "Settled")

    def test_split_equally_exact_cent_preservation(self) -> None:
        """Verify sum of my_share + owed_to_me strictly equals total for uneven divisions."""
        cases = [
            (100.00, ["Alice", "Bob"]),              # 3 heads: 33.33 + 66.67 = 100.00
            (10.01, ["Alice", "Bob"]),               # 3 heads: 3.34 + 6.67 = 10.01
            (100.00, ["Alice", "Bob", "Charlie"]),   # 4 heads: 25.00 + 75.00 = 100.00
            (50.00, ["Alice", "Bob", "Charlie", "David", "Eve", "Frank"]), # 7 heads: 7.14 + 42.86 = 50.00
            (7.00, ["Alice", "Bob"]),                # 3 heads: 2.33 + 4.67 = 7.00
            (0.03, ["Alice", "Bob", "Charlie"]),     # 4 heads: 0.01 + 0.02 = 0.03
        ]
        for total, companions in cases:
            my_share, owed, comp_text, status = self._calc_split_equally(total, companions)
            self.assertEqual(
                round(my_share + owed, 2),
                round(total, 2),
                f"Sum mismatch: {my_share} + {owed} != {total}",
            )
            self.assertEqual(status, "Pending")
            self.assertEqual(comp_text, ", ".join(companions))

    def test_custom_split_boundaries(self) -> None:
        """Verify custom split boundaries: full share, zero share, and out of bounds."""
        # Case 1: 100% personal custom split -> Settled
        my_share, owed, comp, status = self._calc_custom_split(500.00, 500.00, ["Alice"])
        self.assertEqual(my_share, 500.00)
        self.assertEqual(owed, 0.00)
        self.assertEqual(status, "Settled")

        # Case 2: 0% personal custom split -> 100% owed to me -> Pending
        my_share, owed, comp, status = self._calc_custom_split(500.00, 0.00, ["Alice"])
        self.assertEqual(my_share, 0.00)
        self.assertEqual(owed, 500.00)
        self.assertEqual(comp, "Alice")
        self.assertEqual(status, "Pending")

        # Case 3: Negative personal share -> Exception
        with self.assertRaises(ValueError):
            self._calc_custom_split(500.00, -10.00, ["Alice"])

        # Case 4: Personal share exceeds total -> Exception
        with self.assertRaises(ValueError):
            self._calc_custom_split(500.00, 500.01, ["Alice"])

    def test_savings_category_normalization_rules(self) -> None:
        """Verify strict plural 'Savings' normalization and rejection of singular 'Saving'."""
        valid_savings = ["Savings", "savings", "SAVINGS", "investment", "sip", "mutual fund", "SIP"]
        for s in valid_savings:
            self.assertEqual(self._normalize_category(s), "Savings")

        with self.assertRaises(ValueError):
            self._normalize_category("saving")

        with self.assertRaises(ValueError):
            self._normalize_category("Saving")

        self.assertEqual(self._normalize_category("Groceries"), "Groceries")
        self.assertEqual(self._normalize_category("Food & Drink"), "Food & Drink")


class TestChallengerDeduplication(unittest.TestCase):
    """Stress tests for Tier 3 cross-source settle window deduplication."""

    def test_exact_120_second_sliding_window(self) -> None:
        """Verify window boundaries at 119.9s, 120.0s, and 120.1s."""
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        base_txn = Transaction(
            id="txn_base",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Swiggy Delivery",
            category="Food & Drink",
            total_amount=450.00,
            my_share=450.00,
            owed_to_me=0.00,
            status=TransactionStatus.SETTLED,
            timestamp=1000.0,
            package_name="com.google.android.apps.nbu.paisa.user",
        )
        engine.recent_transactions.append(base_txn)

        # Same package, same merchant within 120s window (at 1050s) -> MERGE_EXACT_SIMILAR
        action, _ = engine.evaluate_incoming(
            "com.google.android.apps.nbu.paisa.user", 450.00, "Swiggy Delivery", 1050.0
        )
        self.assertEqual(action, "MERGE_EXACT_SIMILAR")

        # Same amount, same merchant within 120s window -> MERGE_EXACT_SIMILAR
        action_same_merch, _ = engine.evaluate_incoming(
            "com.android.mms", 450.00, "Swiggy Delivery", 1080.0
        )
        self.assertEqual(action_same_merch, "MERGE_EXACT_SIMILAR")

        # Same amount, different merchant within 120s window (at 1110s) -> FLAG_POSSIBLE_DUPLICATE
        action_diff_merch, _ = engine.evaluate_incoming(
            "com.google.android.apps.nbu.paisa.user", 450.00, "Zomato", 1110.0
        )
        self.assertEqual(action_diff_merch, "FLAG_POSSIBLE_DUPLICATE")

        # Outside 120s window (at 1121s -> diff is 121s) -> ACCEPT_NEW
        action_outside, _ = engine.evaluate_incoming(
            "com.google.android.apps.nbu.paisa.user", 450.00, "Swiggy Delivery", 1121.0
        )
        self.assertEqual(action_outside, "ACCEPT_NEW")

    def test_unknown_payee_enrichment(self) -> None:
        """Verify candidate with known payee enriches an existing Unknown Payee transaction."""
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        unknown_txn = Transaction(
            id="txn_enrich_test",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Unknown Payee",
            category="Other",
            total_amount=320.00,
            my_share=320.00,
            owed_to_me=0.00,
            status=TransactionStatus.SETTLED,
            timestamp=2000.0,
            package_name="com.android.mms",
        )
        engine.recent_transactions.append(unknown_txn)

        action, enriched = engine.evaluate_incoming(
            "com.google.android.apps.nbu.paisa.user", 320.00, "Blue Tokai Coffee", 2040.0
        )
        self.assertEqual(action, "MERGE_EXACT_SIMILAR")
        self.assertIsNotNone(enriched)
        if enriched:
            self.assertEqual(enriched.item_description, "Blue Tokai Coffee")


class TestChallengerRegexParsers(unittest.TestCase):
    """Stress tests for notification and bank SMS regex extraction."""

    def test_amount_with_decimals_does_not_break_merchant_name(self) -> None:
        """Verify decimal dot in amount does not split merchant name."""
        test_strings = [
            ("Paid Rs 349.00 to Swiggy on 06-Nov", 349.00, "Swiggy"),
            ("Paid Rs. 1,250.75 to Starbucks Coffee on 05-Nov", 1250.75, "Starbucks Coffee"),
            ("Paid INR 99.50 to Uber India", 99.50, "Uber India"),
            ("Paid ₹ 500.00 to Chai Point", 500.00, "Chai Point"),
            ("Transferred Rs. 200 to friend@okhdfcbank ref 123456789012", 200.00, "friend@okhdfcbank"),
        ]

        for text, expected_amt, expected_merchant in test_strings:
            # Check amount
            match_amt = AMOUNT_REGEX.search(text)
            self.assertIsNotNone(match_amt, f"Failed to match amount in: {text}")
            if match_amt:
                amt = float(match_amt.group(1).replace(",", ""))
                self.assertEqual(amt, expected_amt)

            # Check merchant
            vpa_match = VPA_REGEX.search(text)
            if vpa_match and "ref" not in vpa_match.group(1).lower():
                extracted_merchant = vpa_match.group(1).strip()
            else:
                m_match = MERCHANT_REGEX.search(text)
                self.assertIsNotNone(m_match, f"Failed to match merchant in: {text}")
                extracted_merchant = m_match.group(1).strip() if m_match else ""

            self.assertEqual(
                extracted_merchant,
                expected_merchant,
                f"Merchant mismatch in: '{text}'. Got '{extracted_merchant}', expected '{expected_merchant}'",
            )

    def test_bank_sms_debit_alerts(self) -> None:
        """Verify bank SMS debit formats from major Indian banks."""
        bank_alerts = [
            ("SBI: A/c XX1234 debited by Rs.450.00 on 06-Nov-26; Avl Bal Rs 5000", 450.00, True),
            ("HDFC Bank Alert: Rs 1,500.00 debited from A/c 5678 to Swiggy", 1500.00, True),
            ("Spent INR 320.00 at Costa Coffee on your ICICI Bank Card ending 9012", 320.00, True),
            ("Withdrawn Rs 2,000.00 from ATM using Canara Bank debit card", 2000.00, True),
        ]

        for text, expected_amt, is_debit in bank_alerts:
            match_amt = AMOUNT_REGEX.search(text) or BANK_SMS_AMOUNT_REGEX.search(text)
            self.assertIsNotNone(match_amt, f"Failed amount in bank SMS: {text}")
            if match_amt:
                amt = float(match_amt.group(1).replace(",", ""))
                self.assertEqual(amt, expected_amt)
            self.assertEqual(bool(DEBIT_FLAG_REGEX.search(text)), is_debit)

    def test_credit_inflow_flagging(self) -> None:
        """Verify credit alerts are identified and not flagged as debits."""
        credit_alerts = [
            "Your A/c ending 1234 is credited with Rs 50,000.00 on 01-Nov-26 towards Salary",
            "Cashback of Rs 25.00 received from Google Pay",
            "Refund of Rs 499.00 deposited to your HDFC Bank account",
        ]
        for text in credit_alerts:
            self.assertTrue(bool(CREDIT_FLAG_REGEX.search(text)), f"Failed credit detection: {text}")

    def test_otp_suppression(self) -> None:
        """Verify OTP messages are matched for suppression."""
        otp_alerts = [
            "Your OTP is 458921 for SBI netbanking. Do not share with anyone.",
            "One-time password for transaction of Rs 500 is 123456.",
            "Verification code: 998877. Valid for 10 minutes.",
            "Auth code 654321 for your card payment.",
        ]
        for text in otp_alerts:
            self.assertTrue(bool(OTP_REGEX.search(text)), f"Failed OTP suppression: {text}")


class TestChallengerSyncQueueAndVoid(unittest.TestCase):
    """Stress tests for SyncQueue transitions, operations, and void zeroing."""

    def test_in_place_void_zeroes_all_col_g_and_preserves_row(self) -> None:
        """Verify in-place void zeros Total Amount (Col G), My Share (Col I), Owed (Col J)."""
        origin = Transaction(
            id="txn_test_void",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Team Lunch",
            category="Food & Drink",
            total_amount=1200.00,
            my_share=400.00,
            owed_to_me=800.00,
            status=TransactionStatus.PENDING,
            package_name="com.phonepe.app",
        )

        voided = VoidEngine.apply_void(origin)
        self.assertEqual(voided.total_amount, 0.00)
        self.assertEqual(voided.my_share, 0.00)
        self.assertEqual(voided.owed_to_me, 0.00)
        self.assertEqual(voided.status, TransactionStatus.VOID)
        self.assertTrue(voided.item_description.startswith("[Void]"))
        self.assertEqual(voided.id, origin.id)  # UUID preserved

    def test_sync_queue_entity_schema_invariants(self) -> None:
        """Verify SyncQueueEntity declarations and valid operation/status constants."""
        entity_path = (
            ROOT_DIR / "app/src/main/java/com/financeautomation/data/local/entity/SyncQueueEntity.kt"
        )
        content = entity_path.read_text(encoding="utf-8")

        # Operations
        self.assertIn("OP_INSERT", content)
        self.assertIn("OP_UPDATE", content)
        self.assertIn("OP_VOID", content)

        # Statuses
        self.assertIn("STATUS_PENDING", content)
        self.assertIn("STATUS_IN_PROGRESS", content)
        self.assertIn("STATUS_FAILED", content)
        self.assertIn("STATUS_COMPLETED", content)

        # Columns
        self.assertIn("transactionId", content)
        self.assertIn("operationType", content)
        self.assertIn("payloadJson", content)
        self.assertIn("retryCount", content)


class TestChallengerSourceInvariants(unittest.TestCase):
    """Stress test invariants across the entire codebase."""

    def test_zero_double_bang_in_all_source(self) -> None:
        """Verify zero double-bang (!!) in app/src/."""
        violations = []
        for kt in (ROOT_DIR / "app" / "src").rglob("*.kt"):
            content = kt.read_text(encoding="utf-8", errors="ignore")
            for idx, line in enumerate(content.splitlines(), start=1):
                stripped = line.strip()
                if stripped.startswith(("//", "*")):
                    continue
                if "!!" in line:
                    violations.append(f"{kt.relative_to(ROOT_DIR)}:{idx}: {stripped}")
        self.assertEqual(len(violations), 0, "Found double-bang occurrences: \n" + "\n".join(violations))

    def test_zero_emojis_in_source_docs_and_tests(self) -> None:
        """Verify zero emoji characters in all files."""
        emoji_pattern = re.compile(r"[\U00010000-\U0010ffff]|[\u2600-\u27bf]")
        checked_dirs = ["app/src", "docs", "tests"]
        violations = []
        for d in checked_dirs:
            p = ROOT_DIR / d
            if not p.exists():
                continue
            for f in p.rglob("*"):
                if f.is_file() and f.suffix in [".kt", ".java", ".py", ".md", ".xml", ".gradle", ".kts"]:
                    try:
                        content = f.read_text(encoding="utf-8")
                    except UnicodeDecodeError:
                        continue
                    for idx, line in enumerate(content.splitlines(), start=1):
                        if emoji_pattern.search(line):
                            violations.append(f"{f.relative_to(ROOT_DIR)}:{idx}: {line[:60]}")
        self.assertEqual(len(violations), 0, "Found emoji occurrences: \n" + "\n".join(violations))

    def test_zero_utr_or_raw_notification_storage(self) -> None:
        """Verify zero UTR or raw text fields in persistent entities or DAO queries."""
        entity_file = ROOT_DIR / "app/src/main/java/com/financeautomation/data/local/entity/TransactionEntity.kt"
        content = entity_file.read_text(encoding="utf-8")
        self.assertNotIn("rawTitle", content)
        self.assertNotIn("rawText", content)
        self.assertNotIn("utr", content.lower())


if __name__ == "__main__":
    unittest.main()
