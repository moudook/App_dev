package com.example.smarty.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "timeline_events")
data class TimelineEventEntity(
    @PrimaryKey val eventId: String,
    val traceId: String,
    val timestamp: Long,
    val sessionId: String,
    val eventType: String,     // "ReasoningStarted", "ToolStart", "SubAgentCreated", etc.
    val payloadJson: String,   // Serialized payload of the specific event subclass
    // Hierarchy metadata for nested sub-agent rendering (Migration 44→45)
    val parentId: String? = null,     // parent eventId for sub-agent events (null = top-level)
    val depth: Int = 0,               // nesting depth: 0 = top-level, 1 = sub-agent, 2 = sub-sub-agent
    val sequence: Int = 0,            // ordering within the same parent (monotonically increasing)
    val collapsed: Boolean = false,   // UI collapse state (persisted per session)
)
