package com.financeautomation.domain.repository

import com.financeautomation.core.model.Result

interface LegacyConfigGateway {
    suspend fun readLegacyConfig(spreadsheetId: String): Result<Unit>
}
