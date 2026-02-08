package com.example.smarty.util

/**
 * PII (Personally Identifiable Information) Masking utility.
 *
 * Detects and masks sensitive data before sending to external LLM APIs,
 * then restores original values in responses.
 *
 * Pipeline: User Input -> mask() -> LLM -> unmask() -> User Output
 *
 * Based on Presidio-style anonymization (research recommendation):
 * - Uses XML-style placeholders: <TYPE_N> for better LLM understanding
 * - Supports bidirectional mapping for de-anonymization
 * - Thread-safe for concurrent usage
 *
 * Supported PII types:
 * - Person names (common patterns)
 * - Email addresses
 * - Phone numbers (international format)
 * - Social Security Numbers (US format)
 * - Credit card numbers
 * - IP addresses
 * - Physical addresses
 * - Dates of birth patterns
 */
class PIIMasker(private val logger: Logger) {

    companion object {
        private const val TAG = "PIIMasker"

        // Pre-compiled regex patterns for performance (compiled once at class load)
        // Ordered by specificity: more specific patterns first to avoid false positives
        private val PATTERNS = mapOf(
            // High specificity patterns first
            "EMAIL" to Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
            "SSN" to Regex("\\b\\d{3}[- ]?\\d{2}[- ]?\\d{4}\\b"),
            "CREDIT_CARD" to Regex("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b"),
            "IP_ADDRESS" to Regex("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b"),
            "DOB" to Regex("\\b(?:0?[1-9]|1[0-2])[/-](?:0?[1-9]|[12][0-9]|3[01])[/-](?:19|20)\\d{2}\\b"),
            "PHONE" to Regex("\\+?1?[-.\\s]?\\(?[0-9]{3}\\)?[-.\\s]?[0-9]{3}[-.\\s]?[0-9]{4}"),
            // Address pattern: number + street name + optional apt/suite + city patterns
            "ADDRESS" to Regex("\\b\\d{1,5}\\s+[A-Za-z]+(?:\\s+[A-Za-z]+){0,3}\\s+(?:St|Street|Ave|Avenue|Blvd|Boulevard|Rd|Road|Dr|Drive|Ln|Lane|Way|Ct|Court|Pl|Place)\\.?(?:\\s*(?:#|Apt|Suite|Unit)\\s*[A-Za-z0-9-]+)?\\b", RegexOption.IGNORE_CASE),
            // Person names: Capitalized words (2-3 words) - runs last due to lower specificity
            // Matches: "John Smith", "Mary Jane Watson", "Dr. John Smith"
            "PERSON" to Regex("\\b(?:Mr\\.?|Mrs\\.?|Ms\\.?|Dr\\.?|Prof\\.?)?\\s*[A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,2}\\b")
        )

        // Common first names to improve PERSON detection accuracy
        // L9 FIX: Expanded name dictionary to reduce false positives
        private val COMMON_FIRST_NAMES = setOf(
            // English names
            "james", "john", "robert", "michael", "william", "david", "richard", "joseph",
            "mary", "patricia", "jennifer", "linda", "elizabeth", "barbara", "susan", "jessica",
            "sarah", "karen", "nancy", "lisa", "betty", "helen", "sandra", "donna",
            "thomas", "charles", "christopher", "daniel", "matthew", "anthony", "mark", "donald",
            "steven", "paul", "andrew", "joshua", "kenneth", "kevin", "brian", "george",
            "ashley", "emily", "amanda", "melissa", "michelle", "kimberly", "angela", "stephanie",
            "emma", "olivia", "sophia", "isabella", "mia", "charlotte", "amelia", "harper",
            "liam", "noah", "oliver", "elijah", "lucas", "mason", "logan", "alexander",
            // Indian names
            "raj", "amit", "priya", "rahul", "anita", "vijay", "sunita", "arun",
            "sanjay", "ravi", "deepak", "suresh", "neha", "pooja", "swati", "anjali",
            "arjun", "vikram", "rohit", "kiran", "meera", "lakshmi", "gita", "rekha",
            // Arabic names
            "mohammed", "ali", "ahmed", "fatima", "omar", "hassan", "aisha", "yusuf",
            "ibrahim", "mustafa", "layla", "noor", "zahra", "maryam", "sara", "hana",
            // Hispanic names
            "jose", "carlos", "miguel", "luis", "maria", "ana", "rosa", "carmen",
            "juan", "pedro", "diego", "sofia", "valentina", "camila", "lucia", "elena"
        )

        /**
         * Maximum size for placeholder map to prevent memory leaks.
         * Uses LRU eviction to remove least recently accessed entries.
         * This addresses TECH-038 (PIIMasker session never cleared).
         */
        private const val MAX_PLACEHOLDER_MAP_SIZE = 200
    }

    // Thread-safe counter for generating unique placeholder IDs
    // In Common, we use a simple var with a lock, or assume single-threaded for now if concurrency primitives are missing.
    // However, since we are in a class, we can use a basic counter.
    private var counter = 0
    private val lock = Any()

    /**
     * Session-based placeholder mapping.
     * Maps placeholder strings to their original values.
     *
     * In KMP, we use a MutableMap. We handle size limiting manually since LinkedHashMap
     * removeEldestEntry is JVM specific.
     */
    private val placeholderMap: MutableMap<String, String> = LinkedHashMap()

    /**
     * Mask all PII in the given text.
     *
     * @param text The input text potentially containing PII
     * @return Text with all PII replaced by XML-style placeholders like <EMAIL_0>, <PERSON_1>, etc.
     */
    fun mask(text: String): String {
        if (text.isBlank()) return text

        // Synchronization block for thread safety
        synchronized(lock) {
            var maskedText = text

            PATTERNS.forEach { (type, regex) ->
                // FIX: Collect all matches FIRST to avoid concurrent modification
                val matches = regex.findAll(maskedText).map { it.value }.toList().distinct()

                matches.forEach { originalValue ->
                    // For PERSON type, validate it's likely a real name (reduce false positives)
                    if (type == "PERSON" && !isLikelyPersonName(originalValue)) {
                        return@forEach // Skip this match
                    }

                    // Check if this exact value was already masked (avoid duplicates)
                    val existingPlaceholder = placeholderMap.entries
                        .find { it.value == originalValue }?.key

                    // Use XML-style placeholders for better LLM understanding
                    val placeholder = existingPlaceholder ?: run {
                        val newPlaceholder = "<${type}_${counter++}>"

                        // Manage map size manually
                        if (placeholderMap.size >= MAX_PLACEHOLDER_MAP_SIZE) {
                            val iterator = placeholderMap.iterator()
                            if (iterator.hasNext()) {
                                iterator.next()
                                iterator.remove()
                            }
                        }

                        placeholderMap[newPlaceholder] = originalValue
                        newPlaceholder
                    }

                    maskedText = maskedText.replace(originalValue, placeholder)
                }
            }

            return maskedText
        }
    }

    /**
     * Check if a matched string is likely a person's name.
     * Uses common first names list to reduce false positives.
     */
    private fun isLikelyPersonName(text: String): Boolean {
        val words = text.trim().split("\\s+".toRegex())
        if (words.isEmpty()) return false

        // Remove honorific if present
        val nameWords = if (words.first().matches(Regex("(?:Mr|Mrs|Ms|Dr|Prof)\\.?"))) {
            words.drop(1)
        } else {
            words
        }

        if (nameWords.isEmpty()) return false

        // Check if first word is a common first name
        val firstName = nameWords.first().lowercase()
        if (firstName in COMMON_FIRST_NAMES) return true

        // Check if it follows typical name pattern (2-3 capitalized words)
        val allCapitalized = nameWords.all { it.first().isUpperCase() && it.length >= 2 }
        val reasonableLength = nameWords.size in 2..3

        return allCapitalized && reasonableLength
    }

    /**
     * Restore all PII placeholders to their original values.
     *
     * @param text The masked text with placeholders
     * @return Text with all placeholders replaced by original PII values
     */
    fun unmask(text: String): String {
        if (text.isBlank()) return text

        synchronized(lock) {
            var unmaskedText = text

            // Sort by placeholder length descending to avoid partial replacements
            // e.g., <PERSON_10> should be replaced before <PERSON_1>
            placeholderMap.entries
                .sortedByDescending { it.key.length }
                .forEach { (placeholder, original) ->
                    unmaskedText = unmaskedText.replace(placeholder, original)
                }

            return unmaskedText
        }
    }

    /**
     * Clear all stored placeholder mappings.
     * Call this when starting a new conversation session.
     */
    fun clearSession() {
        synchronized(lock) {
            placeholderMap.clear()
            counter = 0
        }
    }

    /**
     * Check if text contains any detectable PII.
     * Useful for UI warnings or analytics.
     *
     * @param text The text to check
     * @return true if any PII patterns are detected
     */
    fun containsPII(text: String): Boolean {
        return PATTERNS.values.any { it.containsMatchIn(text) }
    }

    /**
     * Get a summary of detected PII types in text.
     *
     * @param text The text to analyze
     * @return Map of PII type to count of occurrences
     */
    fun detectPIITypes(text: String): Map<String, Int> {
        return PATTERNS.mapValues { (_, regex) ->
            regex.findAll(text).count()
        }.filterValues { it > 0 }
    }
}
