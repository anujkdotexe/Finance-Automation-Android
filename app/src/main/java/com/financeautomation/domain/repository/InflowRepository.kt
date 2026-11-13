package com.financeautomation.domain.repository

import com.financeautomation.domain.model.Inflow
import kotlinx.coroutines.flow.Flow

interface InflowRepository {
    fun getInflowsByRange(start: Long, end: Long): Flow<List<Inflow>>
    fun getAllInflows(): Flow<List<Inflow>>
    suspend fun getInflowById(id: String): Inflow?
    suspend fun insertInflow(inflow: Inflow)
    suspend fun updateStatus(id: String, status: String)
}

