package com.financeautomation.data.remote

import com.financeautomation.domain.usecase.ExpenseRow
import com.financeautomation.domain.usecase.PendingExpenseEdit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The payload of a queued expense update. It stays on this device. Older builds stored only the entry id there, which
 * reads back as null.
 */
object PendingExpenseEditJsonAdapter {

    fun toJson(entryId: String, edit: PendingExpenseEdit): String = buildJsonObject {
        put("id", JsonPrimitive(entryId))
        put("seen", JsonArray(edit.seen.map { rowToJson(it) }))
        put("want", rowToJson(edit.want))
        put("changed", JsonArray(edit.changed.sorted().map { JsonPrimitive(it) }))
    }.toString()

    fun fromJson(payload: String): PendingExpenseEdit? {
        if (!payload.startsWith("{")) return null
        return runCatching {
            val root = Json.parseToJsonElement(payload).jsonObject
            PendingExpenseEdit(
                seen = root.getValue("seen").jsonArray.map { rowFromJson(it.jsonObject) },
                want = rowFromJson(root.getValue("want").jsonObject),
                changed = root.getValue("changed").jsonArray.mapNotNull { it.jsonPrimitive.intOrNull }.toSet()
            )
        }.getOrNull()
    }

    private fun rowToJson(row: ExpenseRow): JsonObject = buildJsonObject {
        row.dateSerial?.let { put("date", JsonPrimitive(it)) }
        row.timeSerial?.let { put("time", JsonPrimitive(it)) }
        put("account", JsonPrimitive(row.account))
        put("description", JsonPrimitive(row.description))
        put("trip", JsonPrimitive(row.tripTag))
        put("category", JsonPrimitive(row.category))
        put("total", JsonPrimitive(row.total))
        put("companions", JsonPrimitive(row.companions))
        put("share", JsonPrimitive(row.myShare))
        put("owed", JsonPrimitive(row.owed))
        put("status", JsonPrimitive(row.status))
        put("note", JsonPrimitive(row.note))
    }

    private fun rowFromJson(row: JsonObject): ExpenseRow {
        fun text(key: String): String = row.getValue(key).jsonPrimitive.content
        fun number(key: String): Double = requireNotNull(row.getValue(key).jsonPrimitive.doubleOrNull)
        return ExpenseRow(
            dateSerial = row["date"]?.jsonPrimitive?.intOrNull,
            timeSerial = row["time"]?.jsonPrimitive?.doubleOrNull,
            account = text("account"),
            description = text("description"),
            tripTag = text("trip"),
            category = text("category"),
            total = number("total"),
            companions = text("companions"),
            myShare = number("share"),
            owed = number("owed"),
            status = text("status"),
            note = text("note")
        )
    }
}
