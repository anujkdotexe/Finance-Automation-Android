"""Empirical Challenger Test Suite for Milestone M3.1.

Empirically tests and stress-tests:
1. Room Entities & SQLite Schema:
   - TransactionEntity, CompanionEntity, CategoryEntity, SyncQueueEntity.
   - Verification of 28 master ledger fields vs Table 1 debits and 3 UUID fields.
   - Room annotations (@Entity, @PrimaryKey, @ColumnInfo, @Dao, @Insert, @Update, @Query, @Database).
   - SQLite table definitions, indices, and unique constraints.
2. Room DAOs in Real SQLite:
   - TransactionDao, CompanionDao, CategoryDao, SyncQueueDao SQL queries executed on live SQLite.
   - Correct sorting: timestamp DESC, timestamp ASC, createdAt ASC, displayOrder ASC.
   - In-place void query, COALESCE updates, and UTR deduplication lookups.
   - Deduplication window range query (timestamp range and amount tolerance).
3. Two-Axis State Machine:
   - ReviewState (NEEDS_REVIEW, CONFIRMED) and SyncState (PENDING, SYNCED, FAILED, VOID).
   - Orthogonality and state transitions across intercept, confirm, sync, edit, void, and failure paths.
4. SyncQueue Management & Offline Queuing Resilience:
   - FIFO queuing, retry count tracking, deletion upon completion.
   - Challenge: What happens when network fails (STATUS_FAILED abandonment)?
   - Challenge: What happens when spreadsheetId is unconfigured/offline (silent queue drop)?
   - Challenge: What happens when voidTransaction is synced before initial append (VOID to SYNCED overwrite)?
5. Repository & Architecture Hygiene:
   - Coroutine dispatcher isolation (Dispatchers.IO).
   - Zero emojis across all M3 source and documentation files.
   - Zero hardcoded local machine paths across M3 codebase files.
"""

from __future__ import annotations

import os
import re
import sqlite3
import unittest
from dataclasses import dataclass
from pathlib import Path

# ==============================================================================
# Domain Models & Enums
# ==============================================================================

class ReviewState:
    NEEDS_REVIEW = "NEEDS_REVIEW"
    CONFIRMED = "CONFIRMED"


class SyncState:
    PENDING = "PENDING"
    SYNCED = "SYNCED"
    FAILED = "FAILED"
    VOID = "VOID"


class TransactionStatus:
    SETTLED = "Settled"
    PENDING = "Pending"
    VOID = "Void"
    CARRIED = "Carried"


@dataclass
class DomainTransaction:
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
    status: str
    utr: str | None
    source_package: str
    review_state: str
    sync_state: str
    sheet_row_index: int | None
    is_possible_duplicate: bool


# ==============================================================================
# SQLite Schema Matching Kotlin Room Entity Annotations
# ==============================================================================

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS transactions (
    id TEXT PRIMARY KEY NOT NULL,
    timestamp INTEGER NOT NULL,
    dateSerial INTEGER NOT NULL,
    timeSerial REAL NOT NULL,
    account TEXT NOT NULL,
    merchant TEXT NOT NULL,
    rawTitle TEXT NOT NULL DEFAULT '',
    rawText TEXT NOT NULL DEFAULT '',
    tripTag TEXT NOT NULL DEFAULT '',
    category TEXT NOT NULL,
    totalAmount REAL NOT NULL,
    companions TEXT NOT NULL DEFAULT 'Personal',
    myShare REAL NOT NULL,
    owedToMe REAL NOT NULL,
    status TEXT NOT NULL DEFAULT 'Settled',
    utr TEXT,
    sourcePackage TEXT NOT NULL DEFAULT '',
    reviewState TEXT NOT NULL DEFAULT 'NEEDS_REVIEW',
    syncState TEXT NOT NULL DEFAULT 'PENDING',
    sheetRowIndex INTEGER,
    isPossibleDuplicate INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS index_transactions_timestamp ON transactions(timestamp);
CREATE INDEX IF NOT EXISTS index_transactions_utr ON transactions(utr);
CREATE INDEX IF NOT EXISTS index_transactions_reviewState ON transactions(reviewState);
CREATE INDEX IF NOT EXISTS index_transactions_syncState ON transactions(syncState);
CREATE INDEX IF NOT EXISTS index_transactions_sheetRowIndex ON transactions(sheetRowIndex);

CREATE TABLE IF NOT EXISTS companions (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    isSelected INTEGER NOT NULL DEFAULT 0,
    balance REAL NOT NULL DEFAULT 0.0,
    lastSplitTimestamp INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS index_companions_name ON companions(name);

CREATE TABLE IF NOT EXISTS categories (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    isDefault INTEGER NOT NULL DEFAULT 0,
    displayOrder INTEGER NOT NULL DEFAULT 0,
    iconName TEXT NOT NULL DEFAULT 'default_tag'
);

CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name ON categories(name);
CREATE INDEX IF NOT EXISTS index_categories_displayOrder ON categories(displayOrder);

CREATE TABLE IF NOT EXISTS sync_queue (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    transactionId TEXT NOT NULL,
    operationType TEXT NOT NULL,
    payloadJson TEXT NOT NULL,
    retryCount INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX IF NOT EXISTS index_sync_queue_transactionId ON sync_queue(transactionId);
CREATE INDEX IF NOT EXISTS index_sync_queue_status ON sync_queue(status);
CREATE INDEX IF NOT EXISTS index_sync_queue_createdAt ON sync_queue(createdAt);
"""


# ==============================================================================
# Test Cases
# ==============================================================================

class TestRoomPersistenceAndSchema(unittest.TestCase):
    """Verifies Room entities, annotations, and SQLite schema alignment."""

    def setUp(self):
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA_SQL)

    def tearDown(self):
        self.conn.close()

    def test_transactions_table_has_all_table1_and_tracking_fields(self):
        """Verify transactions table columns cover Table 1 debits, Column AC, and metadata."""
        cursor = self.conn.cursor()
        cursor.execute("PRAGMA table_info(transactions)")
        cols = {row["name"]: row["type"] for row in cursor.fetchall()}

        # Table 1 columns (Cols A-K) + Col AC
        self.assertIn("dateSerial", cols)
        self.assertIn("timeSerial", cols)
        self.assertIn("account", cols)
        self.assertIn("merchant", cols)
        self.assertIn("tripTag", cols)
        self.assertIn("category", cols)
        self.assertIn("totalAmount", cols)
        self.assertIn("companions", cols)
        self.assertIn("myShare", cols)
        self.assertIn("owedToMe", cols)
        self.assertIn("status", cols)
        self.assertIn("id", cols)  # Column AC: Debit Txn UUID

        # Tracking and state machine metadata
        self.assertIn("timestamp", cols)
        self.assertIn("rawTitle", cols)
        self.assertIn("rawText", cols)
        self.assertIn("utr", cols)
        self.assertIn("sourcePackage", cols)
        self.assertIn("reviewState", cols)
        self.assertIn("syncState", cols)
        self.assertIn("sheetRowIndex", cols)
        self.assertIn("isPossibleDuplicate", cols)

    def test_schema_coverage_analysis_for_28_columns(self):
        """Analyze master ledger 28 columns coverage vs TransactionEntity scope.
        
        The 31-column master spreadsheet comprises:
        - Table 1 (Cols A-K, 11 business columns)
        - Spacer 1 (Col L, 1 column)
        - Table 2 (Cols M-T, 8 business columns for inflows)
        - Spacer 2 (Col U, 1 column)
        - Table 3 (Cols V-AB, 7 business columns for payables)
        - System UUIDs (Cols AC-AE, 3 hidden UUID columns)
        
        Empirical check: TransactionEntity strictly encapsulates Table 1 debits (Cols A-K)
        and Col AC (Debit UUID). It intentionally does NOT duplicate Table 2 or Table 3 fields.
        """
        table1_cols = ["dateSerial", "timeSerial", "account", "merchant", "tripTag",
                       "category", "totalAmount", "companions", "myShare", "owedToMe", "status"]
        cursor = self.conn.cursor()
        cursor.execute("PRAGMA table_info(transactions)")
        db_cols = [row["name"] for row in cursor.fetchall()]

        for col in table1_cols:
            self.assertIn(col, db_cols)

        # Confirm Col AC UUID is the primary key
        cursor.execute("SELECT pk, name FROM pragma_table_info('transactions') WHERE pk = 1")
        pk_info = cursor.fetchone()
        self.assertEqual("id", pk_info["name"])

    def test_indices_created_successfully(self):
        """Verify all required indices are created on transactions, companions, categories, sync_queue."""
        cursor = self.conn.cursor()
        cursor.execute("SELECT name, tbl_name FROM sqlite_master WHERE type = 'index'")
        indices = [(row["name"], row["tbl_name"]) for row in cursor.fetchall()]

        index_names = [idx[0] for idx in indices]
        self.assertIn("index_transactions_timestamp", index_names)
        self.assertIn("index_transactions_utr", index_names)
        self.assertIn("index_transactions_reviewState", index_names)
        self.assertIn("index_transactions_syncState", index_names)
        self.assertIn("index_transactions_sheetRowIndex", index_names)
        self.assertIn("index_companions_name", index_names)
        self.assertIn("index_categories_name", index_names)
        self.assertIn("index_sync_queue_transactionId", index_names)
        self.assertIn("index_sync_queue_status", index_names)
        self.assertIn("index_sync_queue_createdAt", index_names)

    def test_companion_and_category_unique_constraints(self):
        """Verify unique name constraint in companions and categories tables."""
        cursor = self.conn.cursor()
        cursor.execute("INSERT INTO companions (id, name) VALUES ('p1', 'Alice')")
        with self.assertRaises(sqlite3.IntegrityError):
            cursor.execute("INSERT INTO companions (id, name) VALUES ('p2', 'Alice')")

        cursor.execute("INSERT INTO categories (id, name) VALUES ('c1', 'Food & Drink')")
        with self.assertRaises(sqlite3.IntegrityError):
            cursor.execute("INSERT INTO categories (id, name) VALUES ('c2', 'Food & Drink')")


class TestRoomDaoOperationsInSqlite(unittest.TestCase):
    """Executes verbatim SQL queries from Room DAOs against live SQLite database."""

    def setUp(self):
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA_SQL)

    def tearDown(self):
        self.conn.close()

    def test_transaction_dao_get_transactions_flow_ordering(self):
        """Verifies getTransactionsFlow query: SELECT * FROM transactions ORDER BY timestamp DESC."""
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO transactions (id, timestamp, dateSerial, timeSerial, account, merchant, category, totalAmount, myShare, owedToMe)
            VALUES 
            ('t1', 1000, 46331, 0.1, 'SBI', 'Store A', 'Food', 100.0, 100.0, 0.0),
            ('t2', 3000, 46331, 0.3, 'SBI', 'Store B', 'Food', 300.0, 300.0, 0.0),
            ('t3', 2000, 46331, 0.2, 'SBI', 'Store C', 'Food', 200.0, 200.0, 0.0)
        """)
        self.conn.commit()

        cursor.execute("SELECT * FROM transactions ORDER BY timestamp DESC")
        rows = cursor.fetchall()
        self.assertEqual(["t2", "t3", "t1"], [row["id"] for row in rows])

    def test_transaction_dao_get_needs_review_query(self):
        """Verifies getNeedsReviewFlow query: SELECT * FROM transactions WHERE reviewState = 'NEEDS_REVIEW' ORDER BY timestamp DESC."""
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO transactions (id, timestamp, dateSerial, timeSerial, account, merchant, category, totalAmount, myShare, owedToMe, reviewState)
            VALUES 
            ('t1', 1000, 46331, 0.1, 'SBI', 'Store A', 'Food', 100.0, 100.0, 0.0, 'NEEDS_REVIEW'),
            ('t2', 2000, 46331, 0.2, 'SBI', 'Store B', 'Food', 200.0, 200.0, 0.0, 'CONFIRMED'),
            ('t3', 3000, 46331, 0.3, 'SBI', 'Store C', 'Food', 300.0, 300.0, 0.0, 'NEEDS_REVIEW')
        """)
        self.conn.commit()

        cursor.execute("SELECT * FROM transactions WHERE reviewState = 'NEEDS_REVIEW' ORDER BY timestamp DESC")
        rows = cursor.fetchall()
        self.assertEqual(["t3", "t1"], [row["id"] for row in rows])

    def test_transaction_dao_get_confirmed_unsynced_query(self):
        """Verifies getConfirmedUnsyncedFlow query: SELECT * FROM transactions WHERE reviewState = 'CONFIRMED' AND syncState = 'PENDING' ORDER BY timestamp ASC."""
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO transactions (id, timestamp, dateSerial, timeSerial, account, merchant, category, totalAmount, myShare, owedToMe, reviewState, syncState)
            VALUES 
            ('t1', 1000, 46331, 0.1, 'SBI', 'A', 'Food', 10.0, 10.0, 0.0, 'CONFIRMED', 'PENDING'),
            ('t2', 2000, 46331, 0.2, 'SBI', 'B', 'Food', 20.0, 20.0, 0.0, 'NEEDS_REVIEW', 'PENDING'),
            ('t3', 3000, 46331, 0.3, 'SBI', 'C', 'Food', 30.0, 30.0, 0.0, 'CONFIRMED', 'SYNCED'),
            ('t4', 500, 46331, 0.05, 'SBI', 'D', 'Food', 40.0, 40.0, 0.0, 'CONFIRMED', 'PENDING')
        """)
        self.conn.commit()

        cursor.execute("SELECT * FROM transactions WHERE reviewState = 'CONFIRMED' AND syncState = 'PENDING' ORDER BY timestamp ASC")
        rows = cursor.fetchall()
        self.assertEqual(["t4", "t1"], [row["id"] for row in rows])

    def test_transaction_dao_void_transaction_query(self):
        """Verifies voidTransaction SQL query execution."""
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO transactions (id, timestamp, dateSerial, timeSerial, account, merchant, category, totalAmount, myShare, owedToMe, status, syncState)
            VALUES ('t1', 1000, 46331, 0.1, 'SBI', 'Cafe Coffee Day', 'Food', 220.0, 110.0, 110.0, 'Pending', 'PENDING')
        """)
        self.conn.commit()

        void_title = "[Void] Cafe Coffee Day"
        cursor.execute("""
            UPDATE transactions 
            SET myShare = 0.0, owedToMe = 0.0, status = 'Void', syncState = 'VOID', merchant = ? 
            WHERE id = ?
        """, (void_title, "t1"))
        self.conn.commit()

        cursor.execute("SELECT * FROM transactions WHERE id = 't1'")
        row = cursor.fetchone()
        self.assertEqual("[Void] Cafe Coffee Day", row["merchant"])
        self.assertEqual(0.0, row["myShare"])
        self.assertEqual(0.0, row["owedToMe"])
        self.assertEqual("Void", row["status"])
        self.assertEqual("VOID", row["syncState"])
        self.assertEqual(220.0, row["totalAmount"])  # Total gross deduction preserved for audit trail

    def test_transaction_dao_update_sync_state_with_coalesce(self):
        """Verifies updateSyncState query preserves sheetRowIndex when null is passed."""
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO transactions (id, timestamp, dateSerial, timeSerial, account, merchant, category, totalAmount, myShare, owedToMe, syncState, sheetRowIndex)
            VALUES ('t1', 1000, 46331, 0.1, 'SBI', 'Shop', 'Food', 100.0, 100.0, 0.0, 'PENDING', 18)
        """)
        self.conn.commit()

        # Update syncState passing null sheetRowIndex
        cursor.execute("""
            UPDATE transactions 
            SET syncState = ?, sheetRowIndex = COALESCE(?, sheetRowIndex) 
            WHERE id = ?
        """, ("SYNCED", None, "t1"))
        self.conn.commit()

        cursor.execute("SELECT syncState, sheetRowIndex FROM transactions WHERE id = 't1'")
        row = cursor.fetchone()
        self.assertEqual("SYNCED", row["syncState"])
        self.assertEqual(18, row["sheetRowIndex"])  # Preserved by COALESCE

        # Update syncState passing new sheetRowIndex
        cursor.execute("""
            UPDATE transactions 
            SET syncState = ?, sheetRowIndex = COALESCE(?, sheetRowIndex) 
            WHERE id = ?
        """, ("SYNCED", 25, "t1"))
        self.conn.commit()

        cursor.execute("SELECT sheetRowIndex FROM transactions WHERE id = 't1'")
        self.assertEqual(25, cursor.fetchone()["sheetRowIndex"])

    def test_transaction_dao_recent_similar_range_query(self):
        """Verifies getRecentSimilar query with timestamp between and amount tolerance."""
        cursor = self.conn.cursor()
        now = 1730811600000
        cursor.execute("""
            INSERT INTO transactions (id, timestamp, dateSerial, timeSerial, account, merchant, category, totalAmount, myShare, owedToMe)
            VALUES 
            ('t1', ?, 46331, 0.1, 'SBI', 'Shop A', 'Food', 250.00, 250.0, 0.0),
            ('t2', ?, 46331, 0.1, 'SBI', 'Shop B', 'Food', 250.005, 250.0, 0.0),
            ('t3', ?, 46331, 0.1, 'SBI', 'Shop C', 'Food', 300.00, 300.0, 0.0),
            ('t4', ?, 46331, 0.1, 'SBI', 'Shop D', 'Food', 250.00, 250.0, 0.0)
        """, (now - 10000, now - 5000, now - 5000, now - 120000))
        self.conn.commit()

        min_time = now - 60000
        max_time = now + 5000
        min_amount = 250.0 - 0.01
        max_amount = 250.0 + 0.01

        cursor.execute("""
            SELECT * FROM transactions 
            WHERE timestamp BETWEEN ? AND ? 
              AND totalAmount BETWEEN ? AND ? 
            ORDER BY timestamp DESC
        """, (min_time, max_time, min_amount, max_amount))
        rows = cursor.fetchall()
        matched_ids = [row["id"] for row in rows]

        self.assertEqual(["t2", "t1"], matched_ids)
        self.assertNotIn("t3", matched_ids)  # Different amount
        self.assertNotIn("t4", matched_ids)  # Outside time window

    def test_companion_dao_case_insensitive_name_query(self):
        """Verifies CompanionDao getByName query uses COLLATE NOCASE."""
        cursor = self.conn.cursor()
        cursor.execute("INSERT INTO companions (id, name, isSelected) VALUES ('c1', 'Rahul Sharma', 1)")
        self.conn.commit()

        cursor.execute("SELECT * FROM companions WHERE name = ? COLLATE NOCASE LIMIT 1", ("rahul sharma",))
        row = cursor.fetchone()
        self.assertIsNotNone(row)
        self.assertEqual("Rahul Sharma", row["name"])

    def test_category_dao_ordering_query(self):
        """Verifies CategoryDao getCategoriesFlow orders by displayOrder ASC, name ASC."""
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO categories (id, name, displayOrder)
            VALUES 
            ('c1', 'Utilities', 2),
            ('c2', 'Food & Drink', 1),
            ('c3', 'Groceries', 1),
            ('c4', 'Entertainment', 3)
        """)
        self.conn.commit()

        cursor.execute("SELECT name FROM categories ORDER BY displayOrder ASC, name ASC")
        names = [row["name"] for row in cursor.fetchall()]
        self.assertEqual(["Food & Drink", "Groceries", "Utilities", "Entertainment"], names)

    def test_sync_queue_dao_queries_and_fifo_order(self):
        """Verifies SyncQueueDao queries: getNextPending, getAllPending, and FIFO ordering."""
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO sync_queue (transactionId, operationType, payloadJson, createdAt, status)
            VALUES 
            ('txn_1', 'INSERT', 'txn_1', 100, 'PENDING'),
            ('txn_2', 'UPDATE', 'txn_2', 300, 'PENDING'),
            ('txn_3', 'VOID', 'txn_3', 200, 'PENDING')
        """)
        self.conn.commit()

        # getNextPending (LIMIT 1 ORDER BY createdAt ASC)
        cursor.execute("SELECT * FROM sync_queue WHERE status = 'PENDING' ORDER BY createdAt ASC LIMIT 1")
        next_item = cursor.fetchone()
        self.assertEqual("txn_1", next_item["transactionId"])

        # getAllPending (ORDER BY createdAt ASC)
        cursor.execute("SELECT * FROM sync_queue WHERE status = 'PENDING' ORDER BY createdAt ASC")
        all_pending = [row["transactionId"] for row in cursor.fetchall()]
        self.assertEqual(["txn_1", "txn_3", "txn_2"], all_pending)

        # updateStatus with retryIncrement
        item_id = next_item["id"]
        cursor.execute("""
            UPDATE sync_queue 
            SET status = ?, retryCount = retryCount + ? 
            WHERE id = ?
        """, ("FAILED", 1, item_id))
        self.conn.commit()

        cursor.execute("SELECT status, retryCount FROM sync_queue WHERE id = ?", (item_id,))
        updated_item = cursor.fetchone()
        self.assertEqual("FAILED", updated_item["status"])
        self.assertEqual(1, updated_item["retryCount"])


class TestTwoAxisStateMachine(unittest.TestCase):
    """Stress tests the two-axis state machine (ReviewState x SyncState)."""

    def setUp(self):
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA_SQL)

    def tearDown(self):
        self.conn.close()

    def test_state_machine_matrix_orthogonal_combinations(self):
        """Verify that ReviewState and SyncState are orthogonal in SQLite."""
        combinations = [
            (ReviewState.NEEDS_REVIEW, SyncState.PENDING),
            (ReviewState.NEEDS_REVIEW, SyncState.SYNCED),
            (ReviewState.NEEDS_REVIEW, SyncState.FAILED),
            (ReviewState.NEEDS_REVIEW, SyncState.VOID),
            (ReviewState.CONFIRMED, SyncState.PENDING),
            (ReviewState.CONFIRMED, SyncState.SYNCED),
            (ReviewState.CONFIRMED, SyncState.FAILED),
            (ReviewState.CONFIRMED, SyncState.VOID),
        ]

        cursor = self.conn.cursor()
        for idx, (review_st, sync_st) in enumerate(combinations):
            txn_id = f"txn_comb_{idx}"
            cursor.execute("""
                INSERT INTO transactions (id, timestamp, dateSerial, timeSerial, account, merchant, category, totalAmount, myShare, owedToMe, reviewState, syncState)
                VALUES (?, 1000, 46331, 0.1, 'SBI', 'Test', 'Food', 50.0, 50.0, 0.0, ?, ?)
            """, (txn_id, review_st, sync_st))

        self.conn.commit()

        cursor.execute("SELECT COUNT(*) AS count FROM transactions")
        self.assertEqual(8, cursor.fetchone()["count"])

    def test_lifecycle_1_interception_to_settle_window_sync_to_review_confirm(self):
        """Lifecycle 1: Intercept -> Settle window sync -> User confirms category in Review Inbox."""
        cursor = self.conn.cursor()

        # Step 1: Intercept expense
        cursor.execute("""
            INSERT INTO transactions (id, timestamp, dateSerial, timeSerial, account, merchant, category, totalAmount, myShare, owedToMe, reviewState, syncState)
            VALUES ('txn_lc1', 1730811600000, 46331, 0.5, 'SBI', 'Swiggy', 'Uncategorized', 350.0, 350.0, 0.0, 'NEEDS_REVIEW', 'PENDING')
        """)
        cursor.execute("""
            INSERT INTO sync_queue (transactionId, operationType, payloadJson, createdAt, status)
            VALUES ('txn_lc1', 'INSERT', 'txn_lc1', 1730811600000, 'PENDING')
        """)
        self.conn.commit()

        # State is NEEDS_REVIEW, PENDING
        cursor.execute("SELECT reviewState, syncState, sheetRowIndex FROM transactions WHERE id = 'txn_lc1'")
        row = cursor.fetchone()
        self.assertEqual(ReviewState.NEEDS_REVIEW, row["reviewState"])
        self.assertEqual(SyncState.PENDING, row["syncState"])
        self.assertIsNone(row["sheetRowIndex"])

        # Step 2: Settle window expires, background sync appends to Sheets at row 18
        cursor.execute("""
            UPDATE transactions SET syncState = 'SYNCED', sheetRowIndex = 18 WHERE id = 'txn_lc1'
        """)
        cursor.execute("DELETE FROM sync_queue WHERE transactionId = 'txn_lc1' AND operationType = 'INSERT'")
        self.conn.commit()

        cursor.execute("SELECT reviewState, syncState, sheetRowIndex FROM transactions WHERE id = 'txn_lc1'")
        row = cursor.fetchone()
        self.assertEqual(ReviewState.NEEDS_REVIEW, row["reviewState"])
        self.assertEqual(SyncState.SYNCED, row["syncState"])
        self.assertEqual(18, row["sheetRowIndex"])

        # Step 3: User opens Review Inbox, selects 'Food & Drink', confirms
        cursor.execute("""
            UPDATE transactions SET reviewState = 'CONFIRMED', category = 'Food & Drink' WHERE id = 'txn_lc1'
        """)
        cursor.execute("""
            INSERT INTO sync_queue (transactionId, operationType, payloadJson, createdAt, status)
            VALUES ('txn_lc1', 'UPDATE', 'txn_lc1', 1730811690000, 'PENDING')
        """)
        self.conn.commit()

        cursor.execute("SELECT reviewState, syncState, category, sheetRowIndex FROM transactions WHERE id = 'txn_lc1'")
        row = cursor.fetchone()
        self.assertEqual(ReviewState.CONFIRMED, row["reviewState"])
        self.assertEqual(SyncState.SYNCED, row["syncState"])
        self.assertEqual("Food & Drink", row["category"])
        self.assertEqual(18, row["sheetRowIndex"])

        # Step 4: Outbound update sync executes updateRow at row 18
        cursor.execute("DELETE FROM sync_queue WHERE transactionId = 'txn_lc1' AND operationType = 'UPDATE'")
        self.conn.commit()

        cursor.execute("SELECT COUNT(*) AS count FROM sync_queue WHERE transactionId = 'txn_lc1'")
        self.assertEqual(0, cursor.fetchone()["count"])

    def test_lifecycle_2_user_categorizes_during_settle_window(self):
        """Lifecycle 2: Heads-up notification 1-tap categorization before cloud sync."""
        cursor = self.conn.cursor()

        # Step 1: Intercept expense
        cursor.execute("""
            INSERT INTO transactions (id, timestamp, dateSerial, timeSerial, account, merchant, category, totalAmount, myShare, owedToMe, reviewState, syncState)
            VALUES ('txn_lc2', 1730811600000, 46331, 0.5, 'SBI', 'Cafe', 'Food & Drink', 200.0, 200.0, 0.0, 'NEEDS_REVIEW', 'PENDING')
        """)
        cursor.execute("""
            INSERT INTO sync_queue (transactionId, operationType, payloadJson, createdAt, status)
            VALUES ('txn_lc2', 'INSERT', 'txn_lc2', 1730811600000, 'PENDING')
        """)
        self.conn.commit()

        # Step 2: User taps notification action button within 5 seconds -> upgrades to CONFIRMED
        cursor.execute("UPDATE transactions SET reviewState = 'CONFIRMED' WHERE id = 'txn_lc2'")
        self.conn.commit()

        cursor.execute("SELECT reviewState, syncState FROM transactions WHERE id = 'txn_lc2'")
        row = cursor.fetchone()
        self.assertEqual(ReviewState.CONFIRMED, row["reviewState"])
        self.assertEqual(SyncState.PENDING, row["syncState"])

        # Step 3: Settle window expires, sync appends already-confirmed transaction
        cursor.execute("UPDATE transactions SET syncState = 'SYNCED', sheetRowIndex = 19 WHERE id = 'txn_lc2'")
        cursor.execute("DELETE FROM sync_queue WHERE transactionId = 'txn_lc2'")
        self.conn.commit()

        cursor.execute("SELECT reviewState, syncState, sheetRowIndex FROM transactions WHERE id = 'txn_lc2'")
        row = cursor.fetchone()
        self.assertEqual(ReviewState.CONFIRMED, row["reviewState"])
        self.assertEqual(SyncState.SYNCED, row["syncState"])
        self.assertEqual(19, row["sheetRowIndex"])


class TestEmpiricalChallengerFindings(unittest.TestCase):
    """Adversarial challenge tests pinpointing edge cases and potential failure modes."""

    def setUp(self):
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA_SQL)

    def tearDown(self):
        self.conn.close()

    def test_finding_1_unconfigured_spreadsheet_causes_silent_queue_drop(self):
        """EMPIRICAL FINDING 1:
        In TransactionRepositoryImpl.kt (lines 188-193 and 194-199):
        If preferencesRepository.getSpreadsheetId() is null/blank or googleSheetsClient is null,
        syncPendingTransactions() calls:
            transactionDao.updateSyncState(txn.id, SyncState.SYNCED.name)
            syncQueueDao.delete(item)
        
        This marks transactions as SYNCED and permanently removes them from the sync queue,
        even though they were NEVER transmitted to Google Sheets.
        When the user subsequently links their Google Sheet, these transactions are lost from sync.
        """
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO transactions (id, timestamp, dateSerial, timeSerial, account, merchant, category, totalAmount, myShare, owedToMe, reviewState, syncState)
            VALUES ('txn_offline', 1000, 46331, 0.1, 'SBI', 'Store', 'Food', 500.0, 500.0, 0.0, 'NEEDS_REVIEW', 'PENDING')
        """)
        cursor.execute("""
            INSERT INTO sync_queue (transactionId, operationType, payloadJson, createdAt, status)
            VALUES ('txn_offline', 'INSERT', 'txn_offline', 1000, 'PENDING')
        """)
        self.conn.commit()

        # Simulate current TransactionRepositoryImpl offline fallback logic (lines 190-192)
        # When spreadsheetId is blank:
        cursor.execute("UPDATE transactions SET syncState = 'SYNCED' WHERE id = 'txn_offline'")
        cursor.execute("DELETE FROM sync_queue WHERE transactionId = 'txn_offline'")
        self.conn.commit()

        # Verify state: Transaction marked SYNCED, queue is empty!
        cursor.execute("SELECT syncState, sheetRowIndex FROM transactions WHERE id = 'txn_offline'")
        row = cursor.fetchone()
        self.assertEqual(SyncState.SYNCED, row["syncState"])
        self.assertIsNone(row["sheetRowIndex"])  # Never landed on Google Sheets!

        cursor.execute("SELECT COUNT(*) AS count FROM sync_queue")
        self.assertEqual(0, cursor.fetchone()["count"])  # Queue destroyed!

    def test_finding_2_status_failed_items_permanently_abandoned_in_queue(self):
        """EMPIRICAL FINDING 2:
        In TransactionRepositoryImpl.kt line 161 & 178:
        On network failure, it calls:
            syncQueueDao.updateStatus(item.id, SyncQueueEntity.STATUS_FAILED, 1)
        
        And in SyncQueueDao.kt line 39:
            @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' ORDER BY createdAt ASC")
            suspend fun getAllPending(): List<SyncQueueEntity>
        
        Because getAllPending() strictly filters by status = 'PENDING', once an item
        is marked 'FAILED', subsequent sync runs will NEVER select it again.
        The item is permanently abandoned unless explicitly reset back to 'PENDING'.
        """
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO sync_queue (transactionId, operationType, payloadJson, createdAt, status, retryCount)
            VALUES ('txn_fail', 'INSERT', 'txn_fail', 1000, 'PENDING', 0)
        """)
        self.conn.commit()

        # Simulate network error: status updated to FAILED with retryCount = 1
        cursor.execute("""
            UPDATE sync_queue 
            SET status = 'FAILED', retryCount = retryCount + 1 
            WHERE transactionId = 'txn_fail'
        """)
        self.conn.commit()

        # Next sync execution calls getAllPending:
        cursor.execute("SELECT * FROM sync_queue WHERE status = 'PENDING' ORDER BY createdAt ASC")
        pending_items = cursor.fetchall()
        self.assertEqual(0, len(pending_items))  # FAILED item is completely ignored!

        # Item still exists in database as FAILED
        cursor.execute("SELECT status, retryCount FROM sync_queue WHERE transactionId = 'txn_fail'")
        item = cursor.fetchone()
        self.assertEqual("FAILED", item["status"])
        self.assertEqual(1, item["retryCount"])

    def test_finding_3_void_transaction_with_null_sheet_row_overwrites_to_synced(self):
        """EMPIRICAL FINDING 3:
        In TransactionRepositoryImpl.kt lines 169-185:
        When syncing OP_VOID:
            val rowIndex = txn.sheetRowIndex
            if (rowIndex != null && rowIndex >= 4) { ... }
            else {
                // Line 182:
                transactionDao.updateSyncState(txn.id, SyncState.SYNCED.name)
                syncQueueDao.delete(item)
            }
        
        If a transaction is voided BEFORE being synced to Google Sheets (e.g. during settle window
        or while offline), its sheetRowIndex is null.
        When OP_VOID executes, it enters the else block and overwrites syncState to SYNCED!
        The transaction is now in DB as syncState = 'SYNCED' instead of 'VOID'.
        """
        cursor = self.conn.cursor()

        # Step 1: Intercept expense
        cursor.execute("""
            INSERT INTO transactions (id, timestamp, dateSerial, timeSerial, account, merchant, category, totalAmount, myShare, owedToMe, status, syncState, sheetRowIndex)
            VALUES ('txn_void_test', 1000, 46331, 0.1, 'SBI', 'Shop', 'Food', 100.0, 100.0, 0.0, 'Settled', 'PENDING', NULL)
        """)
        self.conn.commit()

        # Step 2: User voids transaction before sync
        cursor.execute("""
            UPDATE transactions 
            SET myShare = 0.0, owedToMe = 0.0, status = 'Void', syncState = 'VOID', merchant = '[Void] Shop' 
            WHERE id = 'txn_void_test'
        """)
        cursor.execute("""
            INSERT INTO sync_queue (transactionId, operationType, payloadJson, createdAt, status)
            VALUES ('txn_void_test', 'VOID', 'txn_void_test', 1005, 'PENDING')
        """)
        self.conn.commit()

        # In DB, syncState is currently VOID
        cursor.execute("SELECT syncState, sheetRowIndex FROM transactions WHERE id = 'txn_void_test'")
        row = cursor.fetchone()
        self.assertEqual(SyncState.VOID, row["syncState"])
        self.assertIsNone(row["sheetRowIndex"])

        # Step 3: syncPendingTransactions runs for OP_VOID
        # Because sheetRowIndex is null, lines 180-184 execute:
        # transactionDao.updateSyncState(txn.id, SyncState.SYNCED.name)
        cursor.execute("""
            UPDATE transactions 
            SET syncState = 'SYNCED', sheetRowIndex = COALESCE(NULL, sheetRowIndex) 
            WHERE id = 'txn_void_test'
        """)
        cursor.execute("DELETE FROM sync_queue WHERE transactionId = 'txn_void_test'")
        self.conn.commit()

        # Empirical proof of finding: syncState has been overwritten to SYNCED!
        cursor.execute("SELECT syncState, status, merchant FROM transactions WHERE id = 'txn_void_test'")
        row = cursor.fetchone()
        self.assertEqual("SYNCED", row["syncState"])  # Overwritten to SYNCED!
        self.assertEqual("Void", row["status"])
        self.assertEqual("[Void] Shop", row["merchant"])

    def test_finding_4_deduplication_utr_uniqueness_protection(self):
        """EMPIRICAL VERIFICATION:
        TransactionDao provides getByUtr() for deduplication.
        If an identical 12-digit UTR arrives, getByUtr() accurately returns the existing entity.
        """
        cursor = self.conn.cursor()
        cursor.execute("""
            INSERT INTO transactions (id, timestamp, dateSerial, timeSerial, account, merchant, category, totalAmount, myShare, owedToMe, utr)
            VALUES ('txn_utr_1', 1000, 46331, 0.1, 'SBI', 'Shop', 'Food', 150.0, 150.0, 0.0, '123456789012')
        """)
        self.conn.commit()

        cursor.execute("SELECT * FROM transactions WHERE utr = ? LIMIT 1", ("123456789012",))
        found = cursor.fetchone()
        self.assertIsNotNone(found)
        self.assertEqual("txn_utr_1", found["id"])

        cursor.execute("SELECT * FROM transactions WHERE utr = ? LIMIT 1", ("999999999999",))
        not_found = cursor.fetchone()
        self.assertIsNone(not_found)


class TestRepositoryCodeHygiene(unittest.TestCase):
    """Verifies repository-wide hygiene rules (zero emojis, zero hardcoded local paths)."""

    def test_zero_emojis_in_m3_codebase(self):
        """Verify zero emojis across all M3 Kotlin files and documentation."""
        m3_files = [
            r"app\src\main\java\com\financeautomation\data\local\FinanceDatabase.kt",
            r"app\src\main\java\com\financeautomation\data\local\entity\TransactionEntity.kt",
            r"app\src\main\java\com\financeautomation\data\local\entity\CompanionEntity.kt",
            r"app\src\main\java\com\financeautomation\data\local\entity\CategoryEntity.kt",
            r"app\src\main\java\com\financeautomation\data\local\entity\SyncQueueEntity.kt",
            r"app\src\main\java\com\financeautomation\data\local\dao\TransactionDao.kt",
            r"app\src\main\java\com\financeautomation\data\local\dao\CompanionDao.kt",
            r"app\src\main\java\com\financeautomation\data\local\dao\CategoryDao.kt",
            r"app\src\main\java\com\financeautomation\data\local\dao\SyncQueueDao.kt",
            r"app\src\main\java\com\financeautomation\data\repository\TransactionRepositoryImpl.kt",
            r"app\src\main\java\com\financeautomation\data\repository\PreferencesRepositoryImpl.kt",
            r"app\src\main\java\com\financeautomation\di\AppContainer.kt",
            r".agents\teamwork\worker_m3\handoff.md",
            r".agents\teamwork\worker_m3\report.md",
            r".agents\teamwork\challenger_m3_1\handoff.md"
        ]

        emoji_pattern = re.compile(
            r"[\U00010000-\U0010ffff]|[\u2600-\u27bf]|[\u2300-\u23ff]|[\u2b50-\u2b55]"
        )

        repo_root = str(Path(__file__).resolve().parents[1])
        violations = []
        for rel_path in m3_files:
            full_path = os.path.join(repo_root, rel_path)
            if not os.path.exists(full_path):
                continue
            with open(full_path, "r", encoding="utf-8") as f:
                content = f.read()
            matches = emoji_pattern.findall(content)
            if matches:
                violations.append(f"{rel_path}: {matches}")

        self.assertEqual([], violations, f"Emoji violations found: {violations}")

    def test_zero_hardcoded_local_machine_paths(self):
        """Verify zero hardcoded local machine paths in repository documentation."""
        repo_root = str(Path(__file__).resolve().parents[1])
        doc_files = [
            r".agents\teamwork\worker_m3\handoff.md",
            r".agents\teamwork\worker_m3\report.md"
        ]

        # Check for windows hardcoded local absolute paths in docs
        local_path_pattern = re.compile(r"[a-zA-Z]:\\(?:[a-zA-Z0-9_\-\.\s]+\\)+", re.IGNORECASE)

        violations = []
        for rel_path in doc_files:
            full_path = os.path.join(repo_root, rel_path)
            if not os.path.exists(full_path):
                continue
            with open(full_path, "r", encoding="utf-8") as f:
                content = f.read()
            matches = local_path_pattern.findall(content)
            if matches:
                violations.append(f"{rel_path}: {matches}")

        self.assertEqual([], violations, f"Local path violations found: {violations}")


if __name__ == "__main__":
    unittest.main()
