package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.financeautomation.domain.model.SubscriptionCycle
import com.financeautomation.domain.model.SubscriptionCycleState

/**
 * One due occurrence of a subscription. Cycles are local only and never mirrored to the Config tab.
 */
@Entity(
    tableName = "subscription_cycles",
    indices = [
        Index(value = ["subscriptionId", "dueEpochDay"], unique = true),
        Index(value = ["subscriptionId"])
    ]
)
data class SubscriptionCycleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "subscriptionId")
    val subscriptionId: String,

    @ColumnInfo(name = "dueEpochDay")
    val dueEpochDay: Long,

    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "transactionId")
    val transactionId: String? = null,

    @ColumnInfo(name = "remindCount", defaultValue = "0")
    val remindCount: Int = 0,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long
) {
    fun toDomain(): SubscriptionCycle = SubscriptionCycle(
        id = id,
        subscriptionId = subscriptionId,
        dueEpochDay = dueEpochDay,
        state = SubscriptionCycleState.parse(state),
        transactionId = transactionId,
        remindCount = remindCount,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(c: SubscriptionCycle): SubscriptionCycleEntity = SubscriptionCycleEntity(
            id = c.id,
            subscriptionId = c.subscriptionId,
            dueEpochDay = c.dueEpochDay,
            state = c.state.name,
            transactionId = c.transactionId,
            remindCount = c.remindCount,
            updatedAt = c.updatedAt
        )
    }
}
