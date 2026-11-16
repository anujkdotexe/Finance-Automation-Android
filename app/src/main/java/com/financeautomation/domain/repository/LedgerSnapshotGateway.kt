package com.financeautomation.domain.repository

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.LedgerSnapshot

interface LedgerSnapshotGateway {
    suspend fun readLedgerSnapshot(spreadsheetId: String): Result<LedgerSnapshot>
}
