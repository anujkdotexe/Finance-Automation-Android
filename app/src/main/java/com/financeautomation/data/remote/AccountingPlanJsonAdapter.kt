package com.financeautomation.data.remote

import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.model.NewRow
import com.financeautomation.domain.model.SettledRef
import com.financeautomation.domain.model.TableType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Robust JSON serialization adapter for AccountingPlan.
 * Used to persist the planned mutations into sync_queue.planJson before executing writes,
 * guaranteeing idempotent retries under process termination and network disconnects.
 */
object AccountingPlanJsonAdapter {

    fun toJson(plan: AccountingPlan): String {
        val root = JSONObject()
        root.put("excess", plan.excess)
        root.put("unmatched", plan.unmatched)
        root.put("message", plan.message)

        val editsArr = JSONArray()
        plan.edits.forEach { edit ->
            val obj = JSONObject()
            obj.put("tab", edit.tab)
            obj.put("row", edit.row)
            obj.put("column", edit.column)
            obj.put("value", edit.value ?: JSONObject.NULL)
            editsArr.put(obj)
        }
        root.put("edits", editsArr)

        val appendsArr = JSONArray()
        plan.appends.forEach { append ->
            val obj = JSONObject()
            obj.put("table", append.table.name)
            obj.put("tab", append.tab)
            obj.put("uuid", append.uuid)
            val cellsArr = JSONArray()
            append.cells.forEach { c ->
                cellsArr.put(c ?: JSONObject.NULL)
            }
            obj.put("cells", cellsArr)
            appendsArr.put(obj)
        }
        root.put("appends", appendsArr)

        val settledArr = JSONArray()
        plan.settled.forEach { s ->
            val obj = JSONObject()
            obj.put("tab", s.tab)
            obj.put("row", s.row)
            obj.put("uuid", s.uuid ?: JSONObject.NULL)
            obj.put("applied", s.applied)
            obj.put("remaining", s.remaining)
            settledArr.put(obj)
        }
        root.put("settled", settledArr)

        return root.toString()
    }

    fun fromJson(jsonStr: String): AccountingPlan {
        val root = JSONObject(jsonStr)
        val excess = root.optDouble("excess", 0.0)
        val unmatched = root.optBoolean("unmatched", false)
        val message = root.optString("message", "")

        val edits = mutableListOf<CellEdit>()
        val editsArr = root.optJSONArray("edits")
        if (editsArr != null) {
            for (i in 0 until editsArr.length()) {
                val obj = editsArr.getJSONObject(i)
                val rawVal = if (obj.isNull("value")) null else obj.get("value")
                edits.add(
                    CellEdit(
                        tab = obj.getString("tab"),
                        row = obj.getInt("row"),
                        column = obj.getString("column"),
                        value = rawVal
                    )
                )
            }
        }

        val appends = mutableListOf<NewRow>()
        val appendsArr = root.optJSONArray("appends")
        if (appendsArr != null) {
            for (i in 0 until appendsArr.length()) {
                val obj = appendsArr.getJSONObject(i)
                val table = TableType.valueOf(obj.getString("table"))
                val tab = obj.getString("tab")
                val uuid = obj.getString("uuid")
                val cellsArr = obj.getJSONArray("cells")
                val cells = mutableListOf<Any?>()
                for (j in 0 until cellsArr.length()) {
                    cells.add(if (cellsArr.isNull(j)) null else cellsArr.get(j))
                }
                appends.add(NewRow(table, tab, uuid, cells))
            }
        }

        val settled = mutableListOf<SettledRef>()
        val settledArr = root.optJSONArray("settled")
        if (settledArr != null) {
            for (i in 0 until settledArr.length()) {
                val obj = settledArr.getJSONObject(i)
                settled.add(
                    SettledRef(
                        tab = obj.getString("tab"),
                        row = obj.getInt("row"),
                        uuid = if (obj.isNull("uuid")) null else obj.getString("uuid"),
                        applied = obj.optDouble("applied", 0.0),
                        remaining = obj.optDouble("remaining", 0.0)
                    )
                )
            }
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

