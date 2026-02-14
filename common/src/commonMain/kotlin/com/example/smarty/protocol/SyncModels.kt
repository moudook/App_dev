package com.example.smarty.protocol

import kotlinx.serialization.Serializable

@Serializable
data class NoteInfo(
    val id: String,
    val title: String,
    val content: String,
    val category: String?,
    val isArchived: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class TimerInfo(
    val id: String,
    val name: String,
    val durationMs: Long,
    val triggerAt: Long,
    val isAlarm: Boolean,
    val isActive: Boolean,
    val createdAt: Long
)

@Serializable
data class CalendarEventInfo(
    val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val description: String?,
    val reminderMinutes: Int,
    val createdAt: Long
)
