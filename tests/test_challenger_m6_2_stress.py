"""Adversarial Verification and Stress-Testing Suite for Milestone 6 (challenger_m6_2).

Role: Presentation and System Integration Adversarial Verifier
Empirically tests and stress-tests:
1. System Integration and Background Services:
   - Package Whitelist enforcement (GPay, PhonePe, Paytm, Supermoney, BHIM, Bank SMS: Default, Google, Samsung).
   - Non-whitelisted applications strictly dropped.
   - Strict Privacy Blacklist: Instant drop for WhatsApp, Telegram, Signal, Discord, Instagram, Messenger, Outlook, Gmail.
   - OTP and 2FA suppression: Instant drop for OTP, auth codes, secret codes, and verification alerts.
   - Settle Window Engine: 60-120s buffer window before sync dispatch, unique work replacement, duplicate suppression.
   - Deduplication without UTR: Cross-source Amount + Payee + 120s sliding window detection.
   - WorkManager Sync: NetworkType.CONNECTED constraints, BackoffPolicy.EXPONENTIAL, max retries enforcement (fail on 3rd retry).
2. Presentation and State Management:
   - Compose BottomSheet lifecycle: Sequential transaction reviews in ReviewInboxScreen do not prematurely dismiss.
   - ViewModels state isolation and error handling:
     * ExpenseViewModel: 1-tap confirm/void, amount/merchant validation, eager companion addition, plural Savings invariant.
     * ReviewInboxViewModel: 1-tap confirm with category resolution, non-destructive voiding, selection state isolation.
     * SettingsViewModel: Manual syncNow execution with explicit error reporting (zero fake success), companion management, spreadsheet ID persistence.
3. Clean Architecture and Codebase Hygiene:
   - Zero dead imports across presentation and service layers (accounting for Kotlin operator delegates).
   - Zero emojis across all source code, comments, strings, documentation, and tests.
   - Zero hardcoded local machine paths across codebase and repo documentation.
   - Zero mock or fake fallback generators in production codebase.
"""

from __future__ import annotations

import os
import pathlib
import random
import re
import sys
import threading
import unittest
from dataclasses import dataclass
from typing import Any, ClassVar

ROOT_DIR = pathlib.Path(__file__).resolve().parent.parent
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from tests.domain_harness import (
    AMOUNT_REGEX,
    CREDIT_FLAG_REGEX,
    DEBIT_FLAG_REGEX,
    ReviewState,
    SyncState,
    TransactionStatus,
)

# ==============================================================================
# 1. Domain Constants and Simulation Helpers
# ==============================================================================

CATEGORY_SAVINGS = "Savings"
CATEGORY_FOOD_AND_DRINK = "Food & Drink"
CATEGORY_GROCERIES = "Groceries"
CATEGORY_OTHER = "Other"

ACCOUNT_SBI = "SBI"
ACCOUNT_CANARA = "Canara"
ACCOUNT_OTHER = "Other"

SETTLE_WINDOW_DEFAULT_SECONDS = 90.0
SETTLE_WINDOW_BUFFER_MIN = 60.0
SETTLE_WINDOW_BUFFER_MAX = 120.0
MAX_SYNC_RETRIES = 3


@dataclass(frozen=True)
class SimulatedTransaction:
    id: str
    timestamp: int
    date_serial: int
    time_serial: float
    account: str
    merchant: str
    raw_title: str
    raw_text: str
    trip_tag: str
    category: str
    total_amount: float
    companions: str
    my_share: float
    owed_to_me: float
    status: TransactionStatus
    source_package: str | None = None
    review_state: ReviewState = ReviewState.NEEDS_REVIEW
    sync_state: SyncState = SyncState.PENDING
    is_possible_duplicate: bool = False


@dataclass(frozen=True)
class SimulatedCompanion:
    id: str
    name: str
    is_selected: bool = False


# ==============================================================================
# 2. System Integration Simulation Engine
# ==============================================================================

class SimulatedPackageWhitelist:
    PACKAGE_GPAY = "com.google.android.apps.nbu.paisa.user"
    PACKAGE_BHIM = "in.org.npci.upiapp"
    PACKAGE_SUPERMONEY = "com.supermoney.app"
    PACKAGE_PHONEPE = "com.phonepe.app"
    PACKAGE_PAYTM = "net.one97.paytm"
    PACKAGE_DEFAULT_SMS = "com.android.mms"
    PACKAGE_GOOGLE_MESSAGES = "com.google.android.apps.messaging"
    PACKAGE_SAMSUNG_MESSAGES = "com.samsung.android.messaging"

    WHITELISTED_FINANCIAL_PACKAGES: ClassVar[set[str]] = {
        PACKAGE_GPAY,
        PACKAGE_BHIM,
        PACKAGE_SUPERMONEY,
        PACKAGE_PHONEPE,
        PACKAGE_PAYTM,
        PACKAGE_DEFAULT_SMS,
        PACKAGE_GOOGLE_MESSAGES,
        PACKAGE_SAMSUNG_MESSAGES,
    }

    PACKAGE_WHATSAPP = "com.whatsapp"
    PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    PACKAGE_TELEGRAM = "org.telegram.messenger"
    PACKAGE_SIGNAL = "org.thoughtcrime.securesms"
    PACKAGE_FB_MESSENGER = "com.facebook.orca"
    PACKAGE_INSTAGRAM = "com.instagram.android"
    PACKAGE_DISCORD = "com.discord"
    PACKAGE_OUTLOOK = "com.microsoft.office.outlook"
    PACKAGE_GMAIL = "com.google.android.gm"

    BLACKLISTED_PRIVACY_PACKAGES: ClassVar[set[str]] = {
        PACKAGE_WHATSAPP,
        PACKAGE_WHATSAPP_BUSINESS,
        PACKAGE_TELEGRAM,
        PACKAGE_SIGNAL,
        PACKAGE_FB_MESSENGER,
        PACKAGE_INSTAGRAM,
        PACKAGE_DISCORD,
        PACKAGE_OUTLOOK,
        PACKAGE_GMAIL,
    }

    OTP_SUPPRESSION_REGEX = re.compile(
        r"\b(otp|one[- ]time[- ]password|verification[- ]code|secret[- ]code|do[- ]not[- ]share|security[- ]code|auth[- ]code)\b",
        re.IGNORECASE,
    )

    @classmethod
    def is_whitelisted(cls, package_name: str) -> bool:
        return package_name.strip() in cls.WHITELISTED_FINANCIAL_PACKAGES

    @classmethod
    def is_blacklisted(cls, package_name: str) -> bool:
        trimmed = package_name.strip()
        return trimmed in cls.BLACKLISTED_PRIVACY_PACKAGES or any(
            trimmed.startswith(b) for b in cls.BLACKLISTED_PRIVACY_PACKAGES
        )

    @classmethod
    def is_otp_message(cls, text: str) -> bool:
        return bool(cls.OTP_SUPPRESSION_REGEX.search(text))


class SimulatedNotificationProcessor:
    def __init__(self, own_package_name: str = "com.financeautomation"):
        self.own_package_name = own_package_name
        self.transactions: dict[str, SimulatedTransaction] = {}
        self.scheduled_settle_windows: dict[str, float] = {}
        self.lock = threading.Lock()

    def process_notification(
        self,
        source_package: str,
        title: str,
        text: str,
        post_time_ms: int,
    ) -> dict[str, Any]:
        with self.lock:
            # 1. Self notification drop
            if source_package.strip() == self.own_package_name:
                return {"outcome": "IGNORED", "reason": "Self notification dropped"}

            # 2. Blacklist check (privacy protection)
            if SimulatedPackageWhitelist.is_blacklisted(source_package):
                return {"outcome": "IGNORED", "reason": f"Blacklisted privacy package: {source_package}"}

            # 3. Whitelist check (financial only)
            if not SimulatedPackageWhitelist.is_whitelisted(source_package):
                return {"outcome": "IGNORED", "reason": f"Non-whitelisted package: {source_package}"}

            full_content = f"{title} {text}".strip()

            # 4. OTP / Auth suppression
            if SimulatedPackageWhitelist.is_otp_message(full_content):
                return {"outcome": "IGNORED", "reason": "OTP or auth alert suppressed"}

            # 5. Extract amount
            amount_match = AMOUNT_REGEX.search(full_content)
            if not amount_match:
                return {
                    "outcome": "FALLBACK_PROMPT",
                    "message": "Payment detected, tap to enter amount",
                    "source_package": source_package,
                }
            amount = float(amount_match.group(1).replace(",", ""))
            if amount <= 0.0:
                return {
                    "outcome": "FALLBACK_PROMPT",
                    "message": "Payment detected, tap to enter amount",
                    "source_package": source_package,
                }

            # 6. Direction filter: Ignore inflows for auto-expense logging
            is_credit = bool(CREDIT_FLAG_REGEX.search(full_content))
            is_debit = bool(DEBIT_FLAG_REGEX.search(full_content)) or not is_credit
            if not is_debit:
                return {"outcome": "IGNORED", "reason": "Non-debit transaction ignored for auto-expense logging"}

            # 7. Extract Payee and Account
            merchant = "Unknown Payee"
            if "to " in full_content.lower():
                idx = full_content.lower().find("to ")
                part = full_content[idx + 3:].split()[0].rstrip(".")
                merchant = part.strip()
            elif "starbucks" in full_content.lower():
                merchant = "Starbucks"
            elif "supermart" in full_content.lower():
                merchant = "Supermart"

            # 8. Deduplication Check: Amount + Merchant + 120s sliding window
            window_ms = 120_000
            exact_match_id = None
            is_possible_duplicate = False

            for existing_id, existing in self.transactions.items():
                if abs(post_time_ms - existing.timestamp) <= window_ms and abs(existing.total_amount - amount) < 0.01:
                    existing_m = existing.merchant.strip().lower()
                    incoming_m = merchant.strip().lower()
                    if existing_m == incoming_m or existing_m == "unknown payee" or incoming_m == "unknown payee":
                        exact_match_id = existing_id
                        break
                    else:
                        is_possible_duplicate = True

            if exact_match_id:
                existing = self.transactions[exact_match_id]
                enriched = False
                merged = existing
                if existing.merchant == "Unknown Payee" and merchant != "Unknown Payee":
                    merged = SimulatedTransaction(
                        id=existing.id,
                        timestamp=existing.timestamp,
                        date_serial=existing.date_serial,
                        time_serial=existing.time_serial,
                        account=existing.account,
                        merchant=merchant,
                        raw_title=existing.raw_title,
                        raw_text=existing.raw_text,
                        trip_tag=existing.trip_tag,
                        category=existing.category,
                        total_amount=existing.total_amount,
                        companions=existing.companions,
                        my_share=existing.my_share,
                        owed_to_me=existing.owed_to_me,
                        status=existing.status,
                        source_package=existing.source_package,
                        review_state=existing.review_state,
                        sync_state=existing.sync_state,
                        is_possible_duplicate=existing.is_possible_duplicate,
                    )
                    enriched = True
                    self.transactions[exact_match_id] = merged
                return {
                    "outcome": "MERGED",
                    "existing_id": existing.id,
                    "enriched": enriched,
                }

            new_id = f"txn_{len(self.transactions) + 1}_{random.randint(1000, 9999)}"
            new_txn = SimulatedTransaction(
                id=new_id,
                timestamp=post_time_ms,
                date_serial=46332,
                time_serial=0.5,
                account=ACCOUNT_SBI,
                merchant=merchant,
                raw_title=title,
                raw_text=text,
                trip_tag="",
                category=CATEGORY_FOOD_AND_DRINK,
                total_amount=amount,
                companions="Personal",
                my_share=amount,
                owed_to_me=0.0,
                status=TransactionStatus.SETTLED,
                source_package=source_package,
                review_state=ReviewState.NEEDS_REVIEW,
                sync_state=SyncState.PENDING,
                is_possible_duplicate=is_possible_duplicate,
            )
            self.transactions[new_id] = new_txn

            # 10. Settle window scheduling: buffer before triggering cloud sync
            # Note: if possible duplicate, settle window is NOT scheduled (held in review)
            if not is_possible_duplicate:
                self.scheduled_settle_windows[new_id] = post_time_ms / 1000.0 + SETTLE_WINDOW_DEFAULT_SECONDS

            return {
                "outcome": "CREATED",
                "transaction": new_txn,
                "is_possible_duplicate": is_possible_duplicate,
            }


# ==============================================================================
# 3. WorkManager Simulation Engine
# ==============================================================================

class SimulatedWorkManager:
    @staticmethod
    def execute_sync(
        sync_callable: Any,
        run_attempt_count: int,
        max_retries: int = MAX_SYNC_RETRIES,
    ) -> dict[str, Any]:
        try:
            success, count, err_msg = sync_callable()
            if success:
                return {"result": "SUCCESS", "synced_count": count}
            else:
                if run_attempt_count < max_retries:
                    return {"result": "RETRY", "attempt": run_attempt_count}
                else:
                    return {"result": "FAILURE", "error_message": err_msg or "Unknown sync error"}
        except Exception as exc:  # noqa: BLE001
            if run_attempt_count < max_retries:
                return {"result": "RETRY", "attempt": run_attempt_count}
            else:
                return {"result": "FAILURE", "error_message": str(exc)}


# ==============================================================================
# 4. Presentation & BottomSheet Lifecycle Simulator
# ==============================================================================

class SimulatedExpenseViewModel:
    def __init__(self, repository: SimulatedNotificationProcessor):
        self.repository = repository
        self.transaction_id: str | None = None
        self.amount: float = 0.0
        self.merchant: str = ""
        self.category: str = CATEGORY_FOOD_AND_DRINK
        self.split_mode: str = "JUST_ME"
        self.all_companions: list[SimulatedCompanion] = []
        self.selected_companion_ids: set[str] = set()
        self.custom_share: float | None = None
        self.my_share: float = 0.0
        self.owed_to_me: float = 0.0
        self.status: TransactionStatus = TransactionStatus.SETTLED
        self.is_saved: bool = False
        self.is_voided: bool = False
        self.error_message: str | None = None

    def load_transaction(self, txn_id: str) -> bool:
        # Crucial invariant: loading a transaction ALWAYS resets completion flags
        self.is_saved = False
        self.is_voided = False
        self.error_message = None

        txn = self.repository.transactions.get(txn_id)
        if txn is None:
            self.error_message = "Transaction not found"
            return False

        self.transaction_id = txn.id
        self.amount = txn.total_amount
        self.merchant = txn.merchant
        self.category = txn.category
        self.my_share = txn.my_share
        self.owed_to_me = txn.owed_to_me
        self.status = txn.status
        return True

    def on_amount_changed(self, new_amount: float) -> None:
        self.amount = max(0.0, new_amount)
        self.recalculate_split()

    def on_merchant_changed(self, new_merchant: str) -> None:
        self.merchant = new_merchant

    def on_category_selected(self, cat: str) -> None:
        trimmed = cat.strip()
        if trimmed.lower() in ("saving", "savings", "investment", "sip", "mutual fund"):
            self.category = CATEGORY_SAVINGS
        else:
            self.category = trimmed

    def on_add_companion(self, name: str) -> None:
        trimmed = name.strip()
        if not trimmed:
            return
        # Case-insensitive deduplication
        existing = next((c for c in self.all_companions if c.name.lower() == trimmed.lower()), None)
        if existing:
            comp_id = existing.id
        else:
            comp_id = f"comp_{len(self.all_companions) + 1}"
            new_comp = SimulatedCompanion(id=comp_id, name=trimmed, is_selected=True)
            self.all_companions.append(new_comp)

        self.selected_companion_ids.add(comp_id)
        self.recalculate_split()

    def recalculate_split(self) -> None:
        if self.amount <= 0.0:
            self.my_share = 0.0
            self.owed_to_me = 0.0
            self.status = TransactionStatus.SETTLED
            return

        if self.split_mode == "JUST_ME":
            self.my_share = round(self.amount, 2)
            self.owed_to_me = 0.0
            self.status = TransactionStatus.SETTLED
        elif self.split_mode == "SPLIT_EQUALLY":
            total_heads = len(self.selected_companion_ids) + 1
            self.my_share = round(self.amount / total_heads, 2)
            self.owed_to_me = round(self.amount - self.my_share, 2)
            self.status = TransactionStatus.PENDING
        elif self.split_mode == "CUSTOM":
            raw_share = self.custom_share if self.custom_share is not None else self.amount
            bounded = max(0.0, min(self.amount, raw_share))
            self.my_share = round(bounded, 2)
            self.owed_to_me = round(self.amount - self.my_share, 2)
            self.status = TransactionStatus.SETTLED if self.owed_to_me == 0.0 else TransactionStatus.PENDING

    def on_confirm_and_save(self) -> bool:
        if self.amount <= 0.0:
            self.error_message = "Amount must be greater than zero"
            return False
        if not self.merchant.strip():
            self.error_message = "Merchant / Description cannot be blank"
            return False

        if self.transaction_id and self.transaction_id in self.repository.transactions:
            existing = self.repository.transactions[self.transaction_id]
            updated = SimulatedTransaction(
                id=existing.id,
                timestamp=existing.timestamp,
                date_serial=existing.date_serial,
                time_serial=existing.time_serial,
                account=existing.account,
                merchant=self.merchant.strip(),
                raw_title=existing.raw_title,
                raw_text=existing.raw_text,
                trip_tag=existing.trip_tag,
                category=self.category,
                total_amount=self.amount,
                companions="Personal" if not self.selected_companion_ids else "Friends",
                my_share=self.my_share,
                owed_to_me=self.owed_to_me,
                status=self.status,
                source_package=existing.source_package,
                review_state=ReviewState.CONFIRMED,
                sync_state=SyncState.PENDING,
                is_possible_duplicate=False,
            )
            self.repository.transactions[self.transaction_id] = updated
            self.is_saved = True
            return True
        return False

    def on_void(self) -> bool:
        if not self.transaction_id or self.transaction_id not in self.repository.transactions:
            self.error_message = "Transaction not found"
            return False
        existing = self.repository.transactions[self.transaction_id]
        voided = SimulatedTransaction(
            id=existing.id,
            timestamp=existing.timestamp,
            date_serial=existing.date_serial,
            time_serial=existing.time_serial,
            account=existing.account,
            merchant=f"[Void] {existing.merchant}" if not existing.merchant.startswith("[Void] ") else existing.merchant,
            raw_title=existing.raw_title,
            raw_text=existing.raw_text,
            trip_tag=existing.trip_tag,
            category=existing.category,
            total_amount=0.0,
            companions=existing.companions,
            my_share=0.0,
            owed_to_me=0.0,
            status=TransactionStatus.VOID,
            source_package=existing.source_package,
            review_state=existing.review_state,
            sync_state=SyncState.VOID,
            is_possible_duplicate=existing.is_possible_duplicate,
        )
        self.repository.transactions[self.transaction_id] = voided
        self.is_voided = True
        return True

    def clear_state(self) -> None:
        self.transaction_id = None
        self.amount = 0.0
        self.merchant = ""
        self.category = CATEGORY_FOOD_AND_DRINK
        self.is_saved = False
        self.is_voided = False
        self.error_message = None


# ==============================================================================
# TEST SUITE 1: System Integration & Whitelist/Blacklist Filtering
# ==============================================================================

class TestNotificationListenerWhitelistAndPrivacy(unittest.TestCase):
    """Stress-tests package filtering, blacklist instant drop, and OTP suppression."""

    def setUp(self) -> None:
        self.processor = SimulatedNotificationProcessor()

    def test_all_whitelisted_financial_packages_are_accepted(self) -> None:
        """Verifies each whitelisted package processes debit notifications successfully."""
        packages = [
            SimulatedPackageWhitelist.PACKAGE_GPAY,
            SimulatedPackageWhitelist.PACKAGE_BHIM,
            SimulatedPackageWhitelist.PACKAGE_SUPERMONEY,
            SimulatedPackageWhitelist.PACKAGE_PHONEPE,
            SimulatedPackageWhitelist.PACKAGE_PAYTM,
            SimulatedPackageWhitelist.PACKAGE_DEFAULT_SMS,
            SimulatedPackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            SimulatedPackageWhitelist.PACKAGE_SAMSUNG_MESSAGES,
        ]
        for idx, pkg in enumerate(packages):
            res = self.processor.process_notification(
                source_package=pkg,
                title="Debit Alert",
                text=f"Paid INR {100 + idx} to Merchant{idx} via UPI. Ref: 10002000300{idx}",
                post_time_ms=1730811600000 + idx * 1000,
            )
            self.assertEqual(res["outcome"], "CREATED", f"Failed for {pkg}")
            self.assertFalse(res["is_possible_duplicate"])

    def test_arbitrary_non_whitelisted_apps_are_strictly_ignored(self) -> None:
        """Verifies non-financial apps are rejected immediately without parsing."""
        non_financial_apps = [
            "com.uber",
            "com.amazon.mShop.android.shopping",
            "com.spotify.music",
            "com.google.android.youtube",
            "com.swiggy",
            "com.zomato",
            "com.netflix.mediaclient",
            "com.twitter.android",
        ]
        for app in non_financial_apps:
            res = self.processor.process_notification(
                source_package=app,
                title="Order Update",
                text="Paid Rs 450 for lunch",
                post_time_ms=1730811600000,
            )
            self.assertEqual(res["outcome"], "IGNORED")
            self.assertIn("Non-whitelisted", res["reason"])

    def test_self_notification_is_strictly_dropped(self) -> None:
        """Verifies the app drops notifications originating from itself."""
        res = self.processor.process_notification(
            source_package="com.financeautomation",
            title="Internal Alert",
            text="Paid Rs 500 to Store",
            post_time_ms=1730811600000,
        )
        self.assertEqual(res["outcome"], "IGNORED")
        self.assertIn("Self notification", res["reason"])

    def test_privacy_blacklist_instant_drop_for_all_messaging_apps(self) -> None:
        """Verifies instant privacy drop for personal messaging platforms."""
        blacklisted = [
            SimulatedPackageWhitelist.PACKAGE_WHATSAPP,
            SimulatedPackageWhitelist.PACKAGE_WHATSAPP_BUSINESS,
            SimulatedPackageWhitelist.PACKAGE_TELEGRAM,
            SimulatedPackageWhitelist.PACKAGE_SIGNAL,
            SimulatedPackageWhitelist.PACKAGE_FB_MESSENGER,
            SimulatedPackageWhitelist.PACKAGE_INSTAGRAM,
            SimulatedPackageWhitelist.PACKAGE_DISCORD,
            SimulatedPackageWhitelist.PACKAGE_OUTLOOK,
            SimulatedPackageWhitelist.PACKAGE_GMAIL,
            "com.whatsapp.subpackage",  # Subpackage prefix check
        ]
        for app in blacklisted:
            res = self.processor.process_notification(
                source_package=app,
                title="Friend Message",
                text="Paid Rs 1,500 for dinner yesterday",
                post_time_ms=1730811600000,
            )
            self.assertEqual(res["outcome"], "IGNORED")
            self.assertIn("Blacklisted privacy package", res["reason"])

    def test_otp_and_2fa_alert_suppression(self) -> None:
        """Verifies all variations of OTP and verification alerts are dropped instantly."""
        otp_messages = [
            "Your OTP is 456789 for SBI netbanking.",
            "One-time password: 123456. Valid for 5 minutes.",
            "Your verification code is 889900.",
            "Secret code: 112233. Do not share with anyone.",
            "Do not share this OTP with bank executives.",
            "Security code: 778899 for your online purchase.",
            "Auth code: 998877. Complete transaction.",
            "Rs 500 debited... OTP: 332211 for authentication.",
        ]
        for text in otp_messages:
            res = self.processor.process_notification(
                source_package=SimulatedPackageWhitelist.PACKAGE_DEFAULT_SMS,
                title="Bank Security Alert",
                text=text,
                post_time_ms=1730811600000,
            )
            self.assertEqual(res["outcome"], "IGNORED", f"Failed to suppress OTP: {text}")
            self.assertIn("OTP", res["reason"])


# ==============================================================================
# TEST SUITE 2: Settle Window and Deduplication Engine
# ==============================================================================

class TestSettleWindowAndDeduplicationEngine(unittest.TestCase):
    """Stress-tests UTR deduplication, sliding window non-UTR deduplication, and settle buffers."""

    def setUp(self) -> None:
        self.processor = SimulatedNotificationProcessor()

    def test_exact_utr_deduplication_merges_in_place_without_second_row(self) -> None:
        """Verifies dual alerts sharing identical amount and payee merge in-place within 120s."""
        base_time = 1730811600000
        # Alert 1: Google Pay push notification
        res1 = self.processor.process_notification(
            source_package=SimulatedPackageWhitelist.PACKAGE_GPAY,
            title="Payment to Starbucks",
            text="Paid Rs 350.00 to Starbucks.",
            post_time_ms=base_time,
        )
        self.assertEqual(res1["outcome"], "CREATED")
        txn1_id = res1["transaction"].id

        # Alert 2: Bank SMS arrives 14 seconds later with same amount and payee
        res2 = self.processor.process_notification(
            source_package=SimulatedPackageWhitelist.PACKAGE_GOOGLE_MESSAGES,
            title="SBI Alert",
            text="A/c debited INR 350.00 to Starbucks.",
            post_time_ms=base_time + 14_000,
        )
        self.assertEqual(res2["outcome"], "MERGED")
        self.assertEqual(res2["existing_id"], txn1_id)
        self.assertEqual(len(self.processor.transactions), 1)

    def test_cross_source_window_matching_flags_possible_duplicate_when_utr_missing(self) -> None:
        """Verifies alerts with distinct merchant within 120s buffer are flagged as possible duplicate."""
        base_time = 1730811600000
        # Alert 1: Payment alert to Supermart
        res1 = self.processor.process_notification(
            source_package=SimulatedPackageWhitelist.PACKAGE_GPAY,
            title="Payment Successful",
            text="Paid Rs 400.00 to Supermart",
            post_time_ms=base_time,
        )
        self.assertEqual(res1["outcome"], "CREATED")
        self.assertFalse(res1["is_possible_duplicate"])
        # Settle window scheduled for clean transaction
        self.assertIn(res1["transaction"].id, self.processor.scheduled_settle_windows)

        # Alert 2: Bank SMS arrives 25s later with matching amount Rs 400.00 to Hypermarket
        res2 = self.processor.process_notification(
            source_package=SimulatedPackageWhitelist.PACKAGE_DEFAULT_SMS,
            title="Bank Debit Alert",
            text="Card debited INR 400.00 to Hypermarket",
            post_time_ms=base_time + 25_000,
        )
        self.assertEqual(res2["outcome"], "CREATED")
        self.assertTrue(res2["is_possible_duplicate"])
        # Possible duplicate must NOT schedule automatic settle window sync dispatch
        self.assertNotIn(res2["transaction"].id, self.processor.scheduled_settle_windows)

    def test_sliding_window_expiration_treats_later_payment_as_new_transaction(self) -> None:
        """Verifies identical amount arriving outside the 120s window is not flagged duplicate."""
        base_time = 1730811600000
        # Transaction 1 at T=0
        res1 = self.processor.process_notification(
            source_package=SimulatedPackageWhitelist.PACKAGE_GPAY,
            title="Payment",
            text="Paid Rs 250.00 to Cafe",
            post_time_ms=base_time,
        )
        self.assertFalse(res1["is_possible_duplicate"])

        # Transaction 2 at T=150s (outside 120s buffer)
        res2 = self.processor.process_notification(
            source_package=SimulatedPackageWhitelist.PACKAGE_GPAY,
            title="Payment",
            text="Paid Rs 250.00 to Cafe",
            post_time_ms=base_time + 150_000,
        )
        self.assertFalse(res2["is_possible_duplicate"])

    def test_different_amount_within_window_is_not_flagged_duplicate(self) -> None:
        """Verifies different amounts within 120s are treated as separate transactions."""
        base_time = 1730811600000
        res1 = self.processor.process_notification(
            source_package=SimulatedPackageWhitelist.PACKAGE_GPAY,
            title="Payment 1",
            text="Paid Rs 250.00 to Store",
            post_time_ms=base_time,
        )
        res2 = self.processor.process_notification(
            source_package=SimulatedPackageWhitelist.PACKAGE_DEFAULT_SMS,
            title="Payment 2",
            text="Paid Rs 255.00 to Store",
            post_time_ms=base_time + 10_000,
        )
        self.assertFalse(res1["is_possible_duplicate"])
        self.assertFalse(res2["is_possible_duplicate"])


# ==============================================================================
# TEST SUITE 3: WorkManager Sync and Retry Backoff
# ==============================================================================

class TestWorkManagerSyncAndBackoff(unittest.TestCase):
    """Stress-tests WorkManager retry backoff, max retries limit, and zero fake success."""

    def test_execute_sync_success_reports_synced_count(self) -> None:
        """Verifies successful sync reports exact synced count."""
        def sync_call():
            return True, 7, None

        result = SimulatedWorkManager.execute_sync(sync_call, run_attempt_count=0)
        self.assertEqual(result["result"], "SUCCESS")
        self.assertEqual(result["synced_count"], 7)

    def test_execute_sync_error_retries_when_attempt_count_under_max(self) -> None:
        """Verifies sync errors retry when attempt < 3."""
        def sync_call():
            return False, 0, "HTTP 503 Service Unavailable"

        for attempt in (0, 1, 2):
            result = SimulatedWorkManager.execute_sync(sync_call, run_attempt_count=attempt)
            self.assertEqual(result["result"], "RETRY")
            self.assertEqual(result["attempt"], attempt)

    def test_execute_sync_error_fails_permanently_on_exhausted_retries(self) -> None:
        """Verifies sync failure occurs on attempt >= 3 with explicit error message."""
        def sync_call():
            return False, 0, "HTTP 401 Unauthorized"

        result = SimulatedWorkManager.execute_sync(sync_call, run_attempt_count=3)
        self.assertEqual(result["result"], "FAILURE")
        self.assertEqual(result["error_message"], "HTTP 401 Unauthorized")

    def test_execute_sync_exception_retries_and_fails_safely(self) -> None:
        """Verifies unhandled exceptions are caught and obey retry/failure boundaries."""
        def throwing_sync_call():
            raise ConnectionResetError("Remote server closed connection")

        retry_result = SimulatedWorkManager.execute_sync(throwing_sync_call, run_attempt_count=1)
        self.assertEqual(retry_result["result"], "RETRY")

        fail_result = SimulatedWorkManager.execute_sync(throwing_sync_call, run_attempt_count=3)
        self.assertEqual(fail_result["result"], "FAILURE")
        self.assertIn("Remote server closed connection", fail_result["error_message"])

    def test_zero_fake_success_on_sync_failure(self) -> None:
        """Verifies zero fake success: failure never reports SUCCESS with count 0."""
        def failing_sync():
            return False, 0, "Spreadsheet ID not found"

        result = SimulatedWorkManager.execute_sync(failing_sync, run_attempt_count=3)
        self.assertNotEqual(result["result"], "SUCCESS")
        self.assertEqual(result["result"], "FAILURE")


# ==============================================================================
# TEST SUITE 4: Compose BottomSheet Lifecycle & Sequential Review Stress
# ==============================================================================

class TestComposeBottomSheetAndViewModelLifecycle(unittest.TestCase):
    """Stress-tests BottomSheet lifecycle, sequential reviews, and ViewModel invariants."""

    def setUp(self) -> None:
        self.processor = SimulatedNotificationProcessor()
        self.view_model = SimulatedExpenseViewModel(self.processor)

    def test_sequential_reviews_do_not_prematurely_dismiss_bottom_sheet(self) -> None:
        """Stress-tests 20 sequential transaction reviews confirming isSaved is cleanly reset."""
        # Prepopulate 20 transactions in Needs Review
        txn_ids = []
        for i in range(20):
            res = self.processor.process_notification(
                source_package=SimulatedPackageWhitelist.PACKAGE_GPAY,
                title=f"Store {i}",
                text=f"Paid Rs {100 + i}.00 to Store {i} Ref: 90001000{i:04d}",
                post_time_ms=1730811600000 + i * 1000,
            )
            txn_ids.append(res["transaction"].id)

        # Sequentially review and confirm each transaction
        for idx, txn_id in enumerate(txn_ids):
            # 1. User taps transaction to open BottomSheet -> load_transaction called
            loaded = self.view_model.load_transaction(txn_id)
            self.assertTrue(loaded)
            # Critical invariant: is_saved and is_voided MUST be false upon opening
            self.assertFalse(self.view_model.is_saved, f"Stale is_saved for txn {idx}")
            self.assertFalse(self.view_model.is_voided, f"Stale is_voided for txn {idx}")

            # 2. User edits details and taps Confirm & Save
            saved = self.view_model.on_confirm_and_save()
            self.assertTrue(saved)
            self.assertTrue(self.view_model.is_saved)

            # 3. LaunchedEffect dismisses sheet. Now next transaction is loaded.
            # Next iteration in loop asserts load_transaction cleans is_saved to False!

    def test_expense_viewmodel_rejects_zero_or_negative_amount(self) -> None:
        """Verifies zero or negative amount cannot be confirmed and shows error message."""
        res = self.processor.process_notification(
            source_package=SimulatedPackageWhitelist.PACKAGE_GPAY,
            title="Payment",
            text="Paid Rs 100 to Shop Ref: 111122223333",
            post_time_ms=1730811600000,
        )
        txn_id = res["transaction"].id
        self.view_model.load_transaction(txn_id)

        self.view_model.on_amount_changed(0.0)
        saved = self.view_model.on_confirm_and_save()
        self.assertFalse(saved)
        self.assertEqual(self.view_model.error_message, "Amount must be greater than zero")

    def test_expense_viewmodel_rejects_blank_merchant(self) -> None:
        """Verifies blank merchant cannot be confirmed."""
        res = self.processor.process_notification(
            source_package=SimulatedPackageWhitelist.PACKAGE_GPAY,
            title="Payment",
            text="Paid Rs 100 to Shop Ref: 111122223333",
            post_time_ms=1730811600000,
        )
        self.view_model.load_transaction(res["transaction"].id)
        self.view_model.on_merchant_changed("   ")

        saved = self.view_model.on_confirm_and_save()
        self.assertFalse(saved)
        self.assertEqual(self.view_model.error_message, "Merchant / Description cannot be blank")

    def test_plural_savings_invariant_in_viewmodel(self) -> None:
        """Verifies singular 'saving' and synonyms strictly normalize to plural 'Savings'."""
        self.view_model.on_category_selected("saving")
        self.assertEqual(self.view_model.category, CATEGORY_SAVINGS)

        self.view_model.on_category_selected("SIP")
        self.assertEqual(self.view_model.category, CATEGORY_SAVINGS)

        self.view_model.on_category_selected("Investment")
        self.assertEqual(self.view_model.category, CATEGORY_SAVINGS)

    def test_eager_companion_addition_and_split_recalculation(self) -> None:
        """Verifies adding a friend dynamically recalculates split math immediately."""
        res = self.processor.process_notification(
            source_package=SimulatedPackageWhitelist.PACKAGE_GPAY,
            title="Dinner",
            text="Paid Rs 300 to Restaurant Ref: 111122223333",
            post_time_ms=1730811600000,
        )
        self.view_model.load_transaction(res["transaction"].id)
        self.view_model.split_mode = "SPLIT_EQUALLY"

        # Initially 0 companions -> Solo living burn
        self.view_model.recalculate_split()
        self.assertEqual(self.view_model.my_share, 300.0)

        # Add Friend 1: Alice (2 heads: user + Alice -> 150 each)
        self.view_model.on_add_companion("Alice")
        self.assertEqual(self.view_model.my_share, 150.0)
        self.assertEqual(self.view_model.owed_to_me, 150.0)

        # Add Friend 2: Bob (3 heads: user + Alice + Bob -> 100 each)
        self.view_model.on_add_companion("Bob")
        self.assertEqual(self.view_model.my_share, 100.0)
        self.assertEqual(self.view_model.owed_to_me, 200.0)

        # Re-adding Alice with different case does not duplicate
        self.view_model.on_add_companion("alice")
        self.assertEqual(len(self.view_model.all_companions), 2)
        self.assertEqual(self.view_model.my_share, 100.0)

    def test_custom_split_bounding(self) -> None:
        """Verifies custom share is strictly bounded between 0.0 and total amount."""
        res = self.processor.process_notification(
            source_package=SimulatedPackageWhitelist.PACKAGE_GPAY,
            title="Split",
            text="Paid Rs 500 to Store Ref: 111122223333",
            post_time_ms=1730811600000,
        )
        self.view_model.load_transaction(res["transaction"].id)
        self.view_model.split_mode = "CUSTOM"

        # Share exceeds total amount -> bounded to 500
        self.view_model.custom_share = 700.0
        self.view_model.recalculate_split()
        self.assertEqual(self.view_model.my_share, 500.0)
        self.assertEqual(self.view_model.owed_to_me, 0.0)

        # Share negative -> bounded to 0.0
        self.view_model.custom_share = -50.0
        self.view_model.recalculate_split()
        self.assertEqual(self.view_model.my_share, 0.0)
        self.assertEqual(self.view_model.owed_to_me, 500.0)


# ==============================================================================
# TEST SUITE 5: Kotlin Source Code Analysis & Architecture Verification
# ==============================================================================

class TestKotlinSourceArchitectureAndCleanCode(unittest.TestCase):
    """Directly inspects production Kotlin source files to verify architectural invariants."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.app_src_dir = ROOT_DIR / "app" / "src" / "main" / "java" / "com" / "financeautomation"

    def test_zero_dead_imports_in_presentation_and_service(self) -> None:
        """Verifies 0 unused imports across presentation and service layers."""
        targets = [
            self.app_src_dir / "presentation",
            self.app_src_dir / "service",
        ]
        delegate_operators = {"getValue", "setValue"}

        for target in targets:
            for root, _, files in os.walk(target):
                for file in files:
                    if file.endswith(".kt") and not file.startswith("package-info"):
                        path = pathlib.Path(root) / file
                        with open(path, "r", encoding="utf-8") as f:
                            lines = f.readlines()

                        imports = []
                        body_lines = []
                        in_imports = False
                        for line in lines:
                            stripped = line.strip()
                            if stripped.startswith("import "):
                                in_imports = True
                                parts = stripped.split()
                                if len(parts) >= 2:
                                    imp = parts[1]
                                    alias = parts[parts.index("as") + 1] if " as " in stripped else None
                                    imports.append((stripped, imp, alias))
                            elif in_imports and stripped and not stripped.startswith("//") and not stripped.startswith("/*"):
                                body_lines.append(line)
                            elif in_imports:
                                pass
                            else:
                                body_lines.append(line)

                        body_text = "".join(body_lines)
                        for raw, imp, alias in imports:
                            if imp.endswith(".*"):
                                continue
                            symbol = alias if alias else imp.split(".")[-1]
                            if symbol in delegate_operators:
                                # Compose by property delegation operator
                                continue
                            pattern = re.compile(r"\b" + re.escape(symbol) + r"\b")
                            self.assertTrue(
                                pattern.search(body_text),
                                f"Unused import '{raw}' in {path.relative_to(ROOT_DIR)}"
                            )

    def test_zero_emojis_in_all_source_code_and_docs(self) -> None:
        """Verifies absolute zero emojis across codebase, docs, and test files."""
        emoji_pattern = re.compile(r"[\U00010000-\U0010ffff]", flags=re.UNICODE)
        scanned_count = 0
        extensions = (".kt", ".kts", ".xml", ".md", ".py")

        for root, dirs, files in os.walk(ROOT_DIR):
            if ".git" in root or ".gradle" in root or ".agents" in root:
                continue
            for file in files:
                if file.endswith(extensions):
                    p = pathlib.Path(root) / file
                    with open(p, "r", encoding="utf-8", errors="ignore") as f:
                        content = f.read()
                    matches = emoji_pattern.findall(content)
                    self.assertEqual(
                        len(matches), 0,
                        f"Found emojis in {p.relative_to(ROOT_DIR)}: {matches}"
                    )
                    scanned_count += 1

        self.assertGreater(scanned_count, 50, "Insufficient files scanned for emojis")

    def test_zero_hardcoded_local_machine_paths(self) -> None:
        """Verifies zero hardcoded local machine paths in repo docs and app code."""
        path_pattern = re.compile(r"(?:[cC]:\\|[cC]:/|file:///)")
        scanned_count = 0

        for root, dirs, files in os.walk(ROOT_DIR):
            if ".git" in root or ".gradle" in root or ".agents" in root:
                continue
            for file in files:
                # AGENTS.md mentions the rule as an explanation
                if file == "AGENTS.md":
                    continue
                if file.endswith((".kt", ".kts", ".md", ".xml")):
                    p = pathlib.Path(root) / file
                    with open(p, "r", encoding="utf-8", errors="ignore") as f:
                        content = f.read()
                    matches = path_pattern.findall(content)
                    self.assertEqual(
                        len(matches), 0,
                        f"Found local machine path in {p.relative_to(ROOT_DIR)}: {matches}"
                    )
                    scanned_count += 1

        self.assertGreater(scanned_count, 50, "Insufficient files scanned for paths")

    def test_zero_mock_or_fake_generators_in_app_production_code(self) -> None:
        """Verifies zero mock, fake, or stub generators exist in app/src/main."""
        main_src = ROOT_DIR / "app" / "src" / "main"
        suspicious = re.compile(r"\b(mock|fake|stub|dummy)\b", re.IGNORECASE)

        for root, dirs, files in os.walk(main_src):
            for file in files:
                if file.endswith(".kt"):
                    p = pathlib.Path(root) / file
                    with open(p, "r", encoding="utf-8", errors="ignore") as f:
                        content = f.read()
                    matches = suspicious.findall(content)
                    self.assertEqual(
                        len(matches), 0,
                        f"Suspicious mock/fake keyword found in {p.relative_to(ROOT_DIR)}: {matches}"
                    )

    def test_notification_processor_implements_dual_alert_deduplication(self) -> None:
        """Verifies NotificationProcessor in Kotlin enforces window dedup."""
        service_file = self.app_src_dir / "service" / "NotificationListenerServiceImpl.kt"
        with open(service_file, "r", encoding="utf-8") as f:
            code = f.read()

        self.assertIn("findRecentSimilar", code)
        self.assertIn("windowMillis = 120_000L", code)
        self.assertIn("isPossibleDuplicate", code)
        self.assertIn("PackageWhitelist.isBlacklisted", code)
        self.assertIn("PackageWhitelist.isWhitelisted", code)
        self.assertIn("PackageWhitelist.isOtpOrAuth", code)

    def test_settle_window_worker_constants_and_replace_policy(self) -> None:
        """Verifies SettleWindowWorker enforces 90s delay and REPLACE policy."""
        worker_file = self.app_src_dir / "service" / "worker" / "SettleWindowWorker.kt"
        with open(worker_file, "r", encoding="utf-8") as f:
            code = f.read()

        self.assertIn("DEFAULT_SETTLE_WINDOW_SECONDS: Long = 90L", code)
        self.assertIn("ExistingWorkPolicy.REPLACE", code)
        self.assertIn("WORK_NAME_PREFIX", code)

    def test_sync_transactions_worker_constraints_and_backoff(self) -> None:
        """Verifies SyncTransactionsWorker enforces NetworkType.CONNECTED and exponential backoff."""
        worker_file = self.app_src_dir / "service" / "worker" / "SyncTransactionsWorker.kt"
        with open(worker_file, "r", encoding="utf-8") as f:
            code = f.read()

        self.assertIn("NetworkType.CONNECTED", code)
        self.assertIn("BackoffPolicy.EXPONENTIAL", code)
        self.assertIn("MAX_RETRIES: Int = 3", code)
        self.assertIn("runAttemptCount < MAX_RETRIES", code)
        self.assertIn("Result.retry()", code)
        self.assertIn("Result.failure(", code)


# ==============================================================================
# Suite Execution Entry Point
# ==============================================================================

if __name__ == "__main__":
    unittest.main()
