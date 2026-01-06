package com.example.smarty.agent.prompts

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dynamic Few-Shot Example Store for tool usage.
 *
 * Based on research recommendation: Store 100+ examples, retrieve 3 most similar
 * for the current query to improve tool selection accuracy.
 *
 * Benefits:
 * - 25-40% improvement in tool selection accuracy
 * - More consistent tool argument formatting
 * - Helps LLM understand tool capabilities through examples
 *
 * Example flow:
 * 1. User: "remind me to call John tomorrow at 3pm"
 * 2. Store finds similar examples (calendar, reminder patterns)
 * 3. System prompt includes: "Example: 'remind me about meeting' -> create_event(title=...)"
 * 4. LLM sees concrete usage patterns before responding
 */
class ToolExampleStore {
    companion object {
        private const val TAG = "ToolExampleStore"
        private const val MAX_EXAMPLES_PER_TOOL = 20
        private const val DEFAULT_RETRIEVE_COUNT = 3
    }

    /**
     * A stored example of tool usage.
     */
    data class ToolExample(
        val toolName: String,
        val userQuery: String,        // What the user said
        val arguments: Map<String, String>,  // Tool arguments used
        val description: String,      // Brief description of what this does
        val keywords: Set<String>,    // Keywords for matching
        val successRate: Float = 1.0f // How successful this pattern is
    )

    // Thread-safe storage
    private val examples = mutableMapOf<String, MutableList<ToolExample>>()
    private val mutex = Mutex()

    init {
        // Pre-populate with curated examples for each tool
        initializeDefaultExamples()
    }

    /**
     * Retrieve the most relevant examples for a given query.
     *
     * @param query User's input query
     * @param toolNames Optional filter to specific tools
     * @param count Number of examples to retrieve (default: 3)
     * @return List of most relevant examples
     */
    suspend fun getRelevantExamples(
        query: String,
        toolNames: List<String>? = null,
        count: Int = DEFAULT_RETRIEVE_COUNT
    ): List<ToolExample> = mutex.withLock {
        val queryKeywords = extractKeywords(query)
        val queryLower = query.lowercase()

        // Score all examples
        val scoredExamples = examples.entries
            .filter { toolNames == null || it.key in toolNames }
            .flatMap { it.value }
            .map { example ->
                val score = calculateSimilarity(queryKeywords, queryLower, example)
                example to score
            }
            .filter { it.second > 0.1f }  // Minimum relevance threshold
            .sortedByDescending { it.second }
            .take(count)
            .map { it.first }

        Log.d(TAG, "Retrieved ${scoredExamples.size} examples for query: '${query.take(50)}'")
        return@withLock scoredExamples
    }

    /**
     * Add a new example to the store (e.g., from successful interactions).
     * AGENT-011: Added validation to prevent invalid examples from degrading accuracy.
     */
    suspend fun addExample(example: ToolExample) = mutex.withLock {
        // AGENT-011: Validate inputs to prevent garbage data
        if (example.toolName.isBlank()) {
            Log.w(TAG, "Rejecting example with blank toolName")
            return@withLock
        }
        if (example.userQuery.isBlank()) {
            Log.w(TAG, "Rejecting example with blank userQuery for tool: ${example.toolName}")
            return@withLock
        }
        if (example.userQuery.length < 3) {
            Log.w(TAG, "Rejecting example with too short userQuery: ${example.userQuery}")
            return@withLock
        }

        val toolExamples = examples.getOrPut(example.toolName) { mutableListOf() }

        // Check for duplicates
        if (toolExamples.none { it.userQuery == example.userQuery }) {
            toolExamples.add(example)

            // Prune if over limit (keep highest success rate)
            if (toolExamples.size > MAX_EXAMPLES_PER_TOOL) {
                toolExamples.sortByDescending { it.successRate }
                toolExamples.removeAt(toolExamples.lastIndex)
            }
        }
    }

    /**
     * Format examples into a prompt-friendly string.
     */
    fun formatExamplesForPrompt(examples: List<ToolExample>): String {
        if (examples.isEmpty()) return ""

        return buildString {
            appendLine("## Relevant Tool Usage Examples:")
            examples.forEachIndexed { index, example ->
                appendLine("${index + 1}. User: \"${example.userQuery}\"")
                appendLine("   Tool: ${example.toolName}(${formatArgs(example.arguments)})")
                appendLine("   Result: ${example.description}")
            }
        }
    }

    /**
     * Calculate similarity between query and example.
     */
    private fun calculateSimilarity(
        queryKeywords: Set<String>,
        queryLower: String,
        example: ToolExample
    ): Float {
        var score = 0f

        // Keyword overlap (weighted by importance)
        val keywordOverlap = queryKeywords.intersect(example.keywords).size
        score += keywordOverlap * 0.3f

        // Substring matching
        if (example.userQuery.lowercase() in queryLower ||
            queryLower in example.userQuery.lowercase()) {
            score += 0.4f
        }

        // Partial keyword matches
        example.keywords.forEach { keyword ->
            if (queryLower.contains(keyword)) {
                score += 0.15f
            }
        }

        // Boost by success rate
        score *= example.successRate

        return score.coerceAtMost(1.0f)
    }

    /**
     * Extract keywords from text for matching.
     */
    private fun extractKeywords(text: String): Set<String> {
        val stopWords = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "must", "shall",
            "can", "need", "dare", "ought", "used", "to", "of", "in",
            "for", "on", "with", "at", "by", "from", "as", "into",
            "through", "during", "before", "after", "above", "below",
            "between", "under", "again", "further", "then", "once",
            "here", "there", "when", "where", "why", "how", "all",
            "each", "few", "more", "most", "other", "some", "such",
            "no", "nor", "not", "only", "own", "same", "so", "than",
            "too", "very", "just", "i", "me", "my", "myself", "we",
            "our", "ours", "you", "your", "he", "him", "she", "her",
            "it", "its", "they", "them", "what", "which", "who"
        )

        return text.lowercase()
            .split(Regex("[\\s,.!?;:]+"))
            .filter { it.length > 2 && it !in stopWords }
            .toSet()
    }

    private fun formatArgs(args: Map<String, String>): String {
        return args.entries.joinToString(", ") { "${it.key}=\"${it.value}\"" }
    }

    /**
     * Initialize with curated examples for common tool patterns.
     */
    private fun initializeDefaultExamples() {
        // Note creation examples
        examples["create_note"] = mutableListOf(
            ToolExample(
                toolName = "create_note",
                userQuery = "write down that I need to buy groceries",
                arguments = mapOf("title" to "Shopping", "content" to "Need to buy groceries"),
                description = "Created note for shopping reminder",
                keywords = setOf("write", "down", "note", "remember", "buy", "groceries")
            ),

            ToolExample(
                toolName = "create_note",
                userQuery = "save this idea for later",
                arguments = mapOf("title" to "Idea", "content" to "User's idea content"),
                description = "Saved idea as note",
                keywords = setOf("save", "idea", "later", "remember")
            )
        )

        // Search examples
        examples["search_notes"] = mutableListOf(
            ToolExample(
                toolName = "search_notes",
                userQuery = "find my notes about meetings",
                arguments = mapOf("query" to "meetings"),
                description = "Found notes containing 'meetings'",
                keywords = setOf("find", "search", "look", "notes", "meeting", "meetings")
            ),
            ToolExample(
                toolName = "search_notes",
                userQuery = "what did I write about the budget?",
                arguments = mapOf("query" to "budget"),
                description = "Searched for budget-related notes",
                keywords = setOf("write", "wrote", "budget", "money", "finance")
            ),
            ToolExample(
                toolName = "search_notes",
                userQuery = "show me everything about John",
                arguments = mapOf("query" to "John"),
                description = "Found notes mentioning John",
                keywords = setOf("show", "everything", "about", "person", "name")
            )
        )

        // Calendar/event examples
        examples["create_event"] = mutableListOf(
            ToolExample(
                toolName = "create_event",
                userQuery = "remind me to call mom tomorrow at 3pm",
                arguments = mapOf(
                    "title" to "Call mom",
                    "dateTime" to "tomorrow 15:00",
                    "reminderMinutes" to "15"
                ),
                description = "Created reminder event",
                keywords = setOf("remind", "call", "tomorrow", "pm", "time", "schedule")
            ),
            ToolExample(
                toolName = "create_event",
                userQuery = "schedule a meeting for Monday at 10am",
                arguments = mapOf(
                    "title" to "Meeting",
                    "dateTime" to "Monday 10:00"
                ),
                description = "Scheduled meeting event",
                keywords = setOf("schedule", "meeting", "monday", "am", "calendar")
            ),
            ToolExample(
                toolName = "create_event",
                userQuery = "set an alarm for 7am",
                arguments = mapOf(
                    "title" to "Alarm",
                    "dateTime" to "07:00",
                    "reminderMinutes" to "0"
                ),
                description = "Created alarm event",
                keywords = setOf("alarm", "set", "wake", "morning", "time")
            )
        )

        // Update examples
        examples["update_note"] = mutableListOf(
            ToolExample(
                toolName = "update_note",
                userQuery = "change the title of my shopping note to Grocery List",
                arguments = mapOf("noteId" to "<ID>", "title" to "Grocery List"),
                description = "Updated note title",
                keywords = setOf("change", "update", "title", "rename", "edit")
            ),
            ToolExample(
                toolName = "update_note",
                userQuery = "add milk to my shopping list",
                arguments = mapOf("noteId" to "<ID>", "appendContent" to "milk"),
                description = "Appended content to note",
                keywords = setOf("add", "append", "include", "list", "shopping")
            )
        )

        // Delete examples
        examples["delete_note"] = mutableListOf(
            ToolExample(
                toolName = "delete_note",
                userQuery = "remove the old meeting notes",
                arguments = mapOf("noteId" to "<ID>"),
                description = "Deleted note",
                keywords = setOf("remove", "delete", "trash", "discard", "old")
            ),
            ToolExample(
                toolName = "delete_note",
                userQuery = "get rid of that note",
                arguments = mapOf("noteId" to "<ID>"),
                description = "Deleted specified note",
                keywords = setOf("rid", "delete", "remove", "clear")
            )
        )

        // Todo examples
        examples["add_todos"] = mutableListOf(
            ToolExample(
                toolName = "add_todos",
                userQuery = "add a task to buy milk",
                arguments = mapOf("noteId" to "<ID>", "todos" to "[\"Buy milk\"]"),
                description = "Added todo item",
                keywords = setOf("task", "todo", "add", "buy", "checklist")
            ),
            ToolExample(
                toolName = "add_todos",
                userQuery = "create a checklist for packing",
                arguments = mapOf(
                    "noteId" to "<ID>",
                    "todos" to "[\"Pack clothes\", \"Pack toiletries\", \"Check passport\"]"
                ),
                description = "Created checklist with multiple items",
                keywords = setOf("checklist", "list", "packing", "travel", "items")
            )
        )

        examples["toggle_todo"] = mutableListOf(
            ToolExample(
                toolName = "toggle_todo",
                userQuery = "mark buy milk as done",
                arguments = mapOf("noteId" to "<ID>", "todoId" to "<TODO_ID>"),
                description = "Marked todo as complete",
                keywords = setOf("mark", "done", "complete", "finish", "check")
            ),
            ToolExample(
                toolName = "toggle_todo",
                userQuery = "I finished the first task",
                arguments = mapOf("noteId" to "<ID>", "todoId" to "<TODO_ID>"),
                description = "Toggled todo completion status",
                keywords = setOf("finished", "completed", "done", "task")
            )
        )

        // List/category examples
        examples["list_categories"] = mutableListOf(
            ToolExample(
                toolName = "list_categories",
                userQuery = "what categories do I have?",
                arguments = emptyMap(),
                description = "Listed all note categories",
                keywords = setOf("categories", "folders", "groups", "organize", "list")
            ),
            ToolExample(
                toolName = "list_categories",
                userQuery = "show me my folders",
                arguments = emptyMap(),
                description = "Displayed category list",
                keywords = setOf("show", "folders", "categories", "labels")
            )
        )

        // Smart search examples
        examples["smart_search"] = mutableListOf(
            ToolExample(
                toolName = "smart_search",
                userQuery = "find notes from last week about work",
                arguments = mapOf("query" to "work", "dateFilter" to "last_week"),
                description = "Smart search with date filter",
                keywords = setOf("find", "last", "week", "work", "recent")
            ),
            ToolExample(
                toolName = "smart_search",
                userQuery = "search for important notes",
                arguments = mapOf("query" to "important", "includeArchived" to "false"),
                description = "Searched active important notes",
                keywords = setOf("search", "important", "priority", "key")
            )
        )

        // Archive examples
        examples["archive_note"] = mutableListOf(
            ToolExample(
                toolName = "archive_note",
                userQuery = "archive the old project notes",
                arguments = mapOf("noteId" to "<ID>"),
                description = "Archived note",
                keywords = setOf("archive", "old", "store", "hide", "project")
            )
        )

        // Web Search examples - Added for better knowledge retrieval
        examples["web_search"] = mutableListOf(
            ToolExample(
                toolName = "web_search",
                userQuery = "who won the super bowl this year",
                arguments = mapOf("query" to "super bowl winner 2025", "reason" to "User asked for current event result"),
                description = "Searched for recent sports event",
                keywords = setOf("won", "winner", "super", "bowl", "score", "game")
            ),
            ToolExample(
                toolName = "web_search",
                userQuery = "what is the stock price of Apple",
                arguments = mapOf("query" to "Apple stock price", "topic" to "finance", "reason" to "User asked for real-time stock data"),
                description = "Searched for stock price",
                keywords = setOf("stock", "price", "market", "value", "cost", "share")
            ),
            ToolExample(
                toolName = "web_search",
                userQuery = "latest news about AI",
                arguments = mapOf("query" to "artificial intelligence news", "topic" to "news", "reason" to "User asked for latest news"),
                description = "Searched for news topic",
                keywords = setOf("news", "latest", "update", "happening", "headlines")
            ),
            ToolExample(
                toolName = "web_search",
                userQuery = "when is diwali this year",
                arguments = mapOf("query" to "Diwali date 2025", "reason" to "User asked for holiday date"),
                description = "Searched for holiday date",
                keywords = setOf("when", "date", "festival", "holiday", "calendar")
            )
        )



        // App Launch examples
        examples["open_app"] = mutableListOf(
            ToolExample(
                toolName = "open_app",
                userQuery = "open spotify",
                arguments = mapOf("appName" to "Spotify"),
                description = "Opened Spotify",
                keywords = setOf("open", "launch", "start", "play", "music", "app")
            ),
            ToolExample(
                toolName = "open_app",
                userQuery = "take me to whatsapp",
                arguments = mapOf("appName" to "WhatsApp"),
                description = "Opened WhatsApp",
                keywords = setOf("open", "launch", "chat", "message", "whatsapp")
            ),
            ToolExample(
                toolName = "open_app",
                userQuery = "open calculator",
                arguments = mapOf("appName" to "Calculator"),
                description = "Opened Calculator",
                keywords = setOf("calculate", "math", "open", "tool")
            )
        )

        Log.d(TAG, "Initialized ${examples.values.sumOf { it.size }} default tool examples")
    }

    /**
     * Get statistics about the example store.
     */
    fun getStats(): StoreStats {
        return StoreStats(
            totalExamples = examples.values.sumOf { it.size },
            toolsCovered = examples.size,
            examplesPerTool = examples.mapValues { it.value.size }
        )
    }

    data class StoreStats(
        val totalExamples: Int,
        val toolsCovered: Int,
        val examplesPerTool: Map<String, Int>
    )
}
