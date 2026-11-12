package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency

/**
 * Room entity for a tracked recurring payment. amount null means the amount varies.
 * updatedAt and dirty drive the Config tab mirror, like the other synced entities.
 */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "amount")
    val amount: Double?,

    @ColumnInfo(name = "frequency")
    val frequency: String,

    @ColumnInfo(name = "anchorEpochDay")
    val anchorEpochDay: Long,

    @ColumnInfo(name = "account")
    val account: String,

    @ColumnInfo(name = "category")
    val category: String = Subscription.DEFAULT_CATEGORY,

    @ColumnInfo(name = "remind")
    val remind: Boolean = true,

    @ColumnInfo(name = "keywords")
    val keywords: String = "",

    @ColumnInfo(name = "active")
    val active: Boolean = true,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt", defaultValue = "0")
    val updatedAt: Long = 0L,

    @ColumnInfo(name = "dirty", defaultValue = "0")
    val dirty: Int = 0
) {
    fun toDomain(): Subscription = Subscription(
        id = id,
        name = name,
        amount = amount,
        frequency = SubscriptionFrequency.parse(frequency),
        anchorEpochDay = anchorEpochDay,
        account = account,
        category = category,
        remind = remind,
        keywords = keywords,
        active = active,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(
            s: Subscription,
            updatedAt: Long = System.currentTimeMillis(),
            dirty: Int = 1
        ): SubscriptionEntity = SubscriptionEntity(
            id = s.id,
            name = s.name,
            amount = s.amount,
            frequency = s.frequency.name,
            anchorEpochDay = s.anchorEpochDay,
            account = s.account,
            category = s.category,
            remind = s.remind,
            keywords = s.keywords,
            active = s.active,
            createdAt = s.createdAt,
            updatedAt = updatedAt,
            dirty = dirty
        )
    }
}
