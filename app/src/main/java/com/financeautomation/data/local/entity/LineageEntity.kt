package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.financeautomation.domain.model.LineageRecord

/**
 * Room SQLite entity tracking carried-forward debt and payable lineage across monthly tabs.
 */
@Entity(tableName = "lineage")
data class LineageEntity(
    @PrimaryKey
    @ColumnInfo(name = "uuid")
    val uuid: String,

    @ColumnInfo(name = "carried_from_uuid")
    val carriedFromUuid: String,

    @ColumnInfo(name = "root_uuid")
    val rootUuid: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
) {
    fun toDomain(): LineageRecord {
        return LineageRecord(
            uuid = uuid,
            carriedFromUuid = carriedFromUuid,
            rootUuid = rootUuid,
            createdAt = createdAt
        )
    }

    companion object {
        fun fromDomain(record: LineageRecord): LineageEntity {
            return LineageEntity(
                uuid = record.uuid,
                carriedFromUuid = record.carriedFromUuid,
                rootUuid = record.rootUuid,
                createdAt = record.createdAt
            )
        }
    }
}

