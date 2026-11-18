package com.financeautomation.domain.repository

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.AppConfigSnapshot

interface LegacyConfigGateway {
    suspend fun readLegacyConfig(spreadsheetId: String): Result<AppConfigSnapshot>
}
