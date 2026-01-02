package com.example.smarty.util

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

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
object PIIMasker : ComponentCallbacks2 {

    // Pre-compiled regex patterns for performance (compiled once at class load)
    // Ordered by specificity: more specific patterns first to avoid false positives
    private val PATTERNS = linkedMapOf(
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

    // Thread-safe counter for generating unique placeholder IDs
    private val counter = AtomicInteger(0)

    /**
     * Maximum size for placeholder map to prevent memory leaks.
     * Uses LRU eviction to remove least recently accessed entries.
     * This addresses TECH-038 (PIIMasker session never cleared).
     */
    private const val MAX_PLACEHOLDER_MAP_SIZE = 200

    /**
     * Session-based placeholder mapping with LRU eviction.
     * Maps placeholder strings to their original values.
     * Thread-safe access via synchronized methods.
     *
     * Uses LinkedHashMap with accessOrder=true for LRU behavior:
     * - Entries are ordered by access time (get/put moves entry to end)
     * - removeEldestEntry() automatically evicts oldest entry when size exceeds limit
     * - This prevents unbounded memory growth while preserving recent mappings
     */
    private val placeholderMap: MutableMap<String, String> = object : LinkedHashMap<String, String>(
        MAX_PLACEHOLDER_MAP_SIZE + 1,  // Initial capacity
        0.75f,                          // Load factor
        true                            // accessOrder=true for LRU behavior
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_PLACEHOLDER_MAP_SIZE
        }
    }

    /**
     * Track registration status to prevent duplicate registrations.
     */
    private var isRegistered = false

    /**
     * Register PIIMasker to respond to system memory pressure events.
     * Should be called once during application initialization.
     *
     * @param context Application context
     */
    fun register(context: Context) {
        if (!isRegistered) {
            context.applicationContext.registerComponentCallbacks(this)
            isRegistered = true
            Log.d("PIIMasker", "Registered for memory pressure callbacks")
        }
    }

    /**
     * Handle memory pressure events by clearing the placeholder map.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Log.d("PIIMasker", "Memory pressure detected (level=$level) - clearing placeholder map")
                clearSession()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onConfigurationChanged(newConfig: Configuration) {}

    override fun onLowMemory() {
        Log.d("PIIMasker", "Low memory warning - clearing placeholder map")
        clearSession()
    }

    /**
     * Mask all PII in the given text.
     *
     * @param text The input text potentially containing PII
     * @return Text with all PII replaced by XML-style placeholders like <EMAIL_0>, <PERSON_1>, etc.
     */
    @Synchronized
    fun mask(text: String): String {
        if (text.isBlank()) return text

        // LRU eviction is handled automatically by LinkedHashMap.removeEldestEntry()
        // No need for manual clearing - oldest entries are evicted as new ones are added

        var maskedText = text

        PATTERNS.forEach { (type, regex) ->
            // FIX: Collect all matches FIRST to avoid concurrent modification
            // findAll returns a Sequence that iterates over the original string positions
            // If we modify the string while iterating, positions become invalid
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
                    val newPlaceholder = "<${type}_${counter.getAndIncrement()}>"
                    placeholderMap[newPlaceholder] = originalValue
                    newPlaceholder
                }

                maskedText = maskedText.replace(originalValue, placeholder)
            }
        }

        return maskedText
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
    @Synchronized
    fun unmask(text: String): String {
        if (text.isBlank()) return text

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

    /**
     * Clear all stored placeholder mappings.
     * Call this when starting a new conversation session.
     */
    @Synchronized
    fun clearSession() {
        placeholderMap.clear()
        counter.set(0)
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

    /**
     * Mask text and return both masked text and the mapping.
     * Useful when you need to handle the mapping externally.
     *
     * @param text The input text
     * @return Pair of masked text and the placeholder-to-original mapping
     */
    @Synchronized
    fun maskWithMapping(text: String): Pair<String, Map<String, String>> {
        val beforeSize = placeholderMap.size
        val maskedText = mask(text)
        val newMappings = placeholderMap.entries
            .drop(beforeSize)
            .associate { it.key to it.value }
        return maskedText to newMappings
    }
}
