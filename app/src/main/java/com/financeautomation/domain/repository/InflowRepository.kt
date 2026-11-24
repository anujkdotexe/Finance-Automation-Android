package com.financeautomation.domain.repository

import com.financeautomation.domain.model.Inflow
import kotlinx.coroutines.flow.Flow

interface InflowRepository {
    fun getInflowsByRange(start: Long, end: Long): Flow<List<Inflow>>
    fun getAllInflows(): Flow<List<Inflow>>
    suspend fun getInflowById(id: String): Inflow?
    suspend fun insertInflow(inflow: Inflow)
    suspend fun updateStatus(id: String, status: String)

    /**
     * Voids an income or refund entry: sets the local status to VOID and queues the sheet update in one transaction.
     * When [deleteSheetRow] is true, clears the row cells on the sheet.
     * Voiding an already void entry succeeds without queuing again; an unknown id returns an error.
     */
    suspend fun voidInflow(id: String): com.financeautomation.core.model.Result<Unit>
    suspend fun voidInflow(id: String, deleteSheetRow: Boolean): com.financeautomation.core.model.Result<Unit> = voidInflow(id)
}

