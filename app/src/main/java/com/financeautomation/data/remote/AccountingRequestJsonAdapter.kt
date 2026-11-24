package com.financeautomation.data.remote

import com.financeautomation.domain.model.CarryForwardRequest
import com.financeautomation.domain.model.LogCreditRequest
import com.financeautomation.domain.model.LogPayableRequest
import com.financeautomation.domain.model.VoidInflowRequest
import com.financeautomation.domain.model.VoidExpenseRequest
import com.financeautomation.domain.model.LogRepaymentRequest
import com.financeautomation.domain.model.YearCloseRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Robust JSON serialization adapter for accounting request models stored in sync_queue.payloadJson.
 * Pure Kotlin with zero Android framework dependencies, ensuring deterministic execution in JVM tests and runtime.
 */
object AccountingRequestJsonAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    fun creditRequestToJson(req: LogCreditRequest): String {
        return buildJsonObject {
            put("requestId", JsonPrimitive(req.requestId))
            put("settler", JsonPrimitive(req.settler))
            put("amount", JsonPrimitive(req.amount))
            put("explicitRefs", buildJsonArray {
                req.explicitRefs.forEach { add(JsonPrimitive(it)) }
            })
            put("account", JsonPrimitive(req.account))
            put("inflowType", JsonPrimitive(req.inflowType))
            put("reason", JsonPrimitive(req.reason))
            put("timestamp", JsonPrimitive(req.timestamp))
        }.toString()
    }

    fun creditRequestFromJson(jsonStr: String): LogCreditRequest {
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        val refs = obj["explicitRefs"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        return LogCreditRequest(
            requestId = obj["requestId"]?.jsonPrimitive?.content.orEmpty(),
            settler = obj["settler"]?.jsonPrimitive?.content.orEmpty(),
            amount = obj["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            explicitRefs = refs,
            account = obj["account"]?.jsonPrimitive?.content.orEmpty(),
            inflowType = obj["inflowType"]?.jsonPrimitive?.content.orEmpty(),
            reason = obj["reason"]?.jsonPrimitive?.content.orEmpty(),
            timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
        )
    }

    fun voidInflowRequestToJson(req: VoidInflowRequest): String {
        return buildJsonObject {
            put("inflowId", JsonPrimitive(req.inflowId))
            put("timestamp", JsonPrimitive(req.timestamp))
            put("deleteSheetRow", JsonPrimitive(req.deleteSheetRow))
        }.toString()
    }

    fun voidInflowRequestFromJson(jsonStr: String): VoidInflowRequest {
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        return VoidInflowRequest(
            inflowId = obj["inflowId"]?.jsonPrimitive?.content.orEmpty(),
            timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
            deleteSheetRow = obj["deleteSheetRow"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    }

    fun voidExpenseRequestToJson(req: VoidExpenseRequest): String {
        return buildJsonObject {
            put("id", JsonPrimitive(req.id))
            put("deleteSheetRow", JsonPrimitive(req.deleteSheetRow))
        }.toString()
    }

    fun voidExpenseRequestFromJson(payload: String): VoidExpenseRequest {
        val trimmed = payload.trim()
        if (trimmed.startsWith("{")) {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            return VoidExpenseRequest(
                id = obj["id"]?.jsonPrimitive?.content.orEmpty(),
                deleteSheetRow = obj["deleteSheetRow"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }
        return VoidExpenseRequest(
            id = trimmed,
            deleteSheetRow = false
        )
    }

    fun payableRequestToJson(req: LogPayableRequest): String {
        return buildJsonObject {
            put("requestId", JsonPrimitive(req.requestId))
            put("creditor", JsonPrimitive(req.creditor))
            put("amount", JsonPrimitive(req.amount))
            put("whatFor", JsonPrimitive(req.whatFor))
            put("timestamp", JsonPrimitive(req.timestamp))
            put("category", JsonPrimitive(req.category))
            req.transactionId?.let { put("transactionId", JsonPrimitive(it)) }
        }.toString()
    }

    fun payableRequestFromJson(jsonStr: String): LogPayableRequest {
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        return LogPayableRequest(
            requestId = obj["requestId"]?.jsonPrimitive?.content.orEmpty(),
            creditor = obj["creditor"]?.jsonPrimitive?.content.orEmpty(),
            amount = obj["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            whatFor = obj["whatFor"]?.jsonPrimitive?.content.orEmpty(),
            timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
            category = obj["category"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: com.financeautomation.core.constants.CategoryConstants.CATEGORY_OTHER,
            transactionId = obj["transactionId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        )
    }

    fun repaymentRequestToJson(req: LogRepaymentRequest): String {
        return buildJsonObject {
            put("requestId", JsonPrimitive(req.requestId))
            put("creditor", JsonPrimitive(req.creditor))
            put("amount", JsonPrimitive(req.amount))
            put("explicitRef", req.explicitRef?.let { JsonPrimitive(it) } ?: JsonNull)
            put("timestamp", JsonPrimitive(req.timestamp))
            put("account", req.account?.let { JsonPrimitive(it) } ?: JsonNull)
            put("note", req.note?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString()
    }

    fun repaymentRequestFromJson(jsonStr: String): LogRepaymentRequest {
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        val ref = obj["explicitRef"]?.jsonPrimitive?.takeIf { it !is JsonNull }?.content
        val account = obj["account"]?.jsonPrimitive?.takeIf { it !is JsonNull }?.content
        val note = obj["note"]?.jsonPrimitive?.takeIf { it !is JsonNull }?.content
        return LogRepaymentRequest(
            requestId = obj["requestId"]?.jsonPrimitive?.content.orEmpty(),
            creditor = obj["creditor"]?.jsonPrimitive?.content.orEmpty(),
            amount = obj["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            explicitRef = ref,
            timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
            account = account,
            note = note
        )
    }

    fun carryForwardRequestToJson(req: CarryForwardRequest): String {
        return buildJsonObject {
            put("targetTab", JsonPrimitive(req.targetTab))
            put("sourceTab", req.sourceTab?.let { JsonPrimitive(it) } ?: JsonNull)
            put("timestamp", JsonPrimitive(req.timestamp))
            put("accounts", buildJsonArray { req.accounts.forEach { add(JsonPrimitive(it)) } })
            put("categories", buildJsonArray { req.categories.forEach { add(JsonPrimitive(it)) } })
        }.toString()
    }

    fun carryForwardRequestFromJson(jsonStr: String): CarryForwardRequest {
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        val source = obj["sourceTab"]?.jsonPrimitive?.takeIf { it !is JsonNull }?.content
        val accounts = obj["accounts"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
        val categories = obj["categories"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
        return CarryForwardRequest(
            targetTab = obj["targetTab"]?.jsonPrimitive?.content.orEmpty(),
            sourceTab = source,
            timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
            accounts = accounts,
            categories = categories
        )
    }

    fun yearCloseRequestToJson(req: YearCloseRequest): String {
        return buildJsonObject {
            put("year", JsonPrimitive(req.year))
            put("timestamp", JsonPrimitive(req.timestamp))
        }.toString()
    }

    fun yearCloseRequestFromJson(jsonStr: String): YearCloseRequest {
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        return YearCloseRequest(
            year = obj["year"]?.jsonPrimitive?.intOrNull ?: 0,
            timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
        )
    }

    fun restoreInflowRequestToJson(req: com.financeautomation.domain.model.RestoreInflowRequest): String {
        return buildJsonObject {
            put("inflowId", JsonPrimitive(req.inflowId))
            put("timestamp", JsonPrimitive(req.timestamp))
        }.toString()
    }

    fun restoreInflowRequestFromJson(jsonStr: String): com.financeautomation.domain.model.RestoreInflowRequest {
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        return com.financeautomation.domain.model.RestoreInflowRequest(
            inflowId = obj["inflowId"]?.jsonPrimitive?.content.orEmpty(),
            timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
        )
    }

    fun restorePayableRequestToJson(req: com.financeautomation.domain.model.RestorePayableRequest): String {
        return buildJsonObject {
            put("payableUuid", JsonPrimitive(req.payableUuid))
            put("tabName", JsonPrimitive(req.tabName))
            put("year", JsonPrimitive(req.year))
        }.toString()
    }

    fun restorePayableRequestFromJson(jsonStr: String): com.financeautomation.domain.model.RestorePayableRequest {
        val obj = json.parseToJsonElement(jsonStr).jsonObject
        return com.financeautomation.domain.model.RestorePayableRequest(
            payableUuid = obj["payableUuid"]?.jsonPrimitive?.content.orEmpty(),
            tabName = obj["tabName"]?.jsonPrimitive?.content.orEmpty(),
            year = obj["year"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }
}
