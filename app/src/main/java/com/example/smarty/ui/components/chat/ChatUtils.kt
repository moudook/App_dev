package com.example.smarty.ui.components.chat

/**
 * Pre-compiled regex patterns for content cleaning.
 * Compiled once at class-load time, reused across all calls.
 */
private object CleaningPatterns {
    val thinkBlock = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    val thinkOpen = Regex("<think>.*", RegexOption.DOT_MATCHES_ALL)
    val partialFinal = Regex("<fi?n?a?l?$")
    val partialThink = Regex("<th?i?n?k?$")
}

/**
 * Strips internal AI markup tags (think/final) from raw message content.
 *
 * Handles both complete tags and partial tags that appear during streaming.
 * Uses pre-compiled regex patterns for performance.
 *
 * @param raw The raw message content potentially containing markup tags
 * @return Cleaned content with all markup tags removed
 */
fun cleanContent(raw: String): String {
    var text = raw
    text = CleaningPatterns.thinkBlock.replace(text, "")
    text = CleaningPatterns.thinkOpen.replace(text, "")
    text = text.replace("<final>", "").replace("</final>", "")

    // Clean up partial tags when streaming
    text = CleaningPatterns.partialFinal.replace(text, "")
    text = CleaningPatterns.partialThink.replace(text, "")

    return text.trim()
}
