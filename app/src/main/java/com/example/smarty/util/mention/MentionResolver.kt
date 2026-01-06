package com.example.smarty.util.mention

import android.util.Log
import com.example.smarty.data.local.CategoryDao
import com.example.smarty.data.local.NoteDao
import com.example.smarty.data.model.MentionSuggestion
import com.example.smarty.data.model.MentionType
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ParsedMention
import com.example.smarty.data.model.ResolvedMention
import com.example.smarty.util.search.SemanticSearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Resolves @mentions to actual notes using semantic search and filters.
 *
 * Features:
 * - Fuzzy title matching using SemanticSearchEngine
 * - Type filter resolution (@audios, @documents, etc.)
 * - Special filter resolution (@recent, @pinned, @all)
 * - Category resolution (@Work, @Personal)
 * - Privacy-aware - respects excludeFromAiChat and isFullPrivacy flags
 * - Returns max 4 suggestions for autocomplete
 */
class MentionResolver(
    private val noteDao: NoteDao,
    private val categoryDao: CategoryDao
) {
    companion object {
        private const val TAG = "MentionResolver"
        private const val MAX_SUGGESTIONS = 4
        private const val RECENT_NOTES_LIMIT = 10
        private const val MIN_SEARCH_SCORE = 0.35
    }

    // Cached notes for performance - refreshed on each query
    private var cachedNotes: List<Note> = emptyList()
    private var lastCacheTime: Long = 0
    private val cacheValidityMs = 5000L // 5 seconds

    /**
     * Get autocomplete suggestions for current query.
     *
     * @param query Query text (without @)
     * @return List of suggestions (max 4)
     */
    suspend fun getSuggestions(query: String): List<MentionSuggestion> = withContext(Dispatchers.Default) {
        if (query.isBlank()) {
            return@withContext getDefaultSuggestions()
        }

        val normalizedQuery = MentionParser.normalizeQuery(query)
        Log.d(TAG, "Getting suggestions for query: '$query' -> normalized: '$normalizedQuery'")

        val suggestions = mutableListOf<MentionSuggestion>()

        // 0. Check for command matches (like @thinking) - highest priority
        val matchingCommands = getMatchingCommands(normalizedQuery)
        suggestions.addAll(matchingCommands.take(1)) // Max 1 command

        // 1. Check for type filter matches
        val matchingTypeFilters = getMatchingTypeFilters(normalizedQuery)
        suggestions.addAll(matchingTypeFilters.take(2)) // Max 2 type filters

        // 2. Check for special filter matches
        val matchingSpecialFilters = getMatchingSpecialFilters(normalizedQuery)
        suggestions.addAll(matchingSpecialFilters.take(1)) // Max 1 special filter

        // 3. Check for category matches
        val categoryMatches = searchCategories(normalizedQuery)
        suggestions.addAll(categoryMatches.take(2))

        // 4. Search for matching notes
        val remainingSlots = MAX_SUGGESTIONS - suggestions.size
        if (remainingSlots > 0) {
            val noteMatches = searchNotesByTitle(normalizedQuery, remainingSlots)
            suggestions.addAll(noteMatches)
        }

        Log.d(TAG, "Returning ${suggestions.size} suggestions")
        return@withContext suggestions.take(MAX_SUGGESTIONS)
    }

    /**
     * Resolve parsed mentions to actual notes.
     *
     * @param parsedMentions List of parsed mentions from user message
     * @return List of resolved mentions with notes
     */
    suspend fun resolveMentions(parsedMentions: List<ParsedMention>): List<ResolvedMention> = withContext(Dispatchers.Default) {
        if (parsedMentions.isEmpty()) return@withContext emptyList()

        refreshCacheIfNeeded()

        val resolved = mutableListOf<ResolvedMention>()

        for (mention in parsedMentions) {
            val normalizedQuery = MentionParser.normalizeQuery(mention.query)

            // Check type filter
            val typeFilter = MentionParser.matchTypeFilter(normalizedQuery)
            if (typeFilter != null) {
                val notes = getAiVisibleNotes().filter { it.type == typeFilter }
                resolved.add(
                    ResolvedMention(
                        parsedMention = mention,
                        type = MentionType.TYPE_FILTER,
                        notes = notes.take(50), // Limit per filter
                        noteType = typeFilter
                    )
                )
                continue
            }

            // Check special filter
            val specialFilter = MentionParser.matchSpecialFilter(normalizedQuery)
            if (specialFilter != null) {
                val notes = when (specialFilter) {
                    "recent" -> getAiVisibleNotes()
                        .sortedByDescending { it.createdAt }
                        .take(RECENT_NOTES_LIMIT)
                    "pinned" -> getAiVisibleNotes().filter { it.isPinned }
                    "all" -> getAiVisibleNotes().take(50)
                    else -> emptyList()
                }
                resolved.add(
                    ResolvedMention(
                        parsedMention = mention,
                        type = MentionType.SPECIAL_FILTER,
                        notes = notes,
                        specialFilter = specialFilter
                    )
                )
                continue
            }

            // Check category
            // We use getCategoryByName which returns nullable
            val category = categoryDao.getCategoryByName(normalizedQuery)
            if (category != null) {
                val notes = getAiVisibleNotes().filter { it.categoryId == category.id }
                resolved.add(
                    ResolvedMention(
                        parsedMention = mention,
                        type = MentionType.CATEGORY,
                        notes = notes.take(50),
                        category = category
                    )
                )
                continue
            }

            // Search for note by title
            val matchingNotes = searchNotesByTitleExact(normalizedQuery)
            resolved.add(
                ResolvedMention(
                    parsedMention = mention,
                    type = MentionType.SINGLE_NOTE,
                    notes = matchingNotes.take(5) // Allow up to 5 matches for ambiguous titles
                )
            )
        }

        return@withContext resolved
    }

    /**
     * Get default suggestions when query is empty (just typed @).
     */
    private suspend fun getDefaultSuggestions(): List<MentionSuggestion> {
        refreshCacheIfNeeded()

        val suggestions = mutableListOf<MentionSuggestion>()

        // Show @thinking command first (for deep document analysis)
        val thinkingInfo = MentionParser.COMMANDS["thinking"]
        if (thinkingInfo != null) {
            suggestions.add(
                MentionSuggestion.CommandSuggestion(
                    commandName = thinkingInfo.name,
                    displayName = thinkingInfo.displayName,
                    description = thinkingInfo.description,
                    icon = thinkingInfo.icon
                )
            )
        }

        // Show special filters
        val aiVisibleNotes = getAiVisibleNotes()
        val pinnedCount = aiVisibleNotes.count { it.isPinned }

        suggestions.add(
            MentionSuggestion.SpecialFilter(
                filterName = "recent",
                displayName = "Recent",
                description = "Most recently created notes",
                count = minOf(aiVisibleNotes.size, RECENT_NOTES_LIMIT)
            )
        )

        if (pinnedCount > 0) {
            suggestions.add(
                MentionSuggestion.SpecialFilter(
                    filterName = "pinned",
                    displayName = "Pinned",
                    description = "Your pinned notes",
                    count = pinnedCount
                )
            )
        }

        // Add most common type filters (if there's room)
        val remainingSlots = MAX_SUGGESTIONS - suggestions.size
        if (remainingSlots > 0) {
            val typeCounts = aiVisibleNotes.groupBy { it.type }.mapValues { it.value.size }
            val topTypes = typeCounts.entries
                .sortedByDescending { it.value }
                .take(remainingSlots)
                .filter { it.value > 0 }

            for ((type, count) in topTypes) {
                suggestions.add(
                    MentionSuggestion.TypeFilter(
                        type = type,
                        displayName = MentionParser.getTypeFilterDisplayName(type),
                        keyword = MentionParser.getTypeFilterKeyword(type),
                        count = count
                    )
                )
            }
        }

        return suggestions.take(MAX_SUGGESTIONS)
    }

    /**
     * Get command suggestions matching the query.
     */
    private fun getMatchingCommands(query: String): List<MentionSuggestion.CommandSuggestion> {
        return MentionParser.getMatchingCommands(query).map { cmdInfo ->
            MentionSuggestion.CommandSuggestion(
                commandName = cmdInfo.name,
                displayName = cmdInfo.displayName,
                description = cmdInfo.description,
                icon = cmdInfo.icon
            )
        }
    }

    /**
     * Get type filters that match the query.
     */
    private suspend fun getMatchingTypeFilters(query: String): List<MentionSuggestion.TypeFilter> {
        refreshCacheIfNeeded()

        val aiVisibleNotes = getAiVisibleNotes()
        val typeCounts = aiVisibleNotes.groupBy { it.type }.mapValues { it.value.size }

        // Find type filters that start with the query
        return MentionParser.TYPE_FILTERS.entries
            .filter { (keyword, _) -> keyword.startsWith(query, ignoreCase = true) }
            .distinctBy { it.value } // One entry per NoteType
            .mapNotNull { (_, type) ->
                val count = typeCounts[type] ?: 0
                if (count > 0) {
                    MentionSuggestion.TypeFilter(
                        type = type,
                        displayName = MentionParser.getTypeFilterDisplayName(type),
                        keyword = MentionParser.getTypeFilterKeyword(type),
                        count = count
                    )
                } else null
            }
            .sortedByDescending { it.count }
    }

    /**
     * Get special filters that match the query.
     */
    private suspend fun getMatchingSpecialFilters(query: String): List<MentionSuggestion.SpecialFilter> {
        refreshCacheIfNeeded()

        val aiVisibleNotes = getAiVisibleNotes()

        return MentionParser.SPECIAL_FILTERS
            .filter { it.startsWith(query, ignoreCase = true) }
            .map { filterName ->
                val (displayName, count) = when (filterName) {
                    "recent" -> "Recent" to minOf(aiVisibleNotes.size, RECENT_NOTES_LIMIT)
                    "pinned", "starred", "favorites" -> "Pinned" to aiVisibleNotes.count { it.isPinned }
                    "all" -> "All Notes" to aiVisibleNotes.size
                    else -> filterName.replaceFirstChar { it.uppercase() } to 0
                }
                MentionSuggestion.SpecialFilter(
                    filterName = filterName,
                    displayName = displayName,
                    description = MentionParser.getSpecialFilterDescription(filterName),
                    count = count
                )
            }
            .filter { it.count > 0 }
    }

    /**
     * Search categories by name.
     */
    private suspend fun searchCategories(query: String): List<MentionSuggestion.CategorySuggestion> {
        val categories = categoryDao.searchCategories(query)
        return categories.map { category ->
            MentionSuggestion.CategorySuggestion(
                category = category,
                score = 1.0 // High confidence since it's a direct DB search match
            )
        }
    }

    /**
     * Search notes by title using hybrid approach for autocomplete.
     *
     * IMPROVED: Uses combination of SemanticSearchEngine and MentionParser's
     * similarity calculation for better handling of space-separated queries.
     */
    private suspend fun searchNotesByTitle(query: String, limit: Int): List<MentionSuggestion.NoteSuggestion> {
        refreshCacheIfNeeded()

        val aiVisibleNotes = getAiVisibleNotes()
        if (aiVisibleNotes.isEmpty()) return emptyList()

        // First, try SemanticSearchEngine for fuzzy matching
        val semanticResults = SemanticSearchEngine.search(
            query = query,
            items = aiVisibleNotes,
            textExtractor = { note ->
                // Search in title, summary, and category name
                listOfNotNull(
                    note.title,
                    note.summary?.take(100),
                    note.categoryName
                )
            },
            minScore = MIN_SEARCH_SCORE
        )

        // Also calculate MentionParser similarity for space-separated query handling
        val parserResults = aiVisibleNotes
            .map { note ->
                val titleScore = MentionParser.calculateSimilarity(query, note.title)
                val summaryScore = note.summary?.let { MentionParser.calculateSimilarity(query, it.take(100)) } ?: 0.0
                val combinedScore = maxOf(titleScore, summaryScore * 0.7) // Title weighted higher
                Pair(note, combinedScore)
            }
            .filter { it.second >= MIN_SEARCH_SCORE }
            .sortedByDescending { it.second }

        // Merge results, preferring higher scores from either source
        val mergedResults = mutableMapOf<String, Pair<Note, Double>>()

        // Add semantic search results
        semanticResults.forEach { result ->
            mergedResults[result.item.id] = Pair(result.item, result.score)
        }

        // Add parser results, keeping higher score
        parserResults.forEach { (note, score) ->
            val existing = mergedResults[note.id]
            if (existing == null || score > existing.second) {
                mergedResults[note.id] = Pair(note, score)
            }
        }

        return mergedResults.values
            .sortedByDescending { it.second }
            .take(limit)
            .map { (note, score) ->
                MentionSuggestion.NoteSuggestion(
                    note = note,
                    score = score
                )
            }
    }

    /**
     * Search notes by title for mention resolution (more lenient matching).
     */
    private suspend fun searchNotesByTitleExact(query: String): List<Note> {
        val aiVisibleNotes = getAiVisibleNotes()
        if (aiVisibleNotes.isEmpty()) return emptyList()

        // First try exact title match (case-insensitive)
        val exactMatches = aiVisibleNotes.filter {
            it.title.equals(query, ignoreCase = true) ||
            SemanticSearchEngine.normalizeText(it.title) == query
        }
        if (exactMatches.isNotEmpty()) return exactMatches

        // Fall back to semantic search
        val results = SemanticSearchEngine.search(
            query = query,
            items = aiVisibleNotes,
            textExtractor = { note -> listOf(note.title) },
            minScore = MIN_SEARCH_SCORE
        )

        return results.map { it.item }
    }

    /**
     * Refresh cached notes if stale.
     */
    private suspend fun refreshCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCacheTime > cacheValidityMs || cachedNotes.isEmpty()) {
            try {
                cachedNotes = noteDao.getAllNotes().first()
                lastCacheTime = now
                Log.d(TAG, "Cache refreshed with ${cachedNotes.size} notes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh cache", e)
            }
        }
    }

    /**
     * Get notes visible to AI (respecting privacy settings).
     * Filters out:
     * - Archived notes
     * - Notes with excludeFromAiChat = true
     * - Notes with isFullPrivacy = true
     * - Binary content types (video, audio, archive, apk) - content not readable
     */
    private fun getAiVisibleNotes(): List<Note> {
        return cachedNotes.filter { note ->
            !note.isArchived &&
            !note.excludeFromAiChat &&
            !note.isFullPrivacy
        }
    }

    /**
     * Check if a note type has text content sendable to AI.
     * Binary types like audio/video don't have readable text content.
     */
    fun hasReadableContent(type: NoteType): Boolean {
        return when (type) {
            NoteType.BRAIN_DUMP,
            NoteType.YOUTUBE,
            NoteType.WEBSITE,
            NoteType.IMAGE,  // Has AI-generated description
            NoteType.TWITTER,
            NoteType.INSTAGRAM,
            NoteType.DOCUMENT,
            NoteType.SPREADSHEET,
            NoteType.PRESENTATION,
            NoteType.CODE -> true

            NoteType.VIDEO,
            NoteType.AUDIO,
            NoteType.ARCHIVE,
            NoteType.APK,
            NoteType.FILE -> false
        }
    }

    /**
     * Clear the cache (call when notes are significantly updated).
     */
    fun invalidateCache() {
        cachedNotes = emptyList()
        lastCacheTime = 0
    }
}
