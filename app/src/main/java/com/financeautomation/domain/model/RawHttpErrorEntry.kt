package com.financeautomation.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class RawHttpErrorEntry(
    val timestamp: String,
    val endpointId: String,
    val httpStatus: Int,
    val responseBody: String
)
