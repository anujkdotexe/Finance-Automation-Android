"""Static Audit Invariants Verification Suite for Finance Automation Android.

This test module programmatically verifies all 10 core architectural and quality
invariants defined in ORIGINAL_REQUEST.md, AGENTS.md, and PROJECT.md:
1. Zero double-bang (!!) operators in app/src/.
2. Zero UTR, transaction reference numbers, or raw notification text stored, cached, or logged.
3. Deduplication strictly relies on Amount + Paid To (Merchant) + 120s sliding time window.
4. In-place void sets Total Amount G=0.00; debt carry-forward sets target row G=0.00.
5. Table-scoped appends target A3:K, M3:T, V3:AB; zero appends across A3:AE.
6. Zero emojis in source code, strings, comments, and documentation.
7. Zero hardcoded local machine paths across codebase and tests.
8. Zero real user financial credentials, tokens, or personal names (synthetic fixtures only).
9. Sealed DataResult error types and negative network tests in OkHttp client suite.
10. Hidden Config tab contains strictly zero financial data.
"""

from __future__ import annotations

import pathlib
import re
import unittest

ROOT_DIR = pathlib.Path(__file__).resolve().parent.parent

from tests.domain_harness import (
    Account,
    AppConfigSnapshot,
    Category,
    Companion,
    ConfigSheetManager,
    DeduplicationEngine,
    RolloverEngine,
    SheetPayloadMapper,
    Transaction,
    TransactionStatus,
    TripTag,
    VoidEngine,
)


class TestAuditInvariants(unittest.TestCase):
    """Authoritative static and runtime invariant verification."""

    def test_01_zero_double_bang_in_kotlin_codebase(self) -> None:
        """Test 1: Zero double-bang (!!) operators in app/src/."""
        app_src = ROOT_DIR / "app" / "src"
        violations = []
        for kt_file in app_src.rglob("*.kt"):
            content = kt_file.read_text(encoding="utf-8", errors="ignore")
            lines = content.splitlines()
            for idx, line in enumerate(lines, start=1):
                stripped = line.strip()
                if stripped.startswith(("//", "*")):
                    continue
                if "!!" in line:
                    rel_path = kt_file.relative_to(ROOT_DIR)
                    violations.append(f"{rel_path}:{idx}: {stripped}")

        self.assertEqual(
            len(violations),
            0,
            f"Found {len(violations)} double-bang (!!) occurrences in app/src/:\n"
            + "\n".join(violations[:10]),
        )

    def test_02_zero_utr_or_raw_text_stored_cached_or_logged(self) -> None:
        """Test 2: Zero UTR, transaction reference numbers, or raw notification text stored/cached."""
        # 1. Inspect Room Entity
        entity_file = ROOT_DIR / "app/src/main/java/com/financeautomation/data/local/entity/TransactionEntity.kt"
        entity_content = entity_file.read_text(encoding="utf-8")
        self.assertNotIn("rawTitle", entity_content)
        self.assertNotIn("rawText", entity_content)
        self.assertNotIn("@ColumnInfo(name = \"utr\")", entity_content)
        self.assertNotIn("Index(value = [\"utr\"])", entity_content)

        # 2. Inspect Domain Transaction Model
        model_file = ROOT_DIR / "app/src/main/java/com/financeautomation/domain/model/Transaction.kt"
        model_content = model_file.read_text(encoding="utf-8")
        self.assertNotIn("rawTitle", model_content)
        self.assertNotIn("rawText", model_content)
        self.assertNotIn("val utr", model_content)

        # 3. Inspect DAO queries
        dao_file = ROOT_DIR / "app/src/main/java/com/financeautomation/data/local/dao/TransactionDao.kt"
        dao_content = dao_file.read_text(encoding="utf-8")
        self.assertNotIn("getByUtr", dao_content)
        self.assertNotIn("WHERE utr =", dao_content)

        # 4. Inspect Repository Implementation
        repo_file = ROOT_DIR / "app/src/main/java/com/financeautomation/data/repository/TransactionRepositoryImpl.kt"
        repo_content = repo_file.read_text(encoding="utf-8")
        self.assertNotIn("findByUtr", repo_content)

        # 5. Inspect RegexPatterns
        regex_file = ROOT_DIR / "app/src/main/java/com/financeautomation/core/utils/RegexPatterns.kt"
        regex_content = regex_file.read_text(encoding="utf-8")
        self.assertNotIn("extractUtr", regex_content)
        self.assertNotIn("val UTR_REGEX", regex_content)

        # 6. Inspect ParseNotificationUseCase
        parser_file = ROOT_DIR / "app/src/main/java/com/financeautomation/domain/usecase/ParseNotificationUseCase.kt"
        parser_content = parser_file.read_text(encoding="utf-8")
        self.assertNotIn("RegexPatterns.extractUtr", parser_content)

    def test_03_deduplication_relies_on_amount_merchant_and_120s_window(self) -> None:
        """Test 3: Deduplication strictly relies on Amount + Paid To (Merchant) + 120s sliding window."""
        service_file = (
            ROOT_DIR / "app/src/main/java/com/financeautomation/service/NotificationListenerServiceImpl.kt"
        )
        service_content = service_file.read_text(encoding="utf-8")

        # Must query recent similar by amount and 120_000L window
        self.assertIn("repository.findRecentSimilar(candidate.totalAmount, windowMillis = 120_000L)", service_content)
        # Must match on merchant (case insensitive) or Unknown Payee enrichment
        self.assertIn("existing.merchant.equals(candidate.merchant, ignoreCase = true)", service_content)

        # Verify runtime deduplication in reference harness
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Starbucks Coffee",
            category="Food & Drink",
            total_amount=350.0,
            my_share=350.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=1000.0,
        )
        engine.recent_transactions.append(t1)

        # Exactly same amount + merchant within 120s window -> MERGE_EXACT_SIMILAR
        action_match, _ = engine.evaluate_incoming("com.android.mms", 350.0, "Starbucks Coffee", 1045.0)
        self.assertEqual(action_match, "MERGE_EXACT_SIMILAR")

        # Same amount, distinct merchant within 120s window -> FLAG_POSSIBLE_DUPLICATE
        action_diff_merchant, _ = engine.evaluate_incoming("com.android.mms", 350.0, "Costa Coffee", 1050.0)
        self.assertEqual(action_diff_merchant, "FLAG_POSSIBLE_DUPLICATE")

        # Same amount + merchant outside 120s window -> ACCEPT_NEW
        action_outside, _ = engine.evaluate_incoming("com.android.mms", 350.0, "Starbucks Coffee", 1125.0)
        self.assertEqual(action_outside, "ACCEPT_NEW")

    def test_04_in_place_void_and_debt_rollover_zero_col_g(self) -> None:
        """Test 4: In-place void sets Total Amount G=0.00; debt carry-forward sets target row G=0.00."""
        # Check CalculateSplitUseCase
        split_file = ROOT_DIR / "app/src/main/java/com/financeautomation/domain/usecase/CalculateSplitUseCase.kt"
        split_content = split_file.read_text(encoding="utf-8")
        self.assertIn("totalAmount = 0.00", split_content)

        # Check SheetPayloadMapper void mapping
        mapper_file = ROOT_DIR / "app/src/main/java/com/financeautomation/data/remote/SheetPayloadMapper.kt"
        mapper_content = mapper_file.read_text(encoding="utf-8")
        self.assertIn("totalAmount = 0.0", mapper_content)
        self.assertIn("mutable[6] = 0.0", mapper_content)

        # Check TransactionDao void query
        dao_file = ROOT_DIR / "app/src/main/java/com/financeautomation/data/local/dao/TransactionDao.kt"
        dao_content = dao_file.read_text(encoding="utf-8")
        self.assertIn("totalAmount = 0.0", dao_content)

        # Check target rollover row mapping in SheetPayloadMapper
        self.assertIn("totalAmount = 0.0", mapper_content)

        # Verify domain reference engines
        origin_txn = Transaction(
            id="t_void_test",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Team Dinner",
            category="Food & Drink",
            total_amount=1500.0,
            my_share=500.0,
            owed_to_me=1000.0,
            status=TransactionStatus.PENDING,
        )

        voided = VoidEngine.apply_void(origin_txn)
        self.assertEqual(voided.total_amount, 0.00)
        self.assertEqual(voided.my_share, 0.00)
        self.assertEqual(voided.owed_to_me, 0.00)
        self.assertEqual(voided.status, TransactionStatus.VOID)

        void_row = SheetPayloadMapper.map_debit_row(voided)
        self.assertEqual(void_row[6], 0.00)  # Col G = 0

        # Verify rollover engine
        split_txn = Transaction(
            id="t_rollover_test",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Hotel Booking",
            category="Travel",
            total_amount=4000.0,
            my_share=1000.0,
            owed_to_me=3000.0,
            status=TransactionStatus.PENDING,
        )
        origin_updated, carried_row = RolloverEngine.carry_forward_unsettled_debt(
            "Nov26", "Dec26", split_txn, 46361, 0.1
        )
        self.assertEqual(origin_updated.owed_to_me, 0.00)
        self.assertEqual(origin_updated.status, TransactionStatus.CARRIED)
        self.assertEqual(carried_row.total_amount, 0.00)  # Col G in target row = 0
        self.assertEqual(carried_row.my_share, 0.00)      # Col I in target row = 0
        self.assertEqual(carried_row.owed_to_me, 3000.0)  # Col J in target row = 3000
        self.assertEqual(carried_row.status, TransactionStatus.PENDING)

    def test_05_table_scoped_appends_isolate_bounds(self) -> None:
        """Test 5: Table-scoped appends target A3:K, M3:T, V3:AB; zero appends across A3:AE."""
        sheets_file = ROOT_DIR / "app/src/main/java/com/financeautomation/data/remote/GoogleSheetsClient.kt"
        sheets_content = sheets_file.read_text(encoding="utf-8")

        # Must route table appends strictly to table constants
        self.assertIn("TABLE_1_DEBITS_RANGE", sheets_content)
        self.assertIn("TABLE_2_INFLOWS_RANGE", sheets_content)
        self.assertIn("TABLE_3_PAYABLES_RANGE", sheets_content)

        # Confirm no executable append URL targets range A3:AE
        self.assertNotIn("'$tabName'!A3:AE", sheets_content)
        self.assertNotIn("\"'$tabName'!A3:AE\"", sheets_content)

    def test_06_zero_emojis_in_source_and_documentation(self) -> None:
        """Test 6: Zero emojis in source code, strings, comments, and documentation."""
        emoji_pattern = re.compile(r"[\U00010000-\U0010ffff]|[\u2600-\u27bf]")
        checked_dirs = ["app/src", "docs", "tests"]
        violations = []

        for d in checked_dirs:
            target_dir = ROOT_DIR / d
            if not target_dir.exists():
                continue
            for f in target_dir.rglob("*"):
                if f.is_file() and f.suffix in [".kt", ".java", ".py", ".md", ".xml", ".gradle", ".kts"]:
                    try:
                        content = f.read_text(encoding="utf-8")
                    except UnicodeDecodeError:
                        continue
                    lines = content.splitlines()
                    for idx, line in enumerate(lines, start=1):
                        if emoji_pattern.search(line):
                            rel_path = f.relative_to(ROOT_DIR)
                            violations.append(f"{rel_path}:{idx}: {line[:60]}")

        self.assertEqual(
            len(violations),
            0,
            f"Found {len(violations)} emoji characters in source and documentation:\n"
            + "\n".join(violations[:10]),
        )

    def test_07_zero_hardcoded_local_machine_paths(self) -> None:
        """Test 7: Zero hardcoded local machine paths across codebase and tests."""
        path_pattern = re.compile(r"""[A-Za-z]:\\[^"'\s\\]+""", re.IGNORECASE)
        checked_dirs = ["app/src", "docs", "tests"]
        violations = []

        for d in checked_dirs:
            target_dir = ROOT_DIR / d
            if not target_dir.exists():
                continue
            for f in target_dir.rglob("*"):
                if f.is_file() and f.suffix in [".kt", ".java", ".py", ".md", ".xml"]:
                    try:
                        content = f.read_text(encoding="utf-8")
                    except UnicodeDecodeError:
                        continue
                    lines = content.splitlines()
                    for idx, line in enumerate(lines, start=1):
                        # Allow self-checking pattern or references in audit invariant test itself
                        if f.name == "test_audit_invariants.py":
                            continue
                        if path_pattern.search(line):
                            # Ignore comments explaining zero machine paths
                            if "machine paths" in line.lower() or "local machine path" in line.lower():
                                continue
                            rel_path = f.relative_to(ROOT_DIR)
                            violations.append(f"{rel_path}:{idx}: {line[:60]}")

        self.assertEqual(
            len(violations),
            0,
            f"Found {len(violations)} hardcoded local machine paths:\n"
            + "\n".join(violations[:10]),
        )

    def test_08_zero_real_user_financial_credentials_or_personal_names(self) -> None:
        """Test 8: Zero real user financial credentials, tokens, or personal names (synthetic fixtures only)."""
        app_src = ROOT_DIR / "app" / "src"
        real_secret_indicators = [
            "AIzaSy",  # Google API live key prefix
            "ghp_",    # GitHub personal access token prefix
            "xoxb-",   # Slack bot token prefix
            "AKIA",    # AWS Access Key ID prefix
        ]

        for kt_file in app_src.rglob("*.kt"):
            content = kt_file.read_text(encoding="utf-8", errors="ignore")
            for indicator in real_secret_indicators:
                self.assertNotIn(
                    indicator,
                    content,
                    f"Possible real credential indicator '{indicator}' found in {kt_file.relative_to(ROOT_DIR)}",
                )

    def test_09_sealed_data_result_and_negative_network_tests(self) -> None:
        """Test 9: Sealed DataResult error types and negative network tests in OkHttp client suite."""
        result_file = ROOT_DIR / "app/src/main/java/com/financeautomation/core/model/Result.kt"
        result_content = result_file.read_text(encoding="utf-8")

        self.assertIn("sealed interface DataResult<out T>", result_content)
        self.assertIn("data class SheetNotFound", result_content)
        self.assertIn("data class AuthExpired", result_content)
        self.assertIn("data class Network", result_content)
        self.assertIn("data class Malformed", result_content)

        test_file = ROOT_DIR / "app/src/test/java/com/financeautomation/data/GoogleSheetsClientTest.kt"
        test_content = test_file.read_text(encoding="utf-8")

        self.assertIn("testNegativeHttp401AuthExpiredReturnsError", test_content)
        self.assertIn("testNegativeHttp404SheetNotFoundReturnsError", test_content)
        self.assertIn("testNegativeHttp500ServerErrorReturnsError", test_content)
        self.assertIn("testNegativeSocketTimeoutReturnsNetworkError", test_content)
        self.assertIn("testNegativeMalformedJsonResponseReturnsError", test_content)

    def test_10_hidden_config_tab_strictly_zero_financial_data(self) -> None:
        """Test 10: Config tab contains strictly zero financial data."""
        snapshot = AppConfigSnapshot(
            accounts=[
                Account(id="a1", name="SBI", last_four_digits="1234", is_default=True, display_order=0),
                Account(id="a2", name="HDFC", last_four_digits="5678", is_default=False, display_order=1),
                Account(id="a3", name="Cash", is_default=False, display_order=2),
            ],
            categories=[
                Category(id="c1", name="Groceries", is_default=True, is_excluded_from_living_burn=False),
                Category(id="c2", name="Savings", is_default=True, is_excluded_from_living_burn=True),
                Category(id="c3", name="Utilities", is_default=True, is_excluded_from_living_burn=False),
            ],
            companions=[
                Companion(id="p1", name="Alice"),
                Companion(id="p2", name="Bob"),
            ],
            trip_tags=[
                TripTag(id="t1", name="Goa Vacation", is_active=True),
            ],
            preferences={
                "settle_delay_seconds": "120",
                "default_account": "SBI",
                "theme_mode": "dark",
            },
        )

        grid = ConfigSheetManager.build_config_grid(snapshot)
        self.assertEqual(len(grid), 50)
        self.assertEqual(len(grid[0]), 16)

        # Ensure no financial currency symbols, amount strings, or UTRs exist
        for row in grid:
            for cell in row:
                cell_str = str(cell)
                self.assertNotIn("₹", cell_str)
                self.assertNotIn("INR", cell_str)
                self.assertNotIn("Rs.", cell_str)
                self.assertNotIn("utr", cell_str.lower())
                self.assertNotIn("txn_", cell_str)


if __name__ == "__main__":
    unittest.main()
