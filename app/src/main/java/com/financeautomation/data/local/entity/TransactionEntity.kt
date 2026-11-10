package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction

/**
 * Room SQLite entity representing a financial transaction in the local database.
 * Matches the master ledger Table 1 debits schema with two-axis state tracking.
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["reviewState"]),
        Index(value = ["syncState"]),
        Index(value = ["sheetRowIndex"])
    ]
)
data class TransactionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "dateSerial")
    val dateSerial: Int,

    @ColumnInfo(name = "timeSerial")
    val timeSerial: Double,

    @ColumnInfo(name = "account")
    val account: String,

    @ColumnInfo(name = "merchant")
    val merchant: String,

    @ColumnInfo(name = "tripTag")
    val tripTag: String = "",

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "totalAmount")
    val totalAmount: Double,

    @ColumnInfo(name = "companions")
    val companions: String = "Personal",

    @ColumnInfo(name = "myShare")
    val myShare: Double,

    @ColumnInfo(name = "owedToMe")
    val owedToMe: Double,

    @ColumnInfo(name = "status")
    val status: String = TransactionStatus.PERSONAL.value,

    @ColumnInfo(name = "sourcePackage")
    val sourcePackage: String = "",

    @ColumnInfo(name = "reviewState")
    val reviewState: String = ReviewState.NEEDS_REVIEW.name,

    @ColumnInfo(name = "syncState")
    val syncState: String = SyncState.PENDING.name,

    @ColumnInfo(name = "sheetRowIndex")
    val sheetRowIndex: Int? = null,

    @ColumnInfo(name = "isPossibleDuplicate")
    val isPossibleDuplicate: Boolean = false
) {
    fun toDomain(): Transaction {
        val domainStatus = TransactionStatus.fromString(status)

        val domainReviewState = try {
            ReviewState.valueOf(reviewState)
        } catch (_: Exception) {
            ReviewState.NEEDS_REVIEW
        }

        val domainSyncState = try {
            SyncState.valueOf(syncState)
        } catch (_: Exception) {
            SyncState.PENDING
        }

        return Transaction(
            id = id,
            timestamp = timestamp,
            dateSerial = dateSerial,
            timeSerial = timeSerial,
            account = account,
            merchant = merchant,
            tripTag = tripTag,
            category = category,
            totalAmount = totalAmount,
            companions = companions,
            myShare = myShare,
            owedToMe = owedToMe,
            status = domainStatus,
            sourcePackage = sourcePackage,
            reviewState = domainReviewState,
            syncState = domainSyncState,
            sheetRowIndex = sheetRowIndex,
            isPossibleDuplicate = isPossibleDuplicate
        )
    }

    companion object {
        fun fromDomain(transaction: Transaction): TransactionEntity {
            return TransactionEntity(
                id = transaction.id,
                timestamp = transaction.timestamp,
                dateSerial = transaction.dateSerial,
                timeSerial = transaction.timeSerial,
                account = transaction.account,
                merchant = transaction.merchant,
                tripTag = transaction.tripTag,
                category = transaction.category,
                totalAmount = transaction.totalAmount,
                companions = transaction.companions,
                myShare = transaction.myShare,
                owedToMe = transaction.owedToMe,
                status = transaction.status.value,
                sourcePackage = transaction.sourcePackage,
                reviewState = transaction.reviewState.name,
                syncState = transaction.syncState.name,
                sheetRowIndex = transaction.sheetRowIndex,
                isPossibleDuplicate = transaction.isPossibleDuplicate
            )
        }
    }
}
