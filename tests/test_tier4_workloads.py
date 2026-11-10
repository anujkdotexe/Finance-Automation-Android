"""Tier 4 Workload and Real-World Scenario Tests for Finance Automation Android.

This module provides the workload test suite executing full real-world scenarios
under purged UTR invariants, Amount + Merchant deduplication, non-destructive
in-place voiding (G=0), and debt carry-forward rollover (target G=0).
"""

from __future__ import annotations

import unittest

from tests.test_tier4_real_world import TestTier4RealWorldScenarios

TestTier4Workloads = TestTier4RealWorldScenarios

if __name__ == "__main__":
    unittest.main()
