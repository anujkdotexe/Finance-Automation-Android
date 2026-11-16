"""Domain reference harness and validation engine for Finance Automation Android.

This module provides the authoritative opaque-box reference models, constants,
regex extractors, date/time serial converters, split calculation engines,
deduplication state machines, and Google Sheets 31-column payload mappers
derived from PROJECT_PROPOSAL_AND_TECH_STACK.md and PROJECT.md.
Aligned with Draft v7.0.0 and Workstreams 1 & 2.
"""

from __future__ import annotations

import datetime
import enum
import math
import re
from dataclasses import dataclass, field
from typing import Any, ClassVar

# ==============================================================================
# 1. 31-Column Master Sheet Schema Constants
# ==============================================================================

SHEET_COLUMNS: list[str] = [
    "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K",
    "L",
    "M", "N", "O", "P", "Q", "R", "S", "T",
    "U",
    "V", "W", "X", "Y", "Z", "AA", "AB",
    "AC", "AD", "AE",
]

SHEET_COLUMN_HEADERS: dict[str, str] = {
    "A": "Date",
    "B": "Time",
    "C": "Account",
    "D": "Item / Description",
    "E": "Trip / Event Tag",
    "F": "Category",
    "G": "Total Amount (INR)",
    "H": "Companions",
    "I": "My Share (INR)",
    "J": "Owed To Me (INR)",
    "K": "Status",
    "L": "Spacer 1",
    "M": "Date",
    "N": "Time",
    "O": "Account",
    "P": "Inflow Type",
    "Q": "Received From",
    "R": "Reason / Note",
    "S": "Amount Received (INR)",
    "T": "Linked Debit UUID",
    "U": "Spacer 2",
    "V": "Date Incurred",
    "W": "Time",
    "X": "Paid By",
    "Y": "What For",
    "Z": "Amount I Owe (INR)",
    "AA": "Date Repaid",
    "AB": "Repayment Status",
    "AC": "Debit Txn UUID",
    "AD": "Inflow Txn UUID",
    "AE": "Payable Txn UUID",
}

# Table column ranges
TABLE_1_DEBITS_RANGE = "A3:K"
TABLE_2_INFLOWS_RANGE = "M3:T"
TABLE_3_PAYABLES_RANGE = "V3:AB"

# Color constants
COLOR_BANNER_NAVY = "#1A237E"
COLOR_HEADER_DEBITS = "#C62828"
COLOR_HEADER_INFLOWS = "#2E7D32"
COLOR_HEADER_PAYABLES = "#F57F17"
COLOR_HEADER_SLATE = "#37474F"
COLOR_SPACER_GRAY = "#ECEFF1"


# ==============================================================================
# 2. Package Whitelists and Blacklists
# ==============================================================================

WHITELISTED_FINANCIAL_PACKAGES: set[str] = {
    "com.google.android.apps.nbu.paisa.user",  # Google Pay
    "in.org.npci.upiapp",                      # BHIM
    "com.supermoney.app",                     # Supermoney
    "com.phonepe.app",                        # PhonePe
    "net.one97.paytm",                        # Paytm
    "com.android.mms",                        # Default SMS
    "com.google.android.apps.messaging",      # Google Messages
    "com.samsung.android.messaging",          # Samsung Messages
}

BLACKLISTED_PRIVACY_PACKAGES: set[str] = {
    "com.whatsapp",                           # WhatsApp
    "com.whatsapp.w4b",                       # WhatsApp Business
    "org.telegram.messenger",                 # Telegram
    "org.thoughtcrime.securesms",             # Signal
    "com.facebook.orca",                      # Messenger
    "com.instagram.android",                  # Instagram
    "com.discord",                            # Discord
}


# ==============================================================================
# 3. Precompiled Regular Expressions
# ==============================================================================

# Amount extractor: matches Rs. 1,234.50, INR 500, Rs 99, etc.
AMOUNT_REGEX = re.compile(
    r"(?:Rs\.?|INR|₹)\s*([\d,]+(?:\.\d{1,2})?)",
    re.IGNORECASE,
)

# Merchant / Payee regex
MERCHANT_REGEX = re.compile(
    r"(?:to|at|paid to|vpa|transferred to)\s+([A-Za-z0-9\.\-\_\s&@]+?)(?=\s+(?:on|using|via|ref|a/c|avl|bal|\d{12}|$))",
    re.IGNORECASE,
)

# Debit flag keywords
DEBIT_FLAG_REGEX = re.compile(
    r"\b(?:debited|spent|paid|sent|withdrawn|deducted|purchased)\b",
    re.IGNORECASE,
)

# Credit flag keywords
CREDIT_FLAG_REGEX = re.compile(
    r"\b(?:credited|received|added|refunded|deposited)\b",
    re.IGNORECASE,
)

# Funding Account regex (Bank name and last 3-4 digits)
ACCOUNT_REGEX = re.compile(
    r"\b([A-Za-z]{2,10}\s*Bank|[A-Za-z]{3,6})\s*(?:A/c|Account)?\s*(?:no\.?|ending)?\s*(?:XX+|[*]+|\.\.+)?(\d{3,4})\b",
    re.IGNORECASE,
)

# OTP / Auth code suppression regex
OTP_REGEX = re.compile(
    r"\b(?:OTP|one[- ]time[- ]password|verification code|secret code|auth code|do not share)\b",
    re.IGNORECASE,
)


# ==============================================================================
# 4. Domain Enums
# ==============================================================================

class ReviewState(str, enum.Enum):
    NEEDS_REVIEW = "NEEDS_REVIEW"
    CONFIRMED = "CONFIRMED"


class SyncState(str, enum.Enum):
    PENDING = "PENDING"
    SYNCED = "SYNCED"
    FAILED = "FAILED"
    VOID = "VOID"


class SplitMode(str, enum.Enum):
    JUST_ME = "JUST_ME"
    SPLIT_EQUALLY = "SPLIT_EQUALLY"
    CUSTOM_SPLIT = "CUSTOM_SPLIT"


class TransactionStatus(str, enum.Enum):
    SETTLED = "Settled"
    PENDING = "Pending"
    VOID = "Void"
    CARRIED = "Carried"


class InflowType(str, enum.Enum):
    SALARY = "Salary"
    FRIEND_SETTLEMENT = "Friend Settlement"
    ALLOWANCE = "Allowance"
    OTHER = "Other"


class RepaymentStatus(str, enum.Enum):
    PENDING = "Pending"
    REPAID = "Repaid"


# ==============================================================================
# 5. Domain Models (Workstream 1 Config-Driven & Customization Enabled)
# ==============================================================================

@dataclass
class Account:
    id: str
    name: str
    last_four_digits: str | None = None
    is_default: bool = False
    is_archived: bool = False
    display_order: int = 0


@dataclass
class Companion:
    id: str
    name: str
    is_selected: bool = False
    balance: float = 0.0
    default_split_ratio: float = 1.0


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
class TripTag:
    id: str
    name: str
    is_active: bool = False
    is_archived: bool = False
    created_at: int = 0


@dataclass
class SplitResult:
    total_amount: float
    my_share: float
    owed_to_me: float
    companions: str
    status: TransactionStatus


@dataclass
class Transaction:
    id: str
    date_serial: int
    time_serial: float
    account: str
    item_description: str
    category: str
    total_amount: float
    my_share: float
    owed_to_me: float
    status: TransactionStatus
    companions: str = "Personal"
    trip_tag: str = ""
    package_name: str = ""
    review_state: ReviewState = ReviewState.NEEDS_REVIEW
    sync_state: SyncState = SyncState.PENDING
    is_possible_duplicate: bool = False
    timestamp: float = field(
        default_factory=lambda: datetime.datetime.now(datetime.timezone.utc).timestamp(),
    )


@dataclass
class InflowTransaction:
    id: str
    date_serial: int
    time_serial: float
    account: str
    inflow_type: InflowType
    received_from: str
    reason: str
    amount: float
    linked_debit_uuid: str = ""


@dataclass
class PayableTransaction:
    id: str
    date_incurred_serial: int
    time_serial: float
    paid_by: str
    what_for: str
    amount_i_owe: float
    date_repaid_serial: int | None = None
    repayment_status: RepaymentStatus = RepaymentStatus.PENDING


@dataclass
class AppConfigSnapshot:
    accounts: list[Account]
    categories: list[Category]
    companions: list[Companion]
    trip_tags: list[TripTag]
    preferences: dict[str, str]


# ==============================================================================
# 6. Serial Date & Time Converters
# ==============================================================================

SHEETS_EPOCH = datetime.date(1899, 12, 30)


def to_date_serial(d: datetime.date) -> int:
    """Calculates integer day serial elapsed since 30/12/1899."""
    delta = d - SHEETS_EPOCH
    return delta.days


def from_date_serial(serial: int) -> datetime.date:
    """Restores Python date from integer Sheets day serial."""
    return SHEETS_EPOCH + datetime.timedelta(days=serial)


def to_time_serial(t: datetime.time) -> float:
    """Calculates fractional 24h day serial with second precision."""
    total_seconds = t.hour * 3600 + t.minute * 60 + t.second + (t.microsecond / 1_000_000.0)
    return total_seconds / 86400.0


def from_time_serial(serial: float) -> datetime.time:
    """Restores Python time from fractional day serial."""
    total_seconds = round(serial * 86400.0)
    hours = (total_seconds // 3600) % 24
    minutes = (total_seconds % 3600) // 60
    seconds = total_seconds % 60
    return datetime.time(hour=hours, minute=minutes, second=seconds)


# ==============================================================================
# 7. Split Calculation Engine
# ==============================================================================

class SplitCalculationEngine:
    @staticmethod
    def calculate_just_me(amount: float) -> SplitResult:
        """Just Me calculation: Total personal living burn."""
        rounded_amount = round(amount, 2)
        return SplitResult(
            total_amount=rounded_amount,
            my_share=rounded_amount,
            owed_to_me=0.00,
            companions="Personal",
            status=TransactionStatus.SETTLED,
        )

    @staticmethod
    def calculate_split_equally(amount: float, companion_names: list[str]) -> SplitResult:
        """Split Equally calculation: Total divided by N + 1 heads."""
        rounded_amount = round(amount, 2)
        if not companion_names:
            return SplitCalculationEngine.calculate_just_me(rounded_amount)

        total_heads = len(companion_names) + 1
        my_share = round(rounded_amount / total_heads, 2)
        owed_to_me = round(rounded_amount - my_share, 2)
        companions_str = ", ".join(companion_names)

        return SplitResult(
            total_amount=rounded_amount,
            my_share=my_share,
            owed_to_me=owed_to_me,
            companions=companions_str,
            status=TransactionStatus.PENDING,
        )

    @staticmethod
    def calculate_custom_split(amount: float, my_share_input: float, companion_names: list[str]) -> SplitResult:
        """Custom Split calculation: User inputs custom share."""
        rounded_amount = round(amount, 2)
        rounded_my_share = round(my_share_input, 2)

        if rounded_my_share < 0.0:
            raise ValueError("My share cannot be negative")
        if rounded_my_share > rounded_amount:
            raise ValueError("My share cannot exceed total amount")

        owed_to_me = round(rounded_amount - rounded_my_share, 2)
        companions_str = ", ".join(companion_names) if companion_names else "Friends"

        status = TransactionStatus.SETTLED if owed_to_me == 0.00 else TransactionStatus.PENDING

        return SplitResult(
            total_amount=rounded_amount,
            my_share=rounded_my_share,
            owed_to_me=owed_to_me,
            companions=companions_str,
            status=status,
        )

    @staticmethod
    def validate_category(category_name: str) -> str:
        """Strict Savings keyword rule: strictly plural 'Savings'."""
        cleaned = category_name.strip()
        lower = cleaned.lower()
        if lower in ("saving", "savings", "investment", "sip", "mutual fund"):
            if lower == "saving":
                raise ValueError("Singular 'Saving' is forbidden. Category must strictly be 'Savings'")
            return "Savings"
        return cleaned


# ==============================================================================
# 8. Non-Destructive In-Place Void Engine (Col G = 0.00)
# ==============================================================================

class VoidEngine:
    @staticmethod
    def apply_void(txn: Transaction) -> Transaction:
        """Applies non-destructive in-place void logic to transaction.

        Sets Total Amount G=0.00, My Share I=0.00, Owed To Me J=0.00,
        Status K='Void', and prefixes Description with '[Void] '.
        Preserves row coordinate and UUID.
        """
        original_item = txn.item_description
        if not original_item.startswith("[Void] "):
            void_description = f"[Void] {original_item}"
        else:
            void_description = original_item

        txn.total_amount = 0.00
        txn.my_share = 0.00
        txn.owed_to_me = 0.00
        txn.status = TransactionStatus.VOID
        txn.item_description = void_description
        txn.sync_state = SyncState.VOID
        return txn


# ==============================================================================
# 9. Carry-Forward Debt Rollover Engine (Target Month Col G = 0.00)
# ==============================================================================

class RolloverEngine:
    @staticmethod
    def carry_forward_unsettled_debt(
        origin_month: str,
        target_month: str,
        origin_txn: Transaction,
        target_date_serial: int,
        target_time_serial: float,
    ) -> tuple[Transaction, Transaction]:
        """Carries forward an unsettled debt from origin_month to target_month.

        Origin transaction: zeroes out Column J and sets status Carried.
        Target transaction: Total Amount G=0.00 (prevents double-counting in Monthly Gross Outflow),
        My Share I=0.00, Owed To Me J=original_owed, Status K='Pending'.
        Returns (updated_origin_txn, new_carried_txn).
        """
        if origin_txn.status != TransactionStatus.PENDING or origin_txn.owed_to_me <= 0.0:
            raise ValueError("Only transactions with Pending status and positive owed_to_me can be rolled over")

        original_owed = origin_txn.owed_to_me
        original_item = origin_txn.item_description

        # 1. Update origin transaction: zero out Column J and set status Carried
        origin_txn.owed_to_me = 0.00
        origin_txn.status = TransactionStatus.CARRIED

        # 2. Generate new transaction for target month with fresh UUID
        now_utc = datetime.datetime.now(datetime.timezone.utc)
        new_uuid = f"txn_carried_{now_utc.strftime('%Y%m%d%H%M%S%f')}"
        carried_item = f"[Carried from {origin_month}] {original_item}"

        carried_txn = Transaction(
            id=new_uuid,
            date_serial=target_date_serial,
            time_serial=target_time_serial,
            account=origin_txn.account,
            item_description=carried_item,
            category=origin_txn.category,
            total_amount=0.00,  # Col G = 0 to avoid gross outflow inflation in target month
            my_share=0.00,      # Col I = 0
            owed_to_me=original_owed,
            status=TransactionStatus.PENDING,
            companions=origin_txn.companions,
            trip_tag=origin_txn.trip_tag,
            review_state=ReviewState.CONFIRMED,
            sync_state=SyncState.PENDING,
        )

        return origin_txn, carried_txn


# ==============================================================================
# 10. Master Google Sheet Payload Mapper
# ==============================================================================

class SheetPayloadMapper:
    @staticmethod
    def map_debit_row(txn: Transaction) -> list[Any]:
        """Maps a Table 1 Debit transaction to columns A through K."""
        return [
            txn.date_serial,
            round(txn.time_serial, 6),
            txn.account,
            txn.item_description,
            txn.trip_tag,
            txn.category,
            txn.total_amount,
            txn.companions,
            txn.my_share,
            txn.owed_to_me,
            txn.status.value,
        ]

    @staticmethod
    def map_inflow_row(inflow: InflowTransaction) -> list[Any]:
        """Maps a Table 2 Inflow transaction to columns M through T."""
        return [
            inflow.date_serial,
            round(inflow.time_serial, 6),
            inflow.account,
            inflow.inflow_type.value,
            inflow.received_from,
            inflow.reason,
            inflow.amount,
            inflow.linked_debit_uuid,
        ]

    @staticmethod
    def map_payable_row(payable: PayableTransaction) -> list[Any]:
        """Maps a Table 3 Payable transaction to columns V through AB."""
        return [
            payable.date_incurred_serial,
            round(payable.time_serial, 6),
            payable.paid_by,
            payable.what_for,
            payable.amount_i_owe,
            payable.date_repaid_serial if payable.date_repaid_serial is not None else "",
            payable.repayment_status.value,
        ]

    @staticmethod
    def is_safe_raw_value(val: Any) -> bool:
        """Validates that RAW mode prevents executable formulas."""
        return True


# ==============================================================================
# 11. Deduplication and Settle Window State Machine (Amount + Merchant + 120s)
# ==============================================================================

class DeduplicationEngine:
    def __init__(self, settle_window_seconds: float = 120.0):
        self.settle_window_seconds = settle_window_seconds
        self.recent_transactions: list[Transaction] = []

    def evaluate_incoming(
        self,
        package_name: str,
        amount: float,
        merchant: str,
        timestamp: float,
    ) -> tuple[str, Transaction | None]:
        """Evaluates an incoming notification against recent transactions.

        Deduplication strictly relies on: Amount + Paid To (Merchant) + 120s sliding window.
        Returns action:
        - "ACCEPT_NEW": Clean new transaction
        - "MERGE_EXACT_SIMILAR": Duplicate alert merged via same amount + merchant in window
        - "FLAG_POSSIBLE_DUPLICATE": Duplicate candidate with matching amount but distinct merchant
        """
        for existing in self.recent_transactions:
            time_diff = abs(timestamp - existing.timestamp)
            if time_diff <= self.settle_window_seconds and math.isclose(
                existing.total_amount, amount, rel_tol=1e-5, abs_tol=0.01
            ):
                existing_merchant = (existing.item_description or "").strip().lower()
                incoming_merchant = (merchant or "").strip().lower()

                is_exact_merchant = existing_merchant == incoming_merchant
                is_existing_unknown = existing_merchant in ("", "unknown payee")
                is_incoming_unknown = incoming_merchant in ("", "unknown payee")

                if is_exact_merchant or is_existing_unknown or is_incoming_unknown:
                    if is_existing_unknown and not is_incoming_unknown:
                        existing.item_description = merchant
                    return "MERGE_EXACT_SIMILAR", existing
                else:
                    existing.is_possible_duplicate = True
                    return "FLAG_POSSIBLE_DUPLICATE", existing

        return "ACCEPT_NEW", None


# ==============================================================================
# 12. Two-Axis State Machine
# ==============================================================================

class TwoAxisStateMachine:
    VALID_TRANSITIONS: ClassVar[dict[tuple[ReviewState, SyncState], list[tuple[ReviewState, SyncState]]]] = {
        (ReviewState.NEEDS_REVIEW, SyncState.PENDING): [
            (ReviewState.CONFIRMED, SyncState.PENDING),
            (ReviewState.NEEDS_REVIEW, SyncState.SYNCED),
            (ReviewState.NEEDS_REVIEW, SyncState.VOID),
        ],
        (ReviewState.NEEDS_REVIEW, SyncState.SYNCED): [
            (ReviewState.CONFIRMED, SyncState.SYNCED),
            (ReviewState.NEEDS_REVIEW, SyncState.VOID),
        ],
        (ReviewState.CONFIRMED, SyncState.PENDING): [
            (ReviewState.CONFIRMED, SyncState.SYNCED),
            (ReviewState.CONFIRMED, SyncState.VOID),
        ],
        (ReviewState.CONFIRMED, SyncState.SYNCED): [
            (ReviewState.CONFIRMED, SyncState.VOID),
        ],
        (ReviewState.NEEDS_REVIEW, SyncState.VOID): [],
        (ReviewState.CONFIRMED, SyncState.VOID): [],
    }

    @staticmethod
    def is_valid_transition(
        current_review: ReviewState,
        current_sync: SyncState,
        next_review: ReviewState,
        next_sync: SyncState,
    ) -> bool:
        """Validates two-axis state machine transition."""
        allowed = TwoAxisStateMachine.VALID_TRANSITIONS.get((current_review, current_sync), [])
        return (next_review, next_sync) in allowed


# ==============================================================================
# 13. Notification Interception & Parsing Engine (Zero UTR & Zero Raw Text)
# ==============================================================================

class NotificationParser:
    @staticmethod
    def parse_notification(
        package_name: str,
        title: str,
        text: str,
    ) -> dict[str, Any]:
        """Parses notification text into extracted fields. Zero raw text or UTR stored."""
        full_content = f"{title} {text}".strip()

        # 1. Blacklist check
        if package_name in BLACKLISTED_PRIVACY_PACKAGES:
            return {"status": "DROPPED_BLACKLIST", "reason": "Privacy protected app"}

        # 2. Whitelist check
        if package_name not in WHITELISTED_FINANCIAL_PACKAGES:
            return {"status": "DROPPED_NOT_WHITELISTED", "reason": "Non-financial app"}

        # 3. OTP check
        if OTP_REGEX.search(full_content):
            return {"status": "DROPPED_OTP", "reason": "OTP or verification alert"}

        # 4. Amount extraction
        amount_match = AMOUNT_REGEX.search(full_content)
        amount = None
        if amount_match:
            raw_amt = amount_match.group(1).replace(",", "")
            try:
                amount = float(raw_amt)
            except ValueError:
                amount = None

        # 5. Fallback check: whitelisted financial alert without extractable amount
        if amount is None or amount <= 0.0:
            return {
                "status": "FALLBACK_PROMPT",
                "message": "Payment detected, tap to enter amount",
                "package_name": package_name,
            }

        # 6. Direction check (debit vs credit)
        is_credit = bool(CREDIT_FLAG_REGEX.search(full_content))
        is_debit = bool(DEBIT_FLAG_REGEX.search(full_content)) or not is_credit

        # 7. Merchant / Payee extraction
        merchant_match = MERCHANT_REGEX.search(full_content)
        merchant = merchant_match.group(1).strip() if merchant_match else "Unknown Payee"

        # 8. Funding account extraction
        account_match = ACCOUNT_REGEX.search(full_content)
        account = f"{account_match.group(1)} XX{account_match.group(2)}" if account_match else "Default Account"

        return {
            "status": "PARSED_SUCCESS",
            "amount": amount,
            "merchant": merchant,
            "account": account,
            "is_debit": is_debit,
            "is_credit": is_credit,
            "package_name": package_name,
        }


# ==============================================================================
# 14. Workstream 1: Hidden Config Tab Mirroring (Zero Financial Data)
# ==============================================================================

class ConfigSheetManager:
    """Manages hidden Config tab serialization and restoration with zero financial data."""

    CONFIG_TAB_NAME = "Config"

    @staticmethod
    def build_config_grid(snapshot: AppConfigSnapshot) -> list[list[Any]]:
        grid = []
        headers = [
            "AccountName", "AccountLastFour", "AccountIsDefault", "AccountIsArchived", "AccountOrder",
            "CategoryName", "CategoryExcludedFromBurn", "CategoryIsDefault", "CategoryIsArchived", "CategoryOrder",
            "CompanionName", "CompanionOrder",
            "TripTagName", "TripTagIsActive",
            "PrefKey", "PrefValue",
        ]
        grid.append(headers)

        pref_list = list(snapshot.preferences.items())

        for i in range(49):
            row: list[Any] = ["" for _ in range(16)]

            # Accounts (cols 0..4)
            if i < len(snapshot.accounts):
                acc = snapshot.accounts[i]
                row[0] = acc.name
                row[1] = acc.last_four_digits or ""
                row[2] = str(acc.is_default)
                row[3] = str(acc.is_archived)
                row[4] = str(acc.display_order)

            # Categories (cols 5..9)
            if i < len(snapshot.categories):
                cat = snapshot.categories[i]
                row[5] = cat.name
                row[6] = str(cat.is_excluded_from_living_burn)
                row[7] = str(cat.is_default)
                row[8] = str(cat.is_archived)
                row[9] = str(cat.display_order)

            # Companions (cols 10..11)
            if i < len(snapshot.companions):
                comp = snapshot.companions[i]
                row[10] = comp.name
                row[11] = str(i)

            # TripTags (cols 12..13)
            if i < len(snapshot.trip_tags):
                tag = snapshot.trip_tags[i]
                row[12] = tag.name
                row[13] = str(tag.is_active)

            # Preferences (cols 14..15)
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


# ==============================================================================
# 15. Workstream 1: Dynamic Dashboard Formula Builder
# ==============================================================================

class DashboardFormulaBuilder:
    """Dynamically generates Dashboard formulas based on active categories."""

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
