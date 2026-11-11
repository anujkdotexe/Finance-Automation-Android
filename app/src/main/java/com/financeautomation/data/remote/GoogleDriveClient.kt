package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Direct OkHttp client for Google Drive v3 REST API.
 * Interacts with Google Drive to locate or provision folders and monthly workbooks.
 */
class GoogleDriveClient(
    private val okHttpClient: OkHttpClient,
    private val tokenProvider: TokenProvider? = null,
    private val baseUrl: String = "https://www.googleapis.com/drive/v3"
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun newRequestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        tokenProvider?.getAccessToken()?.let { token ->
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    /**
     * Searches for a folder with [folderName] or creates it under Drive root if not found.
     * Returns the Drive folder ID on success.
     */
    suspend fun findOrCreateFolder(folderName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val escapedFolderName = folderName.replace("'", "\\'")
            val query = "(appProperties has { key='app' and value='FinanceAutomation' } or name = '$escapedFolderName') and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val searchUrl = "$baseUrl/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "files(id, name)")
                .addQueryParameter("spaces", "drive")
                .build()

            val searchRequest = newRequestBuilder(searchUrl.toString()).get().build()
            okHttpClient.newCall(searchRequest).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiError = GoogleApiError.parse("drive.files.list", response.code, bodyString)
                    return@withContext Result.Error(
                        GoogleDriveApiException(response.code, apiError.formatExceptionMessage("Failed to query folder '$folderName'"), apiError)
                    )
                }

                val jsonResponse = json.parseToJsonElement(bodyString).jsonObject
                val files = jsonResponse["files"]?.jsonArray
                if (!files.isNullOrEmpty()) {
                    val folderId = files[0].jsonObject["id"]?.jsonPrimitive?.content
                    if (!folderId.isNullOrBlank()) {
                        return@withContext Result.Success(folderId)
                    }
                }
            }

            // Folder does not exist, create it tagged with appProperties for reinstall recovery
            val createUrl = "$baseUrl/files"
            val requestBodyJson = """
                {
                    "name": "$folderName",
                    "mimeType": "application/vnd.google-apps.folder",
                    "appProperties": {
                        "app": "FinanceAutomation"
                    }
                }
            """.trimIndent()

            val createRequest = newRequestBuilder(createUrl)
                .post(requestBodyJson.toRequestBody(jsonMediaType))
                .build()

            okHttpClient.newCall(createRequest).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiError = GoogleApiError.parse("drive.files.create", response.code, bodyString)
                    return@withContext Result.Error(
                        GoogleDriveApiException(response.code, apiError.formatExceptionMessage("Failed to create folder '$folderName'"), apiError)
                    )
                }

                val createdJson = json.parseToJsonElement(bodyString).jsonObject
                val createdId = createdJson["id"]?.jsonPrimitive?.content
                if (!createdId.isNullOrBlank()) {
                    Result.Success(createdId)
                } else {
                    Result.Error(IOException("Drive API returned empty folder id for '$folderName'"))
                }
            }
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Searches for a spreadsheet named [spreadsheetName] inside parent [folderId].
     * If not found, creates a new spreadsheet inside the folder with appProperties marker.
     * Returns the spreadsheet file ID on success.
     */
    suspend fun findOrCreateSpreadsheet(
        folderId: String,
        spreadsheetName: String,
        year: Int? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // First check if a workbook with appProperties already exists in this folder
            val resolvedYear = year ?: Regex("""\d{4}""").find(spreadsheetName)?.value?.toIntOrNull()
            val propertyQuery = if (resolvedYear != null) {
                "appProperties has { key='app' and value='FinanceAutomation' } and appProperties has { key='fiscalYear' and value='$resolvedYear' } and '$folderId' in parents and mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false"
            } else {
                val escapedSpreadsheetName = spreadsheetName.replace("'", "\\'")
                "name = '$escapedSpreadsheetName' and '$folderId' in parents and mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false"
            }

            val searchUrl = "$baseUrl/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", propertyQuery)
                .addQueryParameter("fields", "files(id, name)")
                .addQueryParameter("spaces", "drive")
                .build()

            val searchRequest = newRequestBuilder(searchUrl.toString()).get().build()
            okHttpClient.newCall(searchRequest).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val jsonResponse = json.parseToJsonElement(bodyString).jsonObject
                    val files = jsonResponse["files"]?.jsonArray
                    if (!files.isNullOrEmpty()) {
                        val fileId = files[0].jsonObject["id"]?.jsonPrimitive?.content
                        if (!fileId.isNullOrBlank()) {
                            return@withContext Result.Success(fileId)
                        }
                    }
                }
            }

            // Fallback: search by exact name if property search returned empty
            if (resolvedYear != null) {
                val escapedSpreadsheetName = spreadsheetName.replace("'", "\\'")
                val nameQuery = "name = '$escapedSpreadsheetName' and '$folderId' in parents and mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false"
                val nameSearchUrl = "$baseUrl/files".toHttpUrl().newBuilder()
                    .addQueryParameter("q", nameQuery)
                    .addQueryParameter("fields", "files(id, name)")
                    .addQueryParameter("spaces", "drive")
                    .build()

                val nameRequest = newRequestBuilder(nameSearchUrl.toString()).get().build()
                okHttpClient.newCall(nameRequest).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val jsonResponse = json.parseToJsonElement(bodyString).jsonObject
                        val files = jsonResponse["files"]?.jsonArray
                        if (!files.isNullOrEmpty()) {
                            val fileId = files[0].jsonObject["id"]?.jsonPrimitive?.content
                            if (!fileId.isNullOrBlank()) {
                                return@withContext Result.Success(fileId)
                            }
                        }
                    }
                }
            }

            // Spreadsheet does not exist, create it with parent folder ID and appProperties
            val createUrl = "$baseUrl/files"
            val fiscalYearValue = resolvedYear?.toString() ?: ""
            val requestBodyJson = """
                {
                    "name": "$spreadsheetName",
                    "mimeType": "application/vnd.google-apps.spreadsheet",
                    "parents": ["$folderId"],
                    "appProperties": {
                        "app": "FinanceAutomation",
                        "fiscalYear": "$fiscalYearValue"
                    }
                }
            """.trimIndent()

            val createRequest = newRequestBuilder(createUrl)
                .post(requestBodyJson.toRequestBody(jsonMediaType))
                .build()

            okHttpClient.newCall(createRequest).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiError = GoogleApiError.parse("drive.files.create", response.code, bodyString)
                    return@withContext Result.Error(
                        GoogleDriveApiException(response.code, apiError.formatExceptionMessage("Failed to create spreadsheet '$spreadsheetName'"), apiError)
                    )
                }

                val createdJson = json.parseToJsonElement(bodyString).jsonObject
                val createdId = createdJson["id"]?.jsonPrimitive?.content
                if (!createdId.isNullOrBlank()) {
                    Result.Success(createdId)
                } else {
                    Result.Error(IOException("Drive API returned empty spreadsheet id for '$spreadsheetName'"))
                }
            }
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Searches Google Drive for an existing workbook marked with appProperties for [year].
     * Returns file ID if found, or null if no matching workbook exists.
     */
    suspend fun findWorkbookForYear(year: Int): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val query = "appProperties has { key='app' and value='FinanceAutomation' } and appProperties has { key='fiscalYear' and value='$year' } and mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false"
            val searchUrl = "$baseUrl/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "files(id, name)")
                .addQueryParameter("spaces", "drive")
                .build()

            val searchRequest = newRequestBuilder(searchUrl.toString()).get().build()
            okHttpClient.newCall(searchRequest).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiError = GoogleApiError.parse("drive.files.list", response.code, bodyString)
                    return@withContext Result.Error(
                        GoogleDriveApiException(response.code, apiError.formatExceptionMessage("Failed to find workbook for year $year"), apiError)
                    )
                }

                val jsonResponse = json.parseToJsonElement(bodyString).jsonObject
                val files = jsonResponse["files"]?.jsonArray
                if (!files.isNullOrEmpty()) {
                    val fileId = files[0].jsonObject["id"]?.jsonPrimitive?.content
                    if (!fileId.isNullOrBlank()) {
                        return@withContext Result.Success(fileId)
                    }
                }
            }

            // Fallback search by standard name FinanceYYYY
            searchSpreadsheet("Finance$year")
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Searches Google Drive for an existing spreadsheet named [spreadsheetName] across all user spaces.
     * Returns the file ID if found, or null if no matching spreadsheet exists.
     */
    suspend fun searchSpreadsheet(spreadsheetName: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val escapedName = spreadsheetName.replace("'", "\\'")
            val query = "name = '$escapedName' and mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false"
            val searchUrl = "$baseUrl/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "files(id, name)")
                .addQueryParameter("spaces", "drive")
                .build()

            val searchRequest = newRequestBuilder(searchUrl.toString()).get().build()
            okHttpClient.newCall(searchRequest).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiError = GoogleApiError.parse("drive.files.list", response.code, bodyString)
                    return@withContext Result.Error(
                        GoogleDriveApiException(response.code, apiError.formatExceptionMessage("Failed to search spreadsheet '$spreadsheetName'"), apiError)
                    )
                }

                val jsonResponse = json.parseToJsonElement(bodyString).jsonObject
                val files = jsonResponse["files"]?.jsonArray
                if (!files.isNullOrEmpty()) {
                    val fileId = files[0].jsonObject["id"]?.jsonPrimitive?.content
                    if (!fileId.isNullOrBlank()) {
                        return@withContext Result.Success(fileId)
                    }
                }
                Result.Success(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Uploads or updates a JSON text file in [folderId] with [fileName] and [content].
     * Creates the file if it does not exist, or updates its content if already present.
     */
    suspend fun uploadOrUpdateTextFile(
        folderId: String,
        fileName: String,
        content: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Check if file already exists in folder
            val escapedName = fileName.replace("'", "\\'")
            val query = "name = '$escapedName' and '$folderId' in parents and trashed = false"
            val searchUrl = "$baseUrl/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "files(id, name)")
                .addQueryParameter("spaces", "drive")
                .build()

            val searchRequest = newRequestBuilder(searchUrl.toString()).get().build()
            val existingFileId = okHttpClient.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                val jsonResponse = json.parseToJsonElement(body).jsonObject
                val files = jsonResponse["files"]?.jsonArray
                files?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = content.toRequestBody(mediaType)

            if (!existingFileId.isNullOrBlank()) {
                // Update content of existing file
                val uploadUrl = "https://www.googleapis.com/upload/drive/v3/files/$existingFileId?uploadType=media"
                val patchRequest = newRequestBuilder(uploadUrl)
                    .patch(requestBody)
                    .build()

                okHttpClient.newCall(patchRequest).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val apiError = GoogleApiError.parse("drive.files.update", response.code, bodyString)
                        return@withContext Result.Error(
                            GoogleDriveApiException(response.code, apiError.formatExceptionMessage("Failed to update file"), apiError)
                        )
                    }
                    Result.Success(existingFileId)
                }
            } else {
                // Create new file with metadata
                val metadata = buildJsonObject {
                    put("name", fileName)
                    put("parents", buildJsonArray { add(folderId) })
                    put("mimeType", "application/json")
                }.toString()

                val multipartBody = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addPart(
                        okhttp3.Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                        metadata.toRequestBody("application/json; charset=utf-8".toMediaType())
                    )
                    .addPart(
                        okhttp3.Headers.headersOf("Content-Type", "application/json"),
                        requestBody
                    )
                    .build()

                val createUrl = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
                val postRequest = newRequestBuilder(createUrl)
                    .post(multipartBody)
                    .build()

                okHttpClient.newCall(postRequest).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val apiError = GoogleApiError.parse("drive.files.create", response.code, bodyString)
                        return@withContext Result.Error(
                            GoogleDriveApiException(response.code, apiError.formatExceptionMessage("Failed to create file"), apiError)
                        )
                    }
                    val jsonResponse = json.parseToJsonElement(bodyString).jsonObject
                    val newId = jsonResponse["id"]?.jsonPrimitive?.content.orEmpty()
                    Result.Success(newId)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
