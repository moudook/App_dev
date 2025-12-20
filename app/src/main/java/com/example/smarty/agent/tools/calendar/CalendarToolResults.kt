package com.example.smarty.agent.tools.calendar

import kotlinx.serialization.Serializable

/**
 * Result of calendar event operations.
 */
@Serializable
data class CalendarOperationResult(
    val success: Boolean,
    val eventId: String? = null,
    val eventTitle: String? = null,
    val message: String? = null,
    val error: String? = null
)

/**
 * Result of timer/alarm operations.
 */
@Serializable
data class TimerOperationResult(
    val success: Boolean,
    val timerId: String? = null,
    val timerName: String? = null,
    val scheduledFor: String? = null,  // Human-readable time
    val message: String? = null,
    val error: String? = null
)
