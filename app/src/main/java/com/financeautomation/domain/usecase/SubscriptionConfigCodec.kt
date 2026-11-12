package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Encodes a subscription as one JSON string for the Value1 column of its Config row.
 * The Config tab has only four value columns, so every field travels in a single JSON object:
 * name, amount ("" when the amount varies), frequency, anchor date, account, category, remind, keywords, active.
 * The anchor date is written as an ISO date (yyyy-MM-dd). Pure Kotlin.
 */
object SubscriptionConfigCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(sub: Subscription): String = buildJsonObject {
        put("name", sub.name)
        put("amount", sub.amount?.let { amountText(it) } ?: "")
        put("frequency", sub.frequency.name)
        put("anchor", java.time.LocalDate.ofEpochDay(sub.anchorEpochDay).toString())
        put("account", sub.account)
        put("category", sub.category)
        put("remind", sub.remind)
        put("keywords", sub.keywords)
        put("active", sub.active)
        put("createdAt", sub.createdAt)
    }.toString()

    /** Decodes the Value1 JSON of a Config row. Returns null when it is unreadable or incomplete. */
    fun decode(id: String, raw: String): Subscription? {
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            val name = obj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
            val amountRaw = obj["amount"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content.orEmpty()
            val anchor = java.time.LocalDate.parse(obj["anchor"]?.jsonPrimitive?.content ?: return null).toEpochDay()
            Subscription(
                id = id,
                name = name,
                amount = amountRaw.toDoubleOrNull(),
                frequency = SubscriptionFrequency.parse(obj["frequency"]?.jsonPrimitive?.content),
                anchorEpochDay = anchor,
                account = obj["account"]?.jsonPrimitive?.content.orEmpty(),
                category = obj["category"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: Subscription.DEFAULT_CATEGORY,
                remind = obj["remind"]?.jsonPrimitive?.booleanOrNull ?: true,
                keywords = obj["keywords"]?.jsonPrimitive?.content.orEmpty(),
                active = obj["active"]?.jsonPrimitive?.booleanOrNull ?: true,
                createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun amountText(amount: Double): String =
        if (amount == Math.floor(amount) && amount < 1e12) amount.toLong().toString() else amount.toString()
}
