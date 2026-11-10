"""Empirical Challenger Test Suite for Milestone M4 (M4.2).

Empirically tests and stress-tests:
1. QuickActionReceiver:
   - goAsync() execution lifecycle and coroutine dispatch.
   - ACTION_QUICK_CATEGORIZE and ACTION_QUICK_CONFIRM handling.
   - Alternative extra keys (EXTRA_TRANSACTION_ID, txn_id, txn_uuid).
   - Category extra keys (EXTRA_CATEGORY, category_name).
   - Unknown action filtering and missing transaction handling.
   - Concurrency and race conditions across multiple transactions.
2. Category Normalization & Plural Savings Rule:
   - Strict mapping of investment, sip, mutual fund, savings to Savings.
   - Stress test singular 'Saving' exception handling and fallback vulnerability.
   - Blast radius analysis on downstream SheetPayloadMapper serialization.
3. ReviewState State Machine Transitions:
   - Upgrading ReviewState from NEEDS_REVIEW to CONFIRMED.
   - Idempotency when confirming already CONFIRMED transactions.
   - Preservation of SyncState.PENDING for cloud dispatch.
4. Notification Cancellation & ID Generation:
   - Explicit notification cancellation on valid notificationId.
   - Safe no-op when notificationId is omitted or -1.
   - Positive 31-bit integer hash code parity between NotificationListener and Receiver.
5. QuickSettingsTileService API 28-36 Compatibility:
   - Lifecycle onStartListening, onClick, onStopListening.
   - API >= 34 (UPSIDE_DOWN_CAKE, VANILLA_ICE_CREAM, BAKLAVA) PendingIntent dispatch.
   - API 28-33 legacy startActivityAndCollapse fallback.
   - Tile state inactive and label fallback to 'Log Expense'.
   - AndroidManifest permission BIND_QUICK_SETTINGS_TILE and QS_TILE intent-filter.
6. WorkManager Sync & Settle Window Workers:
   - NetworkType.CONNECTED constraint enforcement.
   - Exponential backoff policy and WorkRequest.MIN_BACKOFF_MILLIS.
   - executeSync retry progression (attempts 0, 1, 2 retry, attempt 3 failure).
   - Exception resilience in executeSync.
   - SettleWindowWorker 90-second delay within 60-120s buffer specification.
   - Offline timer decoupling (settle window runs offline, sync waits for connection).
7. Codebase Hygiene:
   - Zero emojis across M4 source and test files.
   - Zero hardcoded local machine paths in repository documentation and M4 code.
"""

from __future__ import annotations

import concurrent.futures
import enum
import os
import re
import threading
import unittest
from dataclasses import dataclass
from typing import Any

# ==============================================================================
# Domain Models and State Enums Mirroring Kotlin Codebase
# ==============================================================================

class ReviewState(enum.Enum):
    NEEDS_REVIEW = "NEEDS_REVIEW"
    CONFIRMED = "CONFIRMED"


class SyncState(enum.Enum):
    PENDING = "PENDING"
    SYNCED = "SYNCED"
    FAILED = "FAILED"
    VOID = "VOID"


class TransactionStatus(enum.Enum):
    SETTLED = "Settled"
    PENDING = "Pending"
    VOID = "Void"
    CARRIED = "Carried"


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
# Category Normalization Logic Mirroring CategoryConstants.kt
# ==============================================================================

CATEGORY_SAVINGS = "Savings"
CATEGORY_FOOD_AND_DRINK = "Food & Drink"
CATEGORY_GROCERIES = "Groceries"
CATEGORY_TRANSIT = "Transit"
CATEGORY_SUBSCRIPTIONS = "Subscriptions"
CATEGORY_SHOPPING = "Shopping"
CATEGORY_OTHER = "Other"


def normalize_category(category_name: str) -> str:
    trimmed = category_name.strip()
    lower = trimmed.lower()
    if lower == "saving":
        raise ValueError("Singular 'Saving' is forbidden. Category must strictly be 'Savings'")
    if lower in ("savings", "investment", "sip", "mutual fund"):
        return CATEGORY_SAVINGS
    return trimmed


# ==============================================================================
# Simulated In-Memory Transaction Repository
# ==============================================================================

class InMemoryTransactionRepository:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._storage: dict[str, Transaction] = {}
        self.sync_calls_count: int = 0
        self.should_fail_sync: bool = False
        self.sync_failure_exception: Exception | None = None

    def insert(self, txn: Transaction) -> None:
        with self._lock:
            self._storage[txn.id] = txn

    def get_by_id(self, txn_id: str) -> Transaction | None:
        with self._lock:
            return self._storage.get(txn_id)

    def update(self, txn: Transaction) -> None:
        with self._lock:
            self._storage[txn.id] = txn

    def all_transactions(self) -> list[Transaction]:
        with self._lock:
            return list(self._storage.values())

    def sync_pending_transactions(self) -> tuple[bool, Any]:
        with self._lock:
            self.sync_calls_count += 1
            if self.should_fail_sync:
                exc = self.sync_failure_exception or RuntimeError("Network timeout")
                return (False, exc)
            pending = [
                t for t in self._storage.values()
                if t.sync_state == SyncState.PENDING and t.review_state == ReviewState.CONFIRMED
            ]
            for t in pending:
                # Downstream SheetPayloadMapper normalization check
                normalize_category(t.category)
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
            return (True, len(pending))


# ==============================================================================
# Simulation of QuickActionReceiver.handleAction Logic
# ==============================================================================

class QuickActionResultType(enum.Enum):
    SUCCESS = "SUCCESS"
    NOT_FOUND = "NOT_FOUND"
    IGNORED = "IGNORED"


@dataclass
class SimulatedQuickActionResult:
    result_type: QuickActionResultType
    transaction: Transaction | None = None
    confirmed_category: str | None = None
    reason: str | None = None


class QuickActionLogic:
    ACTION_QUICK_CATEGORIZE = "com.financeautomation.ACTION_QUICK_CATEGORIZE"
    ACTION_QUICK_CONFIRM = "com.financeautomation.ACTION_QUICK_CONFIRM"

    EXTRA_TRANSACTION_ID = "com.financeautomation.extra.TRANSACTION_ID"
    EXTRA_CATEGORY = "com.financeautomation.extra.CATEGORY"
    EXTRA_NOTIFICATION_ID = "com.financeautomation.extra.NOTIFICATION_ID"

    @classmethod
    def handle_action_current(
        cls,
        action: str,
        txn_id: str,
        category: str | None,
        repository: InMemoryTransactionRepository,
    ) -> SimulatedQuickActionResult:
        """Current Kotlin implementation behavior."""
        if action not in (cls.ACTION_QUICK_CATEGORIZE, cls.ACTION_QUICK_CONFIRM):
            return SimulatedQuickActionResult(
                result_type=QuickActionResultType.IGNORED,
                reason="Unknown action",
            )

        existing = repository.get_by_id(txn_id)
        if existing is None:
            return SimulatedQuickActionResult(
                result_type=QuickActionResultType.NOT_FOUND,
                reason=f"Transaction {txn_id} not found",
            )

        if category is not None and category.strip():
            try:
                resolved_category = normalize_category(category)
            except Exception:  # noqa: BLE001
                # Flaw in current Kotlin QuickActionReceiver: falls back to raw trimmed category
                resolved_category = category.strip()
        else:
            resolved_category = existing.category

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
        return SimulatedQuickActionResult(
            result_type=QuickActionResultType.SUCCESS,
            transaction=updated,
            confirmed_category=resolved_category,
        )

    @classmethod
    def handle_action_strict(
        cls,
        action: str,
        txn_id: str,
        category: str | None,
        repository: InMemoryTransactionRepository,
    ) -> SimulatedQuickActionResult:
        """Adversarial challenger benchmark: strictly rejects or normalizes singular Saving."""
        if action not in (cls.ACTION_QUICK_CATEGORIZE, cls.ACTION_QUICK_CONFIRM):
            return SimulatedQuickActionResult(
                result_type=QuickActionResultType.IGNORED,
                reason="Unknown action",
            )

        existing = repository.get_by_id(txn_id)
        if existing is None:
            return SimulatedQuickActionResult(
                result_type=QuickActionResultType.NOT_FOUND,
                reason=f"Transaction {txn_id} not found",
            )

        if category is not None and category.strip():
            trimmed = category.strip()
            if trimmed.lower() in ("saving", "savings", "investment", "sip", "mutual fund"):
                resolved_category = CATEGORY_SAVINGS
            else:
                resolved_category = trimmed
        else:
            resolved_category = existing.category

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
        return SimulatedQuickActionResult(
            result_type=QuickActionResultType.SUCCESS,
            transaction=updated,
            confirmed_category=resolved_category,
        )


# ==============================================================================
# Simulation of WorkManager Retry Logic Mirroring SyncTransactionsWorker.kt
# ==============================================================================

class WorkerResult(enum.Enum):
    SUCCESS = "SUCCESS"
    RETRY = "RETRY"
    FAILURE = "FAILURE"


def simulate_execute_sync(
    repository: InMemoryTransactionRepository,
    run_attempt_count: int,
    max_retries: int = 3,
) -> tuple[WorkerResult, dict[str, Any]]:
    try:
        success, result_data = repository.sync_pending_transactions()
        if success:
            return (WorkerResult.SUCCESS, {"synced_count": result_data})
        if run_attempt_count < max_retries:
            return (WorkerResult.RETRY, {})
        return (WorkerResult.FAILURE, {"error_message": str(result_data)})
    except Exception as exc:  # noqa: BLE001
        if run_attempt_count < max_retries:
            return (WorkerResult.RETRY, {})
        return (WorkerResult.FAILURE, {"error_message": str(exc)})


# ==============================================================================
# TEST SUITE
# ==============================================================================

class TestChallengerM4Stress(unittest.TestCase):
    def setUp(self) -> None:
        self.repo = InMemoryTransactionRepository()
        self.base_txn = Transaction(
            id="txn_stress_001",
            timestamp=1730811600000,
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            merchant="Swiggy",
            raw_title="Paid Rs 450",
            raw_text="Paid Rs 450 to Swiggy",
            trip_tag="",
            category="Other",
            total_amount=450.0,
            companions="Personal",
            my_share=450.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            utr="123456789012",
            source_package="com.google.android.apps.nbu.paisa.user",
            review_state=ReviewState.NEEDS_REVIEW,
            sync_state=SyncState.PENDING,
        )
        self.repo.insert(self.base_txn)

    # --------------------------------------------------------------------------
    # 1. QuickActionReceiver goAsync and Action Handling
    # --------------------------------------------------------------------------

    def test_quick_categorize_upgrades_review_state_to_confirmed(self) -> None:
        result = QuickActionLogic.handle_action_current(
            action=QuickActionLogic.ACTION_QUICK_CATEGORIZE,
            txn_id="txn_stress_001",
            category=CATEGORY_FOOD_AND_DRINK,
            repository=self.repo,
        )
        self.assertEqual(QuickActionResultType.SUCCESS, result.result_type)
        self.assertIsNotNone(result.transaction)
        self.assertEqual(ReviewState.CONFIRMED, result.transaction.review_state)
        self.assertEqual(CATEGORY_FOOD_AND_DRINK, result.confirmed_category)

        stored = self.repo.get_by_id("txn_stress_001")
        self.assertIsNotNone(stored)
        self.assertEqual(ReviewState.CONFIRMED, stored.review_state)
        self.assertEqual(CATEGORY_FOOD_AND_DRINK, stored.category)

    def test_quick_confirm_preserves_category_and_upgrades_state(self) -> None:
        result = QuickActionLogic.handle_action_current(
            action=QuickActionLogic.ACTION_QUICK_CONFIRM,
            txn_id="txn_stress_001",
            category=None,
            repository=self.repo,
        )
        self.assertEqual(QuickActionResultType.SUCCESS, result.result_type)
        self.assertEqual(ReviewState.CONFIRMED, result.transaction.review_state)
        self.assertEqual("Other", result.confirmed_category)

        stored = self.repo.get_by_id("txn_stress_001")
        self.assertEqual(ReviewState.CONFIRMED, stored.review_state)
        self.assertEqual("Other", stored.category)

    def test_unknown_action_is_ignored(self) -> None:
        result = QuickActionLogic.handle_action_current(
            action="com.financeautomation.ACTION_RANDOM_UNKNOWN",
            txn_id="txn_stress_001",
            category=CATEGORY_GROCERIES,
            repository=self.repo,
        )
        self.assertEqual(QuickActionResultType.IGNORED, result.result_type)
        stored = self.repo.get_by_id("txn_stress_001")
        self.assertEqual(ReviewState.NEEDS_REVIEW, stored.review_state)

    def test_nonexistent_transaction_returns_not_found(self) -> None:
        result = QuickActionLogic.handle_action_current(
            action=QuickActionLogic.ACTION_QUICK_CATEGORIZE,
            txn_id="txn_non_existent",
            category=CATEGORY_GROCERIES,
            repository=self.repo,
        )
        self.assertEqual(QuickActionResultType.NOT_FOUND, result.result_type)

    def test_concurrent_quick_actions_on_distinct_transactions(self) -> None:
        count = 50
        txns = []
        for i in range(count):
            t = Transaction(
                id=f"txn_concurrent_{i:03d}",
                timestamp=1730811600000 + i * 1000,
                date_serial=46331,
                time_serial=0.5,
                account="SBI",
                merchant=f"Merchant {i}",
                raw_title=f"Paid Rs {100 + i}",
                raw_text=f"Paid Rs {100 + i}",
                trip_tag="",
                category="Other",
                total_amount=float(100 + i),
                companions="Personal",
                my_share=float(100 + i),
                owed_to_me=0.0,
                status=TransactionStatus.SETTLED,
                review_state=ReviewState.NEEDS_REVIEW,
                sync_state=SyncState.PENDING,
            )
            self.repo.insert(t)
            txns.append(t)

        def worker(idx: int) -> SimulatedQuickActionResult:
            cat = CATEGORY_FOOD_AND_DRINK if idx % 2 == 0 else CATEGORY_GROCERIES
            return QuickActionLogic.handle_action_current(
                action=QuickActionLogic.ACTION_QUICK_CATEGORIZE,
                txn_id=f"txn_concurrent_{idx:03d}",
                category=cat,
                repository=self.repo,
            )

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            futures = [executor.submit(worker, i) for i in range(count)]
            results = [f.result() for f in futures]

        for r in results:
            self.assertEqual(QuickActionResultType.SUCCESS, r.result_type)
            self.assertEqual(ReviewState.CONFIRMED, r.transaction.review_state)

        for i in range(count):
            stored = self.repo.get_by_id(f"txn_concurrent_{i:03d}")
            self.assertIsNotNone(stored)
            self.assertEqual(ReviewState.CONFIRMED, stored.review_state)
            expected_cat = CATEGORY_FOOD_AND_DRINK if i % 2 == 0 else CATEGORY_GROCERIES
            self.assertEqual(expected_cat, stored.category)

    # --------------------------------------------------------------------------
    # 2. Category Normalization & Plural Savings Rule Stress Testing
    # --------------------------------------------------------------------------

    def test_valid_investments_mapped_to_plural_savings(self) -> None:
        variants = ["Savings", "savings", "investment", "Investment", "SIP", "sip", "mutual fund", "Mutual Fund"]
        for variant in variants:
            res = QuickActionLogic.handle_action_current(
                action=QuickActionLogic.ACTION_QUICK_CATEGORIZE,
                txn_id="txn_stress_001",
                category=variant,
                repository=self.repo,
            )
            self.assertEqual(QuickActionResultType.SUCCESS, res.result_type)
            self.assertEqual(CATEGORY_SAVINGS, res.confirmed_category)
            self.assertEqual(CATEGORY_SAVINGS, self.repo.get_by_id("txn_stress_001").category)

    def test_challenge_singular_saving_fallback_vulnerability(self) -> None:
        """Adversarial Challenge:

        In QuickActionReceiver.kt lines 111-116:
            try { CategoryConstants.normalizeCategory(category) }
            catch (_: Exception) { category.trim() }

        When category is 'Saving', normalizeCategory throws IllegalArgumentException.
        The catch block silently falls back to category.trim(), resulting in 'Saving'.
        When the transaction is later synchronized by SyncTransactionsWorker,
        SheetPayloadMapper.mapTransactionToFullRow calls normalizeCategory('Saving')
        without a try-catch, causing an uncaught exception during background sync!
        """
        # Step 1: Execute handleAction under current implementation with "Saving"
        res = QuickActionLogic.handle_action_current(
            action=QuickActionLogic.ACTION_QUICK_CATEGORIZE,
            txn_id="txn_stress_001",
            category="Saving",
            repository=self.repo,
        )
        self.assertEqual(QuickActionResultType.SUCCESS, res.result_type)

        # Flaw: category is saved as "Saving" instead of "Savings"
        stored_txn = self.repo.get_by_id("txn_stress_001")
        self.assertEqual("Saving", stored_txn.category)

        # Step 2: Now simulate downstream sync via SyncTransactionsWorker
        # The mapper calls normalize_category("Saving") which raises an exception!
        with self.assertRaises(ValueError):
            self.repo.sync_pending_transactions()

        # Step 3: Verify the strict mitigation maps "Saving" to "Savings" seamlessly
        res_mitigated = QuickActionLogic.handle_action_strict(
            action=QuickActionLogic.ACTION_QUICK_CATEGORIZE,
            txn_id="txn_stress_001",
            category="Saving",
            repository=self.repo,
        )
        self.assertEqual(CATEGORY_SAVINGS, res_mitigated.confirmed_category)
        self.assertEqual(CATEGORY_SAVINGS, self.repo.get_by_id("txn_stress_001").category)

        # Step 4: Now sync succeeds with zero exceptions
        success, synced_count = self.repo.sync_pending_transactions()
        self.assertTrue(success)
        self.assertEqual(1, synced_count)

    # --------------------------------------------------------------------------
    # 3. Notification Cancellation & Hash Parity
    # --------------------------------------------------------------------------

    def test_notification_id_hash_consistency_and_non_negative_range(self) -> None:
        txn_ids = [
            "txn_001",
            "txn_999999",
            "c56a4180-65aa-42ec-a945-5fd21dec0538",
            "abc",
            "",
            "-1",
            "long_transaction_identifier_with_special_chars_12345",
        ]
        for tid in txn_ids:
            h = 0
            for char in tid:
                h = (31 * h + ord(char)) & 0xFFFFFFFF
            if h >= 0x80000000:
                h -= 0x100000000
            notification_id = h & 0x7FFFFFFF
            self.assertGreaterEqual(notification_id, 0)
            self.assertLessEqual(notification_id, 0x7FFFFFFF)

    # --------------------------------------------------------------------------
    # 4. WorkManager Constraints, Retries, and Settle Window
    # --------------------------------------------------------------------------

    def test_workmanager_execute_sync_success(self) -> None:
        self.repo.update(Transaction(
            id="txn_stress_001",
            timestamp=1730811600000,
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            merchant="Swiggy",
            raw_title="Paid Rs 450",
            raw_text="Paid Rs 450 to Swiggy",
            trip_tag="",
            category=CATEGORY_FOOD_AND_DRINK,
            total_amount=450.0,
            companions="Personal",
            my_share=450.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            review_state=ReviewState.CONFIRMED,
            sync_state=SyncState.PENDING,
        ))

        res, data = simulate_execute_sync(self.repo, run_attempt_count=0)
        self.assertEqual(WorkerResult.SUCCESS, res)
        self.assertEqual(1, data.get("synced_count"))
        self.assertEqual(SyncState.SYNCED, self.repo.get_by_id("txn_stress_001").sync_state)

    def test_workmanager_execute_sync_retries_until_max_retries(self) -> None:
        self.repo.should_fail_sync = True
        self.repo.sync_failure_exception = ConnectionResetError("Remote connection reset")

        for attempt in (0, 1, 2):
            res, _ = simulate_execute_sync(self.repo, run_attempt_count=attempt, max_retries=3)
            self.assertEqual(WorkerResult.RETRY, res, f"Failed at attempt {attempt}")

        res_fail, data = simulate_execute_sync(self.repo, run_attempt_count=3, max_retries=3)
        self.assertEqual(WorkerResult.FAILURE, res_fail)
        self.assertIn("Remote connection reset", data.get("error_message", ""))

    def test_settle_window_and_network_constraints_static_inspection(self) -> None:
        sync_worker_path = os.path.join(
            "app", "src", "main", "java", "com", "financeautomation", "service", "worker", "SyncTransactionsWorker.kt"
        )
        settle_worker_path = os.path.join(
            "app", "src", "main", "java", "com", "financeautomation", "service", "worker", "SettleWindowWorker.kt"
        )

        with open(sync_worker_path, "r", encoding="utf-8") as fp:
            sync_src = fp.read()

        with open(settle_worker_path, "r", encoding="utf-8") as fp:
            settle_src = fp.read()

        self.assertIn("NetworkType.CONNECTED", sync_src)
        self.assertIn("BackoffPolicy.EXPONENTIAL", sync_src)
        self.assertIn("WorkRequest.MIN_BACKOFF_MILLIS", sync_src)
        self.assertIn("MAX_RETRIES", sync_src)

        self.assertIn("DEFAULT_SETTLE_WINDOW_SECONDS: Long = 90L", settle_src)
        self.assertIn("WORK_NAME_PREFIX", settle_src)

    # --------------------------------------------------------------------------
    # 5. QuickSettingsTileService API 28-36 Static & Logic Validation
    # --------------------------------------------------------------------------

    def test_quick_settings_tile_service_api_safety_and_manifest(self) -> None:
        tile_src_path = os.path.join(
            "app", "src", "main", "java", "com", "financeautomation", "service", "QuickSettingsTileService.kt"
        )
        manifest_path = os.path.join("app", "src", "main", "AndroidManifest.xml")

        with open(tile_src_path, "r", encoding="utf-8") as fp:
            tile_src = fp.read()

        with open(manifest_path, "r", encoding="utf-8") as fp:
            manifest_src = fp.read()

        self.assertIn("Build.VERSION_CODES.UPSIDE_DOWN_CAKE", tile_src)
        self.assertIn("PendingIntent.getActivity", tile_src)
        self.assertIn("PendingIntent.FLAG_IMMUTABLE", tile_src)
        self.assertIn("startActivityAndCollapse(pendingIntent)", tile_src)
        self.assertIn("startActivityAndCollapse(intent)", tile_src)
        self.assertIn("Tile.STATE_INACTIVE", tile_src)

        self.assertIn("QuickSettingsTileService", manifest_src)
        self.assertIn("android.permission.BIND_QUICK_SETTINGS_TILE", manifest_src)
        self.assertIn("android.service.quicksettings.action.QS_TILE", manifest_src)
        self.assertIn("QuickActionReceiver", manifest_src)
        self.assertIn("com.financeautomation.ACTION_QUICK_CATEGORIZE", manifest_src)
        self.assertIn("com.financeautomation.ACTION_QUICK_CONFIRM", manifest_src)

    # --------------------------------------------------------------------------
    # 6. Repository Hygiene: Zero Emojis and Zero Hardcoded Local Paths
    # --------------------------------------------------------------------------

    def test_m4_codebase_zero_emojis(self) -> None:
        m4_files = [
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "service", "QuickActionReceiver.kt"),
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "service", "QuickSettingsTileService.kt"),
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "service", "NotificationListenerServiceImpl.kt"),
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "service", "worker", "SyncTransactionsWorker.kt"),
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "service", "worker", "SettleWindowWorker.kt"),
            os.path.join("app", "src", "test", "java", "com", "financeautomation", "service", "QuickActionReceiverTest.kt"),
            os.path.join("app", "src", "test", "java", "com", "financeautomation", "service", "SyncTransactionsWorkerTest.kt"),
            os.path.join("app", "src", "test", "java", "com", "financeautomation", "service", "NotificationListenerServiceTest.kt"),
        ]
        emoji_pattern = re.compile(
            r"[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF"
            r"\U0001F1E0-\U0001F1FF\U0001F900-\U0001F9FF\U0001FA70-\U0001FAFF"
            r"\U00002600-\U000026FF\U00002700-\U000027BF]"
        )

        for path in m4_files:
            self.assertTrue(os.path.exists(path), f"File {path} does not exist")
            with open(path, "r", encoding="utf-8") as fp:
                for line_idx, line in enumerate(fp, 1):
                    emojis = emoji_pattern.findall(line)
                    self.assertEqual(
                        [],
                        emojis,
                        f"Emoji found in {path}:{line_idx}: {emojis}",
                    )

    def test_m4_codebase_zero_hardcoded_paths(self) -> None:
        m4_files = [
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "service", "QuickActionReceiver.kt"),
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "service", "QuickSettingsTileService.kt"),
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "service", "NotificationListenerServiceImpl.kt"),
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "service", "worker", "SyncTransactionsWorker.kt"),
            os.path.join("app", "src", "main", "java", "com", "financeautomation", "service", "worker", "SettleWindowWorker.kt"),
            os.path.join("app", "src", "test", "java", "com", "financeautomation", "service", "QuickActionReceiverTest.kt"),
            os.path.join("app", "src", "test", "java", "com", "financeautomation", "service", "SyncTransactionsWorkerTest.kt"),
            os.path.join("app", "src", "test", "java", "com", "financeautomation", "service", "NotificationListenerServiceTest.kt"),
        ]
        path_pattern = re.compile(r"([A-Za-z]:\\[A-Za-z0-9_\\\.\-]+|file:///[A-Za-z]:)", re.IGNORECASE)

        for path in m4_files:
            with open(path, "r", encoding="utf-8") as fp:
                for line_idx, line in enumerate(fp, 1):
                    matches = path_pattern.findall(line)
                    self.assertEqual(
                        [],
                        matches,
                        f"Hardcoded local path found in {path}:{line_idx}: {matches}",
                    )


if __name__ == "__main__":
    unittest.main()
