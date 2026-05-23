package com.example.smarty.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "timeline_events")
data class TimelineEventEntity(
    @PrimaryKey val eventId: String,
    val traceId: String,
    val timestamp: Long,
    val sessionId: String,
    val eventType: String, // "ReasoningStarted", "ModelResolved", etc.
    val payloadJson: String, // Serialized payload of the specific event subclass
)
