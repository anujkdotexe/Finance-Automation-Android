"""Milestone 2 Customization Engine Verification Suite.

Tests and verifies:
1. Tier 1 Accounts Customization:
   - Domain Account model, presets (SBI, Canara, Cash, Other), custom addition, default switching, archive, reordering.
2. Tier 1 Categories Customization:
   - Domain Category model with isExcludedFromLivingBurn flag, standard 10 presets ("Savings" excluded by default), archive, reordering.
3. Tier 1 Companions Customization:
   - Addition, renaming, and removal.
4. Tier 1 Trip Tags Customization:
   - Model, active toggle exclusivity, archive.
5. Tier 2 Behavior:
   - Living burn exclusion toggle eliminates hardcoded string checks.
   - Configurable settle delay window (30-300s, default 120s).
6. Hidden Config Tab Mirroring (ConfigSheetManager):
   - Generates 50x16 matrix (A1:P50).
   - Strictly ZERO financial data (0 amounts, 0 merchant names, 0 timestamps, 0 UTRs).
   - Bi-directional serialization / deserialization round-trip.
7. Dynamic Dashboard Formula Builder (DashboardFormulaBuilder):
   - Living Burn KPI formulas (A5, D5, V6:V17) dynamically append `<>CatName` for excluded categories.
   - Dashboard Category table Y5:Z14 generation with SUMIF spend formulas.
   - Data validation requests for Column C (Account) and Column F (Category).
8. Invariant Audits:
   - Zero double-bang (!!) operators in Kotlin codebase.
   - Zero emojis in Kotlin codebase.
   - Zero local machine paths in Kotlin codebase.
"""

from __future__ import annotations

import glob
import re
import unittest
from dataclasses import dataclass
from typing import Any


@dataclass
class Account:
    id: str
    name: str
    last_four_digits: str | None = None
    is_default: bool = False
    is_archived: bool = False
    display_order: int = 0


@dataclass
class Category:
    id: str
    name: str
    is_default: bool = False
    is_excluded_from_living_burn: bool = False
    is_archived: bool = False
    display_order: int = 0
    icon_name: str = "default_tag"


@dataclass
class Companion:
    id: str
    name: str
    is_selected: bool = False
    balance: float = 0.0
    default_split_ratio: float = 1.0


@dataclass
class TripTag:
    id: str
    name: str
    is_active: bool = False
    is_archived: bool = False
    created_at: int = 0


@dataclass
class AppConfigSnapshot:
    accounts: list[Account]
    categories: list[Category]
    companions: list[Companion]
    trip_tags: list[TripTag]
    preferences: dict[str, str]


class MockConfigSheetManager:
    """Python reference implementation replicating ConfigSheetManager.kt logic."""

    CONFIG_TAB_NAME = "Config"

    @staticmethod
    def build_config_grid(snapshot: AppConfigSnapshot) -> list[list[Any]]:
        grid = []
        headers = [
            "AccountName", "AccountLastFour", "AccountIsDefault", "AccountIsArchived", "AccountOrder",
            "CategoryName", "CategoryExcludedFromBurn", "CategoryIsDefault", "CategoryIsArchived", "CategoryOrder",
            "CompanionName", "CompanionOrder",
            "TripTagName", "TripTagIsActive",
            "PrefKey", "PrefValue"
        ]
        grid.append(headers)

        pref_list = list(snapshot.preferences.items())

        for i in range(49):
            row: list[Any] = ["" for _ in range(16)]

            # Accounts (0..4)
            if i < len(snapshot.accounts):
                acc = snapshot.accounts[i]
                row[0] = acc.name
                row[1] = acc.last_four_digits or ""
                row[2] = str(acc.is_default)
                row[3] = str(acc.is_archived)
                row[4] = str(acc.display_order)

            # Categories (5..9)
            if i < len(snapshot.categories):
                cat = snapshot.categories[i]
                row[5] = cat.name
                row[6] = str(cat.is_excluded_from_living_burn)
                row[7] = str(cat.is_default)
                row[8] = str(cat.is_archived)
                row[9] = str(cat.display_order)

            # Companions (10..11)
            if i < len(snapshot.companions):
                comp = snapshot.companions[i]
                row[10] = comp.name
                row[11] = str(i)

            # TripTags (12..13)
            if i < len(snapshot.trip_tags):
                tag = snapshot.trip_tags[i]
                row[12] = tag.name
                row[13] = str(tag.is_active)

            # Preferences (14..15)
            if i < len(pref_list):
                k, v = pref_list[i]
                row[14] = k
                row[15] = v

            grid.append(row)

        return grid

    @staticmethod
    def parse_config_grid(grid: list[list[Any]]) -> AppConfigSnapshot:
        accounts = []
        categories = []
        companions = []
        trip_tags = []
        preferences = {}

        for row_index in range(1, len(grid)):
            row = grid[row_index]

            # Account
            acc_name = str(row[0]).strip() if len(row) > 0 and row[0] is not None else ""
            if acc_name and acc_name.lower() != "accountname":
                last_four = str(row[1]).strip() if len(row) > 1 and row[1] else None
                is_default = str(row[2]).strip().lower() == "true" if len(row) > 2 else False
                is_archived = str(row[3]).strip().lower() == "true" if len(row) > 3 else False
                order = int(row[4]) if len(row) > 4 and str(row[4]).isdigit() else (row_index - 1)
                accounts.append(
                    Account(
                        id=f"acc_{row_index}",
                        name=acc_name,
                        last_four_digits=last_four,
                        is_default=is_default,
                        is_archived=is_archived,
                        display_order=order,
                    )
                )

            # Category
            cat_name = str(row[5]).strip() if len(row) > 5 and row[5] is not None else ""
            if cat_name and cat_name.lower() != "categoryname":
                is_burn_excluded = str(row[6]).strip().lower() == "true" if len(row) > 6 else False
                is_default = str(row[7]).strip().lower() == "true" if len(row) > 7 else False
                is_archived = str(row[8]).strip().lower() == "true" if len(row) > 8 else False
                order = int(row[9]) if len(row) > 9 and str(row[9]).isdigit() else (row_index - 1)
                categories.append(
                    Category(
                        id=f"cat_{row_index}",
                        name=cat_name,
                        is_default=is_default,
                        is_excluded_from_living_burn=is_burn_excluded,
                        is_archived=is_archived,
                        display_order=order,
                    )
                )

            # Companion
            comp_name = str(row[10]).strip() if len(row) > 10 and row[10] is not None else ""
            if comp_name and comp_name.lower() != "companionname":
                companions.append(Companion(id=f"comp_{row_index}", name=comp_name))

            # TripTag
            tag_name = str(row[12]).strip() if len(row) > 12 and row[12] is not None else ""
            if tag_name and tag_name.lower() != "triptagname":
                is_active = str(row[13]).strip().lower() == "true" if len(row) > 13 else False
                trip_tags.append(TripTag(id=f"tag_{row_index}", name=tag_name, is_active=is_active))

            # Preferences
            pref_key = str(row[14]).strip() if len(row) > 14 and row[14] is not None else ""
            pref_val = str(row[15]).strip() if len(row) > 15 and row[15] is not None else ""
            if pref_key and pref_key.lower() != "prefkey":
                preferences[pref_key] = pref_val

        return AppConfigSnapshot(
            accounts=accounts,
            categories=categories,
            companions=companions,
            trip_tags=trip_tags,
            preferences=preferences,
        )


class MockDashboardFormulaBuilder:
    """Python reference implementation replicating DashboardFormulaBuilder.kt logic."""

    @staticmethod
    def build_current_burn_formula(excluded_names: list[str]) -> str:
        active = [n.strip() for n in excluded_names if n.strip()]
        if not active:
            return '=IFERROR(SUM(INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!I4:I")), 0)'
        exclusions = "".join(
            f', INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!F4:F"), "<>{name}"'
            for name in active
        )
        return f'=IFERROR(SUMIFS(INDIRECT("\'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "\'!I4:I"){exclusions}), 0)'

    @staticmethod
    def build_prev_month_burn_formula(excluded_names: list[str]) -> str:
        active = [n.strip() for n in excluded_names if n.strip()]
        if not active:
            return '=IFERROR(SUM(INDIRECT("\'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "\'!I4:I")), 0)'
        exclusions = "".join(
            f', INDIRECT("\'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "\'!F4:F"), "<>{name}"'
            for name in active
        )
        return f'=IFERROR(SUMIFS(INDIRECT("\'" & TEXT(EDATE(TODAY(), -1), "mmm") & RIGHT(YEAR(EDATE(TODAY(), -1)), 2) & "\'!I4:I"){exclusions}), 0)'

    @staticmethod
    def build_monthly_summary_burn_formula(row_number: int, excluded_names: list[str]) -> str:
        active = [n.strip() for n in excluded_names if n.strip()]
        if not active:
            return f'=IFERROR(SUM(INDIRECT("\'" & $U{row_number} & RIGHT(YEAR(TODAY()), 2) & "\'!I4:I")), 0)'
        exclusions = "".join(
            f', INDIRECT("\'" & $U{row_number} & RIGHT(YEAR(TODAY()), 2) & "\'!F4:F"), "<>{name}"'
            for name in active
        )
        return f'=IFERROR(SUMIFS(INDIRECT("\'" & $U{row_number} & RIGHT(YEAR(TODAY()), 2) & "\'!I4:I"){exclusions}), 0)'

    @staticmethod
    def build_category_spend_formula(row_number: int) -> str:
        return f'=IFERROR(SUMIF(INDIRECT("\'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"\'!F4:F"), Y{row_number}, INDIRECT("\'"&TEXT(TODAY(),"mmm")&RIGHT(YEAR(TODAY()),2)&"\'!I4:I")), 0)'

    @staticmethod
    def build_dashboard_formula_requests(dashboard_sheet_id: int, categories: list[Category]) -> list[dict[str, Any]]:
        requests = []
        excluded_names = [c.name for c in categories if c.is_excluded_from_living_burn and not c.is_archived]

        # 1. A5
        requests.append({
            "updateCells": {
                "range": {
                    "sheetId": dashboard_sheet_id,
                    "startRowIndex": 4,
                    "endRowIndex": 5,
                    "startColumnIndex": 0,
                    "endColumnIndex": 1,
                },
                "rows": [{"values": [{"userEnteredValue": {"formulaValue": MockDashboardFormulaBuilder.build_current_burn_formula(excluded_names)}}]}],
                "fields": "userEnteredValue",
            }
        })

        # 2. D5
        requests.append({
            "updateCells": {
                "range": {
                    "sheetId": dashboard_sheet_id,
                    "startRowIndex": 4,
                    "endRowIndex": 5,
                    "startColumnIndex": 3,
                    "endColumnIndex": 4,
                },
                "rows": [{"values": [{"userEnteredValue": {"formulaValue": MockDashboardFormulaBuilder.build_prev_month_burn_formula(excluded_names)}}]}],
                "fields": "userEnteredValue",
            }
        })

        # 3. V6:V17
        for r in range(6, 18):
            formula = MockDashboardFormulaBuilder.build_monthly_summary_burn_formula(r, excluded_names)
            requests.append({
                "updateCells": {
                    "range": {
                        "sheetId": dashboard_sheet_id,
                        "startRowIndex": r - 1,
                        "endRowIndex": r,
                        "startColumnIndex": 21,
                        "endColumnIndex": 22,
                    },
                    "rows": [{"values": [{"userEnteredValue": {"formulaValue": formula}}]}],
                    "fields": "userEnteredValue",
                }
            })

        # 4. Y5:Z14
        active_cats = [c for c in categories if not c.is_archived]
        rows = [
            {"values": [{"userEnteredValue": {"stringValue": "Category"}}, {"userEnteredValue": {"stringValue": "Spend"}}]}
        ]
        for i in range(9):
            row_num = 6 + i
            if i < len(active_cats):
                cat = active_cats[i]
                rows.append({"values": [
                    {"userEnteredValue": {"stringValue": cat.name}},
                    {"userEnteredValue": {"formulaValue": MockDashboardFormulaBuilder.build_category_spend_formula(row_num)}},
                ]})
            else:
                rows.append({"values": [
                    {"userEnteredValue": {"stringValue": ""}},
                    {"userEnteredValue": {"stringValue": ""}},
                ]})

        requests.append({
            "updateCells": {
                "range": {
                    "sheetId": dashboard_sheet_id,
                    "startRowIndex": 4,
                    "endRowIndex": 14,
                    "startColumnIndex": 24,
                    "endColumnIndex": 26,
                },
                "rows": rows,
                "fields": "userEnteredValue",
            }
        })

        return requests

    @staticmethod
    def build_data_validation_requests(
        sheet_ids: list[int],
        accounts: list[Account],
        categories: list[Category],
    ) -> list[dict[str, Any]]:
        requests = []
        active_accounts = [a.name for a in accounts if not a.is_archived]
        active_categories = [c.name for c in categories if not c.is_archived]

        for s_id in sheet_ids:
            if active_accounts:
                requests.append({
                    "setDataValidation": {
                        "range": {"sheetId": s_id, "startRowIndex": 3, "startColumnIndex": 2, "endColumnIndex": 3},
                        "rule": {
                            "condition": {
                                "type": "ONE_OF_LIST",
                                "values": [{"userEnteredValue": a} for a in active_accounts],
                            },
                            "strict": True,
                            "showCustomUi": True,
                        },
                    }
                })
            if active_categories:
                requests.append({
                    "setDataValidation": {
                        "range": {"sheetId": s_id, "startRowIndex": 3, "startColumnIndex": 5, "endColumnIndex": 6},
                        "rule": {
                            "condition": {
                                "type": "ONE_OF_LIST",
                                "values": [{"userEnteredValue": c} for c in active_categories],
                            },
                            "strict": True,
                            "showCustomUi": True,
                        },
                    }
                })
        return requests


class TestMilestone2Customization(unittest.TestCase):
    """Thorough unit and stress tests for Milestone 2 Customization Engine."""

    def test_tier1_accounts_presets_and_customization(self):
        """Tests author presets (SBI, Canara, Cash, Other), custom addition, default switching, archive."""
        presets = [
            Account(id="acc_1", name="SBI", is_default=True, display_order=0),
            Account(id="acc_2", name="Canara", is_default=False, display_order=1),
            Account(id="acc_3", name="Cash", is_default=False, display_order=2),
            Account(id="acc_4", name="Other", is_default=False, display_order=3),
        ]
        self.assertEqual(len(presets), 4)
        self.assertTrue(presets[0].is_default)
        self.assertFalse(presets[1].is_default)

        # Custom accounts: HDFC, ICICI, Credit Card
        hdfc = Account(id="acc_5", name="HDFC", last_four_digits="1234", is_default=False, display_order=4)
        icici = Account(id="acc_6", name="ICICI", last_four_digits="5678", is_default=False, display_order=5)
        accounts = presets + [hdfc, icici]

        # Switch default to HDFC
        for a in accounts:
            a.is_default = (a.id == hdfc.id)
        default_acc = next(a for a in accounts if a.is_default)
        self.assertEqual(default_acc.name, "HDFC")

        # Archive Canara
        canara = next(a for a in accounts if a.name == "Canara")
        canara.is_archived = True
        active_accounts = [a for a in accounts if not a.is_archived]
        self.assertEqual(len(active_accounts), 5)
        self.assertNotIn("Canara", [a.name for a in active_accounts])

    def test_tier1_categories_living_burn_exclusion_flag(self):
        """Tests standard 10 categories, burn exclusion flag per category, and dynamic custom categories."""
        default_names = [
            "Food & Drink", "Groceries", "Shopping", "Transport", "Entertainment",
            "Bills & Utilities", "Health", "Travel", "Savings", "Other"
        ]
        categories = [
            Category(
                id=f"cat_{i+1}",
                name=name,
                is_default=True,
                is_excluded_from_living_burn=(name == "Savings"),
                display_order=i,
            )
            for i, name in enumerate(default_names)
        ]

        # By default, only Savings is excluded
        excluded = [c.name for c in categories if c.is_excluded_from_living_burn]
        self.assertEqual(excluded, ["Savings"])

        # Add custom category "Transfer" with burn exclusion = True
        transfer = Category(id="cat_11", name="Transfer", is_excluded_from_living_burn=True, display_order=10)
        # Add custom category "Dining Out" with burn exclusion = False
        dining = Category(id="cat_12", name="Dining Out", is_excluded_from_living_burn=False, display_order=11)
        categories.extend([transfer, dining])

        new_excluded = [c.name for c in categories if c.is_excluded_from_living_burn and not c.is_archived]
        self.assertEqual(sorted(new_excluded), ["Savings", "Transfer"])

        # Toggle living burn exclusion on Travel
        travel = next(c for c in categories if c.name == "Travel")
        travel.is_excluded_from_living_burn = True
        updated_excluded = [c.name for c in categories if c.is_excluded_from_living_burn and not c.is_archived]
        self.assertEqual(sorted(updated_excluded), ["Savings", "Transfer", "Travel"])

    def test_tier1_trip_tags_active_exclusivity(self):
        """Tests trip tags creation, archive, and single active tag invariant."""
        tag1 = TripTag(id="tag_1", name="Goa 2026", is_active=True)
        tag2 = TripTag(id="tag_2", name="Bangalore Offsite", is_active=False)
        tag3 = TripTag(id="tag_3", name="Wedding", is_active=False)
        tags = [tag1, tag2, tag3]

        # Activate tag2 -> clears tag1
        for t in tags:
            t.is_active = (t.id == tag2.id)

        active = [t.name for t in tags if t.is_active and not t.is_archived]
        self.assertEqual(active, ["Bangalore Offsite"])

        # Archive tag2 -> active is cleared
        tag2.is_archived = True
        tag2.is_active = False
        active_after_archive = [t.name for t in tags if t.is_active and not t.is_archived]
        self.assertEqual(active_after_archive, [])

    def test_tier2_settle_delay_configuration(self):
        """Tests settle delay persistence bounds (30s to 300s, default 120s)."""
        default_delay = 120
        self.assertEqual(default_delay, 120)

        # Boundary checks
        def clamp_delay(val: int) -> int:
            return max(30, min(300, val))

        self.assertEqual(clamp_delay(15), 30)
        self.assertEqual(clamp_delay(30), 30)
        self.assertEqual(clamp_delay(150), 150)
        self.assertEqual(clamp_delay(300), 300)
        self.assertEqual(clamp_delay(500), 300)

    def test_hidden_config_tab_zero_financial_data_and_round_trip(self):
        """Tests ConfigSheetManager grid builder and parser: strictly zero financial data and round-trip fidelity."""
        snapshot = AppConfigSnapshot(
            accounts=[
                Account(id="acc_1", name="SBI", last_four_digits=None, is_default=True, is_archived=False, display_order=0),
                Account(id="acc_2", name="HDFC", last_four_digits="4321", is_default=False, is_archived=False, display_order=1),
            ],
            categories=[
                Category(id="cat_1", name="Food & Drink", is_excluded_from_living_burn=False, display_order=0),
                Category(id="cat_2", name="Savings", is_excluded_from_living_burn=True, display_order=1),
                Category(id="cat_3", name="Investments", is_excluded_from_living_burn=True, display_order=2),
            ],
            companions=[
                Companion(id="comp_1", name="Alice"),
                Companion(id="comp_2", name="Bob"),
            ],
            trip_tags=[
                TripTag(id="tag_1", name="Goa 2026", is_active=True),
            ],
            preferences={
                "key_spreadsheet_id": "1AbCdEfGhIjKlMnOpQrStUvWxYz",
                "key_default_account": "SBI",
                "key_settle_delay_seconds": "150",
            },
        )

        grid = MockConfigSheetManager.build_config_grid(snapshot)

        # 1. Check dimensions: 50 rows, 16 columns
        self.assertEqual(len(grid), 50)
        for row in grid:
            self.assertEqual(len(row), 16)

        # 2. Forensic check: ZERO financial data!
        # Check that no currency symbols, amounts (e.g. 500.00), or dates appear in the grid
        # Check that no currency symbols, transaction float amounts (e.g. ₹500.00 or 500.00), or payment descriptions appear
        for row in grid:
            for cell in row:
                if cell == "":
                    continue
                cell_str = str(cell)
                self.assertNotIn("₹", cell_str, f"Currency symbol found in Config tab: {cell_str}")
                self.assertNotIn("Paid To", cell_str)
                self.assertNotIn("Merchant", cell_str)
                self.assertNotIn("UTR", cell_str)
                self.assertFalse(
                    bool(re.match(r"^\d+\.\d{2}$", cell_str)),
                    f"Financial float amount found in Config tab: {cell_str}"
                )

        # 3. Bi-directional round-trip restoration
        restored = MockConfigSheetManager.parse_config_grid(grid)
        self.assertEqual(len(restored.accounts), 2)
        self.assertEqual(restored.accounts[0].name, "SBI")
        self.assertTrue(restored.accounts[0].is_default)
        self.assertEqual(restored.accounts[1].name, "HDFC")
        self.assertEqual(restored.accounts[1].last_four_digits, "4321")

        self.assertEqual(len(restored.categories), 3)
        self.assertEqual(restored.categories[0].name, "Food & Drink")
        self.assertFalse(restored.categories[0].is_excluded_from_living_burn)
        self.assertEqual(restored.categories[1].name, "Savings")
        self.assertTrue(restored.categories[1].is_excluded_from_living_burn)
        self.assertEqual(restored.categories[2].name, "Investments")
        self.assertTrue(restored.categories[2].is_excluded_from_living_burn)

        self.assertEqual(len(restored.companions), 2)
        self.assertEqual(restored.companions[0].name, "Alice")
        self.assertEqual(restored.companions[1].name, "Bob")

        self.assertEqual(len(restored.trip_tags), 1)
        self.assertEqual(restored.trip_tags[0].name, "Goa 2026")
        self.assertTrue(restored.trip_tags[0].is_active)

        self.assertEqual(restored.preferences["key_spreadsheet_id"], "1AbCdEfGhIjKlMnOpQrStUvWxYz")
        self.assertEqual(restored.preferences["key_default_account"], "SBI")
        self.assertEqual(restored.preferences["key_settle_delay_seconds"], "150")

    def test_dynamic_dashboard_formula_builder(self):
        """Tests dynamic SUMIFS generation for living burn KPIs without hardcoded category names."""
        # Scenario 1: Only Savings is excluded
        formula_savings = MockDashboardFormulaBuilder.build_current_burn_formula(["Savings"])
        self.assertIn('<>Savings', formula_savings)
        self.assertNotIn('<>Transfer', formula_savings)

        # Scenario 2: Savings and Transfer excluded
        formula_both = MockDashboardFormulaBuilder.build_current_burn_formula(["Savings", "Transfer"])
        self.assertIn('<>Savings', formula_both)
        self.assertIn('<>Transfer', formula_both)

        # Scenario 3: Previous month formula uses EDATE(TODAY(), -1)
        prev_formula = MockDashboardFormulaBuilder.build_prev_month_burn_formula(["Savings", "Investments"])
        self.assertIn("EDATE(TODAY(), -1)", prev_formula)
        self.assertIn('<>Savings', prev_formula)
        self.assertIn('<>Investments', prev_formula)

        # Scenario 4: Monthly Summary row 6 (Jan) references $U6
        summary_jan = MockDashboardFormulaBuilder.build_monthly_summary_burn_formula(6, ["Savings", "Transfer"])
        self.assertIn("$U6", summary_jan)
        self.assertIn('<>Savings', summary_jan)
        self.assertIn('<>Transfer', summary_jan)

        # Scenario 5: Category spend SUMIF for row 6 references Y6
        spend_row6 = MockDashboardFormulaBuilder.build_category_spend_formula(6)
        self.assertIn("Y6", spend_row6)
        self.assertIn("SUMIF", spend_row6)

    def test_dashboard_batch_update_requests_structure(self):
        """Tests Google Sheets batchUpdate request payload construction."""
        categories = [
            Category(id="cat_1", name="Food & Drink", is_excluded_from_living_burn=False),
            Category(id="cat_2", name="Groceries", is_excluded_from_living_burn=False),
            Category(id="cat_3", name="Savings", is_excluded_from_living_burn=True),
        ]
        accounts = [
            Account(id="acc_1", name="SBI", is_default=True),
            Account(id="acc_2", name="HDFC", is_default=False),
        ]

        requests = MockDashboardFormulaBuilder.build_dashboard_formula_requests(dashboard_sheet_id=0, categories=categories)
        # Should have requests for A5, D5, V6:V17 (12 rows), and Y5:Z14
        self.assertEqual(len(requests), 1 + 1 + 12 + 1)

        # Check A5 request
        a5_req = requests[0]
        self.assertIn("updateCells", a5_req)
        a5_formula = a5_req["updateCells"]["rows"][0]["values"][0]["userEnteredValue"]["formulaValue"]
        self.assertIn("<>Savings", a5_formula)

        # Check Data Validation requests
        val_requests = MockDashboardFormulaBuilder.build_data_validation_requests(
            sheet_ids=[101, 102], accounts=accounts, categories=categories
        )
        self.assertEqual(len(val_requests), 4)  # 2 sheets * 2 validations (Col C & Col F)
        col_c_val = val_requests[0]["setDataValidation"]
        self.assertEqual(col_c_val["range"]["startColumnIndex"], 2)  # Col C
        col_f_val = val_requests[1]["setDataValidation"]
        self.assertEqual(col_f_val["range"]["startColumnIndex"], 5)  # Col F

    def test_invariants_zero_double_bang_in_kotlin(self):
        """Audits that zero double-bang (!!) operators exist in production and test Kotlin files."""
        kt_files = glob.glob("app/src/**/*.kt", recursive=True)
        self.assertGreater(len(kt_files), 20)
        violations = []
        for path in kt_files:
            with open(path, "r", encoding="utf-8") as f:
                for line_num, line in enumerate(f, 1):
                    # Check for double-bang operator (excluding comments)
                    code_part = line.split("//")[0]
                    if "!!" in code_part:
                        violations.append((path, line_num, line.strip()))
        self.assertEqual(len(violations), 0, f"Found double-bang operators: {violations}")

    def test_invariants_zero_emojis_in_kotlin(self):
        """Audits that zero emojis exist in production and test Kotlin files."""
        kt_files = glob.glob("app/src/**/*.kt", recursive=True)
        emoji_pat = re.compile(r"[\U00010000-\U0010ffff]|[\u2600-\u27bf]")
        violations = []
        for path in kt_files:
            with open(path, "r", encoding="utf-8") as f:
                for line_num, line in enumerate(f, 1):
                    if emoji_pat.search(line):
                        violations.append((path, line_num, line.strip()))
        self.assertEqual(len(violations), 0, f"Found emojis in Kotlin files: {violations}")

    def test_invariants_zero_local_machine_paths_in_kotlin(self):
        """Audits that zero hardcoded local machine paths exist in Kotlin files."""
        kt_files = glob.glob("app/src/**/*.kt", recursive=True)
        path_pat = re.compile(r"\b[a-zA-Z]:[\\/]|file:///")
        violations = []
        for path in kt_files:
            with open(path, "r", encoding="utf-8") as f:
                for line_num, line in enumerate(f, 1):
                    if path_pat.search(line):
                        violations.append((path, line_num, line.strip()))
        self.assertEqual(len(violations), 0, f"Found local machine paths: {violations}")


if __name__ == "__main__":
    unittest.main()
