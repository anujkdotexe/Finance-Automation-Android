package com.financeautomation.data.remote

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.InflowTransaction
import com.financeautomation.domain.model.PayableTransaction
import com.financeautomation.domain.model.Transaction
import java.util.UUID

/**
 * Serializes domain models into the strictly standardized 31-column Google Sheets ledger row.
 * Handles Table 1 (Debits A-K), Spacer 1 (L), Table 2 (Inflows M-T), Spacer 2 (U),
 * Table 3 (Payables V-AB), and Hidden System UUIDs (AC-AE).
 */
object SheetPayloadMapper {

    /**
     * Serializes a domain [Transaction] into a 31-column list matching columns A through AE.
     */
    fun mapTransactionToFullRow(transaction: Transaction): List<Any?> {
        val normalizedCategory = CategoryConstants.normalizeCategory(transaction.category)

        return listOf(
            // Table 1: Debits / Expenses (Cols A-K, indices 0-10)
            transaction.dateSerial,
            transaction.timeSerial,
            transaction.account,
            transaction.merchant,
            transaction.tripTag,
            normalizedCategory,
            transaction.totalAmount,
            transaction.companions,
            transaction.myShare,
            transaction.owedToMe,
            transaction.status.value,

            // Spacer 1 (Col L, index 11)
            "",

            // Table 2: Credits / Inflows (Cols M-T, indices 12-19)
            "", "", "", "", "", "", "", "",

            // Spacer 2 (Col U, index 20)
            "",

            // Table 3: Payables / Liabilities (Cols V-AB, indices 21-27)
            "", "", "", "", "", "", "",

            // Hidden System UUIDs (Cols AC-AE, indices 28-30)
            transaction.id, // Col AC: Debit Txn UUID
            "",             // Col AD: Inflow Txn UUID
            ""              // Col AE: Payable Txn UUID
        )
    }

    /**
     * Maps a domain [Transaction] strictly to Table 1 (Cols A-K, 11 values) for targeted append.
     */
    fun mapTransactionToTable1(transaction: Transaction): List<Any?> {
        val normalizedCategory = CategoryConstants.normalizeCategory(transaction.category)
        return listOf(
            transaction.dateSerial,
            transaction.timeSerial,
            transaction.account,
            transaction.merchant,
            transaction.tripTag,
            normalizedCategory,
            transaction.totalAmount,
            transaction.companions,
            transaction.myShare,
            transaction.owedToMe,
            transaction.status.value
        )
    }

    /**
     * Maps an [InflowTransaction] to Table 2 (Cols M-T, 8 values) with Col AD UUID.
     */
    fun mapInflowToFullRow(inflow: InflowTransaction): List<Any?> {
        return listOf(
            // Table 1 (Cols A-K) empty
            "", "", "", "", "", "", "", "", "", "", "",
            // Spacer 1 (Col L)
            "",
            // Table 2 (Cols M-T)
            inflow.dateSerial,
            inflow.timeSerial,
            inflow.account,
            inflow.inflowType.value,
            inflow.receivedFrom,
            inflow.reason,
            inflow.amount,
            inflow.linkedDebitUuid,
            // Spacer 2 (Col U)
            "",
            // Table 3 (Cols V-AB) empty
            "", "", "", "", "", "", "",
            // Hidden System UUIDs (Cols AC-AE)
            "",             // Col AC
            inflow.id,      // Col AD: Inflow Txn UUID
            ""              // Col AE
        )
    }

    /**
     * Maps an [InflowTransaction] strictly to Table 2 (Cols M-T, 8 values) for targeted append.
     */
    fun mapInflowToTable2(inflow: InflowTransaction): List<Any?> {
        return listOf(
            inflow.dateSerial,
            inflow.timeSerial,
            inflow.account,
            inflow.inflowType.value,
            inflow.receivedFrom,
            inflow.reason,
            inflow.amount,
            inflow.linkedDebitUuid
        )
    }

    /**
     * Maps an [PayableTransaction] strictly to Table 3 (Cols V-AB, 7 values) for targeted append.
     */
    fun mapPayableToTable3(payable: PayableTransaction): List<Any?> {
        return listOf(
            payable.dateIncurredSerial,
            payable.timeSerial,
            payable.paidBy,
            payable.whatFor,
            payable.amountIOwe,
            payable.dateRepaidSerial ?: "",
            payable.repaymentStatus.value
        )
    }

    /**
     * Maps a [PayableTransaction] to Table 3 (Cols V-AB, 7 values) with Col AE UUID in 31-column row.
     */
    fun mapPayableToFullRow(payable: PayableTransaction): List<Any?> {
        return listOf(
            // Table 1 (Cols A-K, 11 values) empty
            "", "", "", "", "", "", "", "", "", "", "",
            // Spacer 1 (Col L)
            "",
            // Table 2 (Cols M-T, 8 values) empty
            "", "", "", "", "", "", "", "",
            // Spacer 2 (Col U)
            "",
            // Table 3 (Cols V-AB)
            payable.dateIncurredSerial,
            payable.timeSerial,
            payable.paidBy,
            payable.whatFor,
            payable.amountIOwe,
            payable.dateRepaidSerial ?: "",
            payable.repaymentStatus.value,
            // Hidden System UUIDs (Cols AC-AE)
            "",             // Col AC
            "",             // Col AD
            payable.id      // Col AE: Payable Txn UUID
        )
    }

    /**
     * Converts a domain [Transaction] into an in-place void payload.
     * Sets Total Amount (Col G) = 0.0, zeroes shares, and marks Status = Void
     * to prevent inflating monthly outflow sums while preserving row coordinate.
     */
    fun mapToVoidPayload(transaction: Transaction): List<Any?> {
        val voidDescription = if (transaction.merchant.startsWith("[Void] ")) {
            transaction.merchant
        } else {
            "[Void] ${transaction.merchant}"
        }

        val voidedTxn = transaction.copy(
            merchant = voidDescription,
            totalAmount = 0.0,
            myShare = 0.0,
            owedToMe = 0.0,
            status = TransactionStatus.VOID
        )
        return mapTransactionToFullRow(voidedTxn)
    }

    /**
     * Converts an existing 31-column row into an in-place void row.
     */
    fun mapExistingRowToVoidRow(row: List<Any?>): List<Any?> {
        require(row.size == SheetSchemaConstants.TOTAL_COLUMN_COUNT) {
            "Expected 31 columns but got ${row.size}"
        }
        val mutable = row.toMutableList()
        val originalDesc = mutable[3]?.toString().orEmpty()
        val voidDesc = if (originalDesc.startsWith("[Void] ")) originalDesc else "[Void] $originalDesc"

        mutable[3] = voidDesc // Col D: Item / Description
        mutable[6] = 0.0      // Col G: Total Amount (INR)
        mutable[8] = 0.0      // Col I: My Share
        mutable[9] = 0.0      // Col J: Owed To Me
        mutable[10] = SheetSchemaConstants.STATUS_VOID // Col K: Status
        return mutable
    }

    /**
     * Origin month update for debt rollover:
     * Zeroes Col J (Owed To Me) and marks Status = "Carried" to prevent double-counting.
     */
    fun mapOriginRolloverUpdate(originTransaction: Transaction): Transaction {
        return originTransaction.copy(
            owedToMe = 0.0,
            status = TransactionStatus.CARRIED_FWD
        )
    }

    /**
     * Target month row for debt rollover:
     * Carries forward unsettled balance with fresh UUID, Total Amount = owed (Col G),
     * My Share = 0 (Col I = 0), Owed To Me = originTxn.owedToMe (Col J), Status = "Pending" (Col K),
     * and prefixed description "[Carried from $originMonth] $original_item".
     */
    fun mapTargetRolloverNewRow(
        originTransaction: Transaction,
        originMonth: String,
        newId: String = "txn_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Transaction {
        val carriedDesc = "[Carried from $originMonth] ${originTransaction.merchant}"
        return originTransaction.copy(
            id = newId,
            merchant = carriedDesc,
            totalAmount = originTransaction.owedToMe,
            myShare = 0.0,
            owedToMe = originTransaction.owedToMe,
            status = TransactionStatus.PENDING,
            sheetRowIndex = null
        )
    }
}
