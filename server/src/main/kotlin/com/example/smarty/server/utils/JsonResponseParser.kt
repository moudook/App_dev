package com.example.smarty.server.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * JSON Response Parser for LLM responses.
 * 
 * Single Responsibility: Only handles JSON parsing from LLM responses.
 * DRY: Replaces repeated JSON cleaning and parsing in 5+ files.
 * 
 * Usage:
 * ```
 * val data = JsonResponseParser.parseJsonResponse(response) { json ->
 *     Json.decodeFromString<MyData>(json)
 * }
 * 
 * val jsonElement = JsonResponseParser.parseToJsonElement(response)
 * ```
 */
object JsonResponseParser {
    
    /**
     * Shared JSON configuration for lenient parsing.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    
    /**
     * Clean an LLM response by removing markdown code blocks.
     */
    fun cleanResponse(response: String): String {
        return response.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
    
    /**
     * Parse a JSON response with a custom deserializer.
     */
    fun <T> parseJsonResponse(
        response: String,
        deserializer: (String) -> T
    ): T {
        val cleaned = cleanResponse(response)
        return deserializer(cleaned)
    }
    
    /**
     * Parse a JSON response to a JsonElement.
     */
    fun parseToJsonElement(response: String): JsonElement {
        val cleaned = cleanResponse(response)
        return json.parseToJsonElement(cleaned)
    }
    
    /**
     * Parse a JSON response to a specific type.
     */
    inline fun <reified T> parseToJson(response: String): T {
        val cleaned = cleanResponse(response)
        return json.decodeFromString(cleaned)
    }
    
    /**
     * Safely parse a JSON response, returning null on failure.
     */
    inline fun <reified T> parseToJsonSafe(response: String): T? {
        return try {
            parseToJson(response)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if a response is valid JSON.
     */
    fun isValidJson(response: String): Boolean {
        return try {
            val cleaned = cleanResponse(response)
            json.parseToJsonElement(cleaned)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Extract a specific field from a JSON response.
     */
    fun extractField(response: String, fieldName: String): String? {
        return try {
            val jsonElement = parseToJsonElement(response)
            jsonElement.asJsonObject[fieldName]?.toString()
        } catch (e: Exception) {
            null
        }
    }
}
