package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.financeautomation.data.local.entity.PreferenceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room Data Access Object for preferences_table.
 */
@Dao
interface PreferenceDao {

    @Query("SELECT value FROM preferences_table WHERE `key` = :key LIMIT 1")
    fun getPreferenceFlow(key: String): Flow<String?>

    @Query("SELECT value FROM preferences_table WHERE `key` = :key LIMIT 1")
    suspend fun getPreference(key: String): String?

    @Query("SELECT * FROM preferences_table")
    suspend fun getAllPreferences(): List<PreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPreference(entity: PreferenceEntity)

    @Query("DELETE FROM preferences_table WHERE `key` = :key")
    suspend fun deletePreference(key: String)

    @Query("DELETE FROM preferences_table WHERE `key` LIKE :prefix || '%'")
    suspend fun deletePreferencesWithPrefix(prefix: String)

    @Query("DELETE FROM preferences_table")
    suspend fun clearAll()

    @Query("SELECT * FROM preferences_table WHERE dirty = 1")
    suspend fun getDirtyPreferences(): List<PreferenceEntity>

    @Query("UPDATE preferences_table SET dirty = 0 WHERE `key` IN (:keys)")
    suspend fun markClean(keys: List<String>)
}
