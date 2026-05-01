package com.example.smarty.features.settings.ui

data class PreferenceOption(
    val key: String,
    val label: String,
    val description: String,
)

object PreferenceOptions {
    val aiModes =
        listOf(
            PreferenceOption(
                key = "AUTO",
                label = "Automatic",
                description = "Smarty uses the server default AI automatically for the simplest, most cost-effective setup.",
            ),
        )

    val personalities =
        listOf(
            PreferenceOption("DEFAULT", "Default", "The original Smarty - sharp, warm, and genuinely useful"),
            PreferenceOption("PROFESSIONAL", "Professional", "Formal, precise, and business-like"),
            PreferenceOption("CASUAL", "Casual", "Relaxed, friendly, and conversational"),
            PreferenceOption("CONCISE", "Concise", "Extremely brief, gets to the point fast"),
            PreferenceOption("DETAILED", "Detailed", "Thorough and comprehensive with examples"),
        )

    fun personalityLabel(key: String): String = personalities.firstOrNull { it.key == key }?.label ?: personalities.first().label

    fun aiModeDescription(key: String): String = aiModes.firstOrNull { it.key == key }?.description ?: aiModes.first().description
}
