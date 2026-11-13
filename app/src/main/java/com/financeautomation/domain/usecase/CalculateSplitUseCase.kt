package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.model.NewRow
import com.financeautomation.domain.model.SplitCalculation
import com.financeautomation.domain.model.TableType
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.utils.AccountingUtils
import com.financeautomation.domain.utils.Tolerances
import java.util.UUID
import kotlin.math.abs

/**
 * Pure domain UseCase executing all expense split mathematics, debt rollover,
 * and non-destructive in-place void logic with zero Android framework dependencies.
 * Port of split calculations and status determination from Code.gs v2.3.0 and spec section 3.2.
 */
class CalculateSplitUseCase {

    /**
     * Just Me split: 100% personal living expense burn.
     * My Share = Total Amount, Owed To Me = 0.00, Status = Personal, Companions = "Personal".
     */
    fun calculateJustMe(totalAmount: Double): SplitCalculation {
        val rounded = AccountingUtils.round2(totalAmount)
        return SplitCalculation(
            totalAmount = rounded,
            myShare = rounded,
            owedToMe = 0.00,
            companionsText = "Personal",
            status = TransactionStatus.PERSONAL,
            splitMode = SplitMode.JUST_ME
        )
    }

    /**
     * Split Equally: Total divided across N + 1 heads (user + companions).
     * My Share = Total / (N + 1), Owed To Me = Total - My Share, Status = Pending.
     */
    fun calculateSplitEqually(totalAmount: Double, companions: List<String>): SplitCalculation {
        val rounded = AccountingUtils.round2(totalAmount)
        val cleanCompanions = companions.flatMap { AccountingUtils.tokens(it) }
        if (cleanCompanions.isEmpty()) {
            return calculateJustMe(rounded)
        }

        val totalHeads = cleanCompanions.size + 1
        val myShare = AccountingUtils.round2(rounded / totalHeads)
        var owedToMe = AccountingUtils.round2(rounded - myShare)
        if (abs(myShare + owedToMe - rounded) > 0.01) {
            owedToMe = AccountingUtils.round2(rounded - myShare)
        }

        return SplitCalculation(
            totalAmount = rounded,
            myShare = myShare,
            owedToMe = owedToMe,
            companionsText = cleanCompanions.joinToString(", "),
            status = TransactionStatus.PENDING,
            splitMode = SplitMode.SPLIT_EQUALLY
        )
    }

    /**
     * Custom Split: User specifies their exact numeric share.
     * My share is clamped between 0.0 and total.
     * Owed To Me = Total - My Share, Status = Pending (or Settled/Personal if owed is 0).
     */
    fun calculateCustomSplit(
        totalAmount: Double,
        myShare: Double,
        companions: List<String>
    ): SplitCalculation {
        val roundedTotal = AccountingUtils.round2(totalAmount)
        val clampedMyShare = AccountingUtils.round2(myShare.coerceIn(0.0, roundedTotal))

        var owedToMe = AccountingUtils.round2(roundedTotal - clampedMyShare)
        if (abs(clampedMyShare + owedToMe - roundedTotal) > 0.01) {
            owedToMe = AccountingUtils.round2(roundedTotal - clampedMyShare)
        }

        val cleanCompanions = companions.flatMap { AccountingUtils.tokens(it) }
        val companionsText = if (cleanCompanions.isNotEmpty()) {
            cleanCompanions.joinToString(", ")
        } else if (owedToMe <= Tolerances.MONEY_LEFT) {
            "Personal"
        } else {
            ""
        }

        val status = resolveStatus(owed = owedToMe)

        return SplitCalculation(
            totalAmount = roundedTotal,
            myShare = clampedMyShare,
            owedToMe = owedToMe,
            companionsText = companionsText,
            status = status,
            splitMode = SplitMode.CUSTOM
        )
    }

    /**
     * Resolves transaction status matching Code.gs handleLogDebit logic:
     * - If owed > 0.009: requested status, unless Settled or Personal in which case Pending.
     * - Else if offsetTotal > 0: Settled.
     * - Else if requested Settled: Settled.
     * - Else: Personal.
     */
    fun resolveStatus(
        owed: Double,
        requestedStatus: TransactionStatus? = null,
        offsetTotal: Double = 0.0
    ): TransactionStatus {
        return if (owed > Tolerances.MONEY_LEFT) {
            if (requestedStatus == TransactionStatus.SETTLED || requestedStatus == TransactionStatus.PERSONAL) {
                TransactionStatus.PENDING
            } else {
                requestedStatus ?: TransactionStatus.PENDING
            }
        } else if (offsetTotal > 0.0) {
            TransactionStatus.SETTLED
        } else if (requestedStatus == TransactionStatus.SETTLED) {
            TransactionStatus.SETTLED
        } else {
            TransactionStatus.PERSONAL
        }
    }

    /**
     * Complete AccountingPlan generator for logging a debit (spec 3.2, port of handleLogDebit).
     * Validates input, executes split arithmetic, applies netting against open payables,
     * sets correct status, and returns an AccountingPlan containing Table 1 append and payable edits.
     * Pure function with zero I/O and zero Android framework dependencies.
     */
    operator fun invoke(
        amount: Double,
        merchant: String,
        category: String,
        account: String,
        splitMode: SplitMode,
        companions: List<String>,
        customShare: Double? = null,
        tripTag: String = "",
        dateSerial: Int,
        timeSerial: Double,
        tabName: String,
        openPayables: List<LedgerPayable> = emptyList(),
        nettingUseCase: NettingUseCase = NettingUseCase(),
        uuid: String? = null
    ): AccountingPlan {
        require(amount > 0.0) { "Transaction amount must be strictly greater than zero" }
        require(merchant.isNotBlank()) { "Merchant description cannot be blank" }

        val roundedAmount = AccountingUtils.round2(amount)
        val cleanCompanions = companions.flatMap { AccountingUtils.tokens(it) }
        val companionsText = if (cleanCompanions.isEmpty()) "Personal" else cleanCompanions.joinToString(", ")

        val splitResult = when (splitMode) {
            SplitMode.JUST_ME -> calculateJustMe(roundedAmount)
            SplitMode.SPLIT_EQUALLY -> calculateSplitEqually(roundedAmount, cleanCompanions)
            SplitMode.CUSTOM -> calculateCustomSplit(roundedAmount, customShare ?: roundedAmount, cleanCompanions)
        }

        var owed = splitResult.owedToMe
        var offsetTotal = 0.0
        val payableEdits = mutableListOf<CellEdit>()

        if (owed > 0.0 && companionsText != "Personal" && openPayables.isNotEmpty()) {
            val netRes = nettingUseCase.nettingOnDebit(companionsText, owed, dateSerial, openPayables)
            owed = netRes.netOwed
            offsetTotal = netRes.offsetTotal
            payableEdits.addAll(netRes.edits)
        }

        val status = resolveStatus(owed = owed, requestedStatus = splitResult.status, offsetTotal = offsetTotal)
        val normalizedCat = normalizeCategory(category)
        val txnUuid = uuid ?: ("txn_" + UUID.randomUUID().toString().replace("-", "").take(12))

        val cells = listOf(
            dateSerial,
            timeSerial,
            account,
            merchant.trim(),
            tripTag.trim(),
            normalizedCat,
            roundedAmount,
            companionsText,
            splitResult.myShare,
            owed,
            status.value
        )

        val appendRow = NewRow(
            table = TableType.DEBIT,
            tab = tabName,
            uuid = txnUuid,
            cells = cells
        )

        val msgBuilder = StringBuilder("Debit logged successfully")
        if (offsetTotal > 0.0) {
            msgBuilder.append(" (₹$offsetTotal offset against existing payables)")
        }

        return AccountingPlan(
            edits = payableEdits,
            appends = listOf(appendRow),
            message = msgBuilder.toString()
        )
    }

    /**
     * Carries forward an unsettled debt from origin month to target month.
     * Origin row: Owed To Me zeroed to 0.00, Status = "Carried".
     * Target row: Appended with fresh UUID, description prefixed "[Carried from {originMonth}]",
     * My Share = 0.00, Owed To Me = original owed balance, Status = "Pending".
     */
    fun calculateRollover(
        originalRow: Transaction,
        targetMonthPrefix: String,
        targetDateSerial: Int? = null,
        targetTimeSerial: Double? = null
    ): Pair<Transaction, Transaction> {
        require(originalRow.status == TransactionStatus.PENDING && originalRow.owedToMe > 0.0) {
            "Only transactions with Pending status and positive owed_to_me can be rolled over"
        }

        val originalOwed = originalRow.owedToMe
        val originalItem = originalRow.merchant

        // 1. Update origin transaction: zero out Column J and set status Carried Fwd
        val updatedOrigin = originalRow.copy(
            owedToMe = 0.00,
            status = TransactionStatus.CARRIED_FWD
        )

        // 2. Generate new transaction for target month with fresh UUID
        // Per spec 9.7: Total = owed, my share = 0, owed = owed, status = Pending
        val newUuid = "txn_carried_${UUID.randomUUID().toString().replace("-", "")}"
        val carriedItem = "[Carried from $targetMonthPrefix] $originalItem"

        val carriedTxn = Transaction(
            id = newUuid,
            timestamp = System.currentTimeMillis(),
            dateSerial = targetDateSerial ?: originalRow.dateSerial,
            timeSerial = targetTimeSerial ?: originalRow.timeSerial,
            account = originalRow.account,
            merchant = carriedItem,
            category = normalizeCategory(originalRow.category),
            totalAmount = originalOwed,
            myShare = 0.00,
            owedToMe = originalOwed,
            status = TransactionStatus.PENDING,
            companions = originalRow.companions,
            tripTag = originalRow.tripTag,
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.PENDING
        )

        return Pair(updatedOrigin, carriedTxn)
    }

    /**
     * Non-destructive in-place void logic.
     * Preserves table contiguousness by zeroing shares and totalAmount to prevent inflating
     * Monthly Gross Outflow, setting Status = "Void", and prefixing description with "[Void]".
     * Row coordinate and UUID remain intact.
     */
    fun calculateInPlaceVoid(original: Transaction): Transaction {
        val originalItem = original.merchant
        val voidDesc = if (originalItem.startsWith("[Void] ")) {
            originalItem
        } else {
            "[Void] $originalItem"
        }

        return original.copy(
            totalAmount = 0.00,
            myShare = 0.00,
            owedToMe = 0.00,
            status = TransactionStatus.VOID,
            syncState = SyncState.VOID,
            merchant = voidDesc
        )
    }

    /**
     * Normalizes category name according to master ledger rules.
     * Strictly enforces plural "Savings" for investments and SIPs.
     */
    fun normalizeCategory(categoryName: String): String {
        return CategoryConstants.normalizeCategory(categoryName)
    }
}
