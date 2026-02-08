package com.example.smarty.util

import android.util.Log

/**
 * Security filter to sanitize content before sending to AI models
 * Prevents prompt injection, jailbreaking, and other AI manipulation attacks
 */
object ContentSecurityFilter {

    private const val TAG = "ContentSecurityFilter"

    /**
     * Maximum content length to prevent token exhaustion attacks
     */
    private const val MAX_CONTENT_LENGTH = 15000

    /**
     * Patterns that indicate prompt injection attempts
     * These are common techniques used to manipulate AI models
     */
    private val INJECTION_PATTERNS = listOf(
        // Direct instruction overrides
        Regex("""(?i)\bignore\s+(all\s+)?(previous|above|prior)\s+(instructions?|prompts?|rules?|text)\b"""),
        Regex("""(?i)\bforget\s+(all\s+)?(previous|above|prior|everything)\b"""),
        Regex("""(?i)\bdisregard\s+(all\s+)?(previous|above|prior)\s+(instructions?|prompts?|rules?)\b"""),
        Regex("""(?i)\boverride\s+(system|safety|previous)\b"""),

        // Role manipulation
        Regex("""(?i)\byou\s+are\s+(now|actually|really)\s+a?\s*(DAN|jailbroken|unrestricted|evil)\b"""),
        Regex("""(?i)\bact\s+as\s+(if\s+)?(you\s+)?(are|were)\s+a?\s*(DAN|jailbroken|unrestricted)\b"""),
        Regex("""(?i)\bpretend\s+(you\s+)?(are|were|have)\s+(no|without)\s+(restrictions?|rules?|limits?)\b"""),
        Regex("""(?i)\broleplay\s+as\s+a?\s*(evil|malicious|unrestricted)\b"""),
        Regex("""(?i)\b(DAN|Do\s+Anything\s+Now)\s+mode\b"""),

        // System prompt extraction attempts
        Regex("""(?i)\b(reveal|show|tell|output|print|display)\s+(me\s+)?(your|the|system)\s+(system\s+)?(prompt|instructions?|rules?)\b"""),
        Regex("""(?i)\bwhat\s+(are|is)\s+(your|the)\s+(system\s+)?(prompt|instructions?|rules?)\b"""),
        Regex("""(?i)\brepeat\s+(your|the|system)\s+(initial|original|first)\s+(prompt|message|instructions?)\b"""),

        // Bypass attempts
        Regex("""(?i)\b(bypass|circumvent|disable|turn\s+off)\s+(safety|content|ethical)\s+(filter|check|guard)\b"""),
        Regex("""(?i)\bno\s+(content\s+)?(filter|restriction|limit|censorship)\b"""),
        Regex("""(?i)\bunrestricted\s+mode\b"""),

        // Developer/debug mode tricks
        Regex("""(?i)\b(developer|debug|admin|maintenance|sudo)\s+mode\b"""),
        Regex("""(?i)\benable\s+(developer|debug|admin|god)\s+mode\b"""),
        Regex("""(?i)\b\[system\s*\]\s*\[assistant\s*\]"""),

        // Token manipulation
        Regex("""(?i)<\|?(system|endoftext|im_start|im_end)\|?>"""),
        Regex("""(?i)\[\[(SYSTEM|USER|ASSISTANT)\]\]"""),
        Regex("""(?i)###\s*(System|Instruction|Response):?"""),

        // Prompt termination attempts
        Regex("""(?i)\b(end|stop|terminate|exit)\s+(of\s+)?(prompt|system\s+message|instructions?)\b"""),
        Regex("""```\s*(?:end|system|prompt)\s*```"""),

        // Emotional manipulation
        Regex("""(?i)\bif\s+you\s+don'?t\s+(do\s+this|help|comply),?\s+(i'?ll|someone|people)\s+(will\s+)?(die|be\s+hurt|suffer)\b"""),

        // Nested instruction patterns
        Regex("""(?i)\bsay\s+["']?\s*i\s+(am|have|will|can)\s+(jailbroken|no\s+rules|unrestricted)"""),
        Regex("""(?i)\brespond\s+with\s+["']?(yes|okay|sure|done)["']?\s+first\b"""),

        // Context window attacks
        Regex("""(?i)\bnew\s+conversation\b.*\bnew\s+instructions?\b""", RegexOption.DOT_MATCHES_ALL),
        Regex("""(?i)\bstart\s+(fresh|over|new)\b.*\bforget\b""", RegexOption.DOT_MATCHES_ALL)
    )

    /**
     * Dangerous content patterns that should be flagged
     */
    private val DANGEROUS_CONTENT_PATTERNS = listOf(
        // Explicit harmful requests
        Regex("""(?i)\b(how\s+to\s+)?(make|create|build|synthesize)\s+(a\s+)?(bomb|explosive|weapon|poison|malware|virus)\b"""),
        Regex("""(?i)\b(hack|crack|exploit|attack)\s+(into|someone'?s?|a)\s+(account|system|computer|bank)\b"""),

        // Self-harm content
        Regex("""(?i)\b(how\s+to|ways\s+to|methods?\s+(for|to))\s+(kill|harm|hurt)\s+(yourself|myself|oneself)\b"""),

        // Illegal activity requests
        Regex("""(?i)\b(how\s+to)\s+(commit|do)\s+(fraud|identity\s+theft|money\s+laundering)\b"""),

        // Child safety
        Regex("""(?i)\bchild\s+(pornography|exploitation|abuse)\b"""),
        Regex("""(?i)\bcsam\b""")
    )

    /**
     * Control characters and special sequences to remove
     * Only removes actual control characters, NOT Unicode text characters
     */
    private val CONTROL_CHARS_PATTERN = Regex("""[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]""")

    /**
     * Excessive whitespace/newlines (potential token exhaustion)
     */
    private val EXCESSIVE_WHITESPACE_PATTERN = Regex("""\n{5,}""")
    private val EXCESSIVE_SPACES_PATTERN = Regex(""" {10,}""")

    /**
     * Emoji pattern - ONLY matches actual emoji faces and symbols
     * Does NOT touch any language scripts (Hindi, Arabic, Chinese, etc.)
     * Uses surrogate pairs which are in the emoji-specific Unicode ranges
     */
    private val EMOJI_PATTERN = Regex(
        "[" +
        "\uD83D\uDE00-\uD83D\uDE4F" +             // Emoticons (faces)
        "\uD83D\uDE80-\uD83D\uDEFF" +             // Transport, map symbols
        "\uD83E\uDD00-\uD83E\uDDFF" +             // Supplemental symbols (hands, etc.)
        "\u2764" +                                // Heart
        "\u2728" +                                // Sparkles
        "]+"
    )

    /**
     * Result of content security check
     */
    data class SecurityCheckResult(
        val sanitizedContent: String,
        val wasModified: Boolean,
        val detectedIssues: List<String>,
        val riskLevel: RiskLevel
    )

    enum class RiskLevel {
        SAFE,       // No issues detected
        LOW,        // Minor sanitization applied
        MEDIUM,     // Potential injection patterns removed
        HIGH,       // Dangerous content detected
        BLOCKED     // Content should not be processed
    }

    /**
     * Sanitize content before sending to AI model
     * Returns sanitized content and security report
     *
     * @param content The content to sanitize
     * @param removeEmojis Whether to remove emojis (default false, use true for chat)
     */
    fun sanitize(content: String, removeEmojis: Boolean = false): SecurityCheckResult {
        val issues = mutableListOf<String>()
        var sanitized = content
        var riskLevel = RiskLevel.SAFE

        // Step 1: Check for dangerous content (BLOCK if found)
        for (pattern in DANGEROUS_CONTENT_PATTERNS) {
            if (pattern.containsMatchIn(sanitized)) {
                Log.w(TAG, "BLOCKED: Dangerous content pattern detected")
                return SecurityCheckResult(
                    sanitizedContent = "[Content blocked for safety]",
                    wasModified = true,
                    detectedIssues = listOf("Potentially harmful content detected"),
                    riskLevel = RiskLevel.BLOCKED
                )
            }
        }

        // Step 2: Remove control characters (NOT Unicode text - safe for all languages)
        val beforeControlChars = sanitized
        sanitized = sanitized.replace(CONTROL_CHARS_PATTERN, "")
        if (sanitized != beforeControlChars) {
            issues.add("Control characters removed")
            riskLevel = maxOf(riskLevel, RiskLevel.LOW)
        }

        // Step 3: Normalize excessive whitespace
        val beforeWhitespace = sanitized
        sanitized = sanitized.replace(EXCESSIVE_WHITESPACE_PATTERN, "\n\n\n")
        sanitized = sanitized.replace(EXCESSIVE_SPACES_PATTERN, "    ")
        if (sanitized != beforeWhitespace) {
            issues.add("Excessive whitespace normalized")
            riskLevel = maxOf(riskLevel, RiskLevel.LOW)
        }

        // Step 4: Check for and neutralize injection patterns
        for (pattern in INJECTION_PATTERNS) {
            val matches = pattern.findAll(sanitized).toList()
            if (matches.isNotEmpty()) {
                Log.w(TAG, "Injection pattern detected: ${matches.first().value.take(50)}")
                issues.add("Potential prompt injection neutralized")
                riskLevel = maxOf(riskLevel, RiskLevel.MEDIUM)

                // Neutralize by adding visible markers around suspicious content
                sanitized = pattern.replace(sanitized) { match ->
                    "[user_text: ${match.value}]"
                }
            }
        }

        // Step 5: Truncate if too long (prevent token exhaustion)
        if (sanitized.length > MAX_CONTENT_LENGTH) {
            sanitized = sanitized.take(MAX_CONTENT_LENGTH) + "\n[Content truncated for processing]"
            issues.add("Content truncated to maximum length")
            riskLevel = maxOf(riskLevel, RiskLevel.LOW)
        }

        // Step 6: Remove emojis if requested (for cleaner AI chat responses)
        if (removeEmojis) {
            val beforeEmoji = sanitized
            sanitized = sanitized.replace(EMOJI_PATTERN, "")
            if (sanitized != beforeEmoji) {
                issues.add("Emojis removed")
                riskLevel = maxOf(riskLevel, RiskLevel.LOW)
            }
        }

        // Step 7: Escape potential delimiter patterns
        sanitized = escapeDelimiters(sanitized)

        val wasModified = sanitized != content

        if (wasModified) {
            Log.i(TAG, "Content sanitized. Risk level: $riskLevel, Issues: ${issues.size}")
        }

        return SecurityCheckResult(
            sanitizedContent = sanitized,
            wasModified = wasModified,
            detectedIssues = issues,
            riskLevel = riskLevel
        )
    }

    /**
     * Quick check if content contains obvious injection attempts
     * Faster than full sanitization for pre-screening
     */
    fun hasInjectionAttempt(content: String): Boolean {
        return INJECTION_PATTERNS.any { it.containsMatchIn(content) }
    }

    /**
     * Quick check if content contains dangerous requests
     */
    fun hasDangerousContent(content: String): Boolean {
        return DANGEROUS_CONTENT_PATTERNS.any { it.containsMatchIn(content) }
    }

    /**
     * Escape common AI delimiter patterns to prevent role confusion
     */
    private fun escapeDelimiters(content: String): String {
        var escaped = content

        // Escape markdown-like headers that could be mistaken for prompts
        escaped = escaped.replace(Regex("""^#{1,3}\s*(System|User|Assistant|Human|AI):""", RegexOption.MULTILINE)) { match ->
            match.value.replace("#", "＃") // Full-width number sign
        }

        // Escape XML-like tags that could be role markers
        escaped = escaped.replace(Regex("""<(system|user|assistant|human|ai)>""", RegexOption.IGNORE_CASE)) { match ->
            "＜${match.groupValues[1]}＞" // Full-width angle brackets
        }

        return escaped
    }

    /**
     * Create a safe wrapper around user content
     * Clearly delineates user content from system instructions
     */
    fun wrapUserContent(content: String): String {
        val sanitized = sanitize(content)
        return buildString {
            append("--- BEGIN USER CONTENT ---\n")
            append(sanitized.sanitizedContent)
            append("\n--- END USER CONTENT ---")
        }
    }

    /**
     * Get human-readable security report
     */
    fun getSecurityReport(result: SecurityCheckResult): String {
        return buildString {
            append("Security Check Report:\n")
            append("Risk Level: ${result.riskLevel}\n")
            append("Content Modified: ${result.wasModified}\n")
            if (result.detectedIssues.isNotEmpty()) {
                append("Issues Found:\n")
                result.detectedIssues.forEach { issue ->
                    append("  • $issue\n")
                }
            }
        }
    }

    /**
     * Convenience method for AI chat sanitization
     * Removes emojis by default for cleaner AI responses
     */
    fun sanitizeForChat(content: String): SecurityCheckResult {
        return sanitize(content, removeEmojis = true)
    }

    /**
     * Check if content contains emojis
     */
    fun hasEmojis(content: String): Boolean {
        return EMOJI_PATTERN.containsMatchIn(content)
    }

    /**
     * Remove emojis from content without full sanitization
     */
    fun removeEmojis(content: String): String {
        return content.replace(EMOJI_PATTERN, "")
    }
}
