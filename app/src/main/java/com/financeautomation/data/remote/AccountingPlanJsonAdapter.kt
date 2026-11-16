package com.financeautomation.data.remote

import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.model.NewRow
import com.financeautomation.domain.model.SettledRef
import com.financeautomation.domain.model.TableType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Robust JSON serialization adapter for AccountingPlan using kotlinx.serialization.
 * Used to persist the planned mutations into sync_queue.planJson before executing writes,
 * guaranteeing idempotent retries under process termination and network disconnects.
 * Pure Kotlin with zero Android framework dependencies.
 */
object AccountingPlanJsonAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    private fun Any?.toJsonPrimitive(): JsonPrimitive = when (this) {
        null -> JsonNull
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        else -> JsonPrimitive(this.toString())
    }

    fun toJson(plan: AccountingPlan): String {
        val root = buildJsonObject {
            put("excess", JsonPrimitive(plan.excess))
            put("unmatched", JsonPrimitive(plan.unmatched))
            put("message", JsonPrimitive(plan.message))

            put("edits", buildJsonArray {
                plan.edits.forEach { edit ->
                    add(buildJsonObject {
                        put("tab", JsonPrimitive(edit.tab))
                        put("row", JsonPrimitive(edit.row))
                        put("column", JsonPrimitive(edit.column))
                        put("value", edit.value.toJsonPrimitive())
                    })
                }
            })

            put("appends", buildJsonArray {
                plan.appends.forEach { append ->
                    add(buildJsonObject {
                        put("table", JsonPrimitive(append.table.name))
                        put("tab", JsonPrimitive(append.tab))
                        put("uuid", JsonPrimitive(append.uuid))
                        put("cells", buildJsonArray {
                            append.cells.forEach { c ->
                                add(c.toJsonPrimitive())
                            }
                        })
                    })
                }
            })

            put("settled", buildJsonArray {
                plan.settled.forEach { s ->
                    add(buildJsonObject {
                        put("tab", JsonPrimitive(s.tab))
                        put("row", JsonPrimitive(s.row))
                        put("uuid", s.uuid?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("applied", JsonPrimitive(s.applied))
                        put("remaining", JsonPrimitive(s.remaining))
                    })
                }
            })
        }
        return root.toString()
    }

    fun fromJson(jsonStr: String): AccountingPlan {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val excess = root["excess"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val unmatched = root["unmatched"]?.jsonPrimitive?.booleanOrNull ?: false
        val message = root["message"]?.jsonPrimitive?.content.orEmpty()

        val edits = mutableListOf<CellEdit>()
        root["edits"]?.jsonArray?.forEach { el ->
            val obj = el.jsonObject
            val tab = obj["tab"]?.jsonPrimitive?.content.orEmpty()
            val row = obj["row"]?.jsonPrimitive?.intOrNull ?: 0
            val column = obj["column"]?.jsonPrimitive?.content.orEmpty()
            val valPrim = obj["value"]?.jsonPrimitive
            val rawVal: Any? = when {
                valPrim == null || valPrim is JsonNull -> null
                valPrim.isString -> valPrim.content
                valPrim.booleanOrNull != null -> valPrim.booleanOrNull
                valPrim.doubleOrNull != null -> valPrim.doubleOrNull
                else -> valPrim.content
            }
            edits.add(CellEdit(tab = tab, row = row, column = column, value = rawVal))
        }

        val appends = mutableListOf<NewRow>()
        root["appends"]?.jsonArray?.forEach { el ->
            val obj = el.jsonObject
            val table = TableType.valueOf(obj["table"]?.jsonPrimitive?.content.orEmpty())
            val tab = obj["tab"]?.jsonPrimitive?.content.orEmpty()
            val uuid = obj["uuid"]?.jsonPrimitive?.content.orEmpty()
            val cells = mutableListOf<Any?>()
            obj["cells"]?.jsonArray?.forEach { cEl ->
                val cPrim = cEl.jsonPrimitive
                val number = cPrim.doubleOrNull
                val v: Any? = when {
                    cPrim is JsonNull -> null
                    cPrim.isString -> cPrim.content
                    number != null -> {
                        if (number % 1.0 == 0.0 && number in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                            number.toInt()
                        } else number
                    }
                    cPrim.booleanOrNull != null -> cPrim.booleanOrNull
                    else -> cPrim.content
                }
                cells.add(v)
            }
            appends.add(NewRow(table, tab, uuid, cells))
        }

        val settled = mutableListOf<SettledRef>()
        root["settled"]?.jsonArray?.forEach { el ->
            val obj = el.jsonObject
            val tab = obj["tab"]?.jsonPrimitive?.content.orEmpty()
            val row = obj["row"]?.jsonPrimitive?.intOrNull ?: 0
            val uuid = obj["uuid"]?.jsonPrimitive?.takeIf { it !is JsonNull }?.content
            val applied = obj["applied"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val remaining = obj["remaining"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            settled.add(SettledRef(tab = tab, row = row, uuid = uuid, applied = applied, remaining = remaining))
        }

        return AccountingPlan(
            edits = edits,
            appends = appends,
            settled = settled,
            excess = excess,
            unmatched = unmatched,
            message = message
        )
    }
}
