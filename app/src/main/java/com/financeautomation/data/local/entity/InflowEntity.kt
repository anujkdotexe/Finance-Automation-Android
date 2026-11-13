package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.financeautomation.domain.model.Inflow

@Entity(
    tableName = "inflows",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["inflowType"]),
        Index(value = ["account"]),
        Index(value = ["status"])
    ]
)
data class InflowEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "amount")
    val amount: Double,

    @ColumnInfo(name = "account")
    val account: String,

    @ColumnInfo(name = "inflowType")
    val inflowType: String,

    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "note")
    val note: String,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "status")
    val status: String = "CONFIRMED"
) {
    fun toDomain(): Inflow = Inflow(
        id = id,
        timestamp = timestamp,
        amount = amount,
        account = account,
        inflowType = inflowType,
        source = source,
        note = note,
        createdAt = createdAt,
        status = status
    )

    companion object {
        fun fromDomain(domain: Inflow): InflowEntity = InflowEntity(
            id = domain.id,
            timestamp = domain.timestamp,
            amount = domain.amount,
            account = domain.account,
            inflowType = domain.inflowType,
            source = domain.source,
            note = domain.note,
            createdAt = domain.createdAt,
            status = domain.status
        )
    }
}

