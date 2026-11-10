"""Unified E2E Test Runner for Finance Automation Android.

Executes all four test tiers (Tier 1 Features, Tier 2 Boundaries,
Tier 3 Combinations, Tier 4 Real-World Scenarios) and reports tier-by-tier
counts, total execution time, and quantitative coverage metrics.
Exits with status code 0 on complete pass, or 1 on any failure.
"""

from __future__ import annotations

import io
import pathlib
import sys
import time
import unittest

ROOT_DIR = pathlib.Path(__file__).resolve().parent.parent
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from tests.test_tier1_features import (
    TestFeature01HeadlessGradleScaffolding,
    TestFeature02VersionCatalogConfig,
    TestFeature03RootAndAppBuildScripts,
    TestFeature04AndroidManifestAndPermissions,
    TestFeature05CleanArchitectureTree,
    TestFeature06ManualDependencyInjection,
    TestFeature07SchemaConstants31Columns,
    TestFeature08CurrencyAndAmountRegex,
    TestFeature09MerchantPayeeRegex,
    TestFeature10UtrReferenceRegex,
    TestFeature11DebitVsCreditFlagRegex,
    TestFeature12FundingAccountRegex,
    TestFeature13DateAndTimeSerialUtilities,
    TestFeature14DomainModels,
    TestFeature15JustMeSplitMath,
    TestFeature16SplitEquallyMath,
    TestFeature17CustomSplitMath,
    TestFeature18NetBurnIsolation,
    TestFeature19PluralSavingsKeywordRule,
    TestFeature20StatusValueStandardization,
    TestFeature21NonDestructiveInPlaceVoid,
    TestFeature22DebtRolloverMath,
    TestFeature23InflowSettlementLinking,
    TestFeature24DomainUseCases,
    TestFeature25RepositoryInterfaces,
    TestFeature26RoomSQLiteDatabase,
    TestFeature27RoomEntitiesAndDaos,
    TestFeature28TwoAxisStateMachine,
    TestFeature29RepositoryImplementations,
    TestFeature30DirectOkHttpDriveClient,
    TestFeature31DirectOkHttpSheetsClient,
    TestFeature32OAuthTokenRecovery,
    TestFeature33SheetProvisionerLayout,
    TestFeature34PackageWhitelistFilter,
    TestFeature35PrivacyGuardAndBlacklist,
    TestFeature36OtpAndAuthCodeSuppression,
    TestFeature37SettleWindowEngine,
    TestFeature38ExactUtrDeduplication,
    TestFeature39CrossSourceWindowMatching,
    TestFeature40ThreeButtonHeadsUpNotification,
    TestFeature41QuickActionReceiver,
    TestFeature42WorkManagerSyncWorkers,
    TestFeature43QuickSettingsTileService,
    TestFeature44CustomAccounts,
    TestFeature45CustomCategories,
    TestFeature46CompanionsManagement,
    TestFeature47TripTagsManagement,
    TestFeature48SettleDelayConfiguration,
    TestFeature49HiddenConfigTabMirroring,
    TestFeature50DynamicDashboardFormulaRegeneration,
)
from tests.test_tier2_boundaries import (
    TestTier2AmountBoundaries,
    TestTier2CategoryValidationBoundaries,
    TestTier2CompanionCountBoundaries,
    TestTier2CustomSplitBoundaries,
    TestTier2DateSerialBoundaries,
    TestTier2DebtRolloverBoundaries,
    TestTier2PayloadAndFormulaInjectionBoundaries,
    TestTier2SettleWindowTimeBoundaries,
    TestTier2StateMachineBoundaries,
    TestTier2TimeSerialBoundaries,
    TestTier2UtrLengthBoundaries,
)
from tests.test_tier3_combinations import (
    TestTier3CategoryAndSplitCombinations,
    TestTier3InflowTypeAndRepaymentCombinations,
    TestTier3SettleWindowAndDeduplicationCombinations,
    TestTier3SourceAndDirectionCombinations,
    TestTier3SplitModeStateCombinations,
    TestTier3TwoAxisStateTransitionsPairwise,
)
from tests.test_tier4_real_world import TestTier4RealWorldScenarios


def build_tier1_suite() -> unittest.TestSuite:
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()
    tier1_classes = [
        TestFeature01HeadlessGradleScaffolding,
        TestFeature02VersionCatalogConfig,
        TestFeature03RootAndAppBuildScripts,
        TestFeature04AndroidManifestAndPermissions,
        TestFeature05CleanArchitectureTree,
        TestFeature06ManualDependencyInjection,
        TestFeature07SchemaConstants31Columns,
        TestFeature08CurrencyAndAmountRegex,
        TestFeature09MerchantPayeeRegex,
        TestFeature10UtrReferenceRegex,
        TestFeature11DebitVsCreditFlagRegex,
        TestFeature12FundingAccountRegex,
        TestFeature13DateAndTimeSerialUtilities,
        TestFeature14DomainModels,
        TestFeature15JustMeSplitMath,
        TestFeature16SplitEquallyMath,
        TestFeature17CustomSplitMath,
        TestFeature18NetBurnIsolation,
        TestFeature19PluralSavingsKeywordRule,
        TestFeature20StatusValueStandardization,
        TestFeature21NonDestructiveInPlaceVoid,
        TestFeature22DebtRolloverMath,
        TestFeature23InflowSettlementLinking,
        TestFeature24DomainUseCases,
        TestFeature25RepositoryInterfaces,
        TestFeature26RoomSQLiteDatabase,
        TestFeature27RoomEntitiesAndDaos,
        TestFeature28TwoAxisStateMachine,
        TestFeature29RepositoryImplementations,
        TestFeature30DirectOkHttpDriveClient,
        TestFeature31DirectOkHttpSheetsClient,
        TestFeature32OAuthTokenRecovery,
        TestFeature33SheetProvisionerLayout,
        TestFeature34PackageWhitelistFilter,
        TestFeature35PrivacyGuardAndBlacklist,
        TestFeature36OtpAndAuthCodeSuppression,
        TestFeature37SettleWindowEngine,
        TestFeature38ExactUtrDeduplication,
        TestFeature39CrossSourceWindowMatching,
        TestFeature40ThreeButtonHeadsUpNotification,
        TestFeature41QuickActionReceiver,
        TestFeature42WorkManagerSyncWorkers,
        TestFeature43QuickSettingsTileService,
        TestFeature44CustomAccounts,
        TestFeature45CustomCategories,
        TestFeature46CompanionsManagement,
        TestFeature47TripTagsManagement,
        TestFeature48SettleDelayConfiguration,
        TestFeature49HiddenConfigTabMirroring,
        TestFeature50DynamicDashboardFormulaRegeneration,
    ]
    for cls in tier1_classes:
        suite.addTests(loader.loadTestsFromTestCase(cls))
    return suite


def build_tier2_suite() -> unittest.TestSuite:
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()
    tier2_classes = [
        TestTier2AmountBoundaries,
        TestTier2UtrLengthBoundaries,
        TestTier2DateSerialBoundaries,
        TestTier2TimeSerialBoundaries,
        TestTier2CompanionCountBoundaries,
        TestTier2CustomSplitBoundaries,
        TestTier2PayloadAndFormulaInjectionBoundaries,
        TestTier2SettleWindowTimeBoundaries,
        TestTier2DebtRolloverBoundaries,
        TestTier2CategoryValidationBoundaries,
        TestTier2StateMachineBoundaries,
    ]
    for cls in tier2_classes:
        suite.addTests(loader.loadTestsFromTestCase(cls))
    return suite


def build_tier3_suite() -> unittest.TestSuite:
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()
    tier3_classes = [
        TestTier3SplitModeStateCombinations,
        TestTier3SourceAndDirectionCombinations,
        TestTier3CategoryAndSplitCombinations,
        TestTier3SettleWindowAndDeduplicationCombinations,
        TestTier3InflowTypeAndRepaymentCombinations,
        TestTier3TwoAxisStateTransitionsPairwise,
    ]
    for cls in tier3_classes:
        suite.addTests(loader.loadTestsFromTestCase(cls))
    return suite


def build_tier4_suite() -> unittest.TestSuite:
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()
    suite.addTests(loader.loadTestsFromTestCase(TestTier4RealWorldScenarios))
    return suite


def run_suite(name: str, suite: unittest.TestSuite) -> tuple[int, int, int, float]:
    start_time = time.perf_counter()
    devnull_stream = io.StringIO()
    runner = unittest.TextTestRunner(verbosity=0, stream=devnull_stream)
    result = runner.run(suite)
    duration = time.perf_counter() - start_time
    total = result.testsRun
    failures = len(result.failures)
    errors = len(result.errors)
    passed = total - failures - errors
    return total, passed, (failures + errors), duration


def main() -> int:
    print("=" * 80)
    print("FINANCE AUTOMATION ANDROID — DUAL TRACK E2E TEST SUITE RUNNER")
    print("=" * 80)
    print(f"Timestamp: {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}")
    print("Test Methodology: 4-Tier Verification (CP, BVA, Pairwise, Real-World Workloads)")
    print("External Network Calls: 0 (Zero Paid API Calls / 100% Deterministic Local)")
    print("-" * 80)

    suites = [
        ("Tier 1: Feature Partition (50 Features)", build_tier1_suite()),
        ("Tier 2: Boundary Value Analysis (BVA)", build_tier2_suite()),
        ("Tier 3: Pairwise Combinations (OATS)", build_tier3_suite()),
        ("Tier 4: Real-World Workload Scenarios", build_tier4_suite()),
    ]

    tier_results = []
    grand_total = 0
    grand_passed = 0
    grand_failed = 0
    grand_duration = 0.0

    for name, suite in suites:
        total, passed, failed, duration = run_suite(name, suite)
        tier_results.append((name, total, passed, failed, duration))
        grand_total += total
        grand_passed += passed
        grand_failed += failed
        grand_duration += duration

    print(f"{'Tier Name':<45} | {'Total':<6} | {'Passed':<6} | {'Failed':<6} | {'Time (s)':<8}")
    print("-" * 80)
    for name, total, passed, failed, duration in tier_results:
        print(f"{name:<45} | {total:<6} | {passed:<6} | {failed:<6} | {duration:<8.4f}")
    print("-" * 80)
    print(f"{'GRAND TOTAL':<45} | {grand_total:<6} | {grand_passed:<6} | {grand_failed:<6} | {grand_duration:<8.4f}")
    print("=" * 80)

    pass_rate = (grand_passed / grand_total * 100.0) if grand_total > 0 else 0.0
    print(f"Overall Pass Rate: {pass_rate:.1f}% ({grand_passed}/{grand_total})")

    if grand_failed == 0 and grand_total >= 300:
        print("VERIFICATION RESULT: ALL TEST TIERS PASSED PERFECTLY")
        return 0
    else:
        print(f"VERIFICATION RESULT: FAILURE DETECTED ({grand_failed} failed tests)")
        return 1


if __name__ == "__main__":
    sys.exit(main())
