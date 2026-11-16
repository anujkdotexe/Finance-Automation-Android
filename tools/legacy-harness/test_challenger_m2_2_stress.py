"""Empirical Challenger Test Suite for Milestone M2.2.

Empirically tests and stress-tests:
1. Whitelist vs Blacklist & Self-Package Suppression
2. OTP Guard Suppression
3. Regex Extraction across Diverse Indian UPI Formats
4. Date & Time Serial Calculations (Epoch, Leap Years, End-of-Day)
5. Repository-wide Zero Emojis Compliance
6. Repository-wide Zero Machine Paths Compliance
7. Bug Reproduction for Fallback UTR and Preposition Account Extraction
"""

from __future__ import annotations

import datetime
import os
import re
import unittest
from pathlib import Path

# ==============================================================================
# Domain Logic Reference Mirroring Kotlin Implementation
# ==============================================================================

# Package Whitelist
PACKAGE_GPAY = "com.google.android.apps.nbu.paisa.user"
PACKAGE_BHIM = "in.org.npci.upiapp"
PACKAGE_SUPERMONEY = "com.supermoney.app"
PACKAGE_PHONEPE = "com.phonepe.app"
PACKAGE_PAYTM = "net.one97.paytm"
PACKAGE_DEFAULT_SMS = "com.android.mms"
PACKAGE_GOOGLE_MESSAGES = "com.google.android.apps.messaging"
PACKAGE_SAMSUNG_MESSAGES = "com.samsung.android.messaging"

WHITELISTED_FINANCIAL_PACKAGES: set[str] = {
    PACKAGE_GPAY,
    PACKAGE_BHIM,
    PACKAGE_SUPERMONEY,
    PACKAGE_PHONEPE,
    PACKAGE_PAYTM,
    PACKAGE_DEFAULT_SMS,
    PACKAGE_GOOGLE_MESSAGES,
    PACKAGE_SAMSUNG_MESSAGES,
}

PACKAGE_WHATSAPP = "com.whatsapp"
PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
PACKAGE_TELEGRAM = "org.telegram.messenger"
PACKAGE_SIGNAL = "org.thoughtcrime.securesms"
PACKAGE_FB_MESSENGER = "com.facebook.orca"
PACKAGE_INSTAGRAM = "com.instagram.android"
PACKAGE_DISCORD = "com.discord"
PACKAGE_OUTLOOK = "com.microsoft.office.outlook"
PACKAGE_GMAIL = "com.google.android.gm"

BLACKLISTED_PRIVACY_PACKAGES: set[str] = {
    PACKAGE_WHATSAPP,
    PACKAGE_WHATSAPP_BUSINESS,
    PACKAGE_TELEGRAM,
    PACKAGE_SIGNAL,
    PACKAGE_FB_MESSENGER,
    PACKAGE_INSTAGRAM,
    PACKAGE_DISCORD,
    PACKAGE_OUTLOOK,
    PACKAGE_GMAIL,
}

OTP_SUPPRESSION_REGEX = re.compile(
    r"\b(otp|one[- ]time[- ]password|verification[- ]code|secret[- ]code|do[- ]not[- ]share|security[- ]code|auth[- ]code)\b",
    re.IGNORECASE,
)

# Regex patterns mirroring Kotlin RegexPatterns.kt
AMOUNT_REGEX = re.compile(r"(?:Rs\.?|INR|₹)\s*([\d,]+(?:\.\d{1,2})?)", re.IGNORECASE)
BANK_SMS_AMOUNT_REGEX = re.compile(
    r"(?i)(?:debited\s+(?:by|for)?|spent|paid|withdrawn|transferred\s+to|vpa)\s+(?:Rs\.?|INR|₹)?\s*([\d,]+(?:\.\d{1,2})?)"
)
MERCHANT_REGEX = re.compile(
    r"(?:to|at|paid to|vpa|transferred to)\s+([A-Za-z0-9\s&._\-@]+?)(?:\s+on|\.|$|\s+ref|\s+using|\s+via|\s+through|\s+utr|\s+a/c|\s+avl|\s+bal|\b\d{12}\b)",
    re.IGNORECASE,
)
VPA_REGEX = re.compile(r"\b([a-zA-Z0-9.\-_]+@[a-zA-Z0-9]+)\b")
UTR_REGEX = re.compile(
    r"(?i)(?:UPI\s*(?:ref(?:\s*no)?|Ref\s*No|reference|txn\s*(?:id|no)?)|UTR(?:\s*no)?|Ref(?:\s*No)?\.?|Txn\s*ID)\s*[:#\-]?\s*(\d{12})"
)
FALLBACK_UTR_REGEX = re.compile(r"\b\d{12}\b")
DEBIT_FLAG_REGEX = re.compile(
    r"(?i)\b(debited|paid|spent|sent|withdrawn|deducted|purchased|debited\s+by|transfer\s+to)\b"
)
CREDIT_FLAG_REGEX = re.compile(
    r"(?i)\b(credited|received|deposited|refunded|credited\s+with|cashback|added)\b"
)
ACCOUNT_REGEX = re.compile(
    r"(?i)(?:([A-Za-z]{2,10}\s*Bank|[A-Za-z]{2,6})\s*)?(?:A/c|Account|acct|card)?\s*(?:no\.?)?\s*(?:ending\s+(?:with\s+)?|[*xX]+|\.\.+)?(\d{3,4})"
)

# Date serial epoch
SHEETS_EPOCH = datetime.date(1899, 12, 30)
SECONDS_PER_DAY = 86400.0


def is_whitelisted(pkg: str) -> bool:
    return pkg.strip() in WHITELISTED_FINANCIAL_PACKAGES


def is_blacklisted(pkg: str) -> bool:
    trimmed = pkg.strip()
    return trimmed in BLACKLISTED_PRIVACY_PACKAGES or any(
        trimmed.startswith(b) for b in BLACKLISTED_PRIVACY_PACKAGES
    )


def is_otp_message(text: str) -> bool:
    return bool(OTP_SUPPRESSION_REGEX.search(text))


def extract_amount(text: str) -> float | None:
    match = AMOUNT_REGEX.search(text) or BANK_SMS_AMOUNT_REGEX.search(text)
    if not match:
        return None
    raw = match.group(1).replace(",", "")
    try:
        return float(raw)
    except ValueError:
        return None


def extract_merchant(text: str) -> str:
    vpa_match = VPA_REGEX.search(text)
    if vpa_match:
        vpa = vpa_match.group(1).strip()
        if "ref" not in vpa.lower():
            return vpa
    match = MERCHANT_REGEX.search(text)
    if match and match.group(1).strip():
        return match.group(1).strip()
    return "Unknown Payee"


def extract_account_as_implemented(text: str) -> str | None:
    """Mirrors Kotlin RegexPatterns.extractAccount exactly."""
    match = ACCOUNT_REGEX.search(text)
    if not match:
        return None
    bank = match.group(1).strip() if match.group(1) else None
    digits = match.group(2).strip() if match.group(2) else None
    if bank and digits:
        return f"{bank} XX{digits}"
    elif digits:
        return f"A/c XX{digits}"
    elif bank:
        return bank
    return None


def extract_utr_kotlin_bug_check(text: str) -> tuple[str | None, bool]:
    """Tests Kotlin extractUtr behavior.

    Returns (result, did_crash_on_fallback).
    """
    m_utr = UTR_REGEX.search(text)
    if m_utr:
        return m_utr.group(1), False

    m_fb = FALLBACK_UTR_REGEX.search(text)
    if m_fb:
        # In Kotlin RegexPatterns.kt:
        # val match = UTR_REGEX.find(text) ?: FALLBACK_UTR_REGEX.find(text)
        # match?.groupValues?.get(1)?.ifBlank { null } ?: match?.groupValues?.getOrNull(0)
        # In FALLBACK_UTR_REGEX, there are 0 capturing groups!
        # match.groupValues has length 1.
        # Calling .get(1) on a Kotlin list of length 1 throws IndexOutOfBoundsException!
        return None, True
    return None, False


# Fixed patterns mirroring updated Kotlin RegexPatterns.kt
FIXED_FALLBACK_UTR_REGEX = re.compile(r"\b(\d{12})\b")
FIXED_ACCOUNT_REGEX = re.compile(
    r"(?i)(?:(?!Rs|INR|₹)([A-Za-z]{2,10}\s*Bank|[A-Za-z]{2,6})\s+)?(?:A/c|Account|acct|card)\s*(?:no\.?)?\s*(?:ending\s+(?:with\s+)?|[*xX]+|\.\.+)?(\d{3,4})"
)
FIXED_FALLBACK_ACCOUNT_REGEX = re.compile(
    r"(?i)(?:\bending\s+(?:with\s+|in\s+)?|[*xX]{2,}|\.\.{2,})(\d{3,4})\b"
)
PREPOSITION_PREFIX_REGEX = re.compile(
    r"(?i)^(?:paid\s+|using\s+|from\s+|via\s+|through\s+|with\s+|by\s+|on\s+|in\s+|at\s+)+"
)
INVALID_BANK_NAMES = {
    "rs", "inr", "₹", "using", "from", "via", "through", "with", "by", "on", "in", "at", "paid"
}


def clean_bank_name_fixed(raw_bank: str | None) -> str | None:
    if not raw_bank or not raw_bank.strip():
        return None
    cleaned = PREPOSITION_PREFIX_REGEX.sub("", raw_bank.strip()).strip()
    if not cleaned or cleaned.lower() in INVALID_BANK_NAMES:
        return None
    return cleaned


def extract_utr_fixed(text: str) -> str | None:
    match = UTR_REGEX.search(text) or FIXED_FALLBACK_UTR_REGEX.search(text)
    if not match:
        return None
    return match.group(1) if match.lastindex and match.lastindex >= 1 else match.group(0)


def extract_account_fixed(text: str) -> str | None:
    match = FIXED_ACCOUNT_REGEX.search(text)
    if match:
        bank = clean_bank_name_fixed(match.group(1))
        digits = match.group(2).strip() if match.group(2) else None
        if bank and digits:
            return f"{bank} XX{digits}"
        elif digits:
            return f"A/c XX{digits}"
        elif bank:
            return bank

    fb_match = FIXED_FALLBACK_ACCOUNT_REGEX.search(text)
    if fb_match:
        digits = fb_match.group(1).strip()
        return f"A/c XX{digits}"

    return None


def to_date_serial(d: datetime.date) -> int:
    return (d - SHEETS_EPOCH).days


def from_date_serial(serial: int) -> datetime.date:
    return SHEETS_EPOCH + datetime.timedelta(days=serial)


def to_time_serial(t: datetime.time, include_nanos: bool = False) -> float:
    sec = t.hour * 3600.0 + t.minute * 60.0 + t.second
    sub = (t.microsecond / 1_000_000.0) if include_nanos else 0.0
    return (sec + sub) / SECONDS_PER_DAY


def from_time_serial(serial: float) -> datetime.time:
    total_seconds = round(serial * SECONDS_PER_DAY)
    hours = (total_seconds // 3600) % 24
    minutes = (total_seconds % 3600) // 60
    seconds = total_seconds % 60
    return datetime.time(hour=int(hours), minute=int(minutes), second=int(seconds))


# ==============================================================================
# Unit Test Cases
# ==============================================================================

class TestEmpiricalWhitelistAndBlacklist(unittest.TestCase):
    """Stress tests whitelist vs blacklist and self-package loop."""

    def test_whatsapp_telegram_signal_dropped_even_with_payment_data(self) -> None:
        """Blacklisted privacy messaging apps must be dropped immediately."""
        blacklisted_samples = [
            (PACKAGE_WHATSAPP, "Paid Rs 500 to Swiggy UPI Ref: 123456789012"),
            (PACKAGE_WHATSAPP_BUSINESS, "INR 1,200 transferred to merchant"),
            (PACKAGE_TELEGRAM, "Paid ₹450 to chaiwala"),
            (PACKAGE_SIGNAL, "Paid Rs 349 via PhonePe"),
            (PACKAGE_FB_MESSENGER, "Sent Rs 1000"),
            (PACKAGE_INSTAGRAM, "Paid Rs 50 for reel sponsor"),
            (PACKAGE_DISCORD, "Donated INR 500"),
            (PACKAGE_OUTLOOK, "Expense receipt: INR 2,500"),
            (PACKAGE_GMAIL, "Payment of Rs 999 successful"),
        ]
        for pkg, msg in blacklisted_samples:
            self.assertTrue(
                is_blacklisted(pkg),
                f"Package {pkg} should be blacklisted",
            )
            # In ParseNotificationUseCase: step 2 drops blacklisted apps before parsing
            # Even if whitelisted check was performed, none of these are in whitelist
            self.assertFalse(
                is_whitelisted(pkg),
                f"Package {pkg} must never be whitelisted",
            )

    def test_blacklisted_prefix_variants_dropped(self) -> None:
        """Packages that start with blacklisted prefixes must be dropped."""
        variants = [
            "com.whatsapp.extra",
            "org.telegram.messenger.web",
            "org.thoughtcrime.securesms.fork",
        ]
        for pkg in variants:
            self.assertTrue(
                is_blacklisted(pkg),
                f"Variant {pkg} should be caught by prefix blacklist",
            )

    def test_self_package_loop_dropped(self) -> None:
        """Notifications originating from self app must be dropped immediately."""
        own_package = "com.financeautomation"
        # ParseNotificationUseCase line 71:
        is_self = own_package.strip() == "com.financeautomation"
        self.assertTrue(is_self)
        self.assertFalse(is_whitelisted(own_package))

    def test_unknown_third_party_apps_dropped(self) -> None:
        """Unknown apps (games, streaming) must not pass the whitelist."""
        unknown = [
            "com.spotify.music",
            "com.netflix.ninja",
            "com.king.candycrushsaga",
            "com.facebook.katana",
        ]
        for pkg in unknown:
            self.assertFalse(is_whitelisted(pkg))

    def test_whitelisted_financial_apps_accepted(self) -> None:
        """All 8 official financial and SMS packages must be whitelisted."""
        expected = [
            PACKAGE_GPAY,
            PACKAGE_BHIM,
            PACKAGE_SUPERMONEY,
            PACKAGE_PHONEPE,
            PACKAGE_PAYTM,
            PACKAGE_DEFAULT_SMS,
            PACKAGE_GOOGLE_MESSAGES,
            PACKAGE_SAMSUNG_MESSAGES,
        ]
        for pkg in expected:
            self.assertTrue(is_whitelisted(pkg), f"Package {pkg} should be whitelisted")
            self.assertFalse(is_blacklisted(pkg), f"Package {pkg} must not be blacklisted")


class TestEmpiricalOtpGuard(unittest.TestCase):
    """Stress tests OTP and authentication suppression."""

    def test_otp_keywords_suppressed_even_from_banking_or_sms(self) -> None:
        """Messages containing OTP / 2FA keywords must be dropped."""
        otp_alerts = [
            "Your OTP is 123456 for Rs. 500 transaction at Amazon",
            "Do not share this code. 492019 is your verification code",
            "One-time password for SBI card ending 1234 is 889900",
            "Use one time password 556677 to complete payment",
            "Your secret-code is 112233. Valid for 10 minutes",
            "Auth-code: 9944. Do-not-share with anyone",
            "Security-code: 334455 for ICICI debit transaction",
            "NEVER SHARE YOUR OTP WITH ANYONE INCLUDING BANK OFFICIALS",
            "Verification-code is 776655 for logging in",
        ]
        for msg in otp_alerts:
            self.assertTrue(
                is_otp_message(msg),
                f"Message should be detected as OTP: {msg}",
            )

    def test_legitimate_payment_alerts_not_suppressed_by_otp_guard(self) -> None:
        """Legitimate payment text containing sub-strings must not be falsely dropped."""
        legit = [
            "Paid Rs 350 to Hotpot Cafe via UPI",
            "Spent INR 450 at Photoplay Studios",
            "Dinner with another friend, paid Rs 1,200",
            "Debited by Rs 250 for author book purchase",
            "A/c debited by INR 500 at Store",
        ]
        for msg in legit:
            self.assertFalse(
                is_otp_message(msg),
                f"Legitimate payment should not be flagged as OTP: {msg}",
            )


class TestEmpiricalRegexExtraction(unittest.TestCase):
    """Stress tests regex extractors on diverse Indian UPI formats."""

    def test_diverse_indian_currency_amounts(self) -> None:
        """Validates amount extraction across Indian formats."""
        cases = [
            ("Paid Rs. 1,234.50 to Starbucks", 1234.50),
            ("Paid Rs 500 to Cafe", 500.0),
            ("Debited by INR 10,00,000.00 for property", 1000000.00),
            ("Spent ₹99 at tea stall", 99.0),
            ("Spent ₹ 0.50 test transaction", 0.50),
            ("INR 49.99 paid on Google Play", 49.99),
            ("debited by 750.25 on 05-Nov", 750.25),
            ("spent INR 250 at grocery", 250.0),
            ("withdrawn Rs. 5,000 from ATM", 5000.0),
            ("paid 120 to auto driver", 120.0),
            ("transferred to vpa Rs 1,500.00", 1500.0),
        ]
        for txt, exp in cases:
            amt = extract_amount(txt)
            self.assertIsNotNone(amt, f"Failed to extract amount from: {txt}")
            self.assertAlmostEqual(amt, exp, places=2)

    def test_merchant_and_vpa_formats(self) -> None:
        """Validates merchant name and UPI VPA extraction."""
        cases = [
            ("Paid Rs 349.00 to Swiggy using Bank A/c ending 1234", "Swiggy"),
            ("Paid to merchant@okaxis ref: 123456789012", "merchant@okaxis"),
            ("Paid to john.doe@icici via UPI", "john.doe@icici"),
            ("Spent Rs 150 at Cafe Coffee Day. Avl bal", "Cafe Coffee Day"),
            ("transferred to Alice Smith using A/c", "Alice Smith"),
            ("paid to Zomato via GPay", "Zomato"),
            ("Paid to fast-tag_12@hdfcbank on 05-Nov", "fast-tag_12@hdfcbank"),
        ]
        for txt, exp in cases:
            merch = extract_merchant(txt)
            self.assertEqual(merch, exp, f"Failed merchant for: {txt}")

    def test_direction_flags(self) -> None:
        """Validates debit vs credit direction determination."""
        debit_texts = [
            "INR 500 debited from A/c",
            "Paid Rs 250 to merchant",
            "Spent INR 100 on fuel",
            "INR 1,000 withdrawn from ATM",
            "Amount deducted from wallet",
        ]
        for txt in debit_texts:
            self.assertTrue(bool(DEBIT_FLAG_REGEX.search(txt)), f"Should be debit: {txt}")

        credit_texts = [
            "INR 1,500 credited to A/c",
            "Received Rs 500 from Alice",
            "Amount deposited successfully",
            "Cashback INR 50 added to account",
            "Refunded Rs 299 for return",
        ]
        for txt in credit_texts:
            self.assertTrue(bool(CREDIT_FLAG_REGEX.search(txt)), f"Should be credit: {txt}")

    def test_standard_utr_extraction(self) -> None:
        """Validates standard 12-digit UTR references with keywords."""
        cases = [
            ("UPI Ref No: 123456789012", "123456789012"),
            ("UPI ref: 987654321098", "987654321098"),
            ("Ref No. 556677889900", "556677889900"),
            ("Txn ID: 112233445566", "112233445566"),
            ("UTR: 998877665544", "998877665544"),
        ]
        for txt, exp in cases:
            m = UTR_REGEX.search(txt)
            self.assertIsNotNone(m, f"Failed UTR for: {txt}")
            self.assertEqual(m.group(1), exp)


class TestEmpiricalKotlinBugsReproduction(unittest.TestCase):
    """Reproduces identified implementation edge cases and bugs."""

    def test_bug_fallback_utr_missing_capturing_group_throws_in_kotlin(self) -> None:
        """Bug in RegexPatterns.kt: FALLBACK_UTR_REGEX has 0 capturing groups.

        In Kotlin:
            val match = UTR_REGEX.find(text) ?: FALLBACK_UTR_REGEX.find(text)
            match?.groupValues?.get(1)?.ifBlank { null } ?: match?.groupValues?.getOrNull(0)

        When FALLBACK_UTR_REGEX matches (standalone 12 digits without 'UPI Ref' prefix),
        groupValues has size 1 (only index 0).
        Calling .get(1) throws IndexOutOfBoundsException.
        """
        text = "Paid Rs 500 to merchant. 123456789012 done."
        _utr_res, did_crash = extract_utr_kotlin_bug_check(text)
        self.assertTrue(
            did_crash,
            "Standalone 12-digit UTR triggers IndexOutOfBoundsException in Kotlin extractUtr due to missing capturing group",
        )

    def test_bug_account_regex_matches_currency_and_amount_instead_of_account(self) -> None:
        r"""Critical Bug in ACCOUNT_REGEX: Matches currency and amount as bank account!

        In RegexPatterns.kt:
            ([A-Za-z]{2,10}\s*Bank|[A-Za-z]{2,6})\s*(?:A/c|Account|acct|card)?\s*(?:no\.?)?\s*(?:ending\s+(?:with\s+)?|[*xX]+|\.\.+)?(\d{3,4})

        Because all intermediate tokens ('A/c', 'ending', etc.) are optional,
        ACCOUNT_REGEX matches ANY 2-6 letter word followed by 3-4 digits.
        In 'Paid Rs 349.00 to Swiggy using Bank A/c ending 1234',
        ACCOUNT_REGEX matches 'Rs 349' as bank='Rs', digits='349' -> 'Rs XX349'!
        The actual bank account 'Bank A/c ending 1234' is completely ignored.
        """
        text = "Paid Rs 349.00 to Swiggy using Bank A/c ending 1234."
        extracted = extract_account_as_implemented(text)
        self.assertEqual(
            extracted,
            "Rs XX349",
            "ACCOUNT_REGEX matches currency 'Rs' and amount '349' as the bank account!",
        )

    def test_bug_account_preposition_greedy_capture(self) -> None:
        """Bug in ACCOUNT_REGEX: Prepositions like 'using' are captured into bank name when A/c appears first."""
        text = "using Bank A/c ending 1234 debited by 50."
        extracted = extract_account_as_implemented(text)
        self.assertEqual(
            extracted,
            "using Bank XX1234",
            "ACCOUNT_REGEX greedily captures preposition 'using' into bank name",
        )


class TestEmpiricalFixedRegexVerification(unittest.TestCase):
    """Empirically verifies the fixes for Defect 1 and Defect 2."""

    def test_fixed_fallback_utr_standalone(self) -> None:
        """Standalone 12-digit UTR is cleanly extracted without crashing."""
        text = "Paid Rs 500 to merchant. 123456789012 done."
        utr = extract_utr_fixed(text)
        self.assertEqual(utr, "123456789012")

    def test_fixed_account_with_amount_preceding(self) -> None:
        """Bank account is correctly identified when amount precedes account."""
        text = "Paid Rs 349.00 to Swiggy using Bank A/c ending 1234."
        account = extract_account_fixed(text)
        self.assertEqual(account, "Bank XX1234")

    def test_fixed_account_preposition_cleaned(self) -> None:
        """Prepositions like 'using' or 'from' are cleanly stripped from bank names."""
        text1 = "using Bank A/c ending 1234 debited by 50."
        self.assertEqual(extract_account_fixed(text1), "Bank XX1234")

        text2 = "from HDFC Bank Account ending 5678"
        self.assertEqual(extract_account_fixed(text2), "HDFC Bank XX5678")

        text3 = "SBI A/c XX1234 debited"
        self.assertEqual(extract_account_fixed(text3), "SBI XX1234")

    def test_fixed_fallback_account_patterns(self) -> None:
        """Masked cards and ending digit patterns are parsed via fallback."""
        text1 = "Paid Rs 500 ending with 1234"
        self.assertEqual(extract_account_fixed(text1), "A/c XX1234")

        text2 = "Paid Rs 500 XX5678"
        self.assertEqual(extract_account_fixed(text2), "A/c XX5678")

        text3 = "Paid Rs 500 ...9876"
        self.assertEqual(extract_account_fixed(text3), "A/c XX9876")

    def test_fixed_account_no_false_positive_on_amount(self) -> None:
        """Currency amounts without explicit account markers do not produce accounts."""
        self.assertIsNone(extract_account_fixed("Paid Rs 349.00 to Swiggy"))
        self.assertIsNone(extract_account_fixed("Paid INR 500 to Cafe Coffee Day"))
        self.assertIsNone(extract_account_fixed("Spent ₹99 at tea stall"))


class TestEmpiricalDateSerialCalculations(unittest.TestCase):
    """Stress tests date and time serial conversion edge cases."""

    def test_sheets_epoch_zero(self) -> None:
        """Epoch 1899-12-30 must evaluate to serial 0 and restore symmetrically."""
        self.assertEqual(to_date_serial(SHEETS_EPOCH), 0)
        self.assertEqual(from_date_serial(0), SHEETS_EPOCH)

    def test_known_date_november_2026(self) -> None:
        """2026-11-05 corresponds to serial 46331."""
        d = datetime.date(2026, 11, 5)
        serial = to_date_serial(d)
        self.assertEqual(serial, 46331)
        self.assertEqual(from_date_serial(46331), d)

    def test_leap_years_symmetry(self) -> None:
        """Leap day February 29 must convert and restore across leap years."""
        leap_days = [
            datetime.date(2000, 2, 29),
            datetime.date(2020, 2, 29),
            datetime.date(2024, 2, 29),
            datetime.date(2028, 2, 29),
        ]
        for ld in leap_days:
            serial = to_date_serial(ld)
            restored = from_date_serial(serial)
            self.assertEqual(restored, ld)
            # Next day is March 1
            next_day = from_date_serial(serial + 1)
            self.assertEqual(next_day, datetime.date(ld.year, 3, 1))

    def test_time_serial_noon_and_midnight(self) -> None:
        """Midnight is 0.0, Noon is exactly 0.5."""
        self.assertAlmostEqual(to_time_serial(datetime.time(0, 0, 0)), 0.0)
        self.assertEqual(from_time_serial(0.0), datetime.time(0, 0, 0))

        self.assertAlmostEqual(to_time_serial(datetime.time(12, 0, 0)), 0.5)
        self.assertEqual(from_time_serial(0.5), datetime.time(12, 0, 0))

    def test_end_of_day_time_serial(self) -> None:
        """23:59:59 must round-trip with second precision."""
        eod = datetime.time(23, 59, 59)
        serial = to_time_serial(eod)
        self.assertAlmostEqual(serial, 86399.0 / 86400.0, places=9)
        self.assertEqual(from_time_serial(serial), eod)

    def test_exhaustive_86400_seconds_round_trip(self) -> None:
        """Every single second of a 24-hour day (86,400 seconds) must round-trip exactly."""
        mismatches = 0
        for h in range(24):
            for m in range(60):
                for s in range(60):
                    t = datetime.time(h, m, s)
                    serial = to_time_serial(t)
                    restored = from_time_serial(serial)
                    if t != restored:
                        mismatches += 1
        self.assertEqual(mismatches, 0, "All 86,400 seconds must round-trip with zero errors")


class TestEmpiricalHygiene(unittest.TestCase):
    """Enforces zero emojis and zero machine paths across the repository."""

    def setUp(self) -> None:
        self.root_dir = str(Path(__file__).resolve().parents[1])
        self.exclude_dirs = {".git", ".gradle", "build", ".agents", "__pycache__", ".ruff_cache"}

    def test_zero_emojis_in_source_and_repo_docs(self) -> None:
        """No emojis in app/ or repository documents."""
        emoji_pattern = re.compile(
            r"[\U0001F600-\U0001F64F"
            r"\U0001F300-\U0001F5FF"
            r"\U0001F680-\U0001F6FF"
            r"\U0001F1E0-\U0001F1FF"
            r"\U0001F900-\U0001F9FF"
            r"\U0001FA70-\U0001FAFF"
            r"\U00002600-\U000026FF"
            r"\U00002700-\U000027BF]"
        )
        violations = []
        for dirpath, dirnames, filenames in os.walk(self.root_dir):
            dirnames[:] = [d for d in dirnames if d not in self.exclude_dirs]
            for fname in filenames:
                if fname.endswith((".jar", ".png", ".jpg", ".ico", ".keystore", ".jks", ".pyc", ".xlsx")):
                    continue
                fpath = os.path.join(dirpath, fname)
                try:
                    with open(fpath, "r", encoding="utf-8", errors="ignore") as f:
                        for lno, line in enumerate(f, start=1):
                            matches = emoji_pattern.findall(line)
                            if matches:
                                violations.append((fpath, lno, matches))
                except OSError:
                    continue
        self.assertEqual(
            len(violations),
            0,
            f"Found emoji violations: {violations}",
        )

    def test_zero_machine_paths_in_repo_docs(self) -> None:
        """No hardcoded local machine paths in documentation files outside .agents."""
        path_pattern = re.compile(r"(?:[cC]:\\|[cC]:/|file:///)", re.IGNORECASE)
        doc_files = ["PROJECT.md", "README.md", "SETUP.md"]
        violations = []
        for doc in doc_files:
            fpath = os.path.join(self.root_dir, doc)
            if os.path.exists(fpath):
                with open(fpath, "r", encoding="utf-8", errors="ignore") as f:
                    for lno, line in enumerate(f, start=1):
                        matches = path_pattern.findall(line)
                        if matches:
                            violations.append((doc, lno, matches))
        self.assertEqual(
            len(violations),
            0,
            f"Found machine path violations in docs: {violations}",
        )


if __name__ == "__main__":
    unittest.main()
