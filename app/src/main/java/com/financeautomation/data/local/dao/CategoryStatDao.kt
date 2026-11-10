package com.financeautomation.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.financeautomation.data.local.entity.CategoryStatEntity

/**
 * Data Access Object for category_stats table (Phase 5.4).
 */
@Dao
interface CategoryStatDao {

    @Query("SELECT * FROM category_stats WHERE payeeKey = :payeeKey ORDER BY count DESC, lastUsed DESC")
    suspend fun getStatsForPayee(payeeKey: String): List<CategoryStatEntity>

    @Query("SELECT category FROM category_stats GROUP BY category ORDER BY SUM(count) DESC, MAX(lastUsed) DESC")
    suspend fun getGlobalCategoryRank(): List<String>

    @Query("SELECT * FROM category_stats WHERE payeeKey = :payeeKey AND category = :category LIMIT 1")
    suspend fun getStat(payeeKey: String, category: String): CategoryStatEntity?

    @Query("INSERT INTO category_stats (payeeKey, category, count, lastUsed) VALUES (:payeeKey, :category, 1, :lastUsed) ON CONFLICT(payeeKey, category) DO UPDATE SET count = count + 1, lastUsed = :lastUsed")
    suspend fun incrementStat(payeeKey: String, category: String, lastUsed: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stat: CategoryStatEntity)

    @Query("SELECT * FROM category_stats ORDER BY count DESC")
    suspend fun getAll(): List<CategoryStatEntity>
}

