package com.financeautomation.data.remote

/**
 * Single source of truth for the endpoint ids passed to [GoogleApiError] and [createHttpException].
 * The raw error store decides from this list whether a response body may be kept, so a call site must
 * never use an inline string: an id that is not listed here stores no body.
 */
object GoogleEndpoint {
    // Drive: workbook, folder and file calls
    const val DRIVE_FILES_LIST: String = "drive.files.list"
    const val DRIVE_FILES_CREATE: String = "drive.files.create"
    const val DRIVE_FILES_UPDATE: String = "drive.files.update"
    const val DRIVE_FILES_COPY: String = "drive.files.copy"

    // Sheets: structure and range reads
    const val SHEETS_GET: String = "sheets.get"
    const val SHEETS_BATCH_UPDATE: String = "sheets.batchUpdate"
    const val SHEETS_VALUES_GET: String = "sheets.values.get"
    const val SHEETS_VALUES_BATCH_GET: String = "sheets.values.batchGet"

    // Sheets: calls that write or echo transaction rows (summary line only, never a body)
    const val SHEETS_VALUES_UPDATE: String = "sheets.values.update"
    const val SHEETS_VALUES_BATCH_UPDATE: String = "sheets.values.batchUpdate"
    const val SHEETS_VALUES_APPEND: String = "sheets.values.append"

    /** Default id for a call site that did not name its endpoint. Summary line only. */
    const val SHEETS_OPERATION: String = "sheets.operation"

    /**
     * Provisioning, sheet-structure and range-read calls. A failed response body of these calls may be
     * stored (debug and beta builds only): the error text echoes ranges and sheet names, never row values.
     */
    val BODY_ALLOWED: Set<String> = setOf(
        DRIVE_FILES_LIST,
        DRIVE_FILES_CREATE,
        DRIVE_FILES_UPDATE,
        DRIVE_FILES_COPY,
        SHEETS_GET,
        SHEETS_BATCH_UPDATE,
        SHEETS_VALUES_GET,
        SHEETS_VALUES_BATCH_GET
    )

    /** Calls that carry transaction rows, plus the unnamed default. Only the summary line is kept. */
    val SUMMARY_ONLY: Set<String> = setOf(
        SHEETS_VALUES_UPDATE,
        SHEETS_VALUES_BATCH_UPDATE,
        SHEETS_VALUES_APPEND,
        SHEETS_OPERATION
    )

    val ALL: Set<String> = BODY_ALLOWED + SUMMARY_ONLY
}
