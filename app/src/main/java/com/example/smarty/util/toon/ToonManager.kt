package com.example.smarty.util.toon

import kotlinx.serialization.json.*

/**
 * TOON (Token-Oriented Object Notation) Manager
 *
 * Provides 30-60% token reduction compared to JSON by:
 * - Removing quotes from keys and simple values
 * - Using compact separators (: and |)
 * - Eliminating whitespace
 *
 * Format: key:value|key2:value2|nested:{a:1|b:2}|list:[item1,item2]
 */
object ToonManager {

    @PublishedApi
    internal val json = Json { ignoreUnknownKeys = true }

    /**
     * Convert a data class to TOON format.
     * Uses kotlinx.serialization to first convert to JSON, then to TOON.
     */
    inline fun <reified T> encode(obj: T, serializer: kotlinx.serialization.KSerializer<T>): String {
        val jsonElement = json.encodeToJsonElement(serializer, obj)
        return jsonElementToToon(jsonElement)
    }

    /**
     * Convert JSON string to TOON format.
     */
    fun jsonToToon(jsonString: String): String {
        return try {
            val element = json.parseToJsonElement(jsonString)
            jsonElementToToon(element)
        } catch (e: Exception) {
            jsonString // Return original if parsing fails
        }
    }

    /**
     * Convert JsonElement to TOON string.
     */
    fun jsonElementToToon(element: JsonElement): String {
        return when (element) {
            is JsonObject -> objectToToon(element)
            is JsonArray -> arrayToToon(element)
            is JsonPrimitive -> primitiveToToon(element)
        }
    }

    private fun objectToToon(obj: JsonObject): String {
        if (obj.isEmpty()) return "{}"

        val pairs = obj.entries.map { (key, value) ->
            val toonValue = jsonElementToToon(value)
            "$key:$toonValue"
        }

        return "{${pairs.joinToString("|")}}"
    }

    private fun arrayToToon(arr: JsonArray): String {
        if (arr.isEmpty()) return "[]"

        val items = arr.map { jsonElementToToon(it) }
        return "[${items.joinToString(",")}]"
    }

    private fun primitiveToToon(prim: JsonPrimitive): String {
        return when {
            prim.isString -> {
                val str = prim.content
                // Escape special characters if needed
                if (str.contains("|") || str.contains(":") || str.contains(",") ||
                    str.contains("{") || str.contains("}") || str.contains("[") || str.contains("]")) {
                    "\"${str.replace("\"", "\\\"")}\""
                } else {
                    str
                }
            }
            else -> prim.content // numbers, booleans, null
        }
    }

    /**
     * Parse TOON string back to a Map.
     * Used for decoding tool results if needed.
     */
    fun decode(toon: String): Map<String, Any?> {
        return try {
            parseToonObject(toon.trim().removeSurrounding("{", "}"))
        } catch (e: Exception) {
            mapOf("raw" to toon)
        }
    }

    private fun parseToonObject(content: String): Map<String, Any?> {
        if (content.isBlank()) return emptyMap()

        val result = mutableMapOf<String, Any?>()
        val pairs = splitToonPairs(content)

        for (pair in pairs) {
            val colonIndex = pair.indexOf(':')
            if (colonIndex > 0) {
                val key = pair.substring(0, colonIndex).trim()
                val value = pair.substring(colonIndex + 1).trim()
                result[key] = parseToonValue(value)
            }
        }

        return result
    }

    private fun splitToonPairs(content: String): List<String> {
        val pairs = mutableListOf<String>()
        var depth = 0
        var current = StringBuilder()
        var inQuotes = false

        for (char in content) {
            when {
                char == '"' && (current.isEmpty() || current.last() != '\\') -> {
                    inQuotes = !inQuotes
                    current.append(char)
                }
                !inQuotes && (char == '{' || char == '[') -> {
                    depth++
                    current.append(char)
                }
                !inQuotes && (char == '}' || char == ']') -> {
                    depth--
                    current.append(char)
                }
                !inQuotes && char == '|' && depth == 0 -> {
                    pairs.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }

        if (current.isNotEmpty()) {
            pairs.add(current.toString())
        }

        return pairs
    }

    private fun parseToonValue(value: String): Any? {
        val trimmed = value.trim()

        return when {
            trimmed == "null" -> null
            trimmed == "true" -> true
            trimmed == "false" -> false
            trimmed.startsWith("{") && trimmed.endsWith("}") -> {
                parseToonObject(trimmed.removeSurrounding("{", "}"))
            }
            trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                parseToonArray(trimmed.removeSurrounding("[", "]"))
            }
            trimmed.startsWith("\"") && trimmed.endsWith("\"") -> {
                trimmed.removeSurrounding("\"").replace("\\\"", "\"")
            }
            trimmed.toIntOrNull() != null -> trimmed.toInt()
            trimmed.toLongOrNull() != null -> trimmed.toLong()
            trimmed.toDoubleOrNull() != null -> trimmed.toDouble()
            else -> trimmed
        }
    }

    private fun parseToonArray(content: String): List<Any?> {
        if (content.isBlank()) return emptyList()

        val items = mutableListOf<Any?>()
        var depth = 0
        var current = StringBuilder()
        var inQuotes = false

        for (char in content) {
            when {
                char == '"' && (current.isEmpty() || current.last() != '\\') -> {
                    inQuotes = !inQuotes
                    current.append(char)
                }
                !inQuotes && (char == '{' || char == '[') -> {
                    depth++
                    current.append(char)
                }
                !inQuotes && (char == '}' || char == ']') -> {
                    depth--
                    current.append(char)
                }
                !inQuotes && char == ',' && depth == 0 -> {
                    items.add(parseToonValue(current.toString()))
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }

        if (current.isNotEmpty()) {
            items.add(parseToonValue(current.toString()))
        }

        return items
    }

    /**
     * Calculate token savings (approximate).
     * Returns percentage of tokens saved compared to JSON.
     */
    fun calculateSavings(json: String, toon: String): Double {
        val jsonTokens = json.length / 4.0 // Rough estimate: 4 chars per token
        val toonTokens = toon.length / 4.0
        return ((jsonTokens - toonTokens) / jsonTokens) * 100
    }
}

/**
 * Extension function to convert any serializable object to TOON.
 */
inline fun <reified T> T.toToon(serializer: kotlinx.serialization.KSerializer<T>): String {
    return ToonManager.encode(this, serializer)
}
