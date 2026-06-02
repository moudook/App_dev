package com.example.smarty.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException

/**
 * Manages recent search queries for the notes search feature.
 * Stores up to 20 unique recent searches.
 *
 * Thread-safe implementation using synchronized blocks for all operations
 * that access or modify shared state.
 */
class SearchHistoryManager(
    context: Context,
) {
    companion object {
        private const val PREFS_NAME = "search_history_prefs"
        private const val KEY_SEARCHES = "recent_searches"
        private const val MAX_HISTORY_SIZE = 20
    }

    private val lock = Any()

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    init {
        loadSearches()
    }

    private fun loadSearches() {
        synchronized(lock) {
            val stored = prefs.getString(KEY_SEARCHES, "") ?: ""
            _recentSearches.value = parseJsonArray(stored)
        }
    }

    private fun saveSearches() {
        // Note: Called within synchronized block, no additional sync needed
        val jsonArray = JSONArray(_recentSearches.value)
        prefs
            .edit()
            .putString(KEY_SEARCHES, jsonArray.toString())
            .apply()
    }

    /**
     * Parses JSON array string to list of strings.
     * Falls back to legacy pipe-delimited format for migration, then to empty list.
     */
    private fun parseJsonArray(stored: String): List<String> {
        if (stored.isEmpty()) return emptyList()

        return try {
            val jsonArray = JSONArray(stored)
            (0 until jsonArray.length()).map { jsonArray.getString(it) }
        } catch (e: JSONException) {
            // Fallback: try legacy pipe-delimited format for migration
            if (stored.contains("|||")) {
                stored.split("|||").filter { it.isNotBlank() }
            } else {
                emptyList()
            }
        }
    }

    fun addSearch(query: String) {
        if (query.isBlank() || query.length < 2) return

        val trimmed = query.trim()

        synchronized(lock) {
            val current = _recentSearches.value.toMutableList()

            // Remove if exists (to move to front)
            current.remove(trimmed)

            // Add to front
            current.add(0, trimmed)

            // Trim to max size
            if (current.size > MAX_HISTORY_SIZE) {
                current.removeAt(current.size - 1)
            }

            _recentSearches.value = current
            saveSearches()
        }
    }

    fun removeSearch(query: String) {
        synchronized(lock) {
            val current = _recentSearches.value.toMutableList()
            current.remove(query)
            _recentSearches.value = current
            saveSearches()
        }
    }

    fun clearHistory() {
        synchronized(lock) {
            _recentSearches.value = emptyList()
            saveSearches()
        }
    }

    fun getFilteredSuggestions(
        query: String,
        limit: Int = 5,
    ): List<String> {
        synchronized(lock) {
            val searches = _recentSearches.value
            return if (query.isBlank()) {
                searches.take(limit)
            } else {
                searches.filter { it.contains(query, ignoreCase = true) }.take(limit)
            }
        }
    }
}
