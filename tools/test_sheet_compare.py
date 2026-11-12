"""
Checks for tools/sheet-compare. Run with:  python -m unittest tools/test_sheet_compare.py
"""
import importlib.machinery
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest

import openpyxl

TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(TOOLS_DIR, "sheet-compare")
REFERENCE = os.path.join(os.path.dirname(TOOLS_DIR), "docs", "ios_reference", "Finance2026_Template.xlsx")


def load_tool():
    loader = importlib.machinery.SourceFileLoader("sheet_compare", SCRIPT)
    spec = importlib.util.spec_from_loader("sheet_compare", loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


class SheetCompareTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tool = load_tool()
        cls.tmp = tempfile.TemporaryDirectory()

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def altered_copy(self, name, mutate):
        wb = openpyxl.load_workbook(REFERENCE)
        mutate(wb)
        path = os.path.join(self.tmp.name, name)
        wb.save(path)
        return path

    def test_identical_copy_has_no_differences(self):
        path = self.altered_copy("same.xlsx", lambda wb: None)
        self.assertEqual([], self.tool.compare_workbooks(path, REFERENCE))

    def test_changed_font_size_is_reported(self):
        def mutate(wb):
            month = self.tool.first_month_tab(wb)
            cell = wb[month]["A3"]
            import copy
            font = copy.copy(cell.font)
            font.sz = (cell.font.sz or 10) + 4
            cell.font = font

        path = self.altered_copy("font.xlsx", mutate)
        diffs = self.tool.compare_workbooks(path, REFERENCE)
        self.assertTrue(any("A3 style size" in d for d in diffs), diffs)

    def test_changed_column_width_is_reported(self):
        def mutate(wb):
            month = self.tool.first_month_tab(wb)
            dim = wb[month].column_dimensions["B"]
            dim.width = (dim.width or 10) + 10

        path = self.altered_copy("width.xlsx", mutate)
        diffs = self.tool.compare_workbooks(path, REFERENCE)
        self.assertTrue(any("column B width" in d for d in diffs), diffs)

    def test_width_within_two_pixels_is_equal(self):
        def mutate(wb):
            month = self.tool.first_month_tab(wb)
            dim = wb[month].column_dimensions["B"]
            dim.width = (dim.width or 10) + 0.2

        path = self.altered_copy("close.xlsx", mutate)
        self.assertEqual([], self.tool.compare_workbooks(path, REFERENCE))

    def test_wrong_config_header_is_reported(self):
        def mutate(wb):
            ws = wb.create_sheet("Config")
            for i, h in enumerate(["AccountName", "Key"], start=1):
                ws.cell(row=1, column=i, value=h)

        path = self.altered_copy("config.xlsx", mutate)
        diffs = self.tool.compare_workbooks(path, REFERENCE)
        self.assertTrue(any(d.startswith("Config header col 1") for d in diffs), diffs)

    def test_dummyfunction_wrapper_is_normalized(self):
        wrapped = (
            '''=IFERROR(__xludf.DUMMYFUNCTION("IFERROR(QUERY(INDIRECT(""'"" & TEXT(TODAY(), ""mmm"") & ''' +
            '''RIGHT(YEAR(TODAY()), 2) & ""'!A4:K""), ""SELECT Col"&"1"", 0), """")"),"")'''
        )
        plain = (
            '''=IFERROR(QUERY(INDIRECT("'" & TEXT(TODAY(), "mmm") & RIGHT(YEAR(TODAY()), 2) & "'!A4:K"), ''' +
            '''"SELECT Col1", 0), "")'''
        )
        self.assertEqual(plain, self.tool.normalize_formula(wrapped))
        self.assertEqual(plain, self.tool.normalize_formula(plain))

    def test_grouped_column_widths_are_expanded(self):
        wb = openpyxl.load_workbook(REFERENCE)
        widths = self.tool.column_widths(wb["Dashboard"])
        # In the reference columns A to D share one dimension, so B must have the A width
        self.assertEqual(widths[1], widths[2])
        self.assertIn(13, widths)

    def test_differences_carry_a_category_for_the_summary(self):
        def mutate(wb):
            month = self.tool.first_month_tab(wb)
            wb[month].column_dimensions["B"].width = 40

        path = self.altered_copy("category.xlsx", mutate)
        diffs = self.tool.compare_workbooks(path, REFERENCE)
        self.assertTrue(diffs)
        self.assertIn("widths", {d.category for d in diffs})

    def test_summary_lists_every_category(self):
        path = self.altered_copy("summary.xlsx", lambda wb: None)
        result = subprocess.run([sys.executable, SCRIPT, path], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        for category in ("widths", "heights", "fonts", "borders", "number formats", "formulas", "validations"):
            self.assertIn(category, result.stdout)

    def test_no_argument_prints_usage_and_exits_2(self):
        result = subprocess.run([sys.executable, SCRIPT], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("required", result.stderr)

    def test_self_test_prints_notice_and_passes(self):
        result = subprocess.run([sys.executable, SCRIPT, "--self-test"], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("proves nothing about the app", result.stdout)


if __name__ == "__main__":
    unittest.main()
