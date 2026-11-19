package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.financeautomation.domain.model.TripTag

/**
 * Room SQLite entity representing a trip or event tag.
 */
@Entity(
    tableName = "trip_tags",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["isActive"]),
        Index(value = ["createdAt"])
    ]
)
data class TripTagEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "isActive")
    val isActive: Boolean = false,

    @ColumnInfo(name = "isArchived")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updatedAt", defaultValue = "0")
    val updatedAt: Long = 0L,

    @ColumnInfo(name = "dirty", defaultValue = "0")
    val dirty: Int = 0,

    @ColumnInfo(name = "startDateEpochDay", defaultValue = "NULL")
    val startDateEpochDay: Long? = null,

    @ColumnInfo(name = "endDateEpochDay", defaultValue = "NULL")
    val endDateEpochDay: Long? = null
) {
    fun toDomain(): TripTag {
        return TripTag(
            id = id,
            name = name,
            isActive = isActive,
            isArchived = isArchived,
            createdAt = createdAt,
            updatedAt = updatedAt,
            startDateEpochDay = startDateEpochDay,
            endDateEpochDay = endDateEpochDay
        )
    }

    companion object {
        fun fromDomain(tripTag: TripTag, updatedAt: Long = System.currentTimeMillis(), dirty: Int = 1): TripTagEntity {
            return TripTagEntity(
                id = tripTag.id,
                name = tripTag.name,
                isActive = tripTag.isActive,
                isArchived = tripTag.isArchived,
                createdAt = tripTag.createdAt,
                updatedAt = updatedAt,
                dirty = dirty,
                startDateEpochDay = tripTag.startDateEpochDay,
                endDateEpochDay = tripTag.endDateEpochDay
            )
        }
    }
}
