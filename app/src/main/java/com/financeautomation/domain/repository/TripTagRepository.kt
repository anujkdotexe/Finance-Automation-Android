package com.financeautomation.domain.repository

import com.financeautomation.domain.model.TripTag
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository interface for managing trip and event tags.
 * Pure Kotlin contract with zero Android framework dependencies.
 */
interface TripTagRepository {

    /**
     * Observes trip tags ordered by creation date descending.
     */
    fun getTripTags(includeArchived: Boolean = false): Flow<List<TripTag>>

    /**
     * Retrieves all trip tags as a snapshot.
     */
    suspend fun getAllTripTags(includeArchived: Boolean = false): List<TripTag>

    /**
     * Retrieves active trip tags as a snapshot.
     */
    suspend fun getActiveTripTags(): List<TripTag>

    /**
     * Observes the currently active trip tag, if any.
     */
    fun getActiveTag(): Flow<TripTag?>

    /**
     * Retrieves the currently active trip tag as a snapshot.
     */
    suspend fun getActiveTagSync(): TripTag?

    /**
     * Retrieves a trip tag by unique identifier.
     */
    suspend fun getTagById(id: String): TripTag?

    /**
     * Retrieves a trip tag by name.
     */
    suspend fun getTagByName(name: String): TripTag?

    /**
     * Inserts a new trip tag by name.
     */
    suspend fun insertTripTag(name: String): TripTag

    /**
     * Updates an existing trip tag.
     */
    suspend fun updateTripTag(tripTag: TripTag)

    /**
     * Sets a trip tag as active, clearing active status from all others.
     * Passing null clears active status from all tags.
     */
    suspend fun setActiveTag(id: String?)

    /**
     * Archives a trip tag.
     */
    suspend fun archiveTripTag(id: String)

    /**
     * Restores an archived trip tag.
     */
    suspend fun unarchiveTripTag(id: String)

    /**
     * Permanently deletes a trip tag by identifier.
     */
    suspend fun deleteTripTag(id: String)

    /**
     * Removes a trip from the list. Transactions keep the trip name as text. Refuses while the trip is the active one.
     */
    suspend fun removeTripTag(name: String): com.financeautomation.core.model.Result<Unit> =
        com.financeautomation.core.model.Result.Error(UnsupportedOperationException("removeTripTag is not supported"))
}
