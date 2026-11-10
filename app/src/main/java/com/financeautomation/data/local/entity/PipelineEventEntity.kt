package com.financeautomation.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity storing capped pipeline trail events for failure visibility (Phase 9.3b).
 * Strictly numeric metrics and enums; contains zero alert text, financial numbers, or user data.
 */
@Entity(
    tableName = "pipeline_events",
    indices = [Index(value = ["time"])]
)
data class PipelineEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val time: Long,
    val stage: String,
    val code: String,
    val a: Long? = null,
    val b: Long? = null
)

