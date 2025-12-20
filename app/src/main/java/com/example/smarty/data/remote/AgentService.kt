package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.data.local.AIMemoryDao
import com.example.smarty.data.local.ChatDao
import com.example.smarty.data.model.*
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.util.ContentSecurityFilter
import com.example.smarty.util.PrivacyGuard
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wrapper for agent response containing both the chat message and parsed action.
 * This ensures the parsed action is available for execution without re-parsing.
 */
data class AgentChatResponse(
    val message: ChatMessage,
    val parsedAction: AgentAction?
)

/**
 * Cogni Agent Service - Powerful AI assistant for note management
 *
 * SECURITY: All note access is filtered through PrivacyGuard.
 * Private notes (isFullPrivacy=true OR excludeFromAiChat=true) are INVISIBLE to the agent.
 * The agent cannot read, modify, delete, or even know about private notes.
 *
 * MEMORY: AI memories are stored persistently and included in context.
 * Memories are abstract patterns/preferences, never raw private content.
 *
 * CONVERSATION HISTORY: Past session summaries are included for context continuity.
 * Summaries are abstract and privacy-safe.
 */
class AgentService(
    private val aiService: AIService,
    private val repository: CogniRepository,
    private val aiMemoryDao: AIMemoryDao? = null,  // Optional - for memory integration
    private val chatDao: ChatDao? = null,          // Optional - for conversation summaries
    private val currentSessionId: String? = null   // Current session to exclude from summaries
) {
    companion object {
        private const val TAG = "AgentService"
        private const val MAX_CONTEXT_CHARS = 12000
        private const val MAX_NOTES_IN_CONTEXT = 20
        private const val MAX_CHAT_HISTORY = 8

        // BUG-032: Context window overflow handling constants
        private const val MAX_NOTE_CONTENT_CHARS = 400
        private const val MAX_CHAT_MSG_CHARS = 150
        private const val CONTEXT_SAFETY_MARGIN = 1000  // Reserve for response
        private const val EFFECTIVE_CONTEXT_LIMIT = MAX_CONTEXT_CHARS - CONTEXT_SAFETY_MARGIN

        // BUG-037/044: Action validation constants
        private const val MAX_NOTE_CONTENT_LENGTH = 50000
        private const val MAX_TITLE_LENGTH = 500
        private const val MAX_CATEGORY_LENGTH = 100
        private const val MAX_SEARCH_QUERY_LENGTH = 500
        private const val MAX_DESCRIPTION_LENGTH = 1000
        private const val MAX_TODO_LENGTH = 500
        private const val MAX_TODOS_PER_ACTION = 50
        private const val MAX_QUESTION_LENGTH = 2000
        private const val MAX_CONTEXT_LENGTH = 5000
        private const val MAX_BATCH_ACTIONS = 10

        /**
         * COGNI AGENT SYSTEM PROMPT
         *
         * Comprehensive instructions for:
         * - Precise tool execution
         * - Proper JSON response format
         * - Agentic workflow handling
         * - Web search result processing
         * - Audio playback control
         */
        private val AGENT_SYSTEM_PROMPT = """
# COGNI AI AGENT - SYSTEM INSTRUCTIONS

You are COGNI, an AI agent embedded in a notes app. You have DIRECT ACCESS to user notes through tools. You operate in an AGENTIC LOOP where you can call tools and process their results.

## IDENTITY
- ACTION-ORIENTED agent, not just a chatbot
- EXECUTE operations on notes directly
- NEVER hallucinate or fabricate note content
- Be warm, concise, and helpful

## RESPONSE FORMAT - CRITICAL
ALWAYS respond with raw JSON. No markdown. No code blocks. Just JSON:
{"action": "ACTION_NAME", "params": {...}, "response": "Human-readable message"}

If no tool needed:
{"action": "ANSWER_QUESTION", "params": {}, "response": "Your conversational response here"}

---

## AGENTIC WORKFLOW - HOW YOU OPERATE

You run in a LOOP. Here's the exact flow:

### STEP 1: RECEIVE REQUEST
User sends a message. Analyze what they want.

### STEP 2: DECIDE - TOOL OR DIRECT RESPONSE?
- Need external info? -> Use WEB_SEARCH
- Need to play audio? -> Use PLAY_AUDIO
- Need to modify notes? -> Use appropriate note tool
- Can answer directly? -> Use ANSWER_QUESTION

### STEP 3: CALL TOOL (if needed)
Return JSON with the tool call. System executes it.

### STEP 4: RECEIVE TOOL RESULT
System sends: "TOOL EXECUTION COMPLETED. Tool: [name]. Result: [data]"

**CRITICAL**: The result is JSON. Parse it and use the data!

### STEP 5: RESPOND WITH RESULTS
Use the tool result to formulate your final response to user.

---

## WEB_SEARCH TOOL - DETAILED INSTRUCTIONS

### FORMAT
{"action": "WEB_SEARCH", "params": {"query": "search terms", "reason": "why searching", "topic": "general", "maxResults": 5}, "response": "Searching..."}

### PARAMETERS
- query (REQUIRED): What to search for
- reason (REQUIRED): Why this search is needed (for logging)
- topic: "general" | "news" | "finance" (default: "general")
- maxResults: 1-10 (default: 5)

### WHEN TO USE
- Current events, news, recent information
- Facts you don't know or need verification
- Real-time data (weather, stocks, sports)
- User explicitly says "search for..."

### WHEN NOT TO USE
- Casual conversation
- Questions answerable from user's notes
- Simple reasoning or opinion questions
- Common knowledge you already have

### PROCESSING WEB SEARCH RESULTS
When you receive "TOOL EXECUTION COMPLETED" with WebSearch results, the result is JSON:

```
{
  "status": "success",
  "query": "original query",
  "ai_summary": "AI-generated summary of findings",
  "results": [
    {"title": "Article Title", "url": "https://...", "snippet": "preview text"},
    ...
  ],
  "total_results": 5
}
```

**YOU MUST**:
1. Parse this JSON mentally
2. Extract the ai_summary if present
3. Cite sources with their URLs
4. Summarize findings for the user
5. DO NOT just say "I searched" - give actual results!

### WEB SEARCH EXAMPLE FLOW
User: "What's the latest news about AI?"

YOU respond:
{"action": "WEB_SEARCH", "params": {"query": "latest AI news December 2024", "reason": "User wants current AI news", "topic": "news", "maxResults": 5}, "response": "Let me search for the latest AI news..."}

SYSTEM returns: TOOL EXECUTION COMPLETED. Tool: WebSearch. Result: {"status":"success","ai_summary":"Recent AI developments include...","results":[{"title":"OpenAI releases...","url":"https://...","snippet":"..."}],"total_results":3}

YOU respond:
{"action": "ANSWER_QUESTION", "params": {}, "response": "Here's the latest AI news:\n\n1. **OpenAI releases...** - [Source](https://...)\n2. **Google announces...** - [Source](https://...)\n\nKey takeaway: [summary]"}

---

## PLAY_AUDIO TOOL - DETAILED INSTRUCTIONS

### FORMAT
{"action": "PLAY_AUDIO", "params": {"query": "what to play", "noteId": "optional-note-id", "attachmentIndex": 0}, "response": "Playing..."}

### PARAMETERS
- query (REQUIRED): Description of what to play
- noteId (PREFERRED): Direct note ID if you have it from context
- attachmentIndex: Which attachment if note has multiple (default: 0)
- source: "youtube" | "spotify" | "local" (optional)

### RULES
1. **ALWAYS prefer noteId** if you see a note in the RELEVANT NOTES section
2. Use query to search only if no noteId available
3. Confirm which audio before playing if ambiguous

### EXAMPLE
If context shows: "Note 3 - ID: `abc123` - Title: Jazz Mix - Type: AUDIO"

User: "Play that jazz"
{"action": "PLAY_AUDIO", "params": {"query": "jazz", "noteId": "abc123"}, "response": "Playing Jazz Mix."}

---

## ALL TOOLS REFERENCE

### NOTE MANAGEMENT
| Tool | Format | Required Params |
|------|--------|-----------------|
| CREATE_NOTE | {"action": "CREATE_NOTE", "params": {"content": "...", "title": "...", "category": "..."}} | content |
| SEARCH_NOTES | {"action": "SEARCH_NOTES", "params": {"query": "...", "limit": 10}} | query |
| DELETE_NOTE | {"action": "DELETE_NOTE", "params": {"noteId": "..." OR "description": "..."}} | noteId or description |
| ARCHIVE_NOTE | {"action": "ARCHIVE_NOTE", "params": {"noteId": "..."}} | noteId or description |
| UNARCHIVE_NOTE | {"action": "UNARCHIVE_NOTE", "params": {"noteId": "..."}} | noteId or description |
| UPDATE_NOTE | {"action": "UPDATE_NOTE", "params": {"noteId": "...", "newContent": "...", "newTitle": "...", "newCategory": "..."}} | noteId |
| SUMMARIZE_NOTE | {"action": "SUMMARIZE_NOTE", "params": {"noteId": "..."}} | noteId |

### TODO MANAGEMENT
| Tool | Format | Required Params |
|------|--------|-----------------|
| ADD_TODOS | {"action": "ADD_TODOS", "params": {"noteId": "...", "todos": ["item1", "item2"]}} | noteId, todos |
| TOGGLE_TODO | {"action": "TOGGLE_TODO", "params": {"noteId": "...", "todoId": "..."}} | noteId, todoId |
| DELETE_TODO | {"action": "DELETE_TODO", "params": {"noteId": "...", "todoId": "..."}} | noteId, todoId |

### CATEGORY & INFO
| Tool | Format | Required Params |
|------|--------|-----------------|
| LIST_CATEGORIES | {"action": "LIST_CATEGORIES", "params": {}} | none |
| GET_CATEGORY_NOTES | {"action": "GET_CATEGORY_NOTES", "params": {"categoryName": "..."}} | categoryName |
| ANSWER_QUESTION | {"action": "ANSWER_QUESTION", "params": {"question": "..."}} | none |
| SUGGEST_ACTIONS | {"action": "SUGGEST_ACTIONS", "params": {"context": "..."}} | context |

### EXTERNAL
| Tool | Format | Required Params |
|------|--------|-----------------|
| WEB_SEARCH | {"action": "WEB_SEARCH", "params": {"query": "...", "reason": "...", "topic": "general"}} | query, reason |
| PLAY_AUDIO | {"action": "PLAY_AUDIO", "params": {"query": "...", "noteId": "..."}} | query |

### BATCH
| Tool | Format | Required Params |
|------|--------|-----------------|
| BATCH_ACTIONS | {"action": "BATCH_ACTIONS", "params": {"actions": [...]}} | actions array |

Categories: Learn, Read, Watch, Idea, Todo, Buy, Meet, Code, Quote, Inspo, Recipe, Health, Finance, Work, Play, Note, Legal

---

## ERROR HANDLING

### If tool returns error:
1. Acknowledge the error to user
2. Suggest alternative approach
3. Do NOT retry the same failed call immediately

### If tool returns empty:
1. Tell user honestly: "I didn't find any notes about X"
2. Offer to create a note for them
3. Do NOT pretend data exists

### If web search fails:
1. Apologize for the issue
2. Explain you couldn't retrieve web results
3. Offer to answer from your knowledge if possible

---

## EXECUTION RULES

1. **USE EXACT NOTE IDs** from RELEVANT NOTES section
2. **VERIFY BEFORE DELETE** - Ask confirmation first
3. **NO FABRICATION** - Only reference notes you can see
4. **PREFER noteId** over description/query when available
5. **BATCH WHEN NEEDED** - Multiple operations = BATCH_ACTIONS
6. **PROCESS TOOL RESULTS** - Don't ignore what tools return

---

## BEHAVIORAL RULES

- Be CONCISE - No unnecessary explanations
- Be DIRECT - Execute, don't discuss endlessly
- Be HONEST - If you don't know, say so
- Be SAFE - Confirm before destructive actions
- NO EMOJIS - Ever

---

## EXAMPLES

### Creating a note
User: "Save this: Meeting tomorrow at 3pm"
{"action": "CREATE_NOTE", "params": {"content": "Meeting tomorrow at 3pm", "title": "Upcoming Meeting", "category": "Meet"}, "response": "Saved your meeting reminder."}

### Web search with result processing
User: "What's the weather like in NYC?"
{"action": "WEB_SEARCH", "params": {"query": "current weather New York City", "reason": "User wants current weather info", "topic": "general", "maxResults": 3}, "response": "Let me check the current weather..."}

[After receiving results]
{"action": "ANSWER_QUESTION", "params": {}, "response": "Current weather in NYC: 45°F (7°C), partly cloudy. Expected high of 52°F. [Source](url)"}

### Playing audio with noteId
User: "Play my podcast recording" (Context shows note ID: pod123)
{"action": "PLAY_AUDIO", "params": {"query": "podcast", "noteId": "pod123"}, "response": "Playing your podcast recording."}

### Handling search with no results
User: "Find my recipe for lasagna"
[After search returns empty]
{"action": "ANSWER_QUESTION", "params": {}, "response": "I don't see a lasagna recipe in your notes yet. Would you like me to create one for you?"}
""".trimIndent()

        private val STOP_WORDS = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could", "should",
            "may", "might", "must", "shall", "can", "need", "dare", "ought", "used",
            "my", "your", "his", "her", "its", "our", "their", "this", "that", "these", "those",
            "what", "which", "who", "whom", "whose", "when", "where", "why", "how",
            "all", "any", "some", "most", "other", "such", "no", "not", "only", "same",
            "so", "than", "too", "very", "just", "also", "now", "here", "there", "then",
            "about", "from", "into", "with", "for", "and", "but", "or", "i", "me", "you"
        )
    }

    private val gson = Gson()

    /**
     * Mode for agent processing - determines which API key to use
     */
    enum class AgentMode {
        REASONING,    // Uses FIRST API key - for tool execution and reasoning
        FINAL_RESPONSE // Uses SECOND API key - for final user-facing response
    }

    /**
     * Process user message and return agent response with parsed action.
     * Uses the specified AgentMode to determine which API key to use.
     *
     * SECURITY: All notes are filtered through PrivacyGuard before processing.
     * Private notes are completely invisible to this function.
     *
     * @param mode AgentMode.REASONING for tool execution, AgentMode.FINAL_RESPONSE for final answer
     * @return AgentChatResponse containing both the ChatMessage and the parsed AgentAction
     */
    suspend fun processUserMessage(
        userMessage: String,
        attachments: List<Attachment>,
        chatHistory: List<ChatMessage>,
        allNotes: List<Note>,
        allCategories: List<Category>,
        mode: AgentMode = AgentMode.REASONING
    ): AgentChatResponse = withContext(Dispatchers.IO) {
        Log.i(TAG, "Processing: ${userMessage.take(100)}...")

        // Security filter
        val securityCheck = ContentSecurityFilter.sanitizeForChat(userMessage)
        if (securityCheck.riskLevel == ContentSecurityFilter.RiskLevel.BLOCKED) {
            Log.w(TAG, "Message blocked by security filter")
            return@withContext AgentChatResponse(
                message = ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "Request blocked for security reasons. Please rephrase."
                ),
                parsedAction = null
            )
        }

        val sanitizedMessage = securityCheck.sanitizedContent

        // Select relevant notes (SECURITY: PrivacyGuard filters private notes)
        val contextNotes = selectRelevantNotes(sanitizedMessage, allNotes)
        Log.d(TAG, "Context: ${contextNotes.size} notes selected")

        // Get AI memories for context
        val memories = try {
            aiMemoryDao?.getRecentMemories(limit = 10) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load memories: ${e.message}")
            emptyList()
        }

        // Get past session summaries for context continuity
        val pastSummaries = try {
            chatDao?.getRecentSessionSummaries(
                limit = 3,
                excludeSessionId = currentSessionId ?: ""
            ) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load past summaries: ${e.message}")
            emptyList()
        }

        // Build prompt
        // SECURITY: allNotes param is already PrivacyGuard-filtered (passed from CogniViewModel)
        // Used for accurate category counts across ALL AI-visible notes
        val prompt = buildPrompt(
            userMessage = sanitizedMessage,
            attachments = attachments,
            chatHistory = chatHistory.takeLast(MAX_CHAT_HISTORY),
            contextNotes = contextNotes,
            categories = allCategories,
            allNotes = allNotes,  // Full list of AI-visible notes for category counts
            memories = memories,
            pastSummaries = pastSummaries
        )

        // Track memory usage (non-blocking)
        if (memories.isNotEmpty() && aiMemoryDao != null) {
            try {
                memories.forEach { memory ->
                    aiMemoryDao.incrementUsage(memory.id)
                }
                Log.d(TAG, "Updated usage for ${memories.size} memories")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update memory usage: ${e.message}")
            }
        }

        // Call AI with the appropriate API based on mode
        val aiResponse = when (mode) {
            AgentMode.REASONING -> {
                Log.d(TAG, "Using REASONING API (first key) for tool execution")
                aiService.agentReasoning(AGENT_SYSTEM_PROMPT, prompt)
            }
            AgentMode.FINAL_RESPONSE -> {
                Log.d(TAG, "Using FINAL_RESPONSE API (second key) for user response")
                aiService.agentFinalResponse(AGENT_SYSTEM_PROMPT, prompt)
            }
        }
        Log.d(TAG, "Response: ${aiResponse.take(300)}...")

        // Parse response - keep both text and action!
        val (responseText, action) = parseResponse(aiResponse)
        Log.d(TAG, "Parsed action: ${action?.javaClass?.simpleName ?: "none"}")

        val chatMessage = ChatMessage(
            role = ChatRole.ASSISTANT,
            content = responseText,
            executedActions = action?.let {
                listOf(AgentActionResult(
                    action = it.javaClass.simpleName,
                    success = true,
                    resultSummary = "Parsed: ${it.javaClass.simpleName}"
                ))
            } ?: emptyList(),
            referencedNoteIds = contextNotes.map { it.id }
        )

        // Return both the message and the parsed action
        AgentChatResponse(
            message = chatMessage,
            parsedAction = action
        )
    }

    /**
     * Select notes relevant to the query
     *
     * SECURITY: PrivacyGuard.getAiVisibleNotes() is the ONLY gateway.
     * Private notes DO NOT EXIST from this function's perspective.
     */
    private fun selectRelevantNotes(query: String, allNotes: List<Note>): List<Note> {
        val queryLower = query.lowercase()
        val keywords = extractKeywords(queryLower)

        // SECURITY BARRIER: Only AI-visible notes
        val eligibleNotes = PrivacyGuard.getAiVisibleNotes(allNotes)

        if (keywords.isEmpty()) {
            return eligibleNotes.sortedByDescending { it.updatedAt }.take(8)
        }

        val scoredNotes = eligibleNotes.map { note ->
            note to calculateScore(note, keywords, queryLower)
        }.filter { it.second > 0 }
         .sortedByDescending { it.second }
         .take(MAX_NOTES_IN_CONTEXT)

        var totalChars = 0
        val selected = mutableListOf<Note>()

        for ((note, score) in scoredNotes) {
            val chars = note.title.length + (note.summary?.length ?: 0) + minOf(note.content.length, 500)
            if (totalChars + chars > MAX_CONTEXT_CHARS) break
            selected.add(note)
            totalChars += chars
        }

        return selected
    }

    private fun extractKeywords(text: String): List<String> {
        return text.split(Regex("[\\s,;.!?]+"))
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .distinct()
            .take(15)
    }

    private fun calculateScore(note: Note, keywords: List<String>, query: String): Int {
        var score = 0
        val title = note.title.lowercase()
        val content = note.content.lowercase()
        val summary = note.summary?.lowercase() ?: ""
        val category = note.categoryName?.lowercase() ?: ""

        // Exact match in title = highest
        if (title.contains(query)) score += 150

        // Keyword matches
        for (kw in keywords) {
            if (title.contains(kw)) score += 30
            if (summary.contains(kw)) score += 15
            if (content.contains(kw)) score += 8
            if (category.contains(kw)) score += 20
        }

        // Recency bonus
        val daysOld = (System.currentTimeMillis() - note.updatedAt) / (1000 * 60 * 60 * 24)
        if (daysOld < 3) score += 20
        else if (daysOld < 7) score += 10
        else if (daysOld < 30) score += 5

        return score
    }

    /**
     * Build prompt with context overflow protection (BUG-032 fix).
     *
     * Uses priority-based context management:
     * 1. User message (highest priority - never truncated)
     * 2. Context notes (core functionality)
     * 3. Chat history (conversation continuity)
     * 4. Memories (personalization)
     * 5. Categories/summaries (lowest priority - truncated first)
     */
    private fun buildPrompt(
        userMessage: String,
        attachments: List<Attachment>,
        chatHistory: List<ChatMessage>,
        contextNotes: List<Note>,
        categories: List<Category>,
        allNotes: List<Note>,  // All AI-visible notes for accurate counts
        memories: List<AIMemory> = emptyList(),
        pastSummaries: List<ChatSession> = emptyList()
    ): String {
        // Track context budget (BUG-032)
        var remainingBudget = EFFECTIVE_CONTEXT_LIMIT

        // 1. User message section (highest priority - always include full)
        val userSection = buildString {
            append("## CURRENT REQUEST\n")
            append(userMessage)
            if (attachments.isNotEmpty()) {
                append("\n\nAttachments: ${attachments.joinToString { it.fileName }}")
            }
            append("\n\n## RESPOND WITH JSON ONLY")
        }
        remainingBudget -= userSection.length

        // 2. Context notes (core functionality - use proportional budget)
        val noteBudget = (remainingBudget * 0.5).toInt().coerceAtLeast(2000)
        val notesSection = buildNotesSection(contextNotes, noteBudget)
        remainingBudget -= notesSection.length

        // 3. Chat history (conversation continuity - limit based on remaining budget)
        val historyBudget = (remainingBudget * 0.3).toInt().coerceAtLeast(500)
        val historySection = buildHistorySection(chatHistory, historyBudget)
        remainingBudget -= historySection.length

        // 4. Memories (personalization)
        val memoryBudget = (remainingBudget * 0.5).toInt().coerceAtLeast(300)
        val memorySection = buildMemorySection(memories, memoryBudget)
        remainingBudget -= memorySection.length

        // 5. Categories with AI-safe counts
        val categoryBudget = (remainingBudget * 0.5).toInt().coerceAtLeast(200)
        val categorySection = buildCategorySection(categories, allNotes, categoryBudget)
        remainingBudget -= categorySection.length

        // 6. Past summaries (lowest priority)
        val summarySection = if (remainingBudget > 200) {
            buildSummarySection(pastSummaries, remainingBudget)
        } else ""

        // Log context usage
        val totalUsed = EFFECTIVE_CONTEXT_LIMIT - remainingBudget + summarySection.length
        Log.d(TAG, "Context budget: $totalUsed / $EFFECTIVE_CONTEXT_LIMIT chars used")

        // Assemble prompt in order
        return buildString {
            append(categorySection)
            append(memorySection)
            append(summarySection)
            append(notesSection)
            append(historySection)
            append(userSection)
        }
    }

    /**
     * Build notes section with budget limit
     */
    private fun buildNotesSection(contextNotes: List<Note>, budget: Int): String {
        if (contextNotes.isEmpty()) {
            return "## NO RELEVANT NOTES FOUND\n\n"
        }

        val result = StringBuilder()
        result.append("## RELEVANT NOTES (Use these IDs for operations)\n")
        var usedBudget = result.length

        for ((index, note) in contextNotes.withIndex()) {
            // Calculate note entry size
            val noteEntry = buildString {
                append("### Note ${index + 1}\n")
                append("- ID: `${note.id}`\n")
                append("- Title: ${note.title}\n")
                append("- Category: ${note.categoryName ?: "None"}\n")
                append("- Updated: ${formatTimestamp(note.updatedAt)}\n")
                if (!note.summary.isNullOrBlank()) {
                    append("- Summary: ${note.summary.take(100)}${if ((note.summary?.length ?: 0) > 100) "..." else ""}\n")
                }
                val contentLimit = MAX_NOTE_CONTENT_CHARS.coerceAtMost((budget - usedBudget) / 2)
                if (contentLimit > 50) {
                    append("- Content: ${note.content.take(contentLimit)}${if (note.content.length > contentLimit) "..." else ""}\n")
                }
                append("\n")
            }

            // Check if we have budget for this note
            if (usedBudget + noteEntry.length > budget) {
                result.append("... (${contextNotes.size - index} more notes available)\n\n")
                break
            }

            result.append(noteEntry)
            usedBudget += noteEntry.length
        }

        return result.toString()
    }

    /**
     * Build chat history section with budget limit
     */
    private fun buildHistorySection(chatHistory: List<ChatMessage>, budget: Int): String {
        if (chatHistory.isEmpty()) return ""

        val result = StringBuilder()
        result.append("## CONVERSATION HISTORY\n")
        var usedBudget = result.length

        // Take most recent messages that fit (in chronological order)
        val recentMessages = chatHistory.takeLast(5)
        for (msg in recentMessages) {
            val role = if (msg.role == ChatRole.USER) "USER" else "AGENT"
            val contentLimit = MAX_CHAT_MSG_CHARS.coerceAtMost((budget - usedBudget) / 2)
            val entry = "$role: ${msg.content.take(contentLimit)}${if (msg.content.length > contentLimit) "..." else ""}\n"

            if (usedBudget + entry.length > budget) break
            result.append(entry)
            usedBudget += entry.length
        }

        result.append("\n")
        return result.toString()
    }

    /**
     * Build memory section with budget limit
     */
    private fun buildMemorySection(memories: List<AIMemory>, budget: Int): String {
        if (memories.isEmpty()) return ""

        val result = StringBuilder()
        result.append("## WHAT I REMEMBER ABOUT YOU\n")
        var usedBudget = result.length

        for (memory in memories) {
            val typeLabel = when (memory.type) {
                MemoryType.PREFERENCE -> "[Preference]"
                MemoryType.PATTERN -> "[Pattern]"
                MemoryType.STYLE -> "[Style]"
                MemoryType.FACT -> "[Fact]"
            }
            val entry = "- $typeLabel ${memory.content}\n"

            if (usedBudget + entry.length > budget) break
            result.append(entry)
            usedBudget += entry.length
        }

        result.append("\n")
        return result.toString()
    }

    /**
     * Build category section with AI-safe counts
     */
    private fun buildCategorySection(
        categories: List<Category>,
        allNotes: List<Note>,
        budget: Int
    ): String {
        val aiSafeCounts = PrivacyGuard.getAiSafeCategoryCounts(categories, allNotes)

        val result = StringBuilder()
        result.append("## CATEGORIES\n")
        var usedBudget = result.length

        val sortedCategories = categories
            .map { it to (aiSafeCounts[it.name] ?: 0) }
            .sortedByDescending { it.second }
            .take(15)

        for ((category, count) in sortedCategories) {
            val entry = "- ${category.name}: $count notes\n"
            if (usedBudget + entry.length > budget) break
            result.append(entry)
            usedBudget += entry.length
        }

        result.append("\n")
        return result.toString()
    }

    /**
     * Build past summaries section
     */
    private fun buildSummarySection(pastSummaries: List<ChatSession>, budget: Int): String {
        if (pastSummaries.isEmpty()) return ""

        val result = StringBuilder()
        result.append("## RECENT CONVERSATION CONTEXT\n")
        var usedBudget = result.length

        for (session in pastSummaries) {
            val summary = session.summary ?: continue
            val entry = "- ${summary.take(100)}${if (summary.length > 100) "..." else ""}\n"

            if (usedBudget + entry.length > budget) break
            result.append(entry)
            usedBudget += entry.length
        }

        result.append("(Past conversations - current takes priority)\n\n")
        return result.toString()
    }

    private fun formatTimestamp(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / (1000 * 60)
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> "${days / 7}w ago"
        }
    }

    private fun parseResponse(response: String): Pair<String, AgentAction?> {
        var clean = response.trim()

        // Remove markdown code blocks
        if (clean.startsWith("```json")) {
            clean = clean.substringAfter("```json").substringBefore("```")
        } else if (clean.startsWith("```")) {
            clean = clean.substringAfter("```").substringBefore("```")
        }

        val jsonStart = clean.indexOf("{")
        val jsonEnd = clean.lastIndexOf("}")

        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            Log.w(TAG, "No valid JSON in response")
            return clean to null
        }

        val jsonStr = clean.substring(jsonStart, jsonEnd + 1)

        return try {
            val json = JsonParser.parseString(jsonStr).asJsonObject
            val responseText = json.get("response")?.asString ?: "Done."
            val actionType = json.get("action")?.asString
            val params = json.getAsJsonObject("params")

            val action = parseAction(actionType, params)
            Log.d(TAG, "Parsed action: $actionType")
            responseText to action

        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            "I encountered an error. Please try again." to null
        }
    }

    /**
     * Parse and validate an action from AI response.
     * BUG-037/044 FIX: Added validation for required parameters and ranges.
     */
    private fun parseAction(actionType: String?, params: com.google.gson.JsonObject?): AgentAction? {
        // BUG-037: Validate action type is not null or empty
        if (actionType.isNullOrBlank()) {
            Log.w(TAG, "Invalid action: null or empty action type")
            return null
        }

        return try {
            when (actionType) {
                "CREATE_NOTE" -> {
                    val content = params?.get("content")?.asString
                    if (content.isNullOrBlank()) {
                        Log.w(TAG, "CREATE_NOTE: missing required content")
                        return null
                    }
                    AgentAction.CreateNote(
                        content = content.take(MAX_NOTE_CONTENT_LENGTH), // Limit content length
                        title = params.get("title")?.asString?.take(MAX_TITLE_LENGTH),
                        category = params.get("category")?.asString?.take(MAX_CATEGORY_LENGTH)
                    )
                }
                "SEARCH_NOTES" -> AgentAction.SearchNotes(
                    query = params?.get("query")?.asString?.take(MAX_SEARCH_QUERY_LENGTH) ?: "",
                    category = params?.get("category")?.asString?.take(MAX_CATEGORY_LENGTH),
                    limit = (params?.get("limit")?.asInt ?: 10).coerceIn(1, 50) // BUG-044: Validate range
                )
                "DELETE_NOTE" -> AgentAction.DeleteNote(
                    noteId = validateNoteId(params?.get("noteId")?.asString),
                    description = params?.get("description")?.asString?.take(MAX_DESCRIPTION_LENGTH)
                )
                "ARCHIVE_NOTE" -> AgentAction.ArchiveNote(
                    noteId = validateNoteId(params?.get("noteId")?.asString),
                    description = params?.get("description")?.asString?.take(MAX_DESCRIPTION_LENGTH)
                )
                "UNARCHIVE_NOTE" -> AgentAction.UnarchiveNote(
                    noteId = validateNoteId(params?.get("noteId")?.asString),
                    description = params?.get("description")?.asString?.take(MAX_DESCRIPTION_LENGTH)
                )
                "UPDATE_NOTE" -> {
                    val noteId = validateNoteId(params?.get("noteId")?.asString)
                    if (noteId == null) {
                        Log.w(TAG, "UPDATE_NOTE: missing or invalid noteId")
                        return null
                    }
                    AgentAction.UpdateNote(
                        noteId = noteId,
                        newContent = params?.get("newContent")?.asString?.take(MAX_NOTE_CONTENT_LENGTH),
                        newTitle = params?.get("newTitle")?.asString?.take(MAX_TITLE_LENGTH),
                        newCategory = params?.get("newCategory")?.asString?.take(MAX_CATEGORY_LENGTH)
                    )
                }
                "SUMMARIZE_NOTE" -> {
                    val noteId = validateNoteId(params?.get("noteId")?.asString)
                    if (noteId == null) {
                        Log.w(TAG, "SUMMARIZE_NOTE: missing or invalid noteId")
                        return null
                    }
                    AgentAction.SummarizeNote(noteId = noteId)
                }
                "ADD_TODOS" -> {
                    val noteId = validateNoteId(params?.get("noteId")?.asString)
                    if (noteId == null) {
                        Log.w(TAG, "ADD_TODOS: missing or invalid noteId")
                        return null
                    }
                    val todosArray = params?.getAsJsonArray("todos")
                    val todos = todosArray?.mapNotNull { it.asString?.take(MAX_TODO_LENGTH) }
                        ?.take(MAX_TODOS_PER_ACTION) // BUG-044: Limit number of todos
                        ?: emptyList()
                    AgentAction.AddTodos(noteId = noteId, todos = todos)
                }
                "TOGGLE_TODO" -> {
                    val noteId = validateNoteId(params?.get("noteId")?.asString)
                    val todoId = params?.get("todoId")?.asString
                    if (noteId == null || todoId.isNullOrBlank()) {
                        Log.w(TAG, "TOGGLE_TODO: missing noteId or todoId")
                        return null
                    }
                    AgentAction.ToggleTodo(noteId = noteId, todoId = todoId)
                }
                "DELETE_TODO" -> {
                    val noteId = validateNoteId(params?.get("noteId")?.asString)
                    val todoId = params?.get("todoId")?.asString
                    if (noteId == null || todoId.isNullOrBlank()) {
                        Log.w(TAG, "DELETE_TODO: missing noteId or todoId")
                        return null
                    }
                    AgentAction.DeleteTodo(noteId = noteId, todoId = todoId)
                }
                "LIST_CATEGORIES" -> AgentAction.ListCategories
                "GET_CATEGORY_NOTES" -> AgentAction.GetCategoryNotes(
                    categoryName = params?.get("categoryName")?.asString?.take(MAX_CATEGORY_LENGTH) ?: ""
                )
                "ANSWER_QUESTION" -> AgentAction.AnswerQuestion(
                    question = params?.get("question")?.asString?.take(MAX_QUESTION_LENGTH) ?: ""
                )
                "SUGGEST_ACTIONS" -> AgentAction.SuggestActions(
                    context = params?.get("context")?.asString?.take(MAX_CONTEXT_LENGTH) ?: ""
                )
                "WEB_SEARCH" -> {
                    val query = params?.get("query")?.asString
                    if (query.isNullOrBlank()) {
                        Log.w(TAG, "WEB_SEARCH: missing required query")
                        return null
                    }
                    val topic = params.get("topic")?.asString ?: "general"
                    // BUG-044: Validate topic is valid
                    val validTopic = if (topic in listOf("general", "news", "finance")) topic else "general"
                    AgentAction.WebSearch(
                        query = query.take(MAX_SEARCH_QUERY_LENGTH),
                        reason = params.get("reason")?.asString?.take(MAX_DESCRIPTION_LENGTH) ?: "User requested search",
                        topic = validTopic,
                        maxResults = (params.get("maxResults")?.asInt ?: 5).coerceIn(1, 10)
                    )
                }
                "PLAY_AUDIO" -> AgentAction.PlayAudio(
                    query = params?.get("query")?.asString?.take(MAX_SEARCH_QUERY_LENGTH) ?: "",
                    noteId = params?.get("noteId")?.asString?.let { validateNoteId(it) },
                    attachmentIndex = (params?.get("attachmentIndex")?.asInt ?: 0).coerceIn(0, 10),
                    source = params?.get("source")?.asString
                )
                "BATCH_ACTIONS" -> {
                    val actionsArray = params?.getAsJsonArray("actions")
                    val actions = actionsArray?.mapNotNull { actionJson ->
                        val obj = actionJson.asJsonObject
                        parseAction(obj.get("action")?.asString, obj.getAsJsonObject("params"))
                    }?.take(MAX_BATCH_ACTIONS) // BUG-044: Limit batch size
                        ?: emptyList()
                    if (actions.isEmpty()) {
                        Log.w(TAG, "BATCH_ACTIONS: no valid actions in batch")
                        return null
                    }
                    AgentAction.BatchActions(actions)
                }
                else -> {
                    Log.w(TAG, "Unknown action type: $actionType")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing action $actionType: ${e.message}")
            null
        }
    }

    /**
     * Validate noteId format. Returns null if invalid.
     * BUG-037: Ensures noteId is a valid format before processing.
     */
    private fun validateNoteId(noteId: String?): String? {
        if (noteId.isNullOrBlank()) return null
        // Basic validation - must be a reasonable length and no suspicious characters
        if (noteId.length > 64 || noteId.contains(Regex("[<>\"'\\\\;]"))) {
            Log.w(TAG, "Invalid noteId format: $noteId")
            return null
        }
        return noteId
    }


    /**
     * Find note by description
     * SECURITY: PrivacyGuard filters all private notes
     */
    suspend fun findNoteByDescription(description: String, notes: List<Note>): Note? {
        if (description.isBlank()) return null

        val eligible = PrivacyGuard.filterForAiSearch(notes)
        val descLower = description.lowercase()
        val keywords = extractKeywords(descLower)

        // Exact title match
        eligible.find { it.title.lowercase() == descLower }?.let { return it }

        // Title contains
        eligible.find { it.title.lowercase().contains(descLower) }?.let { return it }

        // Keyword scoring
        return eligible
            .map { note ->
                val score = keywords.fold(0) { acc, kw ->
                    acc + when {
                        note.title.lowercase().contains(kw) -> 15
                        note.content.lowercase().contains(kw) -> 5
                        else -> 0
                    }
                }
                note to score
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    fun getActionFromResponse(response: String): AgentAction? = parseResponse(response).second
}
