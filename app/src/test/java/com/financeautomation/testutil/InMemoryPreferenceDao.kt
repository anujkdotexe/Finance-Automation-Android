package com.financeautomation.testutil

import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.entity.PreferenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** An in-memory [PreferenceDao]. */
class InMemoryPreferenceDao : PreferenceDao {
    val rows = MutableStateFlow<Map<String, PreferenceEntity>>(emptyMap())
    override fun getPreferenceFlow(key: String): Flow<String?> = rows.map { it[key]?.value }
    override suspend fun getPreference(key: String): String? = rows.value[key]?.value
    override suspend fun getAllPreferences(): List<PreferenceEntity> = rows.value.values.toList()
    override suspend fun setPreference(entity: PreferenceEntity) { rows.value = rows.value + (entity.key to entity) }
    override suspend fun deletePreference(key: String) { rows.value = rows.value - key }
    override suspend fun clearAll() { rows.value = emptyMap() }
    override suspend fun getDirtyPreferences(): List<PreferenceEntity> = rows.value.values.filter { it.dirty == 1 }
    override suspend fun markClean(keys: List<String>) {}
}
