package com.example.smarty.viewmodel.managers

import kotlinx.serialization.Serializable

@Serializable
data class CategoryStat(
    val name: String,
    val totalCount: Int,
    val recentCount: Int
)

@Serializable
data class ActivityTrend(
    val notesThisWeek: Int,
    val mostActiveDay: String?
)

@Serializable
data class UserPatternsReport(
    val summary: String,
    val topCategories: List<CategoryStat>,
    val activityTrend: ActivityTrend,
    val frequentTopics: List<String>,
    val suggestions: List<String>
)

@Serializable
data class LearningReport(
    val notesAnalyzed: Int,
    val newInsightsFound: Int,
    val insights: List<String>
)
