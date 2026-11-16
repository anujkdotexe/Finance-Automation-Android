package com.financeautomation.domain.model

class EmptyOrMissingConfigTabException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
