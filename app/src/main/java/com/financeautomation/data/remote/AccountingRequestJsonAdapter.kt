package com.financeautomation.data.remote

import com.financeautomation.domain.model.CarryForwardRequest
import com.financeautomation.domain.model.LogCreditRequest
import com.financeautomation.domain.model.LogPayableRequest
import com.financeautomation.domain.model.LogRepaymentRequest
import com.financeautomation.domain.model.YearCloseRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON serialization adapter for accounting request models stored in sync_queue.payloadJson.
 */
object AccountingRequestJsonAdapter {

    fun creditRequestToJson(req: LogCreditRequest): String {
        val obj = JSONObject()
        obj.put("requestId", req.requestId)
        obj.put("settler", req.settler)
        obj.put("amount", req.amount)
        val refsArr = JSONArray()
        req.explicitRefs.forEach { refsArr.put(it) }
        obj.put("explicitRefs", refsArr)
        obj.put("account", req.account)
        obj.put("inflowType", req.inflowType)
        obj.put("reason", req.reason)
        obj.put("timestamp", req.timestamp)
        return obj.toString()
    }

    fun creditRequestFromJson(jsonStr: String): LogCreditRequest {
        val obj = JSONObject(jsonStr)
        val refsArr = obj.optJSONArray("explicitRefs")
        val explicitRefs = mutableListOf<String>()
        if (refsArr != null) {
            for (i in 0 until refsArr.length()) {
                explicitRefs.add(refsArr.getString(i))
            }
        }
        return LogCreditRequest(
            requestId = obj.getString("requestId"),
            settler = obj.getString("settler"),
            amount = obj.getDouble("amount"),
            explicitRefs = explicitRefs,
            account = obj.getString("account"),
            inflowType = obj.getString("inflowType"),
            reason = obj.getString("reason"),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
        )
    }

    fun payableRequestToJson(req: LogPayableRequest): String {
        val obj = JSONObject()
        obj.put("requestId", req.requestId)
        obj.put("creditor", req.creditor)
        obj.put("amount", req.amount)
        obj.put("whatFor", req.whatFor)
        obj.put("timestamp", req.timestamp)
        return obj.toString()
    }

    fun payableRequestFromJson(jsonStr: String): LogPayableRequest {
        val obj = JSONObject(jsonStr)
        return LogPayableRequest(
            requestId = obj.getString("requestId"),
            creditor = obj.getString("creditor"),
            amount = obj.getDouble("amount"),
            whatFor = obj.getString("whatFor"),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
        )
    }

    fun repaymentRequestToJson(req: LogRepaymentRequest): String {
        val obj = JSONObject()
        obj.put("requestId", req.requestId)
        obj.put("creditor", req.creditor)
        obj.put("amount", req.amount)
        obj.put("explicitRef", req.explicitRef ?: JSONObject.NULL)
        obj.put("timestamp", req.timestamp)
        return obj.toString()
    }

    fun repaymentRequestFromJson(jsonStr: String): LogRepaymentRequest {
        val obj = JSONObject(jsonStr)
        return LogRepaymentRequest(
            requestId = obj.getString("requestId"),
            creditor = obj.getString("creditor"),
            amount = obj.getDouble("amount"),
            explicitRef = if (obj.isNull("explicitRef")) null else obj.getString("explicitRef"),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
        )
    }

    fun carryForwardRequestToJson(req: CarryForwardRequest): String {
        val obj = JSONObject()
        obj.put("targetTab", req.targetTab)
        obj.put("sourceTab", req.sourceTab ?: JSONObject.NULL)
        obj.put("timestamp", req.timestamp)
        return obj.toString()
    }

    fun carryForwardRequestFromJson(jsonStr: String): CarryForwardRequest {
        val obj = JSONObject(jsonStr)
        return CarryForwardRequest(
            targetTab = obj.getString("targetTab"),
            sourceTab = if (obj.isNull("sourceTab")) null else obj.getString("sourceTab"),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
        )
    }

    fun yearCloseRequestToJson(req: YearCloseRequest): String {
        val obj = JSONObject()
        obj.put("year", req.year)
        obj.put("timestamp", req.timestamp)
        return obj.toString()
    }

    fun yearCloseRequestFromJson(jsonStr: String): YearCloseRequest {
        val obj = JSONObject(jsonStr)
        return YearCloseRequest(
            year = obj.getInt("year"),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
        )
    }
}

