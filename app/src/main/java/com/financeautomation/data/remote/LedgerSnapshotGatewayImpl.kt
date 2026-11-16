package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.LedgerSnapshot
import com.financeautomation.domain.repository.LedgerSnapshotGateway

class LedgerSnapshotGatewayImpl(
    private val googleSheetsClient: GoogleSheetsClient
) : LedgerSnapshotGateway {
    override suspend fun readLedgerSnapshot(spreadsheetId: String): Result<LedgerSnapshot> {
        return when (val res = googleSheetsClient.readLedgerSnapshot(spreadsheetId)) {
            is Result.Success -> Result.Success(LedgerSnapshotMapper.mapToDomain(res.data))
            is Result.Error -> Result.Error(res.throwable)
            is Result.Loading -> Result.Loading
        }
    }
}
