"""Adversarial Verification & Stress-Testing Suite for Milestone 5 (Challenger M5.2).

Stress-tests:
1. ReviewInboxViewModel:
   - 1-tap confirm flow and state upgrade to ReviewState.CONFIRMED.
   - Category updates on confirm with strict plural 'Savings' enforcement (rejection/normalization of 'saving').
   - Duplicate alert handling (isPossibleDuplicate == true) and non-destructive voiding from inbox.
   - Error handling for missing transactions and repository failures.
   - Concurrency stress on confirm/void operations.
2. SettingsViewModel:
   - Spreadsheet ID update, whitespace trimming, and persistence.
   - Companion friend add/remove, case-insensitive duplicate rejection, and blank name rejection.
   - Manual syncNow flow: genuine execution and explicit error handling on network failure (Zero Fake Success).
3. QuickActionReceiver:
   - Hardened category resolution ensuring singular 'saving' (case-insensitive) strictly maps to 'Savings'.
   - Null and blank category fallback.
   - Non-existent transaction handled with NotFound error.
4. Repository Hygiene:
   - Zero emojis across all source code, comments, strings, documentation, and tests.
   - Zero hardcoded local machine paths.
"""

from __future__ import annotations

import concurrent.futures
import enum
import os
import re
import threading
import unittest
from dataclasses import dataclass, field

# ==============================================================================
# Domain Models and State Enums Mirroring Kotlin Codebase
# ==============================================================================

class ReviewState(enum.Enum):
    NEEDS_REVIEW = "NEEDS_REVIEW"
    CONFIRMED = "CONFIRMED"


class SyncState(enum.Enum):
    PENDING = "PENDING"
    SYNCED = "SYNCED"
    VOID = "VOID"
    FAILED = "FAILED"


class TransactionStatus(enum.Enum):
    SETTLED = "Settled"
    PENDING = "Pending"
    VOID = "Void"
    CARRIED = "Carried"


@dataclass(frozen=True)
class Companion:
    id: str
    name: str
    is_selected: bool = False


@dataclass(frozen=True)
class Transaction:
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
    utr: str | None = None
    source_package: str | None = None
    review_state: ReviewState = ReviewState.NEEDS_REVIEW
    sync_state: SyncState = SyncState.PENDING
    is_possible_duplicate: bool = False


# ==============================================================================
# Category Normalization & Plural Savings Invariants
# ==============================================================================

CATEGORY_SAVINGS = "Savings"
CATEGORY_FOOD_AND_DRINK = "Food & Drink"
CATEGORY_GROCERIES = "Groceries"
CATEGORY_SHOPPING = "Shopping"
CATEGORY_OTHER = "Other"
ACCOUNT_SBI = "SBI"
ACCOUNT_CANARA = "Canara"


def normalize_category_rule(category_name: str) -> str:
    """Mirrors CategoryConstants.normalizeCategory."""
    trimmed = category_name.strip()
    lower = trimmed.lower()
    if lower == "saving":
        raise ValueError("Singular 'Saving' is forbidden. Category must strictly be 'Savings'")
    if lower in ("savings", "investment", "sip", "mutual fund"):
        return CATEGORY_SAVINGS
    return trimmed


def resolve_category_hardened(category: str | None, fallback: str) -> str:
    """Mirrors the hardened resolution in QuickActionReceiver and ReviewInboxViewModel."""
    if category is not None and category.strip():
        trimmed = category.strip()
        if trimmed.lower() in ("saving", "savings"):
            return CATEGORY_SAVINGS
        try:
            return normalize_category_rule(trimmed)
        except (ValueError, KeyError):
            return trimmed
    return fallback


# ==============================================================================
# Simulated In-Memory Repositories
# ==============================================================================

class SimulatedTransactionRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._storage: dict[str, Transaction] = {}
        self.should_fail_update: bool = False
        self.should_fail_void: bool = False
        self.should_fail_sync: bool = False
        self.sync_failure_error: str | None = None
        self.sync_count_to_return: int = 0

    def insert(self, txn: Transaction) -> None:
        with self._lock:
            self._storage[txn.id] = txn

    def get_by_id(self, txn_id: str) -> Transaction | None:
        with self._lock:
            return self._storage.get(txn_id)

    def get_needs_review(self) -> list[Transaction]:
        with self._lock:
            return [t for t in self._storage.values() if t.review_state == ReviewState.NEEDS_REVIEW]

    def update(self, txn: Transaction) -> tuple[bool, str | None]:
        with self._lock:
            if self.should_fail_update:
                return False, "Database disk I/O error during update"
            self._storage[txn.id] = txn
            return True, None

    def void_transaction(self, txn_id: str) -> tuple[bool, str | None]:
        with self._lock:
            if self.should_fail_void:
                return False, "Failed to void transaction due to SQLite constraint"
            txn = self._storage.get(txn_id)
            if txn is None:
                return False, "Transaction not found"
            voided = Transaction(
                id=txn.id,
                timestamp=txn.timestamp,
                date_serial=txn.date_serial,
                time_serial=txn.time_serial,
                account=txn.account,
                merchant=txn.merchant,
                raw_title=txn.raw_title,
                raw_text=txn.raw_text,
                trip_tag=txn.trip_tag,
                category=txn.category,
                total_amount=txn.total_amount,
                companions=txn.companions,
                my_share=0.0,
                owed_to_me=0.0,
                status=TransactionStatus.VOID,
                utr=txn.utr,
                source_package=txn.source_package,
                review_state=txn.review_state,
                sync_state=SyncState.VOID,
                is_possible_duplicate=txn.is_possible_duplicate,
            )
            self._storage[txn_id] = voided
            return True, None

    def sync_pending_transactions(self, spreadsheet_id: str | None) -> tuple[bool, int, str | None]:
        with self._lock:
            if self.should_fail_sync:
                err = self.sync_failure_error or "Network timeout connecting to sheets.googleapis.com"
                return False, 0, err
            if not spreadsheet_id or not spreadsheet_id.strip():
                return False, 0, "Spreadsheet ID is not configured. Please connect or provision a Google Sheet in Settings."

            # ReviewState Gating: Only CONFIRMED transactions are eligible for cloud sync
            eligible = [
                t for t in self._storage.values()
                if t.sync_state == SyncState.PENDING and t.review_state == ReviewState.CONFIRMED
            ]
            for t in eligible:
                self._storage[t.id] = Transaction(
                    id=t.id,
                    timestamp=t.timestamp,
                    date_serial=t.date_serial,
                    time_serial=t.time_serial,
                    account=t.account,
                    merchant=t.merchant,
                    raw_title=t.raw_title,
                    raw_text=t.raw_text,
                    trip_tag=t.trip_tag,
                    category=t.category,
                    total_amount=t.total_amount,
                    companions=t.companions,
                    my_share=t.my_share,
                    owed_to_me=t.owed_to_me,
                    status=t.status,
                    utr=t.utr,
                    source_package=t.source_package,
                    review_state=t.review_state,
                    sync_state=SyncState.SYNCED,
                    is_possible_duplicate=t.is_possible_duplicate,
                )
            return True, len(eligible), None


class SimulatedPreferencesRepository:
    def __init__(self) -> None:
        self._spreadsheet_id: str | None = None
        self._default_account: str = ACCOUNT_SBI
        self._active_trip: str | None = None
        self._google_email: str | None = None

    def set_spreadsheet_id(self, sid: str) -> None:
        self._spreadsheet_id = sid.strip()

    def get_spreadsheet_id(self) -> str | None:
        return self._spreadsheet_id

    def set_default_account(self, acc: str) -> None:
        self._default_account = acc.strip()

    def get_default_account(self) -> str:
        return self._default_account

    def set_active_trip(self, trip: str | None) -> None:
        self._active_trip = trip.strip() if trip and trip.strip() else None

    def get_active_trip(self) -> str | None:
        return self._active_trip

    def set_google_account_email(self, email: str | None) -> None:
        self._google_email = email.strip() if email and email.strip() else None

    def get_google_account_email(self) -> str | None:
        return self._google_email


class SimulatedPeopleRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._companions: dict[str, Companion] = {}

    def insert(self, companion: Companion) -> None:
        with self._lock:
            self._companions[companion.id] = companion

    def get_by_name(self, name: str) -> Companion | None:
        with self._lock:
            target = name.strip().lower()
            for c in self._companions.values():
                if c.name.strip().lower() == target:
                    return c
            return None

    def get_by_id(self, cid: str) -> Companion | None:
        with self._lock:
            return self._companions.get(cid)

    def delete_by_id(self, cid: str) -> None:
        with self._lock:
            self._companions.pop(cid, None)

    def get_all(self) -> list[Companion]:
        with self._lock:
            return list(self._companions.values())


# ==============================================================================
# Simulated ViewModels & Receiver Logic Mirroring Kotlin Implementations
# ==============================================================================

@dataclass
class ReviewInboxUiState:
    items: list[Transaction] = field(default_factory=list)
    is_loading: bool = False
    selected_transaction: Transaction | None = None
    user_message: str | None = None
    error_message: str | None = None


class ReviewInboxViewModelLogic:
    def __init__(self, repository: SimulatedTransactionRepository) -> None:
        self.repository = repository
        self.ui_state = ReviewInboxUiState(is_loading=False)
        self.refresh()

    def refresh(self) -> None:
        self.ui_state.items = self.repository.get_needs_review()

    def confirm_transaction(self, txn_id: str, category: str | None = None) -> None:
        existing = self.repository.get_by_id(txn_id)
        if existing is None:
            self.ui_state.error_message = "Transaction not found"
            return

        resolved_category = resolve_category_hardened(category, existing.category)
        updated = Transaction(
            id=existing.id,
            timestamp=existing.timestamp,
            date_serial=existing.date_serial,
            time_serial=existing.time_serial,
            account=existing.account,
            merchant=existing.merchant,
            raw_title=existing.raw_title,
            raw_text=existing.raw_text,
            trip_tag=existing.trip_tag,
            category=resolved_category,
            total_amount=existing.total_amount,
            companions=existing.companions,
            my_share=existing.my_share,
            owed_to_me=existing.owed_to_me,
            status=existing.status,
            utr=existing.utr,
            source_package=existing.source_package,
            review_state=ReviewState.CONFIRMED,
            sync_state=SyncState.PENDING,
            is_possible_duplicate=False,  # Duplicate flag cleared on confirmation
        )
        success, error = self.repository.update(updated)
        if success:
            self.ui_state.user_message = "Transaction confirmed"
            self.refresh()
        else:
            self.ui_state.error_message = error or "Failed to confirm transaction"

    def void_transaction(self, txn_id: str) -> None:
        success, error = self.repository.void_transaction(txn_id)
        if success:
            self.ui_state.user_message = "Transaction voided"
            self.refresh()
        else:
            self.ui_state.error_message = error or "Failed to void transaction"

    def select_transaction(self, txn: Transaction) -> None:
        self.ui_state.selected_transaction = txn

    def clear_selection(self) -> None:
        self.ui_state.selected_transaction = None

    def clear_messages(self) -> None:
        self.ui_state.user_message = None
        self.ui_state.error_message = None


@dataclass
class SettingsUiState:
    spreadsheet_id: str = ""
    default_account: str = ACCOUNT_SBI
    active_trip_tag: str = ""
    google_account_email: str | None = None
    companions: list[Companion] = field(default_factory=list)
    is_syncing: bool = False
    sync_result: str | None = None
    user_message: str | None = None
    error_message: str | None = None


class SettingsViewModelLogic:
    def __init__(
        self,
        preferences_repo: SimulatedPreferencesRepository,
        people_repo: SimulatedPeopleRepository,
        transaction_repo: SimulatedTransactionRepository,
    ) -> None:
        self.preferences_repo = preferences_repo
        self.people_repo = people_repo
        self.transaction_repo = transaction_repo
        self.ui_state = SettingsUiState(
            spreadsheet_id=preferences_repo.get_spreadsheet_id() or "",
            default_account=preferences_repo.get_default_account(),
            active_trip_tag=preferences_repo.get_active_trip() or "",
            google_account_email=preferences_repo.get_google_account_email(),
            companions=people_repo.get_all(),
        )

    def on_spreadsheet_id_changed(self, new_id: str) -> None:
        self.preferences_repo.set_spreadsheet_id(new_id)
        self.ui_state.spreadsheet_id = self.preferences_repo.get_spreadsheet_id() or ""
        self.ui_state.user_message = "Spreadsheet ID saved"

    def on_default_account_changed(self, account: str) -> None:
        self.preferences_repo.set_default_account(account)
        self.ui_state.default_account = self.preferences_repo.get_default_account()

    def on_active_trip_tag_changed(self, tag: str) -> None:
        self.preferences_repo.set_active_trip(tag)
        self.ui_state.active_trip_tag = self.preferences_repo.get_active_trip() or ""

    def on_add_companion(self, name: str) -> None:
        trimmed = name.strip()
        if not trimmed:
            self.ui_state.error_message = "Friend name cannot be blank"
            return
        existing = self.people_repo.get_by_name(trimmed)
        if existing is not None:
            self.ui_state.error_message = "A companion with this name already exists"
            return
        new_id = f"comp_{len(self.people_repo.get_all()) + 1}"
        self.people_repo.insert(Companion(id=new_id, name=trimmed))
        self.ui_state.companions = self.people_repo.get_all()
        self.ui_state.user_message = f"Added companion {trimmed}"

    def on_remove_companion(self, companion_id: str) -> None:
        self.people_repo.delete_by_id(companion_id)
        self.ui_state.companions = self.people_repo.get_all()
        self.ui_state.user_message = "Companion removed"

    def on_sync_now(self) -> None:
        self.ui_state.is_syncing = True
        self.ui_state.sync_result = None
        self.ui_state.error_message = None

        sid = self.preferences_repo.get_spreadsheet_id()
        success, count, error = self.transaction_repo.sync_pending_transactions(sid)
        self.ui_state.is_syncing = False
        if success:
            self.ui_state.sync_result = f"Synced {count} transaction(s) to Google Sheets"
            self.ui_state.user_message = "Sync completed successfully"
        else:
            # Zero Fake Success: explicit error, sync_result remains None
            self.ui_state.sync_result = None
            self.ui_state.error_message = error or "Sync failed with unknown error"

    def clear_messages(self) -> None:
        self.ui_state.user_message = None
        self.ui_state.error_message = None


class QuickActionReceiverLogic:
    ACTION_QUICK_CATEGORIZE = "com.financeautomation.ACTION_QUICK_CATEGORIZE"
    ACTION_QUICK_CONFIRM = "com.financeautomation.ACTION_QUICK_CONFIRM"

    @classmethod
    def handle_action(
        cls,
        action: str,
        txn_id: str,
        category: str | None,
        repository: SimulatedTransactionRepository,
    ) -> tuple[str, Transaction | None, str | None]:
        existing = repository.get_by_id(txn_id)
        if existing is None:
            return "NotFound", None, None

        resolved_category = resolve_category_hardened(category, existing.category)
        updated = Transaction(
            id=existing.id,
            timestamp=existing.timestamp,
            date_serial=existing.date_serial,
            time_serial=existing.time_serial,
            account=existing.account,
            merchant=existing.merchant,
            raw_title=existing.raw_title,
            raw_text=existing.raw_text,
            trip_tag=existing.trip_tag,
            category=resolved_category,
            total_amount=existing.total_amount,
            companions=existing.companions,
            my_share=existing.my_share,
            owed_to_me=existing.owed_to_me,
            status=existing.status,
            utr=existing.utr,
            source_package=existing.source_package,
            review_state=ReviewState.CONFIRMED,
            sync_state=existing.sync_state,
            is_possible_duplicate=existing.is_possible_duplicate,
        )
        repository.update(updated)
        return "Success", updated, resolved_category


# ==============================================================================
# Test Cases
# ==============================================================================

class TestReviewInboxViewModelConfirmAndStateTransition(unittest.TestCase):
    """Stress tests for ReviewInboxViewModel 1-tap confirmation & state transitions."""

    def setUp(self) -> None:
        self.repo = SimulatedTransactionRepository()
        self.txn1 = Transaction(
            id="txn_001",
            timestamp=1730811600000,
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            merchant="Swiggy",
            raw_title="Debit Rs 350",
            raw_text="Paid Rs 350",
            trip_tag="",
            category="Food & Drink",
            total_amount=350.0,
            companions="Personal",
            my_share=350.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            review_state=ReviewState.NEEDS_REVIEW,
            sync_state=SyncState.PENDING,
            is_possible_duplicate=False,
        )
        self.txn2 = Transaction(
            id="txn_002",
            timestamp=1730811700000,
            date_serial=46331,
            time_serial=0.51,
            account="Canara",
            merchant="Amazon India",
            raw_title="Debit Rs 1200",
            raw_text="Paid Rs 1200",
            trip_tag="",
            category="Shopping",
            total_amount=1200.0,
            companions="Personal",
            my_share=1200.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            review_state=ReviewState.NEEDS_REVIEW,
            sync_state=SyncState.PENDING,
            is_possible_duplicate=True,
        )
        self.repo.insert(self.txn1)
        self.repo.insert(self.txn2)
        self.vm = ReviewInboxViewModelLogic(self.repo)

    def test_initial_state_loads_needs_review_items(self) -> None:
        self.assertEqual(2, len(self.vm.ui_state.items))
        self.assertFalse(self.vm.ui_state.is_loading)
        self.assertIsNone(self.vm.ui_state.user_message)
        self.assertIsNone(self.vm.ui_state.error_message)

    def test_1_tap_confirm_upgrades_to_confirmed_and_pending_sync(self) -> None:
        self.vm.confirm_transaction("txn_001")
        self.assertEqual("Transaction confirmed", self.vm.ui_state.user_message)
        self.assertIsNone(self.vm.ui_state.error_message)

        stored = self.repo.get_by_id("txn_001")
        self.assertIsNotNone(stored)
        self.assertEqual(ReviewState.CONFIRMED, stored.review_state)
        self.assertEqual(SyncState.PENDING, stored.sync_state)
        self.assertEqual("Food & Drink", stored.category)

        # Removed from inbox after refresh
        self.assertEqual(1, len(self.vm.ui_state.items))
        self.assertEqual("txn_002", self.vm.ui_state.items[0].id)

    def test_1_tap_confirm_with_category_update(self) -> None:
        self.vm.confirm_transaction("txn_001", "Groceries")
        stored = self.repo.get_by_id("txn_001")
        self.assertIsNotNone(stored)
        self.assertEqual("Groceries", stored.category)
        self.assertEqual(ReviewState.CONFIRMED, stored.review_state)

    def test_1_tap_confirm_plural_savings_normalization(self) -> None:
        variants = [
            ("saving", CATEGORY_SAVINGS),
            ("Saving", CATEGORY_SAVINGS),
            ("SAVING", CATEGORY_SAVINGS),
            ("sAvInG", CATEGORY_SAVINGS),
            ("  saving  ", CATEGORY_SAVINGS),
            ("savings", CATEGORY_SAVINGS),
            ("Savings", CATEGORY_SAVINGS),
            ("SAVINGS", CATEGORY_SAVINGS),
            ("Investment", CATEGORY_SAVINGS),
            ("investment", CATEGORY_SAVINGS),
            ("SIP", CATEGORY_SAVINGS),
            ("sip", CATEGORY_SAVINGS),
            ("mutual fund", CATEGORY_SAVINGS),
            ("Mutual Fund", CATEGORY_SAVINGS),
        ]
        for idx, (input_cat, expected) in enumerate(variants):
            txn_id = f"txn_savings_{idx}"
            self.repo.insert(Transaction(
                id=txn_id,
                timestamp=1730811600000 + idx,
                date_serial=46331,
                time_serial=0.5,
                account="SBI",
                merchant="Asset Management",
                raw_title="Investment",
                raw_text="SIP Paid",
                trip_tag="",
                category="Other",
                total_amount=5000.0,
                companions="Personal",
                my_share=5000.0,
                owed_to_me=0.0,
                status=TransactionStatus.SETTLED,
                review_state=ReviewState.NEEDS_REVIEW,
                sync_state=SyncState.PENDING,
            ))
            self.vm.confirm_transaction(txn_id, input_cat)
            stored = self.repo.get_by_id(txn_id)
            self.assertIsNotNone(stored)
            self.assertEqual(
                expected,
                stored.category,
                f"Failed for input '{input_cat}': expected {expected}, got {stored.category}",
            )
            self.assertEqual(ReviewState.CONFIRMED, stored.review_state)

    def test_confirm_non_existent_transaction_surfaces_error(self) -> None:
        self.vm.confirm_transaction("non_existent_id")
        self.assertEqual("Transaction not found", self.vm.ui_state.error_message)

    def test_confirm_repository_failure_surfaces_error(self) -> None:
        self.repo.should_fail_update = True
        self.vm.confirm_transaction("txn_001")
        self.assertIn("Database disk I/O error", self.vm.ui_state.error_message)


class TestReviewInboxDuplicateHandlingAndVoiding(unittest.TestCase):
    """Stress tests for duplicate banner clearing and non-destructive voiding."""

    def setUp(self) -> None:
        self.repo = SimulatedTransactionRepository()
        self.dup_txn = Transaction(
            id="txn_dup_100",
            timestamp=1730811800000,
            date_serial=46331,
            time_serial=0.52,
            account="SBI",
            merchant="Starbucks",
            raw_title="Debit Rs 450",
            raw_text="Paid Rs 450",
            trip_tag="",
            category="Food & Drink",
            total_amount=450.0,
            companions="Personal",
            my_share=450.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            review_state=ReviewState.NEEDS_REVIEW,
            sync_state=SyncState.PENDING,
            is_possible_duplicate=True,
        )
        self.repo.insert(self.dup_txn)
        self.vm = ReviewInboxViewModelLogic(self.repo)

    def test_duplicate_item_flag_detected(self) -> None:
        items = self.vm.ui_state.items
        self.assertEqual(1, len(items))
        self.assertTrue(items[0].is_possible_duplicate)

    def test_confirming_duplicate_clears_duplicate_flag(self) -> None:
        self.vm.confirm_transaction("txn_dup_100")
        stored = self.repo.get_by_id("txn_dup_100")
        self.assertIsNotNone(stored)
        self.assertEqual(ReviewState.CONFIRMED, stored.review_state)
        self.assertFalse(stored.is_possible_duplicate, "is_possible_duplicate must be cleared on confirm")

    def test_voiding_transaction_zeros_shares_and_sets_void_status(self) -> None:
        self.vm.void_transaction("txn_dup_100")
        self.assertEqual("Transaction voided", self.vm.ui_state.user_message)

        stored = self.repo.get_by_id("txn_dup_100")
        self.assertIsNotNone(stored)
        self.assertEqual(TransactionStatus.VOID, stored.status)
        self.assertEqual(0.0, stored.myShare if hasattr(stored, "myShare") else stored.my_share)
        self.assertEqual(0.0, stored.owedToMe if hasattr(stored, "owedToMe") else stored.owed_to_me)
        self.assertEqual(SyncState.VOID, stored.sync_state)

    def test_voiding_non_existent_transaction_surfaces_error(self) -> None:
        self.vm.void_transaction("txn_missing_999")
        self.assertEqual("Transaction not found", self.vm.ui_state.error_message)

    def test_concurrent_confirm_and_void_operations(self) -> None:
        # Pre-populate 50 transactions
        for i in range(50):
            self.repo.insert(Transaction(
                id=f"concurrent_txn_{i}",
                timestamp=1730811900000 + i,
                date_serial=46331,
                time_serial=0.53,
                account="SBI",
                merchant=f"Merchant {i}",
                raw_title="Debit",
                raw_text="Paid",
                trip_tag="",
                category="Food & Drink",
                total_amount=100.0 + i,
                companions="Personal",
                my_share=100.0 + i,
                owed_to_me=0.0,
                status=TransactionStatus.SETTLED,
                review_state=ReviewState.NEEDS_REVIEW,
                sync_state=SyncState.PENDING,
                is_possible_duplicate=(i % 2 == 0),
            ))

        def worker_task(idx: int) -> None:
            txn_id = f"concurrent_txn_{idx}"
            if idx % 3 == 0:
                self.vm.void_transaction(txn_id)
            else:
                self.vm.confirm_transaction(txn_id, "saving" if idx % 2 == 0 else "Shopping")

        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(worker_task, i) for i in range(50)]
            concurrent.futures.wait(futures)

        # Verify all transactions reached valid terminal states without corruptions
        for i in range(50):
            t = self.repo.get_by_id(f"concurrent_txn_{i}")
            self.assertIsNotNone(t)
            if i % 3 == 0:
                self.assertEqual(TransactionStatus.VOID, t.status)
                self.assertEqual(0.0, t.my_share)
            else:
                self.assertEqual(ReviewState.CONFIRMED, t.review_state)
                self.assertFalse(t.is_possible_duplicate)
                if i % 2 == 0:
                    self.assertEqual(CATEGORY_SAVINGS, t.category)
                else:
                    self.assertEqual("Shopping", t.category)


class TestSettingsViewModelSpreadsheetAndCompanions(unittest.TestCase):
    """Stress tests for SettingsViewModel preferences & companion management."""

    def setUp(self) -> None:
        self.pref_repo = SimulatedPreferencesRepository()
        self.people_repo = SimulatedPeopleRepository()
        self.txn_repo = SimulatedTransactionRepository()

        self.pref_repo.set_spreadsheet_id("sheet_master_001")
        self.pref_repo.set_default_account(ACCOUNT_SBI)
        self.pref_repo.set_active_trip("Goa Trip")
        self.pref_repo.set_google_account_email("user@gmail.com")

        self.people_repo.insert(Companion(id="c1", name="Aman"))
        self.people_repo.insert(Companion(id="c2", name="Priya"))

        self.vm = SettingsViewModelLogic(self.pref_repo, self.people_repo, self.txn_repo)

    def test_initial_state_loads_all_settings(self) -> None:
        self.assertEqual("sheet_master_001", self.vm.ui_state.spreadsheet_id)
        self.assertEqual(ACCOUNT_SBI, self.vm.ui_state.default_account)
        self.assertEqual("Goa Trip", self.vm.ui_state.active_trip_tag)
        self.assertEqual("user@gmail.com", self.vm.ui_state.google_account_email)
        self.assertEqual(2, len(self.vm.ui_state.companions))

    def test_spreadsheet_id_update_trims_whitespace_and_saves(self) -> None:
        self.vm.on_spreadsheet_id_changed("  sheet_new_updated_999  ")
        self.assertEqual("sheet_new_updated_999", self.pref_repo.get_spreadsheet_id())
        self.assertEqual("sheet_new_updated_999", self.vm.ui_state.spreadsheet_id)
        self.assertEqual("Spreadsheet ID saved", self.vm.ui_state.user_message)

    def test_default_account_and_trip_tag_update(self) -> None:
        self.vm.on_default_account_changed(ACCOUNT_CANARA)
        self.assertEqual(ACCOUNT_CANARA, self.pref_repo.get_default_account())
        self.assertEqual(ACCOUNT_CANARA, self.vm.ui_state.default_account)

        self.vm.on_active_trip_tag_changed("Manali Winter")
        self.assertEqual("Manali Winter", self.pref_repo.get_active_trip())

        # Empty trip tag sets to None
        self.vm.on_active_trip_tag_changed("   ")
        self.assertIsNone(self.pref_repo.get_active_trip())

    def test_add_companion_success(self) -> None:
        self.vm.on_add_companion("Vikram")
        self.assertEqual("Added companion Vikram", self.vm.ui_state.user_message)
        self.assertIsNone(self.vm.ui_state.error_message)

        c = self.people_repo.get_by_name("Vikram")
        self.assertIsNotNone(c)
        self.assertEqual("Vikram", c.name)
        self.assertEqual(3, len(self.vm.ui_state.companions))

    def test_add_companion_case_insensitive_duplicate_rejection(self) -> None:
        duplicate_attempts = [
            "Aman",
            "aman",
            "AMAN",
            "AmAn",
            "  Aman  ",
            "Priya",
            "priya",
            "PRIYA",
        ]
        for dup in duplicate_attempts:
            self.vm.clear_messages()
            self.vm.on_add_companion(dup)
            self.assertEqual(
                "A companion with this name already exists",
                self.vm.ui_state.error_message,
                f"Failed to reject duplicate: '{dup}'",
            )

    def test_add_companion_blank_rejection(self) -> None:
        blank_attempts = ["", " ", "   ", "\t", "\n"]
        for b in blank_attempts:
            self.vm.clear_messages()
            self.vm.on_add_companion(b)
            self.assertEqual(
                "Friend name cannot be blank",
                self.vm.ui_state.error_message,
                f"Failed to reject blank companion name: '{b}'",
            )

    def test_remove_companion(self) -> None:
        self.vm.on_remove_companion("c1")
        self.assertEqual("Companion removed", self.vm.ui_state.user_message)
        self.assertIsNone(self.people_repo.get_by_id("c1"))
        self.assertEqual(1, len(self.vm.ui_state.companions))


class TestSettingsViewModelSyncNowErrorHandling(unittest.TestCase):
    """Stress tests for manual cloud sync: Zero Fake Success guarantees."""

    def setUp(self) -> None:
        self.pref_repo = SimulatedPreferencesRepository()
        self.people_repo = SimulatedPeopleRepository()
        self.txn_repo = SimulatedTransactionRepository()
        self.pref_repo.set_spreadsheet_id("sheet_test_sync_id")

        # Add confirmed transaction
        self.txn_repo.insert(Transaction(
            id="txn_sync_001",
            timestamp=1730811600000,
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            merchant="BigBasket",
            raw_title="Debit",
            raw_text="Paid",
            trip_tag="",
            category="Groceries",
            total_amount=1500.0,
            companions="Personal",
            my_share=1500.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            review_state=ReviewState.CONFIRMED,
            sync_state=SyncState.PENDING,
        ))

        # Add unreviewed transaction (must be gated from cloud sync)
        self.txn_repo.insert(Transaction(
            id="txn_sync_unreviewed",
            timestamp=1730811600000,
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            merchant="Swiggy",
            raw_title="Debit",
            raw_text="Paid",
            trip_tag="",
            category="Food & Drink",
            total_amount=250.0,
            companions="Personal",
            my_share=250.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            review_state=ReviewState.NEEDS_REVIEW,
            sync_state=SyncState.PENDING,
        ))

        self.vm = SettingsViewModelLogic(self.pref_repo, self.people_repo, self.txn_repo)

    def test_sync_now_success_with_review_state_gating(self) -> None:
        self.vm.on_sync_now()

        self.assertFalse(self.vm.ui_state.is_syncing)
        self.assertEqual("Synced 1 transaction(s) to Google Sheets", self.vm.ui_state.sync_result)
        self.assertEqual("Sync completed successfully", self.vm.ui_state.user_message)
        self.assertIsNone(self.vm.ui_state.error_message)

        # Confirmed transaction was marked SYNCED
        t1 = self.txn_repo.get_by_id("txn_sync_001")
        self.assertIsNotNone(t1)
        self.assertEqual(SyncState.SYNCED, t1.sync_state)

        # Unreviewed transaction was NOT synced (remained PENDING)
        t2 = self.txn_repo.get_by_id("txn_sync_unreviewed")
        self.assertIsNotNone(t2)
        self.assertEqual(SyncState.PENDING, t2.sync_state)

    def test_sync_now_network_failure_emits_zero_fake_success(self) -> None:
        self.txn_repo.should_fail_sync = True
        self.txn_repo.sync_failure_error = "ConnectException: Connection refused to Google Sheets API"

        self.vm.on_sync_now()

        self.assertFalse(self.vm.ui_state.is_syncing)
        # CRITICAL ZERO FAKE SUCCESS INVARIANT:
        self.assertIsNone(
            self.vm.ui_state.sync_result,
            "sync_result MUST remain None on network failure; never report fake synced count",
        )
        self.assertIsNotNone(self.vm.ui_state.error_message)
        self.assertIn("Connection refused", self.vm.ui_state.error_message)

    def test_sync_now_unconfigured_spreadsheet_id_emits_explicit_error(self) -> None:
        self.pref_repo.set_spreadsheet_id("")
        self.vm.on_sync_now()

        self.assertFalse(self.vm.ui_state.is_syncing)
        self.assertIsNone(self.vm.ui_state.sync_result)
        self.assertIsNotNone(self.vm.ui_state.error_message)
        self.assertIn("Spreadsheet ID is not configured", self.vm.ui_state.error_message)


class TestQuickActionReceiverHardening(unittest.TestCase):
    """Stress tests for QuickActionReceiver hardened category normalization."""

    def setUp(self) -> None:
        self.repo = SimulatedTransactionRepository()
        self.repo.insert(Transaction(
            id="quick_txn_1",
            timestamp=1730811600000,
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            merchant="Cafe Coffee Day",
            raw_title="Paid Rs 220",
            raw_text="Paid Rs 220",
            trip_tag="",
            category="Other",
            total_amount=220.0,
            companions="Personal",
            my_share=220.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            review_state=ReviewState.NEEDS_REVIEW,
            sync_state=SyncState.PENDING,
        ))

    def test_singular_saving_hardened_normalization(self) -> None:
        singular_variants = [
            "saving",
            "Saving",
            "SAVING",
            "sAvInG",
            "  saving  ",
            " savings ",
            "Investment",
            "investment",
            "SIP",
            "sip",
            "mutual fund",
        ]
        for variant in singular_variants:
            status, txn, cat = QuickActionReceiverLogic.handle_action(
                QuickActionReceiverLogic.ACTION_QUICK_CATEGORIZE,
                "quick_txn_1",
                variant,
                self.repo,
            )
            self.assertEqual("Success", status)
            self.assertIsNotNone(txn)
            self.assertEqual(ReviewState.CONFIRMED, txn.review_state)
            self.assertEqual(
                CATEGORY_SAVINGS,
                cat,
                f"Failed to normalize variant '{variant}' to Savings",
            )
            self.assertEqual(CATEGORY_SAVINGS, txn.category)

    def test_standard_categories_preserved(self) -> None:
        standard = ["Food & Drink", "Groceries", "Shopping", "Transit"]
        for cat in standard:
            status, txn, confirmed_cat = QuickActionReceiverLogic.handle_action(
                QuickActionReceiverLogic.ACTION_QUICK_CATEGORIZE,
                "quick_txn_1",
                cat,
                self.repo,
            )
            self.assertEqual("Success", status)
            self.assertEqual(cat, confirmed_cat)
            self.assertEqual(cat, txn.category)

    def test_null_or_blank_category_falls_back_to_existing(self) -> None:
        for empty_val in [None, "", "   ", "\t"]:
            # Reset category to Other
            self.repo.insert(Transaction(
                id="quick_txn_1",
                timestamp=1730811600000,
                date_serial=46331,
                time_serial=0.5,
                account="SBI",
                merchant="Cafe Coffee Day",
                raw_title="Paid Rs 220",
                raw_text="Paid Rs 220",
                trip_tag="",
                category="Other",
                total_amount=220.0,
                companions="Personal",
                my_share=220.0,
                owed_to_me=0.0,
                status=TransactionStatus.SETTLED,
                review_state=ReviewState.NEEDS_REVIEW,
                sync_state=SyncState.PENDING,
            ))
            status, txn, confirmed_cat = QuickActionReceiverLogic.handle_action(
                QuickActionReceiverLogic.ACTION_QUICK_CONFIRM,
                "quick_txn_1",
                empty_val,
                self.repo,
            )
            self.assertEqual("Success", status)
            self.assertEqual("Other", confirmed_cat)
            self.assertEqual("Other", txn.category)
            self.assertEqual(ReviewState.CONFIRMED, txn.review_state)

    def test_non_existent_transaction_returns_not_found(self) -> None:
        status, txn, cat = QuickActionReceiverLogic.handle_action(
            QuickActionReceiverLogic.ACTION_QUICK_CATEGORIZE,
            "quick_missing_999",
            "Food & Drink",
            self.repo,
        )
        self.assertEqual("NotFound", status)
        self.assertIsNone(txn)
        self.assertIsNone(cat)


class TestStaticAnalysisAndArchitectureInvariants(unittest.TestCase):
    """Static verification of source files, zero emojis, and zero local paths."""

    def test_all_m5_kotlin_files_exist(self) -> None:
        required_files = [
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "presentation", "viewmodel", "ReviewInboxViewModel.kt"),
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "presentation", "viewmodel", "SettingsViewModel.kt"),
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "presentation", "screens", "ReviewInboxScreen.kt"),
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "presentation", "screens", "SettingsScreen.kt"),
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "service", "QuickActionReceiver.kt"),
            os.path.join("app", "src", "test", "java", "com", "financeautomation", "presentation", "ReviewInboxViewModelTest.kt"),
            os.path.join("app", "src", "test", "java", "com", "financeautomation", "presentation", "SettingsViewModelTest.kt"),
            os.path.join("app", "src", "test", "java", "com", "financeautomation", "service", "QuickActionReceiverTest.kt"),
        ]
        for f in required_files:
            self.assertTrue(os.path.exists(f), f"Required file missing: {f}")

    def test_quick_action_receiver_contains_hardened_saving_check(self) -> None:
        path = os.path.join("app", "src", "main", "java", "com", "financeautomation", "service", "QuickActionReceiver.kt")
        with open(path, "r", encoding="utf-8") as fp:
            content = fp.read()
        self.assertIn("saving", content.lower())
        self.assertIn("CategoryConstants.CATEGORY_SAVINGS", content)

    def test_review_inbox_view_model_clears_duplicate_flag_on_confirm(self) -> None:
        path = os.path.join("app", "src", "main", "java", "com", "financeautomation", "presentation", "viewmodel", "ReviewInboxViewModel.kt")
        with open(path, "r", encoding="utf-8") as fp:
            content = fp.read()
        self.assertIn("isPossibleDuplicate = false", content)
        self.assertIn("ReviewState.CONFIRMED", content)

    def test_settings_view_model_surfaces_sync_error_without_mocking(self) -> None:
        path = os.path.join("app", "src", "main", "java", "com", "financeautomation", "presentation", "viewmodel", "SettingsViewModel.kt")
        with open(path, "r", encoding="utf-8") as fp:
            content = fp.read()
        self.assertIn("is Result.Error ->", content)
        self.assertIn("Sync failed", content)

    def test_zero_emojis_across_entire_app_directory(self) -> None:
        emoji_pattern = re.compile(
            r"[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF"
            r"\U0001F1E0-\U0001F1FF\U0001F900-\U0001F9FF\U0001FA70-\U0001FAFF"
            r"\U00002600-\U000026FF\U00002700-\U000027BF]"
        )
        found_emojis = []
        for root, _, files in os.walk("app"):
            for file in files:
                if file.endswith((".kt", ".java", ".xml", ".gradle", ".kts", ".md", ".txt")):
                    path = os.path.join(root, file)
                    with open(path, "r", encoding="utf-8", errors="ignore") as f:
                        for line_idx, line in enumerate(f, 1):
                            matches = emoji_pattern.findall(line)
                            if matches:
                                found_emojis.append((path, line_idx, matches))
        self.assertEqual(
            [],
            found_emojis,
            f"Emojis found in codebase: {found_emojis}",
        )

    def test_zero_hardcoded_local_machine_paths(self) -> None:
        path_pattern = re.compile(
            r"([A-Za-z]:\\[A-Za-z0-9_\\\.\-]+|file:///[A-Za-z]:|/Users/[A-Za-z0-9_\.\-]+)",
            re.IGNORECASE,
        )
        found_paths = []
        for root, _, files in os.walk("app"):
            for file in files:
                if file.endswith((".kt", ".java", ".xml", ".gradle", ".kts", ".md", ".txt")):
                    path = os.path.join(root, file)
                    with open(path, "r", encoding="utf-8", errors="ignore") as f:
                        for line_idx, line in enumerate(f, 1):
                            matches = path_pattern.findall(line)
                            if matches:
                                found_paths.append((path, line_idx, matches))
        self.assertEqual(
            [],
            found_paths,
            f"Hardcoded local paths found in codebase: {found_paths}",
        )


if __name__ == "__main__":
    unittest.main()
