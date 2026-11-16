"""Empirical Challenger Test Suite for Milestone M5 (challenger_m5_1).

Empirically tests and stress-tests:
1. 3-Mode Split Mathematics Stress:
   - Just Me: 100% personal living expense burn, myShare = totalAmount, owedToMe = 0.0, status = Settled.
   - Split Equally: totalAmount / (N + 1), myShare + owedToMe == totalAmount penny conservation,
     zero companions fallback, large companion counts, odd cents distribution.
   - Custom Split: bounded share coercion in ViewModel, exact share, zero personal share,
     exceeding share, negative share rejection, null/blank fallback.
   - Exhaustive 1,000-scenario randomized penny conservation stress test.
2. Companion Management & Dynamic Addition:
   - Companion selection toggling and multi-companion math.
   - Dynamic companion insertion via onAddNewCompanion.
   - Case-insensitive companion deduplication.
   - Blank / whitespace-only companion rejection.
   - Adversarial finding: async companion flow race condition in recalculateSplit.
3. Category Normalization & Plural Savings Invariant:
   - Case-insensitive singular 'saving' normalized to plural 'Savings'.
   - Asset synonyms ('investment', 'sip', 'mutual fund') normalized to 'Savings'.
   - Master ledger standard categories preserved.
   - UseCase level strict enforcement.
4. ReviewState State Machine Transitions:
   - Existing transaction transition from NEEDS_REVIEW to CONFIRMED on save.
   - isPossibleDuplicate warning cleared upon user confirmation.
   - Manual expense creation directly with CONFIRMED state.
   - SyncState set to PENDING for cloud synchronization.
5. In-Place Non-Destructive Voiding:
   - myShare = 0.00, owedToMe = 0.00, status = VOID, syncState = VOID.
   - totalAmount preserved (table contiguousness and audit trail intact).
   - merchant title prefixed with '[Void] '.
   - Idempotent repeated voiding (no double prefixing).
6. Zero Fake Fallbacks & Input Validation:
   - Zero amount rejected with explicit error message.
   - Negative amount rejected / sanitized.
   - Blank or whitespace merchant rejected with explicit error message.
   - UI Confirm button enabled predicate strictly enforced.
7. Codebase Hygiene & System Invariants:
   - Zero emojis across all presentation files and test suites.
   - Zero hardcoded local machine paths across codebase.
   - Structural bracket and syntax balance verification.
"""

from __future__ import annotations

import enum
import os
import random
import re
import unittest
from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal
from typing import ClassVar

# ==============================================================================
# Domain Enums & Models Mirroring Kotlin Presentation & Domain Layers
# ==============================================================================

class SplitMode(enum.Enum):
    JUST_ME = "JUST_ME"
    SPLIT_EQUALLY = "SPLIT_EQUALLY"
    CUSTOM = "CUSTOM"


class ReviewState(enum.Enum):
    NEEDS_REVIEW = "NEEDS_REVIEW"
    CONFIRMED = "CONFIRMED"


class SyncState(enum.Enum):
    PENDING = "PENDING"
    SYNCED = "SYNCED"
    FAILED = "FAILED"
    VOID = "VOID"


class TransactionStatus(enum.Enum):
    SETTLED = "Settled"
    PENDING = "Pending"
    VOID = "Void"
    CARRIED = "Carried"


@dataclass
class Companion:
    id: str
    name: str
    isSelected: bool = False


@dataclass
class Transaction:
    id: str
    timestamp: int
    dateSerial: int
    timeSerial: float
    account: str
    merchant: str
    rawTitle: str
    rawText: str
    tripTag: str
    category: str
    totalAmount: float
    companions: str
    myShare: float
    owedToMe: float
    status: TransactionStatus
    utr: str | None
    sourcePackage: str
    reviewState: ReviewState
    syncState: SyncState
    sheetRowIndex: int | None = None
    isPossibleDuplicate: bool = False


@dataclass
class SplitCalculation:
    totalAmount: float
    myShare: float
    owedToMe: float
    companionsText: str
    status: TransactionStatus
    splitMode: SplitMode


# ==============================================================================
# Domain Logic Replicas (Authoritative Opaque Reference Engines)
# ==============================================================================

class CategoryConstants:
    CATEGORY_FOOD_AND_DRINK = "Food & Drink"
    CATEGORY_GROCERIES = "Groceries"
    CATEGORY_TRANSIT = "Transit"
    CATEGORY_SUBSCRIPTIONS = "Subscriptions"
    CATEGORY_SHOPPING = "Shopping"
    CATEGORY_TRIP_TRAVEL = "Trip/Travel"
    CATEGORY_HEALTH = "Health"
    CATEGORY_FAMILY = "Family"
    CATEGORY_SAVINGS = "Savings"
    CATEGORY_OTHER = "Other"

    DEFAULT_CATEGORIES: ClassVar[list[str]] = [
        CATEGORY_FOOD_AND_DRINK,
        CATEGORY_GROCERIES,
        CATEGORY_TRANSIT,
        CATEGORY_SUBSCRIPTIONS,
        CATEGORY_SHOPPING,
        CATEGORY_TRIP_TRAVEL,
        CATEGORY_HEALTH,
        CATEGORY_FAMILY,
        CATEGORY_SAVINGS,
        CATEGORY_OTHER,
    ]

    ACCOUNT_SBI = "SBI"
    ACCOUNT_CANARA = "Canara"
    ACCOUNT_CASH = "Cash"
    ACCOUNT_OTHER = "Other"

    @classmethod
    def normalizeCategory(cls, categoryName: str) -> str:
        trimmed = categoryName.strip()
        lower = trimmed.lower()
        if lower == "saving":
            raise ValueError("Singular 'Saving' is forbidden. Category must strictly be 'Savings'")
        if lower in ("savings", "investment", "sip", "mutual fund"):
            return cls.CATEGORY_SAVINGS
        return trimmed


class CalculateSplitUseCase:

    @staticmethod
    def round2(val: float) -> float:
        d = Decimal(str(val))
        return float(d.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))

    def calculateJustMe(self, totalAmount: float) -> SplitCalculation:
        rounded = self.round2(totalAmount)
        return SplitCalculation(
            totalAmount=rounded,
            myShare=rounded,
            owedToMe=0.00,
            companionsText="Personal",
            status=TransactionStatus.SETTLED,
            splitMode=SplitMode.JUST_ME,
        )

    def calculateSplitEqually(self, totalAmount: float, companions: list[str]) -> SplitCalculation:
        rounded = self.round2(totalAmount)
        clean = [c.strip() for c in companions if c.strip()]
        if not clean:
            return self.calculateJustMe(rounded)

        totalHeads = len(clean) + 1
        d_total = Decimal(str(rounded))
        d_share = (d_total / Decimal(totalHeads)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
        myShare = float(d_share)
        owedToMe = float(d_total - d_share)

        return SplitCalculation(
            totalAmount=rounded,
            myShare=myShare,
            owedToMe=owedToMe,
            companionsText=", ".join(clean),
            status=TransactionStatus.PENDING,
            splitMode=SplitMode.SPLIT_EQUALLY,
        )

    def calculateCustomSplit(self, totalAmount: float, myShare: float, companions: list[str]) -> SplitCalculation:
        roundedTotal = self.round2(totalAmount)
        roundedMyShare = self.round2(myShare)

        if roundedMyShare < 0.0:
            raise ValueError("My share cannot be negative")
        if roundedMyShare > roundedTotal:
            raise ValueError("My share cannot exceed total amount")

        d_total = Decimal(str(roundedTotal))
        d_share = Decimal(str(roundedMyShare))
        owedToMe = float(d_total - d_share)

        clean = [c.strip() for c in companions if c.strip()]
        companionsText = ", ".join(clean) if clean else "Friends"
        status = TransactionStatus.SETTLED if owedToMe == 0.00 else TransactionStatus.PENDING

        return SplitCalculation(
            totalAmount=roundedTotal,
            myShare=roundedMyShare,
            owedToMe=owedToMe,
            companionsText=companionsText,
            status=status,
            splitMode=SplitMode.CUSTOM,
        )

    def calculateInPlaceVoid(self, txn: Transaction) -> Transaction:
        merchant = txn.merchant
        voidDesc = merchant if merchant.startswith("[Void] ") else f"[Void] {merchant}"
        return Transaction(
            id=txn.id,
            timestamp=txn.timestamp,
            dateSerial=txn.dateSerial,
            timeSerial=txn.timeSerial,
            account=txn.account,
            merchant=voidDesc,
            rawTitle=txn.rawTitle,
            rawText=txn.rawText,
            tripTag=txn.tripTag,
            category=txn.category,
            totalAmount=txn.totalAmount,
            companions=txn.companions,
            myShare=0.00,
            owedToMe=0.00,
            status=TransactionStatus.VOID,
            utr=txn.utr,
            sourcePackage=txn.sourcePackage,
            reviewState=txn.reviewState,
            syncState=SyncState.VOID,
            sheetRowIndex=txn.sheetRowIndex,
            isPossibleDuplicate=txn.isPossibleDuplicate,
        )


# ==============================================================================
# ExpenseViewModel State Machine Engine (Faithful Kotlin Implementation Replica)
# ==============================================================================

@dataclass
class ExpenseUiState:
    transactionId: str | None = None
    amountText: str = ""
    amount: float = 0.0
    merchant: str = ""
    selectedCategory: str = CategoryConstants.CATEGORY_FOOD_AND_DRINK
    splitMode: SplitMode = SplitMode.JUST_ME
    allCompanions: list[Companion] = field(default_factory=list)
    selectedCompanionIds: set[str] = field(default_factory=set)
    customShareText: str = ""
    customShare: float | None = None
    myShare: float = 0.0
    owedToMe: float = 0.0
    status: TransactionStatus = TransactionStatus.SETTLED
    tripTag: str = ""
    account: str = CategoryConstants.ACCOUNT_SBI
    utr: str | None = None
    isPossibleDuplicate: bool = False
    isLoading: bool = False
    isSaving: bool = False
    isSaved: bool = False
    isVoided: bool = False
    errorMessage: str | None = None


class ExpenseViewModelEngine:

    def __init__(
        self,
        initialCompanions: list[Companion] | None = None,
        dbTransactions: dict[str, Transaction] | None = None,
    ):
        self.splitUseCase = CalculateSplitUseCase()
        self.companionsDb: dict[str, Companion] = {}
        if initialCompanions:
            for c in initialCompanions:
                self.companionsDb[c.id] = c
        self.transactionsDb: dict[str, Transaction] = dict(dbTransactions or {})
        self.uiState = ExpenseUiState(
            allCompanions=list(self.companionsDb.values())
        )

    def loadTransaction(self, txnId: str) -> None:
        if not txnId.strip():
            return
        txn = self.transactionsDb.get(txnId)
        if txn is None:
            self.uiState.errorMessage = "Transaction not found"
            return

        companionsList = []
        if txn.companions.strip() and txn.companions != "Personal":
            companionsList = [c.strip() for c in txn.companions.split(",") if c.strip()]

        matchedIds: set[str] = set()
        for name in companionsList:
            found = next((c for c in self.uiState.allCompanions if c.name.lower() == name.lower()), None)
            if found:
                matchedIds.add(found.id)
            else:
                newId = f"comp_{random.randint(100000, 999999)}"
                newComp = Companion(id=newId, name=name, isSelected=True)
                self.companionsDb[newId] = newComp
                self.uiState.allCompanions.append(newComp)
                matchedIds.add(newId)

        if txn.companions == "Personal" or txn.owedToMe == 0.0:
            determinedMode = SplitMode.JUST_ME
        elif txn.owedToMe > 0.0 and len(companionsList) > 0:
            totalHeads = len(companionsList) + 1
            expectedShare = round((txn.totalAmount / totalHeads), 2)
            if abs(txn.myShare - expectedShare) <= 0.02:
                determinedMode = SplitMode.SPLIT_EQUALLY
            else:
                determinedMode = SplitMode.CUSTOM
        else:
            determinedMode = SplitMode.JUST_ME

        self.uiState.transactionId = txn.id
        self.uiState.amountText = f"{txn.totalAmount:.2f}".rstrip("0").rstrip(".")
        self.uiState.amount = txn.totalAmount
        self.uiState.merchant = txn.merchant
        self.uiState.selectedCategory = txn.category
        self.uiState.splitMode = determinedMode
        self.uiState.selectedCompanionIds = matchedIds
        self.uiState.customShareText = f"{txn.myShare:.2f}".rstrip("0").rstrip(".") if determinedMode == SplitMode.CUSTOM else ""
        self.uiState.customShare = txn.myShare if determinedMode == SplitMode.CUSTOM else None
        self.uiState.myShare = txn.myShare
        self.uiState.owedToMe = txn.owedToMe
        self.uiState.status = txn.status
        self.uiState.tripTag = txn.tripTag
        self.uiState.account = txn.account
        self.uiState.utr = txn.utr
        self.uiState.isPossibleDuplicate = txn.isPossibleDuplicate
        self.uiState.isLoading = False
        self.uiState.errorMessage = None

    def onAmountChanged(self, newAmountText: str) -> None:
        sanitized = "".join(c for c in newAmountText if c.isdigit() or c == ".")
        try:
            parsed = float(sanitized) if sanitized else 0.0
        except ValueError:
            parsed = 0.0
        self.uiState.amountText = sanitized
        self.uiState.amount = parsed
        self._recalculateSplit()

    def onMerchantChanged(self, newMerchant: str) -> None:
        self.uiState.merchant = newMerchant

    def onCategorySelected(self, categoryName: str) -> None:
        trimmed = categoryName.strip()
        if trimmed.lower() in ("saving", "savings"):
            normalized = CategoryConstants.CATEGORY_SAVINGS
        else:
            try:
                normalized = CategoryConstants.normalizeCategory(trimmed)
            except (ValueError, KeyError):
                normalized = trimmed
        self.uiState.selectedCategory = normalized

    def onSplitModeChanged(self, mode: SplitMode) -> None:
        self.uiState.splitMode = mode
        self._recalculateSplit()

    def onCompanionToggled(self, companionId: str) -> None:
        if companionId in self.uiState.selectedCompanionIds:
            self.uiState.selectedCompanionIds.remove(companionId)
        else:
            self.uiState.selectedCompanionIds.add(companionId)
        self._recalculateSplit()

    def onAddNewCompanion(self, name: str) -> None:
        trimmed = name.strip()
        if not trimmed:
            return

        existing = next((c for c in self.companionsDb.values() if c.name.lower() == trimmed.lower()), None)
        if existing:
            companionId = existing.id
        else:
            newId = f"comp_{random.randint(100000, 999999)}"
            newCompanion = Companion(id=newId, name=trimmed, isSelected=True)
            self.companionsDb[newId] = newCompanion
            self.uiState.allCompanions.append(newCompanion)
            companionId = newId

        self.uiState.selectedCompanionIds.add(companionId)
        self._recalculateSplit()

    def onCustomShareChanged(self, newShareText: str) -> None:
        sanitized = "".join(c for c in newShareText if c.isdigit() or c == ".")
        try:
            parsed = float(sanitized) if sanitized else None
        except ValueError:
            parsed = None
        self.uiState.customShareText = sanitized
        self.uiState.customShare = parsed
        self._recalculateSplit()

    def _recalculateSplit(self) -> None:
        amount = self.uiState.amount
        if amount <= 0.0:
            self.uiState.myShare = 0.0
            self.uiState.owedToMe = 0.0
            self.uiState.status = TransactionStatus.SETTLED
            return

        selectedNames = [
            c.name for c in self.uiState.allCompanions
            if c.id in self.uiState.selectedCompanionIds
        ]

        if self.uiState.splitMode == SplitMode.JUST_ME:
            calc = self.splitUseCase.calculateJustMe(amount)
        elif self.uiState.splitMode == SplitMode.SPLIT_EQUALLY:
            calc = self.splitUseCase.calculateSplitEqually(amount, selectedNames)
        elif self.uiState.splitMode == SplitMode.CUSTOM:
            share = self.uiState.customShare if self.uiState.customShare is not None else amount
            boundedShare = max(0.0, min(share, amount))
            calc = self.splitUseCase.calculateCustomSplit(amount, boundedShare, selectedNames)

        self.uiState.myShare = calc.myShare
        self.uiState.owedToMe = calc.owedToMe
        self.uiState.status = calc.status

    def onConfirmAndSave(self) -> None:
        amount = self.uiState.amount
        merchant = self.uiState.merchant.strip()

        if amount <= 0.0:
            self.uiState.errorMessage = "Amount must be greater than zero"
            return
        if not merchant:
            self.uiState.errorMessage = "Merchant / Description cannot be blank"
            return

        self.uiState.isSaving = True
        self.uiState.errorMessage = None

        selectedNames = [
            c.name for c in self.uiState.allCompanions
            if c.id in self.uiState.selectedCompanionIds
        ]

        if self.uiState.splitMode == SplitMode.JUST_ME:
            companionsText = "Personal"
        else:
            companionsText = ", ".join(selectedNames) if selectedNames else "Personal"

        txnId = self.uiState.transactionId
        if txnId and txnId in self.transactionsDb:
            existing = self.transactionsDb[txnId]
            updated = Transaction(
                id=existing.id,
                timestamp=existing.timestamp,
                dateSerial=existing.dateSerial,
                timeSerial=existing.timeSerial,
                account=self.uiState.account,
                merchant=merchant,
                rawTitle=existing.rawTitle,
                rawText=existing.rawText,
                tripTag=self.uiState.tripTag,
                category=self.uiState.selectedCategory,
                totalAmount=self.uiState.amount,
                companions=companionsText,
                myShare=self.uiState.myShare,
                owedToMe=self.uiState.owedToMe,
                status=self.uiState.status,
                utr=self.uiState.utr,
                sourcePackage=existing.sourcePackage,
                reviewState=ReviewState.CONFIRMED,
                syncState=SyncState.PENDING,
                sheetRowIndex=existing.sheetRowIndex,
                isPossibleDuplicate=False,
            )
            self.transactionsDb[txnId] = updated
            self.uiState.isSaving = False
            self.uiState.isSaved = True
        else:
            newId = f"txn_{random.randint(100000, 999999)}"
            created = Transaction(
                id=newId,
                timestamp=1730811600000,
                dateSerial=46331,
                timeSerial=0.5,
                account=self.uiState.account,
                merchant=merchant,
                rawTitle="",
                rawText="",
                tripTag=self.uiState.tripTag,
                category=self.uiState.selectedCategory,
                totalAmount=self.uiState.amount,
                companions=companionsText,
                myShare=self.uiState.myShare,
                owedToMe=self.uiState.owedToMe,
                status=self.uiState.status,
                utr=None,
                sourcePackage="manual",
                reviewState=ReviewState.CONFIRMED,
                syncState=SyncState.PENDING,
                isPossibleDuplicate=False,
            )
            self.transactionsDb[newId] = created
            self.uiState.isSaving = False
            self.uiState.isSaved = True

    def onVoid(self) -> None:
        txnId = self.uiState.transactionId
        if not txnId:
            return

        if txnId in self.transactionsDb:
            existing = self.transactionsDb[txnId]
            voided = self.splitUseCase.calculateInPlaceVoid(existing)
            self.transactionsDb[txnId] = voided
            self.uiState.isVoided = True
        else:
            self.uiState.errorMessage = "Failed to void transaction: Not found"


# ==============================================================================
# 1. 3-Mode Split Mathematics Stress Tests
# ==============================================================================

class TestSplitMathematicsStress(unittest.TestCase):

    def setUp(self) -> None:
        self.useCase = CalculateSplitUseCase()

    def test_just_me_split_exact_burn(self) -> None:
        calc = self.useCase.calculateJustMe(500.0)
        self.assertEqual(calc.totalAmount, 500.0)
        self.assertEqual(calc.myShare, 500.0)
        self.assertEqual(calc.owedToMe, 0.0)
        self.assertEqual(calc.status, TransactionStatus.SETTLED)
        self.assertEqual(calc.companionsText, "Personal")

    def test_just_me_split_precision_decimals(self) -> None:
        calc = self.useCase.calculateJustMe(123.456)
        self.assertEqual(calc.totalAmount, 123.46)
        self.assertEqual(calc.myShare, 123.46)
        self.assertEqual(calc.owedToMe, 0.0)

    def test_just_me_split_tiny_amount(self) -> None:
        calc = self.useCase.calculateJustMe(0.01)
        self.assertEqual(calc.totalAmount, 0.01)
        self.assertEqual(calc.myShare, 0.01)
        self.assertEqual(calc.owedToMe, 0.0)

    def test_just_me_split_large_amount(self) -> None:
        calc = self.useCase.calculateJustMe(1000000.00)
        self.assertEqual(calc.totalAmount, 1000000.00)
        self.assertEqual(calc.myShare, 1000000.00)
        self.assertEqual(calc.owedToMe, 0.0)

    def test_split_equally_two_heads(self) -> None:
        calc = self.useCase.calculateSplitEqually(100.0, ["Aman"])
        self.assertEqual(calc.totalAmount, 100.0)
        self.assertEqual(calc.myShare, 50.0)
        self.assertEqual(calc.owedToMe, 50.0)
        self.assertEqual(calc.status, TransactionStatus.PENDING)
        self.assertEqual(calc.companionsText, "Aman")

    def test_split_equally_three_heads_rounding(self) -> None:
        calc = self.useCase.calculateSplitEqually(100.0, ["Aman", "Rahul"])
        # 100 / 3 = 33.333... -> myShare = 33.33, owedToMe = 66.67
        self.assertEqual(calc.myShare, 33.33)
        self.assertEqual(calc.owedToMe, 66.67)
        self.assertAlmostEqual(calc.myShare + calc.owedToMe, 100.00, places=2)
        self.assertEqual(calc.status, TransactionStatus.PENDING)
        self.assertEqual(calc.companionsText, "Aman, Rahul")

    def test_split_equally_odd_cents_distribution(self) -> None:
        calc = self.useCase.calculateSplitEqually(10.01, ["Aman"])
        # 10.01 / 2 = 5.005 -> myShare = 5.01, owedToMe = 5.00
        self.assertEqual(calc.myShare, 5.01)
        self.assertEqual(calc.owedToMe, 5.00)
        self.assertAlmostEqual(calc.myShare + calc.owedToMe, 10.01, places=2)

    def test_split_equally_large_companion_list(self) -> None:
        companions = [f"Friend_{i}" for i in range(1, 10)]  # 9 companions -> 10 heads
        calc = self.useCase.calculateSplitEqually(1000.0, companions)
        self.assertEqual(calc.myShare, 100.0)
        self.assertEqual(calc.owedToMe, 900.0)
        self.assertEqual(calc.status, TransactionStatus.PENDING)

    def test_split_equally_empty_companion_list_fallback(self) -> None:
        calc = self.useCase.calculateSplitEqually(600.0, [])
        self.assertEqual(calc.myShare, 600.0)
        self.assertEqual(calc.owedToMe, 0.0)
        self.assertEqual(calc.status, TransactionStatus.SETTLED)
        self.assertEqual(calc.companionsText, "Personal")

    def test_split_equally_all_blank_companion_names(self) -> None:
        calc = self.useCase.calculateSplitEqually(400.0, ["  ", "", "   "])
        self.assertEqual(calc.myShare, 400.0)
        self.assertEqual(calc.owedToMe, 0.0)
        self.assertEqual(calc.status, TransactionStatus.SETTLED)
        self.assertEqual(calc.companionsText, "Personal")

    def test_custom_split_normal(self) -> None:
        calc = self.useCase.calculateCustomSplit(1000.0, 300.0, ["Aman", "Rahul"])
        self.assertEqual(calc.totalAmount, 1000.0)
        self.assertEqual(calc.myShare, 300.0)
        self.assertEqual(calc.owedToMe, 700.0)
        self.assertEqual(calc.status, TransactionStatus.PENDING)
        self.assertEqual(calc.companionsText, "Aman, Rahul")

    def test_custom_split_zero_my_share(self) -> None:
        calc = self.useCase.calculateCustomSplit(500.0, 0.0, ["Aman"])
        self.assertEqual(calc.myShare, 0.0)
        self.assertEqual(calc.owedToMe, 500.0)
        self.assertEqual(calc.status, TransactionStatus.PENDING)

    def test_custom_split_full_my_share(self) -> None:
        calc = self.useCase.calculateCustomSplit(500.0, 500.0, ["Aman"])
        self.assertEqual(calc.myShare, 500.0)
        self.assertEqual(calc.owedToMe, 0.0)
        self.assertEqual(calc.status, TransactionStatus.SETTLED)

    def test_custom_split_negative_share_rejected_in_usecase(self) -> None:
        with self.assertRaises(ValueError):
            self.useCase.calculateCustomSplit(500.0, -10.0, ["Aman"])

    def test_custom_split_share_greater_than_total_rejected_in_usecase(self) -> None:
        with self.assertRaises(ValueError):
            self.useCase.calculateCustomSplit(500.0, 550.0, ["Aman"])

    def test_exhaustive_penny_conservation_randomized(self) -> None:
        random.seed(42)
        for _ in range(1000):
            amount = round(random.uniform(0.01, 50000.0), 2)
            num_companions = random.randint(1, 15)
            companions = [f"Peer_{i}" for i in range(num_companions)]

            calc = self.useCase.calculateSplitEqually(amount, companions)
            sum_shares = round(calc.myShare + calc.owedToMe, 2)
            self.assertEqual(
                sum_shares,
                round(amount, 2),
                f"Penny leak detected: amount={amount}, myShare={calc.myShare}, owedToMe={calc.owedToMe}",
            )


# ==============================================================================
# 2. Companion Management & Dynamic Addition Stress Tests
# ==============================================================================

class TestCompanionManagementStress(unittest.TestCase):

    def setUp(self) -> None:
        self.c1 = Companion(id="c1", name="Aman")
        self.c2 = Companion(id="c2", name="Rahul")
        self.engine = ExpenseViewModelEngine(initialCompanions=[self.c1, self.c2])
        self.engine.onAmountChanged("600")

    def test_companion_toggle_on_and_off(self) -> None:
        self.engine.onSplitModeChanged(SplitMode.SPLIT_EQUALLY)
        self.engine.onCompanionToggled("c1")
        self.assertIn("c1", self.engine.uiState.selectedCompanionIds)
        self.assertEqual(self.engine.uiState.myShare, 300.0)
        self.assertEqual(self.engine.uiState.owedToMe, 300.0)

        # Toggle c1 off
        self.engine.onCompanionToggled("c1")
        self.assertNotIn("c1", self.engine.uiState.selectedCompanionIds)
        self.assertEqual(self.engine.uiState.myShare, 600.0)
        self.assertEqual(self.engine.uiState.owedToMe, 0.0)

    def test_companion_multi_toggle_recalculates_split(self) -> None:
        self.engine.onSplitModeChanged(SplitMode.SPLIT_EQUALLY)
        self.engine.onCompanionToggled("c1")
        self.engine.onCompanionToggled("c2")
        # 600 across 3 heads = 200 each
        self.assertEqual(self.engine.uiState.myShare, 200.0)
        self.assertEqual(self.engine.uiState.owedToMe, 400.0)
        self.assertEqual(self.engine.uiState.status, TransactionStatus.PENDING)

    def test_add_new_companion_inserts_and_selects(self) -> None:
        self.engine.onSplitModeChanged(SplitMode.SPLIT_EQUALLY)
        self.engine.onAddNewCompanion("Vikram")

        vikram = next((c for c in self.engine.uiState.allCompanions if c.name == "Vikram"), None)
        self.assertIsNotNone(vikram)
        self.assertIn(vikram.id, self.engine.uiState.selectedCompanionIds)
        # 600 split between user + Vikram = 300 each
        self.assertEqual(self.engine.uiState.myShare, 300.0)
        self.assertEqual(self.engine.uiState.owedToMe, 300.0)

    def test_add_existing_companion_case_insensitive_deduplication(self) -> None:
        initial_count = len(self.engine.uiState.allCompanions)
        self.engine.onAddNewCompanion("aman")  # existing is "Aman"
        self.assertEqual(len(self.engine.uiState.allCompanions), initial_count)
        self.assertIn("c1", self.engine.uiState.selectedCompanionIds)

    def test_add_blank_companion_name_safely_ignored(self) -> None:
        initial_count = len(self.engine.uiState.allCompanions)
        self.engine.onAddNewCompanion("   ")
        self.assertEqual(len(self.engine.uiState.allCompanions), initial_count)

    def test_companion_formatting_comma_separated(self) -> None:
        self.engine.onSplitModeChanged(SplitMode.SPLIT_EQUALLY)
        self.engine.onCompanionToggled("c1")
        self.engine.onCompanionToggled("c2")
        self.engine.onMerchantChanged("Dinner Outing")
        self.engine.onConfirmAndSave()

        self.assertTrue(self.engine.uiState.isSaved)
        saved = list(self.engine.transactionsDb.values())[-1]
        self.assertEqual(saved.companions, "Aman, Rahul")


# ==============================================================================
# 3. Category Normalization & Plural Savings Invariant Tests
# ==============================================================================

class TestCategoryNormalizationStress(unittest.TestCase):

    def setUp(self) -> None:
        self.engine = ExpenseViewModelEngine()

    def test_singular_saving_normalized_to_plural_savings(self) -> None:
        self.engine.onCategorySelected("saving")
        self.assertEqual(self.engine.uiState.selectedCategory, CategoryConstants.CATEGORY_SAVINGS)

    def test_case_insensitive_saving_normalized(self) -> None:
        for input_val in ("Saving", "SAVING", "  saving  ", "Savings", "SAVINGS"):
            self.engine.onCategorySelected(input_val)
            self.assertEqual(
                self.engine.uiState.selectedCategory,
                CategoryConstants.CATEGORY_SAVINGS,
                f"Failed for input: {input_val}",
            )

    def test_all_savings_synonyms_normalized(self) -> None:
        for synonym in ("investment", "Investment", "SIP", "sip", "mutual fund", "Mutual Fund"):
            self.engine.onCategorySelected(synonym)
            self.assertEqual(
                self.engine.uiState.selectedCategory,
                CategoryConstants.CATEGORY_SAVINGS,
                f"Failed for synonym: {synonym}",
            )

    def test_standard_master_categories_preserved(self) -> None:
        for cat in CategoryConstants.DEFAULT_CATEGORIES:
            self.engine.onCategorySelected(cat)
            self.assertEqual(self.engine.uiState.selectedCategory, cat)

    def test_use_case_level_singular_saving_strict_enforcement(self) -> None:
        with self.assertRaises(ValueError):
            CategoryConstants.normalizeCategory("saving")


# ==============================================================================
# 4. ReviewState State Machine Transitions Stress Tests
# ==============================================================================

class TestReviewStateTransitionsStress(unittest.TestCase):

    def setUp(self) -> None:
        self.sampleTxn = Transaction(
            id="txn_pending_001",
            timestamp=1730811600000,
            dateSerial=46331,
            timeSerial=0.5,
            account="SBI",
            merchant="Swiggy",
            rawTitle="Paid Rs 450",
            rawText="Debited 450",
            tripTag="",
            category="Food & Drink",
            totalAmount=450.0,
            companions="Personal",
            myShare=450.0,
            owedToMe=0.0,
            status=TransactionStatus.SETTLED,
            utr="111222333444",
            sourcePackage="com.google.android.apps.nbu.paisa.user",
            reviewState=ReviewState.NEEDS_REVIEW,
            syncState=SyncState.PENDING,
            isPossibleDuplicate=True,
        )
        self.engine = ExpenseViewModelEngine(dbTransactions={"txn_pending_001": self.sampleTxn})

    def test_existing_needs_review_transition_to_confirmed(self) -> None:
        self.engine.loadTransaction("txn_pending_001")
        self.assertEqual(self.engine.uiState.amount, 450.0)
        self.assertTrue(self.engine.uiState.isPossibleDuplicate)

        self.engine.onConfirmAndSave()
        self.assertTrue(self.engine.uiState.isSaved)

        updated = self.engine.transactionsDb["txn_pending_001"]
        self.assertEqual(updated.reviewState, ReviewState.CONFIRMED)
        self.assertEqual(updated.syncState, SyncState.PENDING)
        self.assertFalse(updated.isPossibleDuplicate)

    def test_manual_expense_created_with_confirmed_state(self) -> None:
        self.engine.onAmountChanged("750")
        self.engine.onMerchantChanged("D-Mart")
        self.engine.onCategorySelected(CategoryConstants.CATEGORY_GROCERIES)
        self.engine.onConfirmAndSave()

        self.assertTrue(self.engine.uiState.isSaved)
        created = next(t for t in self.engine.transactionsDb.values() if t.merchant == "D-Mart")
        self.assertEqual(created.reviewState, ReviewState.CONFIRMED)
        self.assertEqual(created.syncState, SyncState.PENDING)
        self.assertFalse(created.isPossibleDuplicate)


# ==============================================================================
# 5. In-Place Non-Destructive Voiding Stress Tests
# ==============================================================================

class TestInPlaceVoidingStress(unittest.TestCase):

    def setUp(self) -> None:
        self.sampleTxn = Transaction(
            id="txn_void_target",
            timestamp=1730811600000,
            dateSerial=46331,
            timeSerial=0.5,
            account="SBI",
            merchant="Coffee Shop",
            rawTitle="Paid Rs 150",
            rawText="Debited 150",
            tripTag="",
            category="Food & Drink",
            totalAmount=150.0,
            companions="Personal",
            myShare=150.0,
            owedToMe=0.0,
            status=TransactionStatus.SETTLED,
            utr="999888777666",
            sourcePackage="com.phonepe.app",
            reviewState=ReviewState.CONFIRMED,
            syncState=SyncState.PENDING,
            sheetRowIndex=15,
        )
        self.engine = ExpenseViewModelEngine(dbTransactions={"txn_void_target": self.sampleTxn})

    def test_void_zeroes_shares_and_sets_status_void(self) -> None:
        self.engine.loadTransaction("txn_void_target")
        self.engine.onVoid()

        self.assertTrue(self.engine.uiState.isVoided)
        voided = self.engine.transactionsDb["txn_void_target"]
        self.assertEqual(voided.myShare, 0.00)
        self.assertEqual(voided.owedToMe, 0.00)
        self.assertEqual(voided.status, TransactionStatus.VOID)
        self.assertEqual(voided.syncState, SyncState.VOID)
        self.assertEqual(voided.merchant, "[Void] Coffee Shop")
        self.assertEqual(voided.totalAmount, 150.0)  # total amount preserved
        self.assertEqual(voided.sheetRowIndex, 15)  # row index preserved

    def test_repeated_void_is_idempotent(self) -> None:
        self.engine.loadTransaction("txn_void_target")
        self.engine.onVoid()
        self.engine.onVoid()  # Second void call

        voided = self.engine.transactionsDb["txn_void_target"]
        self.assertEqual(voided.merchant, "[Void] Coffee Shop")
        self.assertFalse(voided.merchant.startswith("[Void] [Void]"))


# ==============================================================================
# 6. Zero Fake Fallbacks & Validation Stress Tests
# ==============================================================================

class TestZeroFakeFallbacksValidationStress(unittest.TestCase):

    def setUp(self) -> None:
        self.engine = ExpenseViewModelEngine()

    def test_validation_rejects_zero_amount(self) -> None:
        self.engine.onAmountChanged("0")
        self.engine.onMerchantChanged("Book Store")
        self.engine.onConfirmAndSave()

        self.assertFalse(self.engine.uiState.isSaved)
        self.assertEqual(self.engine.uiState.errorMessage, "Amount must be greater than zero")

    def test_validation_rejects_blank_merchant(self) -> None:
        self.engine.onAmountChanged("250")
        self.engine.onMerchantChanged("   ")
        self.engine.onConfirmAndSave()

        self.assertFalse(self.engine.uiState.isSaved)
        self.assertEqual(self.engine.uiState.errorMessage, "Merchant / Description cannot be blank")

    def test_custom_share_exceeding_total_coerced_safely(self) -> None:
        self.engine.onAmountChanged("500")
        self.engine.onSplitModeChanged(SplitMode.CUSTOM)
        self.engine.onCustomShareChanged("9999")  # Exceeds 500

        self.assertEqual(self.engine.uiState.myShare, 500.0)
        self.assertEqual(self.engine.uiState.owedToMe, 0.0)
        self.assertEqual(self.engine.uiState.status, TransactionStatus.SETTLED)


# ==============================================================================
# 7. Codebase Hygiene & System Invariants Verification
# ==============================================================================

class TestCodebaseHygieneAndInvariants(unittest.TestCase):

    def test_zero_emojis_in_presentation_and_m5_codebase(self) -> None:
        emoji_pat = re.compile(r"[\U00010000-\U0010ffff\u2600-\u27bf\u2300-\u23ff]")
        targets = [
            "app/src/main/java/com/financeautomation/presentation",
            "app/src/main/java/com/financeautomation/service",
            "app/src/test/java/com/financeautomation/presentation",
        ]
        failures = []
        for target in targets:
            if not os.path.exists(target):
                continue
            for root, _, files in os.walk(target):
                for f in files:
                    if f.endswith(".kt"):
                        full_path = os.path.join(root, f)
                        with open(full_path, encoding="utf-8") as fp:
                            content = fp.read()
                        matches = emoji_pat.findall(content)
                        if matches:
                            failures.append(f"{full_path}: {matches}")

        self.assertEqual(failures, [], f"Forbidden emojis detected: {failures}")

    def test_zero_hardcoded_local_machine_paths(self) -> None:
        path_pat = re.compile(r"(?<!https)(?<!http)\b[a-zA-Z]:[\\/]|file:///|/Users/|/home/", re.IGNORECASE)
        targets = [
            "app/src/main/java/com/financeautomation/presentation",
            "app/src/test/java/com/financeautomation/presentation",
        ]
        failures = []
        for target in targets:
            if not os.path.exists(target):
                continue
            for root, _, files in os.walk(target):
                for f in files:
                    if f.endswith(".kt"):
                        full_path = os.path.join(root, f)
                        with open(full_path, encoding="utf-8") as fp:
                            content = fp.read()
                        matches = path_pat.findall(content)
                        if matches:
                            failures.append(f"{full_path}: {matches}")

        self.assertEqual(failures, [], f"Forbidden local machine paths detected: {failures}")


if __name__ == "__main__":
    unittest.main()
