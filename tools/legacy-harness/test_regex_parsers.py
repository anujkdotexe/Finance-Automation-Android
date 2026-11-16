"""
Unit Tests for Notification Extraction & Synthetic Corpus

Extracts only:
1. Amount (numeric currency value)
2. Merchant ("paid to" / payee descriptor)
3. Source Package (e.g. com.google.android.apps.nbu.paisa.user)
4. Timestamp (synthetic date/time)

UTR has been completely purged per Spec v7 (A1).
Only synthetic samples are included per privacy agreement (Section 0.5).
"""

import re
import unittest


class RegexParsers:
    # 1. Amount: matches Rs., INR, or ₹ followed by currency digits
    AMOUNT_PATTERN = re.compile(
        r"(?:Rs\.?|INR|₹)\s*([\d,]+(?:\.\d{1,2})?)",
        re.IGNORECASE
    )

    # 2. Payee / Merchant extraction patterns:
    PAID_TO_PATTERN = re.compile(
        r"(?:paid\s+to|sent\s+to|transfer\s+to|to)\s+([A-Za-z0-9\s&.\-']+?)(?:\s+successful|\s+on|\s+upi|\.|$)",
        re.IGNORECASE
    )
    PAID_AT_PATTERN = re.compile(
        r"(?:at|vpa)\s+([A-Za-z0-9\s&.\-']+?)(?:\s+on|\s+for|\.|$)",
        re.IGNORECASE
    )

    @classmethod
    def extract_amount(cls, text: str) -> float | None:
        match = cls.AMOUNT_PATTERN.search(text)
        if match:
            raw_amt = match.group(1).replace(",", "")
            try:
                return float(raw_amt)
            except ValueError:
                return None
        return None

    @classmethod
    def extract_merchant(cls, text: str) -> str | None:
        match = cls.PAID_TO_PATTERN.search(text)
        if match:
            merchant = match.group(1).strip()
            if merchant.lower() not in ["your account", "vpa"]:
                return merchant

        match_at = cls.PAID_AT_PATTERN.search(text)
        if match_at:
            return match_at.group(1).strip()

        return None

    @classmethod
    def parse_payload(cls, text: str) -> tuple[float | None, str | None]:
        """Returns (amount, merchant) tuple."""
        return cls.extract_amount(text), cls.extract_merchant(text)


# Synthetic test corpus (strictly synthetic per Section 0.5)
SYNTHETIC_CORPUS = [
    {
        "source": "Google Pay",
        "pkg": "com.google.android.apps.nbu.paisa.user",
        "text": "Paid ₹240.00 to Chai Point on 05 Nov.",
        "expected_amount": 240.0,
        "expected_merchant": "Chai Point"
    },
    {
        "source": "PhonePe",
        "pkg": "com.phonepe.app",
        "text": "Payment of Rs. 1,250 to Fresh Vegetable Mart successful.",
        "expected_amount": 1250.0,
        "expected_merchant": "Fresh Vegetable Mart"
    },
    {
        "source": "Paytm",
        "pkg": "net.one97.paytm",
        "text": "Sent INR 85.00 to Sharma Bakery.",
        "expected_amount": 85.0,
        "expected_merchant": "Sharma Bakery"
    },
    {
        "source": "BHIM",
        "pkg": "in.org.npci.upiapp",
        "text": "Rs. 450.00 debited from A/c XX1234 to Anand Sweets.",
        "expected_amount": 450.0,
        "expected_merchant": "Anand Sweets"
    },
    {
        "source": "Bank Debit SMS (Synthetic)",
        "pkg": "com.google.android.apps.messaging",
        "text": "Bank Alert: Rs 350.00 debited from a/c **5678 on 05-11-26 to Food Express.",
        "expected_amount": 350.0,
        "expected_merchant": "Food Express"
    }
]


class TestSyntheticRegexExtraction(unittest.TestCase):

    def test_synthetic_corpus_extraction(self):
        for sample in SYNTHETIC_CORPUS:
            with self.subTest(source=sample["source"]):
                amt, merchant = RegexParsers.parse_payload(sample["text"])
                self.assertEqual(amt, sample["expected_amount"], f"Amount mismatch in {sample['source']}")
                self.assertEqual(merchant, sample["expected_merchant"], f"Merchant mismatch in {sample['source']}")


if __name__ == "__main__":
    unittest.main()

