package com.financeautomation.data.remote

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.InflowTransaction
import com.financeautomation.domain.model.PayableTransaction
import com.financeautomation.domain.model.Transaction
import java.util.UUID

/**
 * Serializes domain models into the strictly standardized 32-column Google Sheets ledger row.
 * Handles Table 1 (Debits A-L with Note at L), Spacer 1 (M), Table 2 (Inflows N-U), Spacer 2 (V),
 * Table 3 (Payables W-AC), and Hidden System UUIDs (AD-AF).
 */
object SheetPayloadMapper {

    /**
     * Serializes a domain [Transaction] into a 32-column list matching columns A through AF.
     */
    fun mapTransactionToFullRow(transaction: Transaction): List<Any?> {
        val normalizedCategory = CategoryConstants.normalizeCategory(transaction.category)

        return listOf(
            // Table 1: Debits / Expenses (Cols A-L, indices 0-11)
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
            transaction.note, // Col L: Expense Note

            // Spacer 1 (Col M, index 12)
            "",

            // Table 2: Credits / Inflows (Cols N-U, indices 13-20)
            "", "", "", "", "", "", "", "",

            // Spacer 2 (Col V, index 21)
            "",

            // Table 3: Payables / Liabilities (Cols W-AC, indices 22-28)
            "", "", "", "", "", "", "",

            // Hidden System UUIDs (Cols AD-AF, indices 29-31)
            transaction.id, // Col AD: Debit Txn UUID
            "",             // Col AE: Inflow Txn UUID
            ""              // Col AF: Payable Txn UUID
        )
    }

    /**
     * Maps a domain [Transaction] strictly to Table 1 (Cols A-L, 12 values) for targeted append.
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
            transaction.status.value,
            transaction.note
        )
    }

    /**
     * Maps an [InflowTransaction] to Table 2 (Cols N-U, 8 values) with Col AE UUID in 32-column row.
     */
    fun mapInflowToFullRow(inflow: InflowTransaction): List<Any?> {
        return listOf(
            // Table 1 (Cols A-L, 12 values) empty
            "", "", "", "", "", "", "", "", "", "", "", "",
            // Spacer 1 (Col M)
            "",
            // Table 2 (Cols N-U)
            inflow.dateSerial,
            inflow.timeSerial,
            inflow.account,
            inflow.inflowType.value,
            inflow.receivedFrom,
            inflow.reason,
            inflow.amount,
            inflow.linkedDebitUuid,
            // Spacer 2 (Col V)
            "",
            // Table 3 (Cols W-AC, 7 values) empty
            "", "", "", "", "", "", "",
            // Hidden System UUIDs (Cols AD-AF)
            "",             // Col AD
            inflow.id,      // Col AE: Inflow Txn UUID
            ""              // Col AF
        )
    }

    /**
     * Maps an [InflowTransaction] strictly to Table 2 (Cols N-U, 8 values) for targeted append.
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
     * Maps an [PayableTransaction] strictly to Table 3 (Cols W-AC, 7 values) for targeted append.
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
     * Maps a [PayableTransaction] to Table 3 (Cols W-AC, 7 values) with Col AF UUID in 32-column row.
     */
    fun mapPayableToFullRow(payable: PayableTransaction): List<Any?> {
        return listOf(
            // Table 1 (Cols A-L, 12 values) empty
            "", "", "", "", "", "", "", "", "", "", "", "",
            // Spacer 1 (Col M)
            "",
            // Table 2 (Cols N-U, 8 values) empty
            "", "", "", "", "", "", "", "",
            // Spacer 2 (Col V)
            "",
            // Table 3 (Cols W-AC)
            payable.dateIncurredSerial,
            payable.timeSerial,
            payable.paidBy,
            payable.whatFor,
            payable.amountIOwe,
            payable.dateRepaidSerial ?: "",
            payable.repaymentStatus.value,
            // Hidden System UUIDs (Cols AD-AF)
            "",             // Col AD
            "",             // Col AE
            payable.id      // Col AF: Payable Txn UUID
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
     * Converts an existing 31 or 32-column row into an in-place void row.
     */
    fun mapExistingRowToVoidRow(row: List<Any?>): List<Any?> {
        require(row.size == SheetSchemaConstants.TOTAL_COLUMN_COUNT || row.size == SheetSchemaConstants.TOTAL_COLUMN_COUNT_V1) {
            "Expected ${SheetSchemaConstants.TOTAL_COLUMN_COUNT} or ${SheetSchemaConstants.TOTAL_COLUMN_COUNT_V1} columns but got ${row.size}"
        }
        val mutable = row.toMutableList()
        val originalDesc = mutable[3]?.toString().orEmpty()
        val voidDesc = if (originalDesc.startsWith("[Void] ")) originalDesc else "[Void] $originalDesc"

        mutable[3] = voidDesc // Col D: Item / Description
        mutable[6] = 0.0      // Col G: Total Amount (INR)
        mutable[8] = 0.0      // Col I: My Share
        mutable[9] = 0.0      // Col J: Owed To Me
        mutable[10] = TransactionStatus.VOID.value // Col K: Status
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
