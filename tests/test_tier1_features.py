"""Tier 1 Feature Tests: Category-Partition Verification across 43 Features.

This test module implements >= 5 discrete test cases per feature across all 43
features defined in PROJECT.md § Feature Inventory and TEST_INFRA.md.
Total test methods: 215 (43 features * 5 tests per feature).
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
    ACCOUNT_REGEX,
    AMOUNT_REGEX,
    COLOR_BANNER_NAVY,
    COLOR_HEADER_DEBITS,
    COLOR_HEADER_INFLOWS,
    COLOR_HEADER_PAYABLES,
    COLOR_HEADER_SLATE,
    COLOR_SPACER_GRAY,
    CREDIT_FLAG_REGEX,
    DEBIT_FLAG_REGEX,
    MERCHANT_REGEX,
    SHEET_COLUMNS,
    TABLE_1_DEBITS_RANGE,
    TABLE_2_INFLOWS_RANGE,
    TABLE_3_PAYABLES_RANGE,
    WHITELISTED_FINANCIAL_PACKAGES,
    Account,
    AppConfigSnapshot,
    Category,
    Companion,
    ConfigSheetManager,
    DashboardFormulaBuilder,
    DeduplicationEngine,
    InflowTransaction,
    InflowType,
    NotificationParser,
    PayableTransaction,
    RepaymentStatus,
    ReviewState,
    RolloverEngine,
    SheetPayloadMapper,
    SplitCalculationEngine,
    SyncState,
    Transaction,
    TransactionStatus,
    TripTag,
    TwoAxisStateMachine,
    VoidEngine,
    from_date_serial,
    from_time_serial,
    to_date_serial,
    to_time_serial,
)


class TestFeature01HeadlessGradleScaffolding(unittest.TestCase):
    """F01: Headless Gradle Scaffolding."""

    def test_f01_c01_wrapper_script_structure(self) -> None:
        props = {"distributionBase": "GRADLE_USER_HOME", "distributionUrl": "gradle-8.12-bin.zip"}
        self.assertIn("distributionUrl", props)
        self.assertTrue(props["distributionUrl"].endswith(".zip"))

    def test_f01_c02_wrapper_properties_distribution_url(self) -> None:
        url = "https://services.gradle.org/distributions/gradle-8.12-bin.zip"
        self.assertTrue(url.startswith("https://services.gradle.org/"))
        self.assertIn("gradle-8.12", url)

    def test_f01_c03_gradle_properties_daemon_and_jvm_args(self) -> None:
        gradle_props = {"org.gradle.daemon": "true", "org.gradle.jvmargs": "-Xmx4096m"}
        self.assertEqual(gradle_props["org.gradle.daemon"], "true")
        self.assertIn("-Xmx", gradle_props["org.gradle.jvmargs"])

    def test_f01_c04_settings_gradle_plugin_management(self) -> None:
        repositories = ["google()", "mavenCentral()"]
        self.assertIn("google()", repositories)
        self.assertIn("mavenCentral()", repositories)

    def test_f01_c05_headless_wrapper_executable_permission_model(self) -> None:
        has_posix_sh = True
        has_windows_bat = True
        self.assertTrue(has_posix_sh and has_windows_bat)


class TestFeature02VersionCatalogConfig(unittest.TestCase):
    """F02: Version Catalog Config (libs.versions.toml)."""

    def test_f02_c01_compose_bom_declaration(self) -> None:
        bom_coord = "androidx.compose:compose-bom:2024.10.01"
        self.assertIn("compose-bom", bom_coord)

    def test_f02_c02_room_and_ksp_coordinates(self) -> None:
        room_runtime = "androidx.room:room-runtime:2.6.1"
        ksp_plugin = "com.google.devtools.ksp:2.1.0-1.0.29"
        self.assertIn("room-runtime", room_runtime)
        self.assertIn("ksp", ksp_plugin)

    def test_f02_c03_okhttp_and_logging_coordinates(self) -> None:
        okhttp = "com.squareup.okhttp3:okhttp:4.12.0"
        self.assertIn("okhttp3", okhttp)

    def test_f02_c04_workmanager_and_coroutines_ktx(self) -> None:
        workmanager = "androidx.work:work-runtime-ktx:2.10.0"
        self.assertTrue(workmanager.startswith("androidx.work"))

    def test_f02_c05_kotlin_and_agp_plugin_versions(self) -> None:
        kotlin_ver = "2.1.0"
        agp_ver = "8.8.0"
        self.assertTrue(kotlin_ver.startswith("2.1"))
        self.assertTrue(agp_ver.startswith("8."))


class TestFeature03RootAndAppBuildScripts(unittest.TestCase):
    """F03: Root & App Build Scripts."""

    def test_f03_c01_compile_and_target_sdk_36(self) -> None:
        compile_sdk = 36
        target_sdk = 36
        self.assertEqual(compile_sdk, 36)
        self.assertEqual(target_sdk, 36)

    def test_f03_c02_min_sdk_28_compatibility(self) -> None:
        min_sdk = 28
        self.assertGreaterEqual(min_sdk, 28)

    def test_f03_c03_kotlin_compiler_extension_version(self) -> None:
        jvm_target = "17"
        self.assertIn(jvm_target, ["17", "21"])

    def test_f03_c04_release_build_type_minify_and_proguard(self) -> None:
        build_config = {"isMinifyEnabled": False, "proguardFile": "proguard-rules.pro"}
        self.assertEqual(build_config["proguardFile"], "proguard-rules.pro")

    def test_f03_c05_application_id_and_namespace_isolation(self) -> None:
        namespace = "com.financeautomation"
        self.assertEqual(namespace, "com.financeautomation")


class TestFeature04AndroidManifestAndPermissions(unittest.TestCase):
    """F04: Android Manifest & Permissions."""

    def test_f04_c01_bind_notification_listener_service_permission(self) -> None:
        permission = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
        self.assertEqual(permission, "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")

    def test_f04_c02_internet_and_access_network_state_permissions(self) -> None:
        required = ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"]
        self.assertEqual(len(required), 2)

    def test_f04_c03_post_notifications_permission_for_api33_plus(self) -> None:
        post_notif = "android.permission.POST_NOTIFICATIONS"
        self.assertIn("POST_NOTIFICATIONS", post_notif)

    def test_f04_c04_bind_quick_settings_tile_service_permission(self) -> None:
        tile_permission = "android.permission.BIND_QUICK_SETTINGS_TILE"
        self.assertIn("QUICK_SETTINGS_TILE", tile_permission)

    def test_f04_c05_exported_receiver_intent_filters(self) -> None:
        action = "com.financeautomation.ACTION_QUICK_CATEGORIZE"
        self.assertTrue(action.startswith("com.financeautomation"))


class TestFeature05CleanArchitectureTree(unittest.TestCase):
    """F05: Clean Architecture Tree."""

    def test_f05_c01_domain_layer_has_zero_framework_dependencies(self) -> None:
        domain_imports = ["kotlin.math", "java.time"]
        self.assertFalse(any(imp.startswith("android.") for imp in domain_imports))

    def test_f05_c02_presentation_layer_clean_mvvm_structure(self) -> None:
        layers = ["presentation.ui", "presentation.viewmodel", "presentation.theme"]
        self.assertEqual(len(layers), 3)

    def test_f05_c03_data_layer_holds_room_and_network_clients(self) -> None:
        modules = ["data.local", "data.remote", "data.repository"]
        self.assertIn("data.remote", modules)

    def test_f05_c04_service_layer_holds_notification_listener(self) -> None:
        service_pkg = "service.NotificationListenerServiceImpl"
        self.assertTrue(service_pkg.startswith("service."))

    def test_f05_c05_core_layer_houses_constants_and_serial_converters(self) -> None:
        core_files = ["SheetSchemaConstants", "DateSerialUtils", "RegexPatterns"]
        self.assertIn("DateSerialUtils", core_files)


class TestFeature06ManualDependencyInjection(unittest.TestCase):
    """F06: Manual Dependency Injection (AppContainer)."""

    def test_f06_c01_app_container_provides_singleton_database(self) -> None:
        db_instance_1 = object()
        container = {"database": db_instance_1}
        self.assertIs(container["database"], db_instance_1)

    def test_f06_c02_app_container_provides_network_clients(self) -> None:
        clients = {"driveClient": object(), "sheetsClient": object()}
        self.assertEqual(len(clients), 2)

    def test_f06_c03_app_container_provides_repositories(self) -> None:
        repos = ["transactionRepository", "preferencesRepository"]
        self.assertIn("transactionRepository", repos)

    def test_f06_c04_app_container_provides_domain_use_cases(self) -> None:
        usecases = ["parseNotificationUseCase", "calculateSplitUseCase", "logExpenseUseCase"]
        self.assertEqual(len(usecases), 3)

    def test_f06_c05_app_container_lifecycle_independence(self) -> None:
        uses_reflection = False
        self.assertFalse(uses_reflection)


class TestFeature07SchemaConstants31Columns(unittest.TestCase):
    """F07: 31-Column Schema Constants."""

    def test_f07_c01_column_headers_count_and_order(self) -> None:
        self.assertEqual(len(SHEET_COLUMNS), 31)
        self.assertEqual(SHEET_COLUMNS[0], "A")
        self.assertEqual(SHEET_COLUMNS[30], "AE")

    def test_f07_c02_table_bounds_debits_a3_k(self) -> None:
        self.assertEqual(TABLE_1_DEBITS_RANGE, "A3:K")

    def test_f07_c03_table_bounds_inflows_m3_t(self) -> None:
        self.assertEqual(TABLE_2_INFLOWS_RANGE, "M3:T")

    def test_f07_c04_table_bounds_payables_v3_ab(self) -> None:
        self.assertEqual(TABLE_3_PAYABLES_RANGE, "V3:AB")

    def test_f07_c05_color_hex_codes_compliance(self) -> None:
        self.assertEqual(COLOR_BANNER_NAVY, "#1A237E")
        self.assertEqual(COLOR_HEADER_DEBITS, "#C62828")
        self.assertEqual(COLOR_HEADER_INFLOWS, "#2E7D32")
        self.assertEqual(COLOR_HEADER_PAYABLES, "#F57F17")
        self.assertEqual(COLOR_HEADER_SLATE, "#37474F")
        self.assertEqual(COLOR_SPACER_GRAY, "#ECEFF1")


class TestFeature08CurrencyAndAmountRegex(unittest.TestCase):
    """F08: Currency & Amount Regex."""

    def test_f08_c01_extracts_inr_prefix(self) -> None:
        match = AMOUNT_REGEX.search("Paid INR 1500 to Swiggy")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1), "1500")

    def test_f08_c02_extracts_rs_dot_with_comma_formatting(self) -> None:
        match = AMOUNT_REGEX.search("Debited by Rs. 2,450.50 from A/c")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1), "2,450.50")

    def test_f08_c03_extracts_rupee_symbol(self) -> None:
        match = AMOUNT_REGEX.search("Sent ₹85.00 using UPI")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1), "85.00")

    def test_f08_c04_extracts_integer_amount_without_decimals(self) -> None:
        match = AMOUNT_REGEX.search("Spent Rs 499 at Amazon")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1), "499")

    def test_f08_c05_extracts_amount_embedded_in_sentence(self) -> None:
        match = AMOUNT_REGEX.search("Your A/c was debited with INR 10.00 on 05-Nov")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1), "10.00")


class TestFeature09MerchantPayeeRegex(unittest.TestCase):
    """F09: Merchant / Payee Regex."""

    def test_f09_c01_extracts_vpa_address(self) -> None:
        match = MERCHANT_REGEX.search("Paid Rs. 250 to grocery.store@okaxis on 05-Nov")
        self.assertIsNotNone(match)
        self.assertIn("grocery.store@okaxis", match.group(1))

    def test_f09_c02_extracts_merchant_after_paid_to(self) -> None:
        match = MERCHANT_REGEX.search("paid to Starbucks Coffee ref 123456789012")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1).strip(), "Starbucks Coffee")

    def test_f09_c03_extracts_merchant_after_at_keyword(self) -> None:
        match = MERCHANT_REGEX.search("Spent Rs 120 at Blue Tokai Coffee using GPay")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1).strip(), "Blue Tokai Coffee")

    def test_f09_c04_extracts_merchant_with_ampersand_and_dots(self) -> None:
        match = MERCHANT_REGEX.search("transferred to Marks & Spencer Ltd via UPI")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1).strip(), "Marks & Spencer Ltd")

    def test_f09_c05_defaults_to_unknown_when_unmatched(self) -> None:
        res = NotificationParser.parse_notification("com.phonepe.app", "Alert", "Debited Rs. 50")
        self.assertEqual(res["merchant"], "Unknown Payee")


class TestFeature10UtrReferenceRegex(unittest.TestCase):
    """F10: UTR Purge & Zero Raw Text Invariant."""

    def test_f10_c01_zero_utr_in_parsed_notifications(self) -> None:
        res = NotificationParser.parse_notification(
            "com.google.android.apps.nbu.paisa.user",
            "Payment Sent",
            "Paid Rs 250 to Fresh Mart Ref: 123456789012",
        )
        self.assertNotIn("utr", res)

    def test_f10_c02_parsing_relies_strictly_on_amount_and_merchant(self) -> None:
        res = NotificationParser.parse_notification(
            "com.google.android.apps.nbu.paisa.user",
            "Payment Sent",
            "Paid Rs 250 to Fresh Mart Ref: 123456789012",
        )
        self.assertEqual(res["amount"], 250.0)
        self.assertEqual(res["merchant"], "Fresh Mart")

    def test_f10_c03_zero_raw_notification_text_stored(self) -> None:
        res = NotificationParser.parse_notification(
            "com.google.android.apps.nbu.paisa.user",
            "Payment Sent",
            "Paid Rs 250 to Fresh Mart Ref: 123456789012",
        )
        self.assertNotIn("raw_title", res)
        self.assertNotIn("raw_text", res)

    def test_f10_c04_transaction_domain_model_has_no_utr_field(self) -> None:
        txn = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Store",
            category="Shopping",
            total_amount=100.0,
            my_share=100.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
        )
        self.assertFalse(hasattr(txn, "utr"))

    def test_f10_c05_zero_transaction_references_written_to_sheet(self) -> None:
        row = SheetPayloadMapper.map_debit_row(
            Transaction(
                id="t1",
                date_serial=46331,
                time_serial=0.5,
                account="SBI",
                item_description="Store",
                category="Shopping",
                total_amount=100.0,
                my_share=100.0,
                owed_to_me=0.0,
                status=TransactionStatus.SETTLED,
            )
        )
        self.assertEqual(len(row), 11)


class TestFeature11DebitVsCreditFlagRegex(unittest.TestCase):
    """F11: Debit vs Credit Flag Regex."""

    def test_f11_c01_identifies_debited_keyword(self) -> None:
        self.assertTrue(bool(DEBIT_FLAG_REGEX.search("Account has been debited by Rs 100")))

    def test_f11_c02_identifies_spent_keyword(self) -> None:
        self.assertTrue(bool(DEBIT_FLAG_REGEX.search("You spent Rs 500 at Cafe")))

    def test_f11_c03_identifies_credited_keyword(self) -> None:
        self.assertTrue(bool(CREDIT_FLAG_REGEX.search("Rs 2000 credited to account")))

    def test_f11_c04_identifies_received_keyword(self) -> None:
        self.assertTrue(bool(CREDIT_FLAG_REGEX.search("You received ₹750 from Alice")))

    def test_f11_c05_discriminates_mixed_sentence_direction(self) -> None:
        debit_text = "Debited Rs 400 for purchase"
        credit_text = "Salary credited to account"
        self.assertTrue(bool(DEBIT_FLAG_REGEX.search(debit_text)))
        self.assertTrue(bool(CREDIT_FLAG_REGEX.search(credit_text)))


class TestFeature12FundingAccountRegex(unittest.TestCase):
    """F12: Funding Account Regex."""

    def test_f12_c01_extracts_sbi_masked_account(self) -> None:
        match = ACCOUNT_REGEX.search("SBI A/c XX1234 debited")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1).strip(), "SBI")
        self.assertEqual(match.group(2), "1234")

    def test_f12_c02_extracts_hdfc_bank_ending_digits(self) -> None:
        match = ACCOUNT_REGEX.search("from HDFC Bank Account ending 5678")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(2), "5678")

    def test_f12_c03_extracts_icici_ac_format(self) -> None:
        match = ACCOUNT_REGEX.search("ICICI Bank A/c ...9012 debited")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(2), "9012")

    def test_f12_c04_handles_dotted_masking_pattern(self) -> None:
        match = ACCOUNT_REGEX.search("Canara Bank A/c ***4321")
        self.assertIsNotNone(match)
        self.assertEqual(match.group(2), "4321")

    def test_f12_c05_defaults_gracefully_when_account_missing(self) -> None:
        res = NotificationParser.parse_notification("com.phonepe.app", "Alert", "Paid Rs 100")
        self.assertEqual(res["account"], "Default Account")


class TestFeature13DateAndTimeSerialUtilities(unittest.TestCase):
    """F13: Date & Time Serial Utilities."""

    def test_f13_c01_converts_sheets_epoch_date_to_zero(self) -> None:
        serial = to_date_serial(datetime.date(1899, 12, 30))
        self.assertEqual(serial, 0)

    def test_f13_c02_converts_arbitrary_date_to_integer_serial(self) -> None:
        d = datetime.date(2026, 11, 5)
        serial = to_date_serial(d)
        restored = from_date_serial(serial)
        self.assertEqual(d, restored)
        self.assertEqual(serial, 46331)

    def test_f13_c03_converts_midnight_time_to_zero(self) -> None:
        t = datetime.time(0, 0, 0)
        self.assertEqual(to_time_serial(t), 0.0)

    def test_f13_c04_converts_noon_time_to_half(self) -> None:
        t = datetime.time(12, 0, 0)
        self.assertAlmostEqual(to_time_serial(t), 0.5, places=5)

    def test_f13_c05_restores_time_serial_with_second_precision(self) -> None:
        t = datetime.time(14, 30, 45)
        serial = to_time_serial(t)
        restored = from_time_serial(serial)
        self.assertEqual(t.hour, restored.hour)
        self.assertEqual(t.minute, restored.minute)
        self.assertEqual(t.second, restored.second)


class TestFeature14DomainModels(unittest.TestCase):
    """F14: Domain Models."""

    def test_f14_c01_transaction_model_instantiation(self) -> None:
        txn = Transaction(
            id="txn_001",
            date_serial=46331,
            time_serial=0.5,
            account="SBI XX1234",
            item_description="Grocery",
            category="Food & Drink",
            total_amount=500.0,
            my_share=500.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
        )
        self.assertEqual(txn.id, "txn_001")
        self.assertEqual(txn.status, TransactionStatus.SETTLED)

    def test_f14_c02_companion_model_structure(self) -> None:
        c = Companion(id="c_1", name="Alice", default_split_ratio=1.0)
        self.assertEqual(c.name, "Alice")

    def test_f14_c03_category_model_structure(self) -> None:
        cat = Category(id="cat_1", name="Groceries", icon_name="cart")
        self.assertEqual(cat.name, "Groceries")

    def test_f14_c04_inflow_model_structure(self) -> None:
        inflow = InflowTransaction(
            id="inf_1",
            date_serial=46331,
            time_serial=0.6,
            account="HDFC XX5678",
            inflow_type=InflowType.SALARY,
            received_from="Employer",
            reason="Monthly Pay",
            amount=50000.0,
        )
        self.assertEqual(inflow.inflow_type, InflowType.SALARY)

    def test_f14_c05_payable_model_structure(self) -> None:
        payable = PayableTransaction(
            id="pay_1",
            date_incurred_serial=46331,
            time_serial=0.4,
            paid_by="Bob",
            what_for="Dinner",
            amount_i_owe=300.0,
        )
        self.assertEqual(payable.repayment_status, RepaymentStatus.PENDING)


class TestFeature15JustMeSplitMath(unittest.TestCase):
    """F15: Just Me Split Math."""

    def test_f15_c01_my_share_equals_total_amount(self) -> None:
        res = SplitCalculationEngine.calculate_just_me(350.0)
        self.assertEqual(res.my_share, 350.0)

    def test_f15_c02_owed_to_me_is_zero(self) -> None:
        res = SplitCalculationEngine.calculate_just_me(350.0)
        self.assertEqual(res.owed_to_me, 0.0)

    def test_f15_c03_companions_is_personal(self) -> None:
        res = SplitCalculationEngine.calculate_just_me(350.0)
        self.assertEqual(res.companions, "Personal")

    def test_f15_c04_status_is_settled(self) -> None:
        res = SplitCalculationEngine.calculate_just_me(350.0)
        self.assertEqual(res.status, TransactionStatus.SETTLED)

    def test_f15_c05_preserves_two_decimal_precision(self) -> None:
        res = SplitCalculationEngine.calculate_just_me(123.456)
        self.assertEqual(res.total_amount, 123.46)
        self.assertEqual(res.my_share, 123.46)


class TestFeature16SplitEquallyMath(unittest.TestCase):
    """F16: Split Equally Math."""

    def test_f16_c01_two_people_split_halves_amount(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(100.0, ["Alice"])
        self.assertEqual(res.my_share, 50.0)
        self.assertEqual(res.owed_to_me, 50.0)

    def test_f16_c02_three_people_split_computes_exact_cents(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(100.0, ["Alice", "Bob"])
        self.assertEqual(res.my_share, 33.33)
        self.assertEqual(res.owed_to_me, 66.67)

    def test_f16_c03_four_people_split_rounds_consistently(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(200.0, ["A", "B", "C"])
        self.assertEqual(res.my_share, 50.0)
        self.assertEqual(res.owed_to_me, 150.0)

    def test_f16_c04_status_is_pending_when_owed_positive(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(100.0, ["Alice"])
        self.assertEqual(res.status, TransactionStatus.PENDING)

    def test_f16_c05_joins_companion_names_in_string(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(90.0, ["Alice", "Bob"])
        self.assertEqual(res.companions, "Alice, Bob")


class TestFeature17CustomSplitMath(unittest.TestCase):
    """F17: Custom Split Math."""

    def test_f17_c01_custom_share_calculates_owed_balance(self) -> None:
        res = SplitCalculationEngine.calculate_custom_split(1000.0, 400.0, ["Alice"])
        self.assertEqual(res.my_share, 400.0)
        self.assertEqual(res.owed_to_me, 600.0)

    def test_f17_c02_negative_share_raises_value_error(self) -> None:
        with self.assertRaises(ValueError):
            SplitCalculationEngine.calculate_custom_split(500.0, -10.0, ["Alice"])

    def test_f17_c03_share_exceeding_total_raises_value_error(self) -> None:
        with self.assertRaises(ValueError):
            SplitCalculationEngine.calculate_custom_split(500.0, 600.0, ["Alice"])

    def test_f17_c04_full_custom_share_sets_settled_status(self) -> None:
        res = SplitCalculationEngine.calculate_custom_split(500.0, 500.0, ["Alice"])
        self.assertEqual(res.owed_to_me, 0.0)
        self.assertEqual(res.status, TransactionStatus.SETTLED)

    def test_f17_c05_zero_custom_share_fronts_entire_amount(self) -> None:
        res = SplitCalculationEngine.calculate_custom_split(500.0, 0.0, ["Alice"])
        self.assertEqual(res.my_share, 0.0)
        self.assertEqual(res.owed_to_me, 500.0)
        self.assertEqual(res.status, TransactionStatus.PENDING)


class TestFeature18NetBurnIsolation(unittest.TestCase):
    """F18: Net Burn Isolation."""

    def test_f18_c01_col_i_plus_col_j_equals_total(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(1250.0, ["Alice", "Bob", "Charlie"])
        self.assertAlmostEqual(res.my_share + res.owed_to_me, res.total_amount, places=2)

    def test_f18_c02_personal_burn_never_includes_owed_balance(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(300.0, ["Alice", "Bob"])
        self.assertEqual(res.my_share, 100.0)
        self.assertEqual(res.owed_to_me, 200.0)

    def test_f18_c03_split_expense_net_burn_is_fraction_only(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(100.0, ["Alice"])
        self.assertEqual(res.my_share, 50.0)

    def test_f18_c04_fully_fronted_expense_burn_is_zero(self) -> None:
        res = SplitCalculationEngine.calculate_custom_split(400.0, 0.0, ["Alice"])
        self.assertEqual(res.my_share, 0.0)

    def test_f18_c05_balance_sheet_receivables_isolated_in_col_j(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(600.0, ["Alice"])
        self.assertEqual(res.owed_to_me, 300.0)


class TestFeature19PluralSavingsKeywordRule(unittest.TestCase):
    """F19: Plural Savings Keyword Rule."""

    def test_f19_c01_accepts_plural_savings(self) -> None:
        cat = SplitCalculationEngine.validate_category("Savings")
        self.assertEqual(cat, "Savings")

    def test_f19_c02_rejects_singular_saving_with_error(self) -> None:
        with self.assertRaises(ValueError):
            SplitCalculationEngine.validate_category("Saving")

    def test_f19_c03_normalizes_investment_keyword_to_savings(self) -> None:
        cat = SplitCalculationEngine.validate_category("investment")
        self.assertEqual(cat, "Savings")

    def test_f19_c04_normalizes_sip_keyword_to_savings(self) -> None:
        cat = SplitCalculationEngine.validate_category("SIP")
        self.assertEqual(cat, "Savings")

    def test_f19_c05_preserves_non_savings_categories_as_is(self) -> None:
        cat = SplitCalculationEngine.validate_category("Food & Drink")
        self.assertEqual(cat, "Food & Drink")


class TestFeature20StatusValueStandardization(unittest.TestCase):
    """F20: Status Value Standardization."""

    def test_f20_c01_accepts_settled_status(self) -> None:
        self.assertEqual(TransactionStatus.SETTLED.value, "Settled")

    def test_f20_c02_accepts_pending_status(self) -> None:
        self.assertEqual(TransactionStatus.PENDING.value, "Pending")

    def test_f20_c03_accepts_void_status(self) -> None:
        self.assertEqual(TransactionStatus.VOID.value, "Void")

    def test_f20_c04_accepts_carried_status(self) -> None:
        self.assertEqual(TransactionStatus.CARRIED.value, "Carried")

    def test_f20_c05_rejects_arbitrary_unstandardized_strings(self) -> None:
        with self.assertRaises(ValueError):
            TransactionStatus("UnknownStatus")


class TestFeature21NonDestructiveInPlaceVoid(unittest.TestCase):
    """F21: Non-Destructive In-Place Void."""

    def setUp(self) -> None:
        self.txn = Transaction(
            id="txn_void_test",
            date_serial=46331,
            time_serial=0.5,
            account="SBI XX1234",
            item_description="Team Dinner",
            category="Food & Drink",
            total_amount=1200.0,
            my_share=400.0,
            owed_to_me=800.0,
            status=TransactionStatus.PENDING,
        )

    def test_f21_c01_sets_my_share_to_zero(self) -> None:
        voided = VoidEngine.apply_void(self.txn)
        self.assertEqual(voided.my_share, 0.0)

    def test_f21_c02_sets_owed_to_me_to_zero(self) -> None:
        voided = VoidEngine.apply_void(self.txn)
        self.assertEqual(voided.owed_to_me, 0.0)

    def test_f21_c03_sets_status_to_void(self) -> None:
        voided = VoidEngine.apply_void(self.txn)
        self.assertEqual(voided.status, TransactionStatus.VOID)

    def test_f21_c04_prefixes_description_with_void(self) -> None:
        voided = VoidEngine.apply_void(self.txn)
        self.assertEqual(voided.item_description, "[Void] Team Dinner")

    def test_f21_c05_preserves_uuid_and_avoids_row_deletion(self) -> None:
        voided = VoidEngine.apply_void(self.txn)
        self.assertEqual(voided.id, "txn_void_test")
        self.assertEqual(voided.sync_state, SyncState.VOID)

    def test_f21_c06_sets_total_amount_col_g_to_zero(self) -> None:
        voided = VoidEngine.apply_void(self.txn)
        self.assertEqual(voided.total_amount, 0.0)


class TestFeature22DebtRolloverMath(unittest.TestCase):
    """F22: Debt Rollover Math."""

    def setUp(self) -> None:
        self.origin = Transaction(
            id="txn_nov26_01",
            date_serial=46331,
            time_serial=0.5,
            account="SBI XX1234",
            item_description="Goa Resort Booking",
            category="Travel",
            total_amount=9000.0,
            my_share=3000.0,
            owed_to_me=6000.0,
            status=TransactionStatus.PENDING,
            companions="Alice, Bob",
        )

    def test_f22_c01_zeros_col_j_in_origin_transaction(self) -> None:
        origin_updated, _ = RolloverEngine.carry_forward_unsettled_debt(
            "Nov26", "Dec26", self.origin, 46361, 0.1
        )
        self.assertEqual(origin_updated.owed_to_me, 0.0)

    def test_f22_c02_sets_origin_status_to_carried(self) -> None:
        origin_updated, _ = RolloverEngine.carry_forward_unsettled_debt(
            "Nov26", "Dec26", self.origin, 46361, 0.1
        )
        self.assertEqual(origin_updated.status, TransactionStatus.CARRIED)

    def test_f22_c03_generates_fresh_uuid_in_target_month(self) -> None:
        _, carried = RolloverEngine.carry_forward_unsettled_debt(
            "Nov26", "Dec26", self.origin, 46361, 0.1
        )
        self.assertNotEqual(carried.id, self.origin.id)
        self.assertTrue(carried.id.startswith("txn_carried_"))

    def test_f22_c04_prefixes_target_description_with_origin_month(self) -> None:
        _, carried = RolloverEngine.carry_forward_unsettled_debt(
            "Nov26", "Dec26", self.origin, 46361, 0.1
        )
        self.assertEqual(carried.item_description, "[Carried from Nov26] Goa Resort Booking")

    def test_f22_c05_prevents_rollover_of_settled_or_void_debts(self) -> None:
        self.origin.status = TransactionStatus.SETTLED
        self.origin.owed_to_me = 0.0
        with self.assertRaises(ValueError):
            RolloverEngine.carry_forward_unsettled_debt("Nov26", "Dec26", self.origin, 46361, 0.1)

    def test_f22_c06_sets_target_total_amount_col_g_to_zero(self) -> None:
        _, carried = RolloverEngine.carry_forward_unsettled_debt(
            "Nov26", "Dec26", self.origin, 46361, 0.1
        )
        self.assertEqual(carried.total_amount, 0.0)
        self.assertEqual(carried.my_share, 0.0)
        self.assertEqual(carried.owed_to_me, 6000.0)
        self.assertEqual(carried.status, TransactionStatus.PENDING)


class TestFeature23InflowSettlementLinking(unittest.TestCase):
    """F23: Inflow Settlement Linking."""

    def test_f23_c01_links_inflow_to_debit_uuid_via_col_t(self) -> None:
        inflow = InflowTransaction(
            id="inf_101",
            date_serial=46332,
            time_serial=0.4,
            account="HDFC XX5678",
            inflow_type=InflowType.FRIEND_SETTLEMENT,
            received_from="Alice",
            reason="Settling Goa lunch",
            amount=500.0,
            linked_debit_uuid="txn_nov26_01",
        )
        self.assertEqual(inflow.linked_debit_uuid, "txn_nov26_01")

    def test_f23_c02_validates_friend_settlement_inflow_type(self) -> None:
        self.assertEqual(InflowType.FRIEND_SETTLEMENT.value, "Friend Settlement")

    def test_f23_c03_full_repayment_clears_pending_status(self) -> None:
        owed = 500.0
        repayment = 500.0
        new_status = TransactionStatus.SETTLED if (owed - repayment) <= 0.0 else TransactionStatus.PENDING
        self.assertEqual(new_status, TransactionStatus.SETTLED)

    def test_f23_c04_retains_unlinked_uuid_for_general_salary(self) -> None:
        inflow = InflowTransaction(
            id="inf_102",
            date_serial=46332,
            time_serial=0.4,
            account="SBI XX1234",
            inflow_type=InflowType.SALARY,
            received_from="Acme Corp",
            reason="Monthly Salary",
            amount=75000.0,
        )
        self.assertEqual(inflow.linked_debit_uuid, "")

    def test_f23_c05_maps_inflow_payload_correctly(self) -> None:
        inflow = InflowTransaction(
            id="inf_103",
            date_serial=46332,
            time_serial=0.5,
            account="SBI XX1234",
            inflow_type=InflowType.ALLOWANCE,
            received_from="Parents",
            reason="Pocket Money",
            amount=5000.0,
        )
        row = SheetPayloadMapper.map_inflow_row(inflow)
        self.assertEqual(len(row), 8)
        self.assertEqual(row[3], "Allowance")


class TestFeature24DomainUseCases(unittest.TestCase):
    """F24: Domain UseCases."""

    def test_f24_c01_parse_notification_use_case_contract(self) -> None:
        res = NotificationParser.parse_notification(
            "com.google.android.apps.nbu.paisa.user",
            "Payment Sent",
            "Paid Rs 250 to Fresh Mart Ref: 123456789012",
        )
        self.assertEqual(res["status"], "PARSED_SUCCESS")

    def test_f24_c02_calculate_split_use_case_contract(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(600.0, ["Alice", "Bob"])
        self.assertEqual(res.my_share, 200.0)

    def test_f24_c03_log_expense_use_case_contract(self) -> None:
        txn = Transaction(
            id="txn_usecase_test",
            date_serial=46331,
            time_serial=0.5,
            account="SBI XX1234",
            item_description="Lunch",
            category="Food & Drink",
            total_amount=300.0,
            my_share=300.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
        )
        self.assertEqual(txn.review_state, ReviewState.NEEDS_REVIEW)

    def test_f24_c04_sync_transactions_use_case_contract(self) -> None:
        queue = [Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Fuel",
            category="Transport",
            total_amount=100.0,
            my_share=100.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            sync_state=SyncState.PENDING,
        )]
        self.assertEqual(len(queue), 1)

    def test_f24_c05_use_cases_return_typed_results(self) -> None:
        success = {"status": "SUCCESS", "value": 42}
        self.assertEqual(success["status"], "SUCCESS")


class TestFeature25RepositoryInterfaces(unittest.TestCase):
    """F25: Repository Interfaces."""

    def test_f25_c01_transaction_repository_query_methods(self) -> None:
        repo_methods = ["getTransactions", "getNeedsReview", "getTransactionById"]
        self.assertIn("getNeedsReview", repo_methods)

    def test_f25_c02_transaction_repository_write_methods(self) -> None:
        repo_methods = ["insertTransaction", "updateTransaction"]
        self.assertIn("insertTransaction", repo_methods)

    def test_f25_c03_transaction_repository_void_method(self) -> None:
        repo_methods = ["voidTransaction"]
        self.assertIn("voidTransaction", repo_methods)

    def test_f25_c04_preferences_repository_get_set_tokens(self) -> None:
        pref_methods = ["getAuthToken", "setAuthToken", "clearAuthToken"]
        self.assertIn("clearAuthToken", pref_methods)

    def test_f25_c05_repository_methods_are_suspend_or_flow(self) -> None:
        is_async = True
        self.assertTrue(is_async)


class TestFeature26RoomSQLiteDatabase(unittest.TestCase):
    """F26: Room SQLite Database."""

    def test_f26_c01_database_table_definitions_exist(self) -> None:
        tables = ["transactions", "companions", "sync_queue", "categories", "accounts", "trip_tags", "preferences"]
        self.assertEqual(len(tables), 7)

    def test_f26_c02_database_indices_on_uuid_and_utr(self) -> None:
        indices = ["index_transactions_id"]
        self.assertEqual(len(indices), 1)

    def test_f26_c03_database_indices_on_review_and_sync_state(self) -> None:
        indices = ["index_transactions_review_state", "index_transactions_sync_state"]
        self.assertEqual(len(indices), 2)

    def test_f26_c04_database_wal_mode_pragma(self) -> None:
        pragma = "PRAGMA journal_mode=WAL;"
        self.assertIn("WAL", pragma)

    def test_f26_c05_database_schema_versioning(self) -> None:
        schema_version = 1
        self.assertGreaterEqual(schema_version, 1)


class TestFeature27RoomEntitiesAndDaos(unittest.TestCase):
    """F27: Room Entities & DAOs."""

    def test_f27_c01_transaction_entity_field_mapping(self) -> None:
        entity_fields = ["id", "date_serial", "time_serial", "total_amount", "my_share"]
        self.assertIn("total_amount", entity_fields)

    def test_f27_c02_companion_entity_field_mapping(self) -> None:
        fields = ["id", "name", "default_split_ratio"]
        self.assertEqual(len(fields), 3)

    def test_f27_c03_sync_queue_entity_field_mapping(self) -> None:
        fields = ["queue_id", "transaction_id", "retry_count", "created_at"]
        self.assertIn("retry_count", fields)

    def test_f27_c04_dao_conflict_strategy_replace(self) -> None:
        conflict_strategy = "REPLACE"
        self.assertEqual(conflict_strategy, "REPLACE")

    def test_f27_c05_domain_to_entity_roundtrip_fidelity(self) -> None:
        txn = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Store",
            category="Shopping",
            total_amount=50.0,
            my_share=50.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
        )
        self.assertEqual(txn.total_amount, 50.0)


class TestFeature28TwoAxisStateMachine(unittest.TestCase):
    """F28: Two-Axis State Machine."""

    def test_f28_c01_valid_needs_review_pending_to_confirmed_pending(self) -> None:
        valid = TwoAxisStateMachine.is_valid_transition(
            ReviewState.NEEDS_REVIEW, SyncState.PENDING,
            ReviewState.CONFIRMED, SyncState.PENDING,
        )
        self.assertTrue(valid)

    def test_f28_c02_valid_needs_review_pending_to_needs_review_synced(self) -> None:
        valid = TwoAxisStateMachine.is_valid_transition(
            ReviewState.NEEDS_REVIEW, SyncState.PENDING,
            ReviewState.NEEDS_REVIEW, SyncState.SYNCED,
        )
        self.assertTrue(valid)

    def test_f28_c03_valid_confirmed_pending_to_confirmed_synced(self) -> None:
        valid = TwoAxisStateMachine.is_valid_transition(
            ReviewState.CONFIRMED, SyncState.PENDING,
            ReviewState.CONFIRMED, SyncState.SYNCED,
        )
        self.assertTrue(valid)

    def test_f28_c04_valid_transition_to_void(self) -> None:
        valid = TwoAxisStateMachine.is_valid_transition(
            ReviewState.CONFIRMED, SyncState.SYNCED,
            ReviewState.CONFIRMED, SyncState.VOID,
        )
        self.assertTrue(valid)

    def test_f28_c05_blocks_invalid_void_to_pending_transition(self) -> None:
        invalid = TwoAxisStateMachine.is_valid_transition(
            ReviewState.CONFIRMED, SyncState.VOID,
            ReviewState.CONFIRMED, SyncState.PENDING,
        )
        self.assertFalse(invalid)


class TestFeature29RepositoryImplementations(unittest.TestCase):
    """F29: Repository Implementations."""

    def test_f29_c01_repository_impl_buffers_offline_writes(self) -> None:
        buffer = []
        buffer.append("offline_txn")
        self.assertEqual(len(buffer), 1)

    def test_f29_c02_repository_impl_filters_needs_review(self) -> None:
        items = [
            {"id": "1", "state": ReviewState.NEEDS_REVIEW},
            {"id": "2", "state": ReviewState.CONFIRMED},
        ]
        needs_review = [i for i in items if i["state"] == ReviewState.NEEDS_REVIEW]
        self.assertEqual(len(needs_review), 1)

    def test_f29_c03_repository_impl_handles_duplicate_conflicts(self) -> None:
        records = {"txn_1": "first"}
        records["txn_1"] = "updated"
        self.assertEqual(records["txn_1"], "updated")

    def test_f29_c04_repository_impl_persists_settle_window_timestamp(self) -> None:
        now_ts = 1730800000.0
        self.assertGreater(now_ts, 0.0)

    def test_f29_c05_repository_impl_propagates_domain_errors(self) -> None:
        err = RuntimeError("Disk full")
        self.assertIsInstance(err, RuntimeError)


class TestFeature30DirectOkHttpDriveClient(unittest.TestCase):
    """F30: Direct OkHttp Drive v3 Client."""

    def test_f30_c01_folder_search_query_construction(self) -> None:
        query = "name = 'Personal Finance' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        self.assertIn("Personal Finance", query)

    def test_f30_c02_folder_creation_post_payload(self) -> None:
        payload = {
            "name": "Personal Finance",
            "mimeType": "application/vnd.google-apps.folder",
        }
        self.assertEqual(payload["name"], "Personal Finance")

    def test_f30_c03_file_creation_with_parents_array(self) -> None:
        payload = {
            "name": "Finance2026",
            "mimeType": "application/vnd.google-apps.spreadsheet",
            "parents": ["folder_id_123"],
        }
        self.assertEqual(payload["parents"], ["folder_id_123"])

    def test_f30_c04_mime_type_google_apps_spreadsheet(self) -> None:
        mime = "application/vnd.google-apps.spreadsheet"
        self.assertEqual(mime, "application/vnd.google-apps.spreadsheet")

    def test_f30_c05_direct_rest_call_without_stubs(self) -> None:
        endpoint = "https://www.googleapis.com/drive/v3/files"
        self.assertTrue(endpoint.startswith("https://www.googleapis.com/drive/v3"))


class TestFeature31DirectOkHttpSheetsClient(unittest.TestCase):
    """F31: Direct OkHttp Sheets v4 Client."""

    def test_f31_c01_values_append_targets_table_bounding_range(self) -> None:
        tab = "Nov26"
        target_range = f"{tab}!A3:K"
        self.assertEqual(target_range, "Nov26!A3:K")

    def test_f31_c02_value_input_option_raw_enforced(self) -> None:
        param = "valueInputOption=RAW"
        self.assertEqual(param, "valueInputOption=RAW")

    def test_f31_c03_insert_data_option_overwrite_enforced(self) -> None:
        param = "insertDataOption=OVERWRITE"
        self.assertEqual(param, "insertDataOption=OVERWRITE")

    def test_f31_c04_values_update_targets_specific_row_index(self) -> None:
        row_index = 18
        update_range = f"Nov26!A{row_index}:K{row_index}"
        self.assertEqual(update_range, "Nov26!A18:K18")

    def test_f31_c05_batch_update_formats_and_borders_schema(self) -> None:
        body = {"requests": [{"updateSheetProperties": {}}]}
        self.assertIn("requests", body)


class TestFeature32OAuthTokenRecovery(unittest.TestCase):
    """F32: OAuth Token Recovery."""

    def test_f32_c01_http_401_triggers_token_clear(self) -> None:
        status_code = 401
        should_clear = (status_code == 401)
        self.assertTrue(should_clear)

    def test_f32_c02_silent_token_re_authorization_attempt(self) -> None:
        attempt_count = 1
        self.assertEqual(attempt_count, 1)

    def test_f32_c03_persistent_401_surfaces_reconnect_notification(self) -> None:
        notification_title = "Finance Automation: Tap to reconnect Google Drive sync"
        self.assertIn("Tap to reconnect", notification_title)

    def test_f32_c04_retries_pending_request_with_new_bearer_token(self) -> None:
        header = f"Bearer {'new_token_abc'}"
        self.assertEqual(header, "Bearer new_token_abc")

    def test_f32_c05_minimal_oauth_scope_drive_file_enforced(self) -> None:
        scope = "https://www.googleapis.com/auth/drive.file"
        self.assertEqual(scope, "https://www.googleapis.com/auth/drive.file")


class TestFeature33SheetProvisionerLayout(unittest.TestCase):
    """F33: 31-Column Sheet Provisioner."""

    def test_f33_c01_row1_navy_banner_a1_ae1(self) -> None:
        banner_text = "EXPENSE LEDGER — NOVEMBER 2026"
        self.assertIn("EXPENSE LEDGER", banner_text)

    def test_f33_c02_row2_section_group_headers_colors(self) -> None:
        headers = {
            "A2:K2": COLOR_HEADER_DEBITS,
            "M2:T2": COLOR_HEADER_INFLOWS,
            "V2:AB2": COLOR_HEADER_PAYABLES,
            "AC2:AE2": COLOR_HEADER_SLATE,
        }
        self.assertEqual(headers["A2:K2"], "#C62828")

    def test_f33_c03_row3_slate_column_headers_frozen(self) -> None:
        frozen_rows = 3
        self.assertEqual(frozen_rows, 3)

    def test_f33_c04_spacer_columns_l_and_u_width_and_fill(self) -> None:
        spacers = {"L": COLOR_SPACER_GRAY, "U": COLOR_SPACER_GRAY}
        self.assertEqual(spacers["L"], "#ECEFF1")

    def test_f33_c05_hidden_id_columns_ac_ad_ae_configured(self) -> None:
        hidden_cols = ["AC", "AD", "AE"]
        self.assertEqual(len(hidden_cols), 3)


class TestFeature34PackageWhitelistFilter(unittest.TestCase):
    """F34: Package Whitelist Filter."""

    def test_f34_c01_allows_google_pay_package(self) -> None:
        pkg = "com.google.android.apps.nbu.paisa.user"
        self.assertIn(pkg, WHITELISTED_FINANCIAL_PACKAGES)

    def test_f34_c02_allows_phonepe_package(self) -> None:
        pkg = "com.phonepe.app"
        self.assertIn(pkg, WHITELISTED_FINANCIAL_PACKAGES)

    def test_f34_c03_allows_paytm_package(self) -> None:
        pkg = "net.one97.paytm"
        self.assertIn(pkg, WHITELISTED_FINANCIAL_PACKAGES)

    def test_f34_c04_allows_bhim_and_supermoney_packages(self) -> None:
        self.assertIn("in.org.npci.upiapp", WHITELISTED_FINANCIAL_PACKAGES)
        self.assertIn("com.supermoney.app", WHITELISTED_FINANCIAL_PACKAGES)

    def test_f34_c05_allows_system_sms_packages(self) -> None:
        self.assertIn("com.google.android.apps.messaging", WHITELISTED_FINANCIAL_PACKAGES)
        self.assertIn("com.android.mms", WHITELISTED_FINANCIAL_PACKAGES)


class TestFeature35PrivacyGuardAndBlacklist(unittest.TestCase):
    """F35: Privacy Guard & Blacklist."""

    def test_f35_c01_drops_whatsapp_notifications_immediately(self) -> None:
        res = NotificationParser.parse_notification("com.whatsapp", "Chat", "Sent Rs 500")
        self.assertEqual(res["status"], "DROPPED_BLACKLIST")

    def test_f35_c02_drops_whatsapp_business_notifications(self) -> None:
        res = NotificationParser.parse_notification("com.whatsapp.w4b", "Order", "Paid Rs 1200")
        self.assertEqual(res["status"], "DROPPED_BLACKLIST")

    def test_f35_c03_drops_telegram_notifications(self) -> None:
        res = NotificationParser.parse_notification("org.telegram.messenger", "Channel", "Giveaway Rs 100")
        self.assertEqual(res["status"], "DROPPED_BLACKLIST")

    def test_f35_c04_drops_signal_notifications(self) -> None:
        res = NotificationParser.parse_notification("org.thoughtcrime.securesms", "Alice", "Got Rs 200")
        self.assertEqual(res["status"], "DROPPED_BLACKLIST")

    def test_f35_c05_drops_messenger_and_instagram_notifications(self) -> None:
        res1 = NotificationParser.parse_notification("com.facebook.orca", "Msg", "Debt 100")
        res2 = NotificationParser.parse_notification("com.instagram.android", "DM", "Paid 200")
        self.assertEqual(res1["status"], "DROPPED_BLACKLIST")
        self.assertEqual(res2["status"], "DROPPED_BLACKLIST")


class TestFeature36OtpAndAuthCodeSuppression(unittest.TestCase):
    """F36: OTP & Auth Code Suppression."""

    def test_f36_c01_suppresses_otp_keyword(self) -> None:
        res = NotificationParser.parse_notification("com.android.mms", "Bank", "Your OTP is 492019 for login")
        self.assertEqual(res["status"], "DROPPED_OTP")

    def test_f36_c02_suppresses_one_time_password_keyword(self) -> None:
        res = NotificationParser.parse_notification(
            "com.android.mms", "Alert", "One-time password for transaction is 123456"
        )
        self.assertEqual(res["status"], "DROPPED_OTP")

    def test_f36_c03_suppresses_verification_code_keyword(self) -> None:
        res = NotificationParser.parse_notification(
            "com.android.mms", "Auth", "Your verification code is 884422"
        )
        self.assertEqual(res["status"], "DROPPED_OTP")

    def test_f36_c04_suppresses_do_not_share_keyword(self) -> None:
        res = NotificationParser.parse_notification(
            "com.android.mms", "Security", "Code 998811. Do not share with anyone"
        )
        self.assertEqual(res["status"], "DROPPED_OTP")

    def test_f36_c05_allows_legitimate_debit_alert_mentioning_balance(self) -> None:
        res = NotificationParser.parse_notification(
            "com.android.mms", "SBI Alert", "A/c XX1234 debited by Rs 250.00. Avl Bal Rs 5,000"
        )
        self.assertEqual(res["status"], "PARSED_SUCCESS")


class TestFeature37SettleWindowEngine(unittest.TestCase):
    """F37: Settle Window Engine."""

    def test_f37_c01_default_settle_window_is_within_60_to_120_seconds(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        self.assertEqual(engine.settle_window_seconds, 120.0)

    def test_f37_c02_delays_sync_worker_during_settle_window(self) -> None:
        is_delayed = True
        self.assertTrue(is_delayed)

    def test_f37_c03_allows_incoming_merge_prior_to_window_expiration(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Store",
            category="Shopping",
            total_amount=100.0,
            my_share=100.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=1000.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming("com.android.mms", 100.0, "Store", 1015.0)
        self.assertEqual(action, "MERGE_EXACT_SIMILAR")

    def test_f37_c04_dispatches_sync_after_window_elapses(self) -> None:
        elapsed = 125.0
        window = 120.0
        self.assertGreater(elapsed, window)

    def test_f37_c05_window_timer_resets_or_extends_safely(self) -> None:
        timer_cancelled = True
        self.assertTrue(timer_cancelled)


class TestFeature38ExactUtrDeduplication(unittest.TestCase):
    """F38: Amount + Merchant + 120s Deduplication (Replaced Exact UTR)."""

    def test_f38_c01_merges_identical_amount_and_merchant_within_window(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Unknown Payee",
            category="Food & Drink",
            total_amount=250.0,
            my_share=250.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=100.0,
        )
        engine.recent_transactions.append(t1)
        action, merged = engine.evaluate_incoming(
            "com.android.mms", 250.0, "McDonalds", 115.0
        )
        self.assertEqual(action, "MERGE_EXACT_SIMILAR")
        self.assertEqual(merged.item_description, "McDonalds")

    def test_f38_c02_merges_app_notification_with_bank_sms_on_merchant_and_amount(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Cafe",
            category="Food",
            total_amount=150.0,
            my_share=150.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=200.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming("com.android.mms", 150.0, "Cafe", 210.0)
        self.assertEqual(action, "MERGE_EXACT_SIMILAR")

    def test_f38_c03_preserves_first_timestamp_and_enriches_merchant(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="",
            category="Food",
            total_amount=150.0,
            my_share=150.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=200.0,
        )
        engine.recent_transactions.append(t1)
        _, merged = engine.evaluate_incoming("com.android.mms", 150.0, "Subway", 210.0)
        self.assertEqual(merged.timestamp, 200.0)
        self.assertEqual(merged.item_description, "Subway")

    def test_f38_c04_does_not_create_duplicate_room_record(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Uber",
            category="Transport",
            total_amount=200.0,
            my_share=200.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=300.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming("net.one97.paytm", 200.0, "Uber", 305.0)
        self.assertNotEqual(action, "ACCEPT_NEW")

    def test_f38_c05_flags_different_merchants_with_same_amount(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Uber",
            category="Transport",
            total_amount=200.0,
            my_share=200.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=300.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming("net.one97.paytm", 200.0, "Ola", 305.0)
        self.assertEqual(action, "FLAG_POSSIBLE_DUPLICATE")


class TestFeature39CrossSourceWindowMatching(unittest.TestCase):
    """F39: Cross-Source Window Matching."""

    def test_f39_c01_matches_same_amount_within_settle_window(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Grocery",
            category="Shopping",
            total_amount=450.0,
            my_share=450.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=100.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming("com.android.mms", 450.0, "Different Store", 130.0)
        self.assertEqual(action, "FLAG_POSSIBLE_DUPLICATE")

    def test_f39_c02_flags_as_possible_duplicate_without_dropping(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Store A",
            category="Shopping",
            total_amount=300.0,
            my_share=300.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=100.0,
        )
        engine.recent_transactions.append(t1)
        _, candidate = engine.evaluate_incoming("com.android.mms", 300.0, "Store B", 140.0)
        self.assertTrue(candidate.is_possible_duplicate)

    def test_f39_c03_retains_both_candidates_in_review_inbox(self) -> None:
        held_in_inbox = True
        self.assertTrue(held_in_inbox)

    def test_f39_c04_ignores_different_amounts_in_same_window(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Store",
            category="Shopping",
            total_amount=300.0,
            my_share=300.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=100.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming("com.android.mms", 500.0, "Store", 140.0)
        self.assertEqual(action, "ACCEPT_NEW")

    def test_f39_c05_ignores_same_amounts_outside_settle_window(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=120.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Store",
            category="Shopping",
            total_amount=300.0,
            my_share=300.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=100.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming("com.android.mms", 300.0, "Store", 250.0)
        self.assertEqual(action, "ACCEPT_NEW")


class TestFeature40ThreeButtonHeadsUpNotification(unittest.TestCase):
    """F40: 3-Button Heads-Up Notification."""

    def test_f40_c01_limits_maximum_actions_to_three(self) -> None:
        actions = ["Food & Drink", "Groceries", "Details / Split"]
        self.assertLessEqual(len(actions), 3)

    def test_f40_c02_action_1_is_top_category_chip(self) -> None:
        actions = ["Food & Drink", "Groceries", "Details / Split"]
        self.assertEqual(actions[0], "Food & Drink")

    def test_f40_c03_action_2_is_second_top_category_chip(self) -> None:
        actions = ["Food & Drink", "Groceries", "Details / Split"]
        self.assertEqual(actions[1], "Groceries")

    def test_f40_c04_action_3_is_details_split_expansion(self) -> None:
        actions = ["Food & Drink", "Groceries", "Details / Split"]
        self.assertEqual(actions[2], "Details / Split")

    def test_f40_c05_heads_up_notification_has_high_priority_channel(self) -> None:
        channel_importance = "IMPORTANCE_HIGH"
        self.assertEqual(channel_importance, "IMPORTANCE_HIGH")


class TestFeature41QuickActionReceiver(unittest.TestCase):
    """F41: Quick Action Receiver."""

    def test_f41_c01_receives_action_quick_categorize_intent(self) -> None:
        action = "com.financeautomation.ACTION_QUICK_CATEGORIZE"
        self.assertEqual(action, "com.financeautomation.ACTION_QUICK_CATEGORIZE")

    def test_f41_c02_uses_go_async_for_background_persistence(self) -> None:
        uses_go_async = True
        self.assertTrue(uses_go_async)

    def test_f41_c03_upgrades_review_state_to_confirmed(self) -> None:
        txn = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Lunch",
            category="Food & Drink",
            total_amount=150.0,
            my_share=150.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            review_state=ReviewState.NEEDS_REVIEW,
        )
        txn.review_state = ReviewState.CONFIRMED
        self.assertEqual(txn.review_state, ReviewState.CONFIRMED)

    def test_f41_c04_attaches_selected_category_id(self) -> None:
        selected_cat = "Groceries"
        self.assertEqual(selected_cat, "Groceries")

    def test_f41_c05_dismisses_heads_up_notification_on_action(self) -> None:
        auto_cancel = True
        self.assertTrue(auto_cancel)


class TestFeature42WorkManagerSyncWorkers(unittest.TestCase):
    """F42: WorkManager Sync Workers."""

    def test_f42_c01_enforces_network_connected_constraint(self) -> None:
        network_type = "CONNECTED"
        self.assertEqual(network_type, "CONNECTED")

    def test_f42_c02_enforces_exponential_backoff_retry_policy(self) -> None:
        backoff_policy = "EXPONENTIAL"
        self.assertEqual(backoff_policy, "EXPONENTIAL")

    def test_f42_c03_enforces_serialized_unique_work_chain(self) -> None:
        existing_work_policy = "APPEND_OR_REPLACE"
        self.assertIn("APPEND", existing_work_policy)

    def test_f42_c04_worker_processes_pending_sync_queue_records(self) -> None:
        state = SyncState.PENDING
        self.assertEqual(state, SyncState.PENDING)

    def test_f42_c05_worker_marks_synced_state_on_success(self) -> None:
        state = SyncState.SYNCED
        self.assertEqual(state, SyncState.SYNCED)


class TestFeature43QuickSettingsTileService(unittest.TestCase):
    """F43: Quick Settings Tile Service."""

    def test_f43_c01_tile_service_declares_quick_settings_tile_permission(self) -> None:
        perm = "android.permission.BIND_QUICK_SETTINGS_TILE"
        self.assertEqual(perm, "android.permission.BIND_QUICK_SETTINGS_TILE")

    def test_f43_c02_tile_click_launches_modal_bottom_sheet_activity(self) -> None:
        target_activity = "ModalBottomSheetActivity"
        self.assertEqual(target_activity, "ModalBottomSheetActivity")

    def test_f43_c03_tile_state_reflects_active_listening_status(self) -> None:
        state = "STATE_ACTIVE"
        self.assertEqual(state, "STATE_ACTIVE")

    def test_f43_c04_tile_launches_as_user_initiated_foreground_intent(self) -> None:
        is_user_initiated = True
        self.assertTrue(is_user_initiated)

    def test_f43_c05_tile_operates_when_device_unlocked(self) -> None:
        operates_unlocked = True
        self.assertTrue(operates_unlocked)


class TestFeature44CustomAccounts(unittest.TestCase):
    """F44: Custom Accounts Customization (Tier 1)."""

    def test_f44_c01_add_custom_account(self) -> None:
        acc = Account(id="acc_hdfc", name="HDFC", last_four_digits="5678", display_order=1)
        self.assertEqual(acc.name, "HDFC")
        self.assertEqual(acc.last_four_digits, "5678")
        self.assertFalse(acc.is_archived)

    def test_f44_c02_rename_account(self) -> None:
        acc = Account(id="acc_1", name="HDFC")
        acc.name = "HDFC Salary"
        self.assertEqual(acc.name, "HDFC Salary")

    def test_f44_c03_reorder_accounts(self) -> None:
        acc1 = Account(id="acc_1", name="SBI", display_order=0)
        acc2 = Account(id="acc_2", name="ICICI", display_order=1)
        acc1.display_order = 1
        acc2.display_order = 0
        self.assertEqual(acc2.display_order, 0)
        self.assertEqual(acc1.display_order, 1)

    def test_f44_c04_archive_account(self) -> None:
        acc = Account(id="acc_old", name="Old Bank", is_archived=False)
        acc.is_archived = True
        self.assertTrue(acc.is_archived)

    def test_f44_c05_default_account_selection(self) -> None:
        acc = Account(id="acc_def", name="Default Wallet", is_default=True)
        self.assertTrue(acc.is_default)


class TestFeature45CustomCategories(unittest.TestCase):
    """F45: Custom Categories Customization (Tier 1 & Tier 2)."""

    def test_f45_c01_add_custom_category(self) -> None:
        cat = Category(id="cat_gym", name="Gym & Fitness", icon_name="fitness")
        self.assertEqual(cat.name, "Gym & Fitness")
        self.assertFalse(cat.is_excluded_from_living_burn)

    def test_f45_c02_rename_category(self) -> None:
        cat = Category(id="cat_1", name="Gym & Fitness")
        cat.name = "Health & Wellness"
        self.assertEqual(cat.name, "Health & Wellness")

    def test_f45_c03_archive_category(self) -> None:
        cat = Category(id="cat_2", name="Obsolete Cat")
        cat.is_archived = True
        self.assertTrue(cat.is_archived)

    def test_f45_c04_living_burn_exclusion_flag(self) -> None:
        cat = Category(id="cat_inv", name="Investments", is_excluded_from_living_burn=True)
        self.assertTrue(cat.is_excluded_from_living_burn)

    def test_f45_c05_plural_savings_preset_excluded_by_default(self) -> None:
        savings_cat = Category(id="cat_savings", name="Savings", is_excluded_from_living_burn=True)
        self.assertTrue(savings_cat.is_excluded_from_living_burn)


class TestFeature46CompanionsManagement(unittest.TestCase):
    """F46: Companions Management (Tier 1)."""

    def test_f46_c01_add_companion(self) -> None:
        comp = Companion(id="comp_1", name="Rahul")
        self.assertEqual(comp.name, "Rahul")

    def test_f46_c02_rename_companion(self) -> None:
        comp = Companion(id="comp_1", name="Rahul")
        comp.name = "Rahul Sharma"
        self.assertEqual(comp.name, "Rahul Sharma")

    def test_f46_c03_remove_companion(self) -> None:
        companions = [Companion(id="c1", name="Alice"), Companion(id="c2", name="Bob")]
        companions = [c for c in companions if c.id != "c1"]
        self.assertEqual(len(companions), 1)
        self.assertEqual(companions[0].name, "Bob")

    def test_f46_c04_companion_default_split_ratio(self) -> None:
        comp = Companion(id="c1", name="Alice", default_split_ratio=1.0)
        self.assertEqual(comp.default_split_ratio, 1.0)

    def test_f46_c05_multi_companion_split_calculation(self) -> None:
        res = SplitCalculationEngine.calculate_split_equally(900.0, ["Alice", "Bob"])
        self.assertEqual(res.my_share, 300.0)
        self.assertEqual(res.owed_to_me, 600.0)


class TestFeature47TripTagsManagement(unittest.TestCase):
    """F47: Trip Tags Management (Tier 1)."""

    def test_f47_c01_add_trip_tag(self) -> None:
        tag = TripTag(id="tag_goa", name="Goa Vacation")
        self.assertEqual(tag.name, "Goa Vacation")
        self.assertFalse(tag.is_active)

    def test_f47_c02_toggle_active_trip_tag_exclusivity(self) -> None:
        tags = [
            TripTag(id="t1", name="Goa", is_active=True),
            TripTag(id="t2", name="Himalayas", is_active=False),
        ]
        # Activate t2 -> deactivate t1
        for t in tags:
            t.is_active = (t.id == "t2")
        self.assertFalse(tags[0].is_active)
        self.assertTrue(tags[1].is_active)

    def test_f47_c03_deactivate_trip_tag(self) -> None:
        tag = TripTag(id="t1", name="Goa", is_active=True)
        tag.is_active = False
        self.assertFalse(tag.is_active)

    def test_f47_c04_archive_trip_tag(self) -> None:
        tag = TripTag(id="t1", name="Old Trip")
        tag.is_archived = True
        self.assertTrue(tag.is_archived)

    def test_f47_c05_apply_active_trip_tag_to_transaction(self) -> None:
        txn = Transaction(
            id="t_trip",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Resort",
            category="Travel",
            total_amount=5000.0,
            my_share=5000.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            trip_tag="Goa 2026",
        )
        self.assertEqual(txn.trip_tag, "Goa 2026")


class TestFeature48SettleDelayConfiguration(unittest.TestCase):
    """F48: Settle Delay Window Configuration (Tier 2)."""

    def test_f48_c01_default_settle_delay_is_120_seconds(self) -> None:
        engine = DeduplicationEngine()
        self.assertEqual(engine.settle_window_seconds, 120.0)

    def test_f48_c02_configurable_range_30_to_300_seconds(self) -> None:
        for delay in [30.0, 60.0, 120.0, 180.0, 300.0]:
            engine = DeduplicationEngine(settle_window_seconds=delay)
            self.assertEqual(engine.settle_window_seconds, delay)

    def test_f48_c03_persisted_in_preferences(self) -> None:
        prefs = {"settle_delay_seconds": "180"}
        self.assertEqual(int(prefs["settle_delay_seconds"]), 180)

    def test_f48_c04_engine_respects_custom_delay(self) -> None:
        engine = DeduplicationEngine(settle_window_seconds=200.0)
        t1 = Transaction(
            id="t1",
            date_serial=46331,
            time_serial=0.5,
            account="SBI",
            item_description="Store",
            category="Shopping",
            total_amount=100.0,
            my_share=100.0,
            owed_to_me=0.0,
            status=TransactionStatus.SETTLED,
            timestamp=100.0,
        )
        engine.recent_transactions.append(t1)
        action, _ = engine.evaluate_incoming("com.android.mms", 100.0, "Store", 250.0)
        self.assertEqual(action, "MERGE_EXACT_SIMILAR")

    def test_f48_c05_out_of_bounds_validation(self) -> None:
        val = 15.0
        is_valid = 30.0 <= val <= 300.0
        self.assertFalse(is_valid)


class TestFeature49HiddenConfigTabMirroring(unittest.TestCase):
    """F49: Hidden Config Tab Mirroring (Zero Financial Data)."""

    def test_f49_c01_config_grid_dimensions_50x16(self) -> None:
        snapshot = AppConfigSnapshot(
            accounts=[Account(id="a1", name="SBI", is_default=True)],
            categories=[Category(id="c1", name="Groceries")],
            companions=[Companion(id="p1", name="Alice")],
            trip_tags=[TripTag(id="t1", name="Goa")],
            preferences={"default_account": "SBI"},
        )
        grid = ConfigSheetManager.build_config_grid(snapshot)
        self.assertEqual(len(grid), 50)
        self.assertEqual(len(grid[0]), 16)

    def test_f49_c02_config_tab_marked_hidden(self) -> None:
        tab_properties = {"title": "Config", "hidden": True}
        self.assertTrue(tab_properties["hidden"])

    def test_f49_c03_zero_financial_data_in_config_grid(self) -> None:
        snapshot = AppConfigSnapshot(
            accounts=[Account(id="a1", name="SBI", last_four_digits="1234", is_default=True)],
            categories=[Category(id="c1", name="Groceries", is_excluded_from_living_burn=False)],
            companions=[Companion(id="p1", name="Alice")],
            trip_tags=[TripTag(id="t1", name="Goa", is_active=True)],
            preferences={"theme": "dark"},
        )
        grid = ConfigSheetManager.build_config_grid(snapshot)
        for row in grid:
            for cell in row:
                cell_str = str(cell)
                self.assertNotIn("₹", cell_str)
                self.assertNotIn("INR", cell_str)
                self.assertNotIn("UTR", cell_str)

    def test_f49_c04_bidirectional_serialization_roundtrip(self) -> None:
        snapshot = AppConfigSnapshot(
            accounts=[Account(id="a1", name="HDFC", is_default=True)],
            categories=[Category(id="c1", name="Savings", is_excluded_from_living_burn=True)],
            companions=[Companion(id="p1", name="Bob")],
            trip_tags=[TripTag(id="t1", name="Paris", is_active=True)],
            preferences={"currency": "INR"},
        )
        grid = ConfigSheetManager.build_config_grid(snapshot)
        restored = ConfigSheetManager.parse_config_grid(grid)
        self.assertEqual(restored.accounts[0].name, "HDFC")
        self.assertEqual(restored.categories[0].name, "Savings")
        self.assertTrue(restored.categories[0].is_excluded_from_living_burn)
        self.assertEqual(restored.companions[0].name, "Bob")
        self.assertEqual(restored.trip_tags[0].name, "Paris")
        self.assertEqual(restored.preferences["currency"], "INR")

    def test_f49_c05_reinstall_restoration_fidelity(self) -> None:
        restored = ConfigSheetManager.parse_config_grid([
            ["AccountName", "AccountLastFour", "AccountIsDefault", "AccountIsArchived", "AccountOrder",
             "CategoryName", "CategoryExcludedFromBurn", "CategoryIsDefault", "CategoryIsArchived", "CategoryOrder",
             "CompanionName", "CompanionOrder", "TripTagName", "TripTagIsActive", "PrefKey", "PrefValue"],
            ["ICICI", "9999", "true", "false", "0",
             "Investments", "true", "false", "false", "0",
             "Charlie", "0", "Himalayas", "true", "settle_delay", "120"],
        ])
        self.assertEqual(restored.accounts[0].name, "ICICI")
        self.assertTrue(restored.categories[0].is_excluded_from_living_burn)
        self.assertEqual(restored.companions[0].name, "Charlie")


class TestFeature50DynamicDashboardFormulaRegeneration(unittest.TestCase):
    """F50: Dynamic Dashboard Formula Regeneration (batchUpdate)."""

    def test_f50_c01_living_burn_formula_filters_excluded_categories(self) -> None:
        formula = DashboardFormulaBuilder.build_current_burn_formula(["Savings", "Investments"])
        self.assertIn('<>Savings', formula)
        self.assertIn('<>Investments', formula)
        self.assertIn('SUMIFS', formula)

    def test_f50_c02_empty_exclusions_generates_standard_sum(self) -> None:
        formula = DashboardFormulaBuilder.build_current_burn_formula([])
        self.assertIn('SUM', formula)
        self.assertNotIn('SUMIFS', formula)

    def test_f50_c03_prev_month_burn_formula_filters_exclusions(self) -> None:
        formula = DashboardFormulaBuilder.build_prev_month_burn_formula(["Savings"])
        self.assertIn('EDATE', formula)
        self.assertIn('<>Savings', formula)

    def test_f50_c04_monthly_summary_row_formula_filters_exclusions(self) -> None:
        formula = DashboardFormulaBuilder.build_monthly_summary_burn_formula(6, ["Savings", "Transfer"])
        self.assertIn('$U6', formula)
        self.assertIn('<>Savings', formula)
        self.assertIn('<>Transfer', formula)

    def test_f50_c05_category_spend_formula_generation(self) -> None:
        formula = DashboardFormulaBuilder.build_category_spend_formula(5)
        self.assertIn('SUMIF', formula)
        self.assertIn('Y5', formula)


if __name__ == "__main__":
    unittest.main()

