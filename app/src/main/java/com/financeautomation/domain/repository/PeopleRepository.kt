package com.financeautomation.domain.repository

import com.financeautomation.domain.model.Companion
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository interface for managing friends and companions.
 * Pure Kotlin contract with zero Android framework dependencies.
 */
interface PeopleRepository {

    /**
     * Observes all registered companions ordered alphabetically.
     */
    fun getCompanions(): Flow<List<Companion>>

    /**
     * Retrieves all registered companions as a snapshot.
     */
    suspend fun getAllCompanions(): List<Companion>

    /**
     * Retrieves a single companion by unique identifier.
     */
    suspend fun getCompanionById(id: String): Companion?

    /**
     * Retrieves a single companion by name.
     */
    suspend fun getCompanionByName(name: String): Companion?

    /**
     * Inserts or updates a companion.
     */
    suspend fun insertCompanion(companion: Companion)

    /**
     * Updates an existing companion.
     */
    suspend fun updateCompanion(companion: Companion)

    /**
     * Renames an existing companion.
     */
    suspend fun updateCompanionName(id: String, newName: String)

    /**
     * Deletes a companion.
     */
    suspend fun deleteCompanion(companion: Companion)

    /**
     * Deletes a companion by unique identifier.
     */
    suspend fun deleteCompanionById(id: String)

    /**
     * Removes a person from the people list. Their transactions keep the name as text; only the list entry goes, and
     * it stays gone (the people rebuild skips it) until a new transaction names them again.
     * Refuses with a clear reason while the person has an open balance.
     */
    suspend fun removePerson(name: String): com.financeautomation.core.model.Result<Unit> =
        com.financeautomation.core.model.Result.Error(UnsupportedOperationException("removePerson is not supported"))
}
