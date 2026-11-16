package com.financeautomation.domain.usecase

import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.remote.DashboardFormulaBuilder
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PreferencesRepository
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

/**
 * UseCase that dynamically regenerates Dashboard formulas (living burn KPIs A5:C5, D5:F5, V6:V17,
 * and category breakdown table Y5:Z14) and applies data validation rules to Column C and F
 * across all active ledger sheets.
 */
class RegenerateDashboardUseCase(
    private val googleSheetsClient: GoogleSheetsClient,
    private val preferencesRepository: PreferencesRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val okHttpClient: OkHttpClient? = null,
    private val tokenProvider: TokenProvider? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend operator fun invoke(): Result<Unit> = withContext(ioDispatcher) {
        try {
            val spreadsheetId = preferencesRepository.getSpreadsheetIdSync()
            if (spreadsheetId.isNullOrBlank()) {
                return@withContext Result.Error(
                    IllegalArgumentException("Spreadsheet ID is not configured. Please set a valid spreadsheet ID in Settings.")
                )
            }

            val sheetProperties = fetchSheetProperties(spreadsheetId)
                ?: return@withContext Result.Error(
                    IllegalStateException("Failed to fetch sheet properties for spreadsheet $spreadsheetId")
                )
            val dashboardSheetId = sheetProperties["Dashboard"]
                ?: return@withContext Result.Error(
                    IllegalStateException("Dashboard tab not found in spreadsheet $spreadsheetId")
                )

            val accounts = accountRepository.getAllAccounts(includeArchived = false)
            val categories = categoryRepository.getAllCategories(includeArchived = false)

            val requests = mutableListOf<Map<String, Any?>>()

            // 1. Dashboard formulas and Category table (Y5:Z14)
            val dashboardRequests = DashboardFormulaBuilder.buildDashboardFormulaRequests(
                dashboardSheetId = dashboardSheetId,
                categories = categories
            )
            requests.addAll(dashboardRequests)

            if (requests.isEmpty()) {
                return@withContext Result.Success(Unit)
            }

            googleSheetsClient.batchUpdate(spreadsheetId, requests)
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
                    Diagnostics.record("RegenerateDashboardUseCase", "fetchSheetProperties failed: HTTP ${response.code}")
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
            Diagnostics.record("RegenerateDashboardUseCase", "${e.javaClass.simpleName}: fetchSheetProperties failed")
            null
        }
    }
}
