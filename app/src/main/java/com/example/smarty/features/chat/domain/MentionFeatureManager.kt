package com.example.smarty.features.chat.domain

import android.util.Log
import com.example.smarty.core.common.util.mention.MentionParser
import com.example.smarty.core.common.util.search.SemanticSearchEngine
import com.example.smarty.core.domain.model.*
import com.example.smarty.data.repository.SmartyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Centralized manager for the Mention System (@notes, #categories, /commands).
 * Hybridizes logic for:
 * - Autocomplete suggestions
 * - Mention resolution (parsing to actual notes)
 * - Command detection (@analyze)
 * - Type filtering (@documents, @images)
 *
 * This manager ensures the AI and UI use identical logic for resolving context.
 */
class MentionFeatureManager(
    private val repository: SmartyRepository,
) {
    /**
     * Get the application context for resource resolution.
     */
    fun getContext(): android.content.Context = repository.getApplicationContext()

    companion object {
        private const val TAG = "MentionFeatureManager"
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
     */
    suspend fun getSuggestions(query: String): List<MentionSuggestion> =
        withContext(Dispatchers.Default) {
            if (query.isBlank()) {
                return@withContext getDefaultSuggestions()
            }

            val normalizedQuery = MentionParser.normalizeQuery(query)
            Log.d(TAG, "Getting suggestions for query: '$query' -> normalized: '$normalizedQuery'")

            val suggestions = mutableListOf<MentionSuggestion>()

            // 0. Check for command matches (like @analyze)
            val matchingCommands = getMatchingCommands(normalizedQuery)
            suggestions.addAll(matchingCommands.take(1))

            // 1. Check for type filter matches
            val matchingTypeFilters = getMatchingTypeFilters(normalizedQuery)
            suggestions.addAll(matchingTypeFilters.take(2))

            // 2. Check for special filter matches
            val matchingSpecialFilters = getMatchingSpecialFilters(normalizedQuery)
            suggestions.addAll(matchingSpecialFilters.take(1))

            // 3. Check for category matches
            val categoryMatches = searchCategories(normalizedQuery)
            suggestions.addAll(categoryMatches.take(2))

            // 4. Search for matching notes
            val remainingSlots = MAX_SUGGESTIONS - suggestions.size
            if (remainingSlots > 0) {
                val noteMatches = searchNotesByTitle(normalizedQuery, remainingSlots)
                suggestions.addAll(noteMatches)
            }

            return@withContext suggestions.take(MAX_SUGGESTIONS)
        }

    /**
     * Resolve parsed mentions to actual notes.
     */
    suspend fun resolveMentions(parsedMentions: List<ParsedMention>): List<ResolvedMention> =
        withContext(Dispatchers.Default) {
            if (parsedMentions.isEmpty()) return@withContext emptyList()

            refreshCacheIfNeeded()

            val resolved = mutableListOf<ResolvedMention>()

            for (mention in parsedMentions) {
                val normalizedQuery = MentionParser.normalizeQuery(mention.query)

                // 1. Check type filter
                val typeFilter = MentionParser.matchTypeFilter(normalizedQuery)
                if (typeFilter != null) {
                    val notes = getAiVisibleNotes().filter { it.type == typeFilter }
                    resolved.add(
                        ResolvedMention(
                            parsedMention = mention,
                            type = MentionType.TYPE_FILTER,
                            notes = notes.take(50),
                            noteType = typeFilter,
                        ),
                    )
                    continue
                }

                // 2. Check special filter
                val specialFilter = MentionParser.matchSpecialFilter(normalizedQuery)
                if (specialFilter != null) {
                    val notes =
                        when (specialFilter) {
                            "recent" ->
                                getAiVisibleNotes()
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
                            specialFilter = specialFilter,
                        ),
                    )
                    continue
                }

                // 3. Check category
                val category = repository.getCategoryByName(normalizedQuery)
                if (category != null) {
                    val notes = getAiVisibleNotes().filter { it.categoryId == category.id }
                    resolved.add(
                        ResolvedMention(
                            parsedMention = mention,
                            type = MentionType.CATEGORY,
                            notes = notes.take(50),
                            category = category,
                        ),
                    )
                    continue
                }

                // 4. Search for note by title
                val matchingNotes = searchNotesByTitleExact(normalizedQuery)
                resolved.add(
                    ResolvedMention(
                        parsedMention = mention,
                        type = MentionType.SINGLE_NOTE,
                        notes = matchingNotes.take(5),
                    ),
                )
            }

            return@withContext resolved
        }

    private suspend fun getDefaultSuggestions(): List<MentionSuggestion> {
        refreshCacheIfNeeded()
        val suggestions = mutableListOf<MentionSuggestion>()

        MentionParser.COMMANDS["analyze"]?.let { analyzeInfo ->
            suggestions.add(
                MentionSuggestion.CommandSuggestion(
                    commandName = analyzeInfo.name,
                    displayName = analyzeInfo.getDisplayName(repository.getApplicationContext()),
                    description = analyzeInfo.getDescription(repository.getApplicationContext()),
                    icon = analyzeInfo.icon,
                ),
            )
        }

        val aiVisibleNotes = getAiVisibleNotes()
        val pinnedCount = aiVisibleNotes.count { it.isPinned }

        suggestions.add(
            MentionSuggestion.SpecialFilter(
                filterName = "recent",
                displayName = repository.getApplicationContext().getString(com.example.smarty.R.string.recent),
                description = MentionParser.getSpecialFilterDescription(repository.getApplicationContext(), "recent"),
                count = minOf(aiVisibleNotes.size, RECENT_NOTES_LIMIT),
            ),
        )

        if (pinnedCount > 0) {
            suggestions.add(
                MentionSuggestion.SpecialFilter(
                    filterName = "pinned",
                    displayName = repository.getApplicationContext().getString(com.example.smarty.R.string.pin),
                    description = MentionParser.getSpecialFilterDescription(repository.getApplicationContext(), "pinned"),
                    count = pinnedCount,
                ),
            )
        }

        val remainingSlots = MAX_SUGGESTIONS - suggestions.size
        if (remainingSlots > 0) {
            val typeCounts = aiVisibleNotes.groupBy { it.type }.mapValues { it.value.size }
            val topTypes =
                typeCounts.entries
                    .sortedByDescending { it.value }
                    .take(remainingSlots)
                    .filter { it.value > 0 }

            for ((type, count) in topTypes) {
                suggestions.add(
                    MentionSuggestion.TypeFilter(
                        type = type,
                        displayName = MentionParser.getTypeFilterDisplayName(repository.getApplicationContext(), type),
                        keyword = MentionParser.getTypeFilterKeyword(type),
                        count = count,
                    ),
                )
            }
        }

        return suggestions.take(MAX_SUGGESTIONS)
    }

    private fun getMatchingCommands(query: String): List<MentionSuggestion.CommandSuggestion> {
        return MentionParser.getMatchingCommands(query).map { cmdInfo ->
            MentionSuggestion.CommandSuggestion(
                commandName = cmdInfo.name,
                displayName = cmdInfo.getDisplayName(repository.getApplicationContext()),
                description = cmdInfo.getDescription(repository.getApplicationContext()),
                icon = cmdInfo.icon,
            )
        }
    }

    private suspend fun getMatchingTypeFilters(query: String): List<MentionSuggestion.TypeFilter> {
        refreshCacheIfNeeded()
        val aiVisibleNotes = getAiVisibleNotes()
        val typeCounts = aiVisibleNotes.groupBy { it.type }.mapValues { it.value.size }

        return MentionParser.TYPE_FILTERS.entries
            .filter { (keyword, _) -> keyword.startsWith(query, ignoreCase = true) }
            .distinctBy { it.value }
            .mapNotNull { (_, type) ->
                val count = typeCounts[type] ?: 0
                if (count > 0) {
                    MentionSuggestion.TypeFilter(
                        type = type,
                        displayName = MentionParser.getTypeFilterDisplayName(repository.getApplicationContext(), type),
                        keyword = MentionParser.getTypeFilterKeyword(type),
                        count = count,
                    )
                } else {
                    null
                }
            }
            .sortedByDescending { it.count }
    }

    private suspend fun getMatchingSpecialFilters(query: String): List<MentionSuggestion.SpecialFilter> {
        refreshCacheIfNeeded()
        val aiVisibleNotes = getAiVisibleNotes()
        val context = repository.getApplicationContext()

        return MentionParser.SPECIAL_FILTERS
            .filter { it.startsWith(query, ignoreCase = true) }
            .map { filterName ->
                val (displayName, count) =
                    when (filterName) {
                        "recent" -> context.getString(com.example.smarty.R.string.recent) to minOf(aiVisibleNotes.size, RECENT_NOTES_LIMIT)
                        "pinned", "starred", "favorites" -> context.getString(com.example.smarty.R.string.pin) to aiVisibleNotes.count { it.isPinned }
                        "all" -> context.getString(com.example.smarty.R.string.notes) to aiVisibleNotes.size
                        else -> filterName.replaceFirstChar { it.uppercase() } to 0
                    }
                MentionSuggestion.SpecialFilter(
                    filterName = filterName,
                    displayName = displayName,
                    description = MentionParser.getSpecialFilterDescription(context, filterName),
                    count = count,
                )
            }
            .filter { it.count > 0 }
    }

    private suspend fun searchCategories(query: String): List<MentionSuggestion.CategorySuggestion> {
        // We'll need to add searchCategories to SmartyRepository if it doesn't exist
        // For now, using the cached approach or direct repo access if we update it
        val categories = repository.getAllCategories().first()
        return categories.filter { it.name.contains(query, ignoreCase = true) }
            .map { category ->
                MentionSuggestion.CategorySuggestion(
                    category = category,
                    score = 1.0,
                )
            }
    }

    private suspend fun searchNotesByTitle(
        query: String,
        limit: Int,
    ): List<MentionSuggestion.NoteSuggestion> {
        refreshCacheIfNeeded()
        val aiVisibleNotes = getAiVisibleNotes()
        if (aiVisibleNotes.isEmpty()) return emptyList()

        val semanticResults =
            SemanticSearchEngine.search(
                query = query,
                items = aiVisibleNotes,
                textExtractor = { note -> listOfNotNull(note.title, note.summary?.take(100), note.categoryName) },
                minScore = MIN_SEARCH_SCORE,
            )

        val parserResults =
            aiVisibleNotes
                .map { note ->
                    val titleScore = MentionParser.calculateSimilarity(query, note.title)
                    val summaryScore = note.summary?.let { MentionParser.calculateSimilarity(query, it.take(100)) } ?: 0.0
                    Pair(note, maxOf(titleScore, summaryScore * 0.7))
                }
                .filter { it.second >= MIN_SEARCH_SCORE }

        val mergedResults = mutableMapOf<String, Pair<Note, Double>>()
        semanticResults.forEach { mergedResults[it.item.id] = Pair(it.item, it.score) }
        parserResults.forEach { (note, score) ->
            val existing = mergedResults[note.id]
            if (existing == null || score > existing.second) {
                mergedResults[note.id] = Pair(note, score)
            }
        }

        return mergedResults.values
            .sortedByDescending { it.second }
            .take(limit)
            .map { (note, score) -> MentionSuggestion.NoteSuggestion(note, score) }
    }

    private suspend fun searchNotesByTitleExact(query: String): List<Note> {
        val aiVisibleNotes = getAiVisibleNotes()
        if (aiVisibleNotes.isEmpty()) return emptyList()

        val exactMatches =
            aiVisibleNotes.filter {
                it.title.equals(query, ignoreCase = true) ||
                    SemanticSearchEngine.normalizeText(it.title) == query
            }
        if (exactMatches.isNotEmpty()) return exactMatches

        val results =
            SemanticSearchEngine.search(
                query = query,
                items = aiVisibleNotes,
                textExtractor = { note -> listOf(note.title) },
                minScore = MIN_SEARCH_SCORE,
            )
        return results.map { it.item }
    }

    private suspend fun refreshCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCacheTime > cacheValidityMs || cachedNotes.isEmpty()) {
            try {
                cachedNotes = repository.getAllNotes().first()
                lastCacheTime = now
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh cache", e)
            }
        }
    }

    private fun getAiVisibleNotes(): List<Note> {
        return cachedNotes.filter { note ->
            !note.isArchived && !note.excludeFromAiChat && !note.isFullPrivacy
        }
    }

    fun invalidateCache() {
        cachedNotes = emptyList()
        lastCacheTime = 0
    }

    /**
     * Check if a note type has text content sendable to AI.
     */
    fun hasReadableContent(type: NoteType): Boolean {
        return when (type) {
            NoteType.BRAIN_DUMP,
            NoteType.YOUTUBE,
            NoteType.WEBSITE,
            NoteType.IMAGE,
            NoteType.TWITTER,
            NoteType.INSTAGRAM,
            NoteType.DOCUMENT,
            NoteType.SPREADSHEET,
            NoteType.PRESENTATION,
            NoteType.CODE,
            NoteType.WEB_CLIPPING,
            -> true

            NoteType.VIDEO,
            NoteType.AUDIO,
            NoteType.ARCHIVE,
            NoteType.APK,
            NoteType.FILE,
            -> false
        }
    }
}
