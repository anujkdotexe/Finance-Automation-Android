package com.financeautomation.core.utils

import com.financeautomation.core.model.Result
import kotlinx.coroutines.CancellationException

/**
 * Executes [block] safely, immediately rethrowing [CancellationException] to preserve
 * structured concurrency, while catching any other [Exception] and returning [Result.Error].
 */
suspend inline fun <T> safeCall(crossinline block: suspend () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.Error(e)
    }
}

