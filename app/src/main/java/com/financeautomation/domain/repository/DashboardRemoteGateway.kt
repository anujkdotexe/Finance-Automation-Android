package com.financeautomation.domain.repository

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Category

interface DashboardRemoteGateway {
    suspend fun regenerateDashboard(
        spreadsheetId: String,
        categories: List<Category>
    ): Result<Unit>
}
