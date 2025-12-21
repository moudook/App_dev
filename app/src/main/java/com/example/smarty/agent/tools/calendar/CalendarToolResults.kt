package com.example.smarty.agent.tools.calendar

import com.example.smarty.util.toon.ToonManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = false }

/**
 * Result of calendar event operations.
 * Output in TOON format for reduced token usage.
 */
@Serializable
data class CalendarOperationResult(
    val success: Boolean,
    val eventId: String? = null,
    val eventTitle: String? = null,
    val message: String? = null,
    val error: String? = null
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Result of timer/alarm operations.
 * Output in TOON format for reduced token usage.
 */
@Serializable
data class TimerOperationResult(
    val success: Boolean,
    val timerId: String? = null,
    val timerName: String? = null,
    val scheduledFor: String? = null,  // Human-readable time
    val message: String? = null,
    val error: String? = null
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}
