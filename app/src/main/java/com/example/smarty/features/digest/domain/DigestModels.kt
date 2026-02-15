package com.example.smarty.features.digest.domain

import kotlinx.serialization.Serializable

/**
 * Data models for the Digest system.
 * Matches the server-side DigestService models.
 */

@Serializable
data class DigestResult(
    val id: String,
    val userId: String,
    val digestDate: String,
    val digestType: String,  // "daily" or "weekly"
    val summary: String,
    val keyInsights: List<String> = emptyList(),
    val goalsProgress: List<GoalProgress> = emptyList(),
    val priorities: List<String> = emptyList(),
    val criticalInfo: String? = null,
    val notesAnalyzed: Int = 0,
    val chatsAnalyzed: Int = 0,
    val memoriesAnalyzed: Int = 0
)

@Serializable
data class GoalProgress(
    val goal: String,
    val status: String,  // "on-track", "at-risk", "completed"
    val updates: List<String> = emptyList()
)

@Serializable
data class DigestPreferences(
    val dailyEnabled: Boolean = true,
    val dailyTime: String = "07:00",
    val weeklyEnabled: Boolean = true,
    val weeklyDay: Int = 0,  // 0=Sunday, 1=Monday, etc.
    val weeklyTime: String = "08:00",
    val pushNotification: Boolean = true,
    val calendarLogging: Boolean = true
)

@Serializable
data class DigestListResponse(
    val digests: List<DigestResult>
)

@Serializable
data class TriggerDigestRequest(
    val type: String? = null  // "daily" or "weekly"
)

@Serializable
data class TriggerDigestResponse(
    val success: Boolean,
    val digest: DigestResult? = null,
    val message: String? = null
)

@Serializable
data class UpdatePreferencesRequest(
    val dailyEnabled: Boolean? = null,
    val dailyTime: String? = null,
    val weeklyEnabled: Boolean? = null,
    val weeklyDay: Int? = null,
    val weeklyTime: String? = null,
    val pushNotification: Boolean? = null,
    val calendarLogging: Boolean? = null
)
