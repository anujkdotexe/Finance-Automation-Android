package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.repository.DashboardRemoteGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class DashboardRemoteGatewayImpl(
    private val googleSheetsClient: GoogleSheetsClient,
    private val tokenProvider: TokenProvider? = null,
    private val okHttpClient: OkHttpClient? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : DashboardRemoteGateway {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun regenerateDashboard(
        spreadsheetId: String,
        categories: List<Category>
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val sheetProperties = fetchSheetProperties(spreadsheetId)
                ?: return@withContext Result.Error(
                    IllegalStateException("Failed to fetch sheet properties for spreadsheet ${com.financeautomation.core.utils.PrivacyScrubber.scrub(spreadsheetId)}")
                )
            val dashboardSheetId = sheetProperties["Dashboard"]
                ?: return@withContext Result.Error(
                    IllegalStateException("Dashboard tab not found in spreadsheet ${com.financeautomation.core.utils.PrivacyScrubber.scrub(spreadsheetId)}")
                )

            val resolvedYear = DashboardFormulaBuilder.extractWorkbookYear(sheetProperties.keys)
            val dashboardRequests = DashboardFormulaBuilder.buildDashboardFormulaRequests(
                dashboardSheetId = dashboardSheetId,
                categories = categories,
                year = resolvedYear
            )

            if (dashboardRequests.isEmpty()) {
                return@withContext Result.Success(Unit)
            }

            googleSheetsClient.batchUpdate(spreadsheetId, dashboardRequests)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    private fun fetchSheetProperties(spreadsheetId: String): Map<String, Int>? {
        val client = okHttpClient ?: return null
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId?fields=sheets(properties(sheetId%2Ctitle))"
        val requestBuilder = Request.Builder().url(url)
        val token = tokenProvider?.getAccessToken()
        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Diagnostics.record("DashboardRemoteGatewayImpl", "fetchSheetProperties failed: HTTP ${response.code}")
                    return null
                }
                val bodyString = response.body?.string().orEmpty()
                val parsed = json.parseToJsonElement(bodyString).jsonObject
                val sheets = parsed["sheets"]?.jsonArray.orEmpty()
                val result = mutableMapOf<String, Int>()
                for (sheet in sheets) {
                    val props = sheet.jsonObject["properties"]?.jsonObject ?: continue
                    val title = props["title"]?.jsonPrimitive?.content ?: continue
                    val id = props["sheetId"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                    result[title] = id
                }
                result
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record("DashboardRemoteGatewayImpl", "${e.javaClass.simpleName}: fetchSheetProperties failed")
            null
        }
    }
}
