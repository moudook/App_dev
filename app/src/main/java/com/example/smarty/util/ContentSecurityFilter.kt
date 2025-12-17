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
     */
    private val CONTROL_CHARS_PATTERN = Regex("""[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]""")

    /**
     * Unicode direction override characters (used in text manipulation)
     */
    private val UNICODE_OVERRIDE_PATTERN = Regex("""[\u202A-\u202E\u2066-\u2069\u200E\u200F]""")

    /**
     * Excessive whitespace/newlines (potential token exhaustion)
     */
    private val EXCESSIVE_WHITESPACE_PATTERN = Regex("""\n{5,}""")
    private val EXCESSIVE_SPACES_PATTERN = Regex(""" {10,}""")

    /**
     * Emoji pattern - matches common emoji Unicode ranges
     * Used to remove emojis from content sent to AI for cleaner responses
     */
    private val EMOJI_PATTERN = Regex(
        "[" +
        "\u00A9\u00AE" +                          // Copyright, Registered
        "\u203C\u2049" +                          // Exclamation marks
        "\u2122\u2139" +                          // TM, Info
        "\u2194-\u21AA" +                         // Arrows
        "\u231A-\u231B" +                         // Watch, Hourglass
        "\u2328\u23CF" +                          // Keyboard, Eject
        "\u23E9-\u23F3" +                         // Media controls
        "\u23F8-\u23FA" +                         // More media controls
        "\u24C2" +                                // M circle
        "\u25AA-\u25AB" +                         // Squares
        "\u25B6\u25C0" +                          // Play buttons
        "\u25FB-\u25FE" +                         // More squares
        "\u2600-\u2604" +                         // Sun, clouds
        "\u260E" +                                // Phone
        "\u2611" +                                // Checkbox
        "\u2614-\u2615" +                         // Umbrella, coffee
        "\u2618" +                                // Shamrock
        "\u261D\u2620" +                          // Pointing, skull
        "\u2622-\u2623" +                         // Radioactive, biohazard
        "\u2626\u262A" +                          // Religious symbols
        "\u262E-\u262F" +                         // Peace, Yin Yang
        "\u2638-\u263A" +                         // Religious, smiley
        "\u2640\u2642" +                          // Gender symbols
        "\u2648-\u2653" +                         // Zodiac
        "\u265F-\u2660" +                         // Chess
        "\u2663\u2665-\u2666" +                   // Card suits
        "\u2668\u267B" +                          // Hot springs, recycling
        "\u267E-\u267F" +                         // Infinity, wheelchair
        "\u2692-\u2697" +                         // Tools
        "\u2699" +                                // Gear
        "\u269B-\u269C" +                         // Atom, fleur-de-lis
        "\u26A0-\u26A1" +                         // Warning, high voltage
        "\u26A7" +                                // Transgender
        "\u26AA-\u26AB" +                         // Circles
        "\u26B0-\u26B1" +                         // Coffin, urn
        "\u26BD-\u26BE" +                         // Soccer, baseball
        "\u26C4-\u26C5" +                         // Snowman, sun behind cloud
        "\u26C8" +                                // Thunder cloud
        "\u26CE" +                                // Ophiuchus
        "\u26CF" +                                // Pick
        "\u26D1" +                                // Rescue worker helmet
        "\u26D3-\u26D4" +                         // Chains, no entry
        "\u26E9-\u26EA" +                         // Shinto shrine, church
        "\u26F0-\u26F5" +                         // Mountain, sailboat
        "\u26F7-\u26FA" +                         // Skier, tent
        "\u26FD" +                                // Fuel pump
        "\u2702" +                                // Scissors
        "\u2705" +                                // Check mark
        "\u2708-\u270D" +                         // Airplane, writing hand
        "\u270F" +                                // Pencil
        "\u2712" +                                // Black nib
        "\u2714" +                                // Check mark
        "\u2716" +                                // X mark
        "\u271D" +                                // Latin cross
        "\u2721" +                                // Star of David
        "\u2728" +                                // Sparkles
        "\u2733-\u2734" +                         // Eight spoked asterisks
        "\u2744" +                                // Snowflake
        "\u2747" +                                // Sparkle
        "\u274C\u274E" +                          // X marks
        "\u2753-\u2755" +                         // Question marks
        "\u2757" +                                // Exclamation
        "\u2763-\u2764" +                         // Heart exclamation, heart
        "\u2795-\u2797" +                         // Plus, minus, divide
        "\u27A1" +                                // Right arrow
        "\u27B0\u27BF" +                          // Curly loops
        "\u2934-\u2935" +                         // Curved arrows
        "\u2B05-\u2B07" +                         // Arrows
        "\u2B1B-\u2B1C" +                         // Squares
        "\u2B50" +                                // Star
        "\u2B55" +                                // Circle
        "\u3030\u303D" +                          // Wavy dash, part alternation
        "\u3297\u3299" +                          // Circled ideographs
        "\uD83C\uDC04" +                          // Mahjong
        "\uD83C\uDCCF" +                          // Joker
        "\uD83C\uDD70-\uD83C\uDD71" +             // Blood types
        "\uD83C\uDD7E-\uD83C\uDD7F" +             // O, P buttons
        "\uD83C\uDD8E" +                          // AB button
        "\uD83C\uDD91-\uD83C\uDD9A" +             // CL, symbols
        "\uD83C\uDDE6-\uD83C\uDDFF" +             // Regional indicators (flags)
        "\uD83C\uDE01-\uD83C\uDE02" +             // Japanese symbols
        "\uD83C\uDE1A" +                          // Japanese symbol
        "\uD83C\uDE2F" +                          // Japanese symbol
        "\uD83C\uDE32-\uD83C\uDE3A" +             // Japanese symbols
        "\uD83C\uDE50-\uD83C\uDE51" +             // Japanese symbols
        "\uD83C\uDF00-\uD83D\uDDFF" +             // Misc symbols, emoticons
        "\uD83D\uDE00-\uD83D\uDE4F" +             // Emoticons
        "\uD83D\uDE80-\uD83D\uDEFF" +             // Transport, map symbols
        "\uD83E\uDD00-\uD83E\uDDFF" +             // Supplemental symbols
        "\uD83E\uDE00-\uD83E\uDEFF" +             // Chess, symbols
        "\uFE00-\uFE0F" +                         // Variation selectors
        "\u200D" +                                // Zero width joiner
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

        // Step 2: Remove control characters
        val beforeControlChars = sanitized
        sanitized = sanitized.replace(CONTROL_CHARS_PATTERN, "")
        if (sanitized != beforeControlChars) {
            issues.add("Control characters removed")
            riskLevel = maxOf(riskLevel, RiskLevel.LOW)
        }

        // Step 3: Remove Unicode direction overrides
        val beforeUnicode = sanitized
        sanitized = sanitized.replace(UNICODE_OVERRIDE_PATTERN, "")
        if (sanitized != beforeUnicode) {
            issues.add("Unicode override characters removed")
            riskLevel = maxOf(riskLevel, RiskLevel.LOW)
        }

        // Step 4: Normalize excessive whitespace
        val beforeWhitespace = sanitized
        sanitized = sanitized.replace(EXCESSIVE_WHITESPACE_PATTERN, "\n\n\n")
        sanitized = sanitized.replace(EXCESSIVE_SPACES_PATTERN, "    ")
        if (sanitized != beforeWhitespace) {
            issues.add("Excessive whitespace normalized")
            riskLevel = maxOf(riskLevel, RiskLevel.LOW)
        }

        // Step 5: Check for and neutralize injection patterns
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

        // Step 6: Truncate if too long (prevent token exhaustion)
        if (sanitized.length > MAX_CONTENT_LENGTH) {
            sanitized = sanitized.take(MAX_CONTENT_LENGTH) + "\n[Content truncated for processing]"
            issues.add("Content truncated to maximum length")
            riskLevel = maxOf(riskLevel, RiskLevel.LOW)
        }

        // Step 7: Remove emojis if requested (for cleaner AI chat responses)
        if (removeEmojis) {
            val beforeEmoji = sanitized
            sanitized = sanitized.replace(EMOJI_PATTERN, "")
            if (sanitized != beforeEmoji) {
                issues.add("Emojis removed")
                riskLevel = maxOf(riskLevel, RiskLevel.LOW)
            }
        }

        // Step 8: Escape potential delimiter patterns
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
