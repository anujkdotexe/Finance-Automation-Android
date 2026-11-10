package com.financeautomation.core.model

/**
 * Generic result wrapper representing an operation status.
 */
sealed interface Result<out T> {
    data class Success<out T>(val data: T) : Result<T>

    data class Error(
        val throwable: Throwable,
        val message: String? = throwable.message
    ) : Result<Nothing> {
        val exception: Throwable get() = throwable

        constructor(message: String) : this(Exception(message), message)
    }

    data object Loading : Result<Nothing>
}

/**
 * Sealed domain result type for fine-grained network and data state modeling.
 * Strictly guarantees explicit errors on failures with zero simulated fallbacks.
 */
sealed interface DataResult<out T> {
    data class Success<out T>(val data: T) : DataResult<T>
    data class SheetNotFound(val spreadsheetId: String, val message: String = "Spreadsheet not found: $spreadsheetId") : DataResult<Nothing>
    data class AuthExpired(val message: String = "OAuth token expired or unauthorized") : DataResult<Nothing>
    data class Network(val exception: Throwable) : DataResult<Nothing>
    data class Malformed(val details: String) : DataResult<Nothing>
    data class General(val message: String, val cause: Throwable? = null) : DataResult<Nothing>

    fun toResult(): Result<T> = when (this) {
        is Success -> Result.Success(data)
        is SheetNotFound -> Result.Error(java.io.IOException(message))
        is AuthExpired -> Result.Error(java.io.IOException(message))
        is Network -> Result.Error(exception)
        is Malformed -> Result.Error(java.io.IOException("Malformed response: $details"))
        is General -> Result.Error(cause ?: Exception(message), message)
    }
}
