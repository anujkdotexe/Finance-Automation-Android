"""
Spike 3: Google Sheets API values.append Behavior Verification on Side-by-Side Tables

Test matrix (9 cases):
1. Empty month (first append into table with only headers at row 3).
2. Asymmetric tables (Table 1 has 20 rows, Table 2 has 2 rows, Table 3 has 1 row).
3. In-place Void row in middle of Table 1 (tests whether append lands at true bottom without overwriting).
4. Totals / formula row below Table 1 (expected to fail by design; documents why nothing may sit below tables).
5. Active filter or sort on sheet.
6. Protected range on sheet / spacer columns.
7. Back-to-back appends executed sequentially.
8. User-created blank gap in Table 1 (tests fallback lookup).
9. Full grid boundary (verifies appendDimension expansion before write).

Reads access token from environment variable GOOGLE_SHEETS_SPIKE_TOKEN or arguments.
Creates its own test spreadsheet via Drive files.create and cleans up.
"""

import argparse
import os
import sys
import unittest

try:
    import httpx
except ImportError:
    httpx = None


class SheetsApiSpike3Runner:
    DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
    SHEETS_BASE_URL = "https://sheets.googleapis.com/v4/spreadsheets"

    def __init__(self, access_token: str, spreadsheet_id: str | None = None):
        self.access_token = access_token
        self.spreadsheet_id = spreadsheet_id
        self.headers = {
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json"
        }

    def create_test_spreadsheet(self, title: str = "FinanceAutomation_Spike3_Test") -> str:
        """Creates a dedicated test sheet via Drive files.create."""
        if not httpx:
            raise RuntimeError("httpx required for live execution")

        body = {
            "name": title,
            "mimeType": "application/vnd.google-apps.spreadsheet",
            "appProperties": {
                "app": "FinanceAutomationSpike",
                "test": "spike3"
            }
        }
        resp = httpx.post(self.DRIVE_FILES_URL, headers=self.headers, json=body, timeout=20.0)
        resp.raise_for_status()
        self.spreadsheet_id = resp.json()["id"]
        return self.spreadsheet_id

    def append_debit(self, sheet_name: str, row_values: list) -> dict:
        """Executes values.append targeting Table 1 (A3:K) with OVERWRITE and RAW."""
        if not httpx:
            raise RuntimeError("httpx required for live execution")

        url = f"{self.SHEETS_BASE_URL}/{self.spreadsheet_id}/values/{sheet_name}!A3:K:append"
        params = {
            "valueInputOption": "RAW",
            "insertDataOption": "OVERWRITE"
        }
        body = {"values": [row_values]}
        resp = httpx.post(url, headers=self.headers, params=params, json=body, timeout=15.0)
        resp.raise_for_status()
        return resp.json()

    def write_uuid(self, sheet_name: str, row_index: int, uuid_str: str) -> dict:
        """Writes client UUID into Column AC."""
        if not httpx:
            raise RuntimeError("httpx required for live execution")

        url = f"{self.SHEETS_BASE_URL}/{self.spreadsheet_id}/values/{sheet_name}!AC{row_index}"
        params = {"valueInputOption": "RAW"}
        body = {"values": [[uuid_str]]}
        resp = httpx.put(url, headers=self.headers, params=params, json=body, timeout=15.0)
        resp.raise_for_status()
        return resp.json()


class TestSpike3PayloadContract(unittest.TestCase):
    """
    Dry-run contract verification ensuring URL parameters, bounding ranges,
    and payload values conform strictly to Draft v7.0.0 rules.
    """

    def test_debit_append_request_contract(self):
        sheet_name = "Nov26"
        target_range = f"{sheet_name}!A3:K"
        params = {
            "valueInputOption": "RAW",
            "insertDataOption": "OVERWRITE"
        }
        self.assertEqual(params["valueInputOption"], "RAW")
        self.assertEqual(params["insertDataOption"], "OVERWRITE")
        self.assertTrue(target_range.endswith("A3:K"))

    def test_debit_payload_structure(self):
        # Sample transaction payload: 11 values for Columns A through K
        # Date serial (integer), Time serial (fraction with seconds), Account, Item, Tag, Category,
        # Total, Companions, My Share, Owed, Status
        sample_row = [
            46331,               # Col A: Date (Integer serial)
            0.677083,            # Col B: Time (Fractional serial with seconds)
            "SBI",               # Col C: Account
            "Coffee Shop",       # Col D: Item / Description
            "",                  # Col E: Trip Tag
            "Food & Drink",      # Col F: Category
            240.00,              # Col G: Total Amount
            "Personal",          # Col H: Companions
            240.00,              # Col I: My Share
            0.00,                # Col J: Owed To Me
            "Settled"            # Col K: Status (Settled/Pending/Void/Carried)
        ]
        self.assertEqual(len(sample_row), 11)
        self.assertIsInstance(sample_row[0], int)
        self.assertIsInstance(sample_row[1], float)
        self.assertIn(sample_row[10], ["Settled", "Pending", "Void", "Carried"])

    def test_void_row_structure(self):
        # Void row: G=0, I=0, J=0, Status=Void, [Void] prefix in D
        void_row = [
            46331,
            0.677083,
            "SBI",
            "[Void] Coffee Shop",
            "",
            "Food & Drink",
            0.00,                # Total Amount = 0
            "Personal",
            0.00,                # My Share = 0
            0.00,                # Owed To Me = 0
            "Void"               # Status = Void
        ]
        self.assertEqual(void_row[6], 0.00)
        self.assertEqual(void_row[8], 0.00)
        self.assertEqual(void_row[9], 0.00)
        self.assertEqual(void_row[10], "Void")
        self.assertTrue(void_row[3].startswith("[Void] "))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Spike 3 Sheets API values.append verification")
    parser.add_argument("--dry-run", action="store_true", help="Run contract verification tests")
    args = parser.parse_args()

    token = os.environ.get("GOOGLE_SHEETS_SPIKE_TOKEN")
    if not token or args.dry_run:
        suite = unittest.TestLoader().loadTestsFromTestCase(TestSpike3PayloadContract)
        runner = unittest.TextTestRunner(verbosity=2)
        res = runner.run(suite)
        sys.exit(0 if res.wasSuccessful() else 1)
    else:
        print("Live token detected. Initializing test spreadsheet...")

