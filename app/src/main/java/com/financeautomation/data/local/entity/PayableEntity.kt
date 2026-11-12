package com.financeautomation.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local Room persistence for Table 3 (Payables / "I Owe People") rows from Google Sheets.
 */
@Entity(
    tableName = "payables",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["paidBy"]),
        Index(value = ["repaymentStatus"])
    ]
)
data class PayableEntity(
    @PrimaryKey
    val uuid: String,
    val dateIncurredSerial: Int?,
    val timeSerial: Double?,
    val paidBy: String,
    val whatFor: String,
    val amountIOwe: Double,
    val dateRepaidSerial: Int?,
    val repaymentStatus: String,
    val tabName: String,
    val sheetRowIndex: Int?,
    val updatedAt: Long = System.currentTimeMillis()
)

