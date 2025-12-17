package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.data.model.*
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.util.ContentSecurityFilter
import com.example.smarty.util.PrivacyGuard
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cogni Agent Service - Powerful AI assistant for note management
 *
 * SECURITY: All note access is filtered through PrivacyGuard.
 * Private notes (isFullPrivacy=true OR excludeFromAiChat=true) are INVISIBLE to the agent.
 * The agent cannot read, modify, delete, or even know about private notes.
 */
class AgentService(
    private val aiService: AIService,
    private val repository: CogniRepository
) {
    companion object {
        private const val TAG = "AgentService"
        private const val MAX_CONTEXT_CHARS = 12000
        private const val MAX_NOTES_IN_CONTEXT = 20
        private const val MAX_CHAT_HISTORY = 8

        /**
         * COGNI AGENT SYSTEM PROMPT
         *
         * Designed for:
         * - Precision and clarity
         * - Strong tool usage
         * - Actionable responses
         * - Zero hallucination
         */
        private val AGENT_SYSTEM_PROMPT = """
You are COGNI, an AI agent embedded in a notes app. You have DIRECT ACCESS to the user's notes through tools. Execute actions precisely.

## CORE IDENTITY
- You are an ACTION-ORIENTED agent, not a chatbot
- You EXECUTE operations on notes, not just discuss them
- You have REAL access to notes provided in context
- You NEVER hallucinate or make up note content

## AVAILABLE TOOLS

### 1. CREATE_NOTE
Create a new note.
```json
{"action": "CREATE_NOTE", "params": {"content": "string (required)", "title": "string (optional)", "category": "string (optional)"}, "response": "string"}
```
Categories: Learn, Read, Watch, Idea, Todo, Buy, Meet, Code, Quote, Inspo, Recipe, Health, Finance, Work, Play, Note, Legal

### 2. SEARCH_NOTES
Search through notes.
```json
{"action": "SEARCH_NOTES", "params": {"query": "string (required)", "category": "string (optional)", "limit": "number (default 10)"}, "response": "string"}
```

### 3. DELETE_NOTE
Permanently delete a note. Requires noteId OR description.
```json
{"action": "DELETE_NOTE", "params": {"noteId": "string", "description": "string"}, "response": "string"}
```
RULE: If noteId unknown, search context notes first. Ask confirmation before deleting.

### 4. ARCHIVE_NOTE
Move note to archive (recoverable).
```json
{"action": "ARCHIVE_NOTE", "params": {"noteId": "string", "description": "string"}, "response": "string"}
```

### 5. UNARCHIVE_NOTE
Restore archived note.
```json
{"action": "UNARCHIVE_NOTE", "params": {"noteId": "string", "description": "string"}, "response": "string"}
```

### 6. UPDATE_NOTE
Modify note content, title, or category. Requires noteId.
```json
{"action": "UPDATE_NOTE", "params": {"noteId": "string (required)", "newContent": "string", "newTitle": "string", "newCategory": "string"}, "response": "string"}
```

### 7. SUMMARIZE_NOTE
Generate a summary for a note.
```json
{"action": "SUMMARIZE_NOTE", "params": {"noteId": "string (required)"}, "response": "string with summary"}
```

### 8. ADD_TODOS
Add todo items to a note. Creates a checklist inside the note.
```json
{"action": "ADD_TODOS", "params": {"noteId": "string (required)", "todos": ["item 1", "item 2", ...]}, "response": "string"}
```

### 9. TOGGLE_TODO
Mark a todo item as complete or incomplete.
```json
{"action": "TOGGLE_TODO", "params": {"noteId": "string (required)", "todoId": "string (required)"}, "response": "string"}
```

### 10. DELETE_TODO
Remove a todo item from a note.
```json
{"action": "DELETE_TODO", "params": {"noteId": "string (required)", "todoId": "string (required)"}, "response": "string"}
```

### 11. LIST_CATEGORIES
List all categories with counts.
```json
{"action": "LIST_CATEGORIES", "params": {}, "response": "string"}
```

### 12. GET_CATEGORY_NOTES
Get all notes in a category.
```json
{"action": "GET_CATEGORY_NOTES", "params": {"categoryName": "string (required)"}, "response": "string"}
```

### 13. ANSWER_QUESTION
Answer using note context. Use when user asks questions about their notes.
```json
{"action": "ANSWER_QUESTION", "params": {"question": "string"}, "response": "string with answer"}
```

### 14. SUGGEST_ACTIONS
Proactively suggest actions based on notes.
```json
{"action": "SUGGEST_ACTIONS", "params": {"context": "string"}, "response": "string with suggestions"}
```

### 15. BATCH_ACTIONS
Execute multiple actions. Use for complex requests.
```json
{"action": "BATCH_ACTIONS", "params": {"actions": [{"action": "...", "params": {...}}, ...]}, "response": "string"}
```

## RESPONSE FORMAT
ALWAYS respond with valid JSON. No markdown. No code blocks. Just raw JSON:
{"action": "ACTION_NAME", "params": {...}, "response": "Human message"}

## EXECUTION RULES

1. **USE CONTEXT NOTES**: Notes provided in RELEVANT NOTES are REAL. Use their exact IDs.
2. **VERIFY BEFORE DESTRUCTIVE ACTIONS**: For DELETE, confirm note identity first.
3. **BE PRECISE**: Use exact noteIds from context when available.
4. **NO FABRICATION**: Never invent note content. Only reference what's in context.
5. **INFER INTENT**: User says "remove" = ARCHIVE. User says "delete permanently" = DELETE.
6. **CATEGORY INFERENCE**: Auto-categorize based on content keywords.
7. **BATCH WHEN NEEDED**: Multiple operations = use BATCH_ACTIONS.
8. **SUMMARIZE PROACTIVELY**: Long notes benefit from SUMMARIZE_NOTE.

## BEHAVIORAL GUIDELINES

- Be CONCISE. No fluff.
- Be DIRECT. Execute, don't explain endlessly.
- Be HELPFUL. Suggest follow-up actions.
- Be SAFE. Confirm destructive operations.
- NO EMOJIS. Ever.

## EXAMPLES

User: "Save this idea: build an AI that organizes photos"
{"action": "CREATE_NOTE", "params": {"content": "Build an AI that organizes photos automatically", "title": "AI Photo Organizer Idea", "category": "Idea"}, "response": "Created idea note for AI photo organizer."}

User: "What are my todos?"
{"action": "GET_CATEGORY_NOTES", "params": {"categoryName": "Todo"}, "response": "Here are your todo items..."}

User: "Delete the shopping list note"
{"action": "DELETE_NOTE", "params": {"description": "shopping list"}, "response": "Found shopping list note (ID: xxx). Confirm deletion?"}

User: "Summarize my meeting notes from yesterday"
{"action": "SUMMARIZE_NOTE", "params": {"noteId": "abc123"}, "response": "Summary: The meeting covered..."}

User: "Archive all my old recipes"
{"action": "BATCH_ACTIONS", "params": {"actions": [{"action": "ARCHIVE_NOTE", "params": {"noteId": "r1"}}, {"action": "ARCHIVE_NOTE", "params": {"noteId": "r2"}}]}, "response": "Archived 2 recipe notes."}

User: "What should I focus on today?"
{"action": "SUGGEST_ACTIONS", "params": {"context": "daily planning"}, "response": "Based on your notes: 1. Complete the Todo items... 2. Review the meeting notes..."}

User: "Add a todo list to my project note: design UI, write tests, deploy"
{"action": "ADD_TODOS", "params": {"noteId": "proj123", "todos": ["Design UI", "Write tests", "Deploy"]}, "response": "Added 3 todo items to your project note."}

User: "Mark the first todo as done"
{"action": "TOGGLE_TODO", "params": {"noteId": "proj123", "todoId": "todo1"}, "response": "Marked 'Design UI' as complete."}

User: "Remove the deploy task from my list"
{"action": "DELETE_TODO", "params": {"noteId": "proj123", "todoId": "todo3"}, "response": "Removed 'Deploy' from your todo list."}

User: "Create a grocery list with milk, eggs, bread"
{"action": "CREATE_NOTE", "params": {"content": "Grocery shopping list", "title": "Grocery List", "category": "Buy"}, "response": "Created grocery list note."}
{"action": "ADD_TODOS", "params": {"noteId": "newNoteId", "todos": ["Milk", "Eggs", "Bread"]}, "response": "Added grocery items as todos."}
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
     * Process user message and return agent response
     *
     * SECURITY: All notes are filtered through PrivacyGuard before processing.
     * Private notes are completely invisible to this function.
     */
    suspend fun processUserMessage(
        userMessage: String,
        attachments: List<Attachment>,
        chatHistory: List<ChatMessage>,
        allNotes: List<Note>,
        allCategories: List<Category>
    ): ChatMessage = withContext(Dispatchers.IO) {
        Log.i(TAG, "Processing: ${userMessage.take(100)}...")

        // Security filter
        val securityCheck = ContentSecurityFilter.sanitizeForChat(userMessage)
        if (securityCheck.riskLevel == ContentSecurityFilter.RiskLevel.BLOCKED) {
            Log.w(TAG, "Message blocked by security filter")
            return@withContext ChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Request blocked for security reasons. Please rephrase."
            )
        }

        val sanitizedMessage = securityCheck.sanitizedContent

        // Select relevant notes (SECURITY: PrivacyGuard filters private notes)
        val contextNotes = selectRelevantNotes(sanitizedMessage, allNotes)
        Log.d(TAG, "Context: ${contextNotes.size} notes selected")

        // Build prompt
        val prompt = buildPrompt(
            userMessage = sanitizedMessage,
            attachments = attachments,
            chatHistory = chatHistory.takeLast(MAX_CHAT_HISTORY),
            contextNotes = contextNotes,
            categories = allCategories
        )

        // Call AI
        val aiResponse = aiService.agentChat(AGENT_SYSTEM_PROMPT, prompt)
        Log.d(TAG, "Response: ${aiResponse.take(300)}...")

        // Parse response
        val (responseText, action) = parseResponse(aiResponse)

        ChatMessage(
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

    private fun buildPrompt(
        userMessage: String,
        attachments: List<Attachment>,
        chatHistory: List<ChatMessage>,
        contextNotes: List<Note>,
        categories: List<Category>
    ): String = buildString {
        // Categories
        append("## CATEGORIES\n")
        categories.sortedByDescending { it.noteCount }.take(15).forEach {
            append("- ${it.name}: ${it.noteCount} notes\n")
        }
        append("\n")

        // Context notes - CRITICAL: These are the ONLY notes the agent can access
        if (contextNotes.isNotEmpty()) {
            append("## RELEVANT NOTES (Use these IDs for operations)\n")
            contextNotes.forEachIndexed { index, note ->
                append("### Note ${index + 1}\n")
                append("- ID: `${note.id}`\n")
                append("- Title: ${note.title}\n")
                append("- Category: ${note.categoryName ?: "None"}\n")
                append("- Updated: ${formatTimestamp(note.updatedAt)}\n")
                if (!note.summary.isNullOrBlank()) {
                    append("- Summary: ${note.summary}\n")
                }
                append("- Content: ${note.content.take(400)}${if (note.content.length > 400) "..." else ""}\n")
                append("\n")
            }
        } else {
            append("## NO RELEVANT NOTES FOUND\n\n")
        }

        // Chat history
        if (chatHistory.isNotEmpty()) {
            append("## CONVERSATION HISTORY\n")
            chatHistory.takeLast(5).forEach { msg ->
                val role = if (msg.role == ChatRole.USER) "USER" else "AGENT"
                append("$role: ${msg.content.take(150)}${if (msg.content.length > 150) "..." else ""}\n")
            }
            append("\n")
        }

        // Current message
        append("## CURRENT REQUEST\n")
        append(userMessage)

        if (attachments.isNotEmpty()) {
            append("\n\nAttachments: ${attachments.joinToString { it.fileName }}")
        }

        append("\n\n## RESPOND WITH JSON ONLY")
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

    private fun parseAction(actionType: String?, params: com.google.gson.JsonObject?): AgentAction? {
        return when (actionType) {
            "CREATE_NOTE" -> AgentAction.CreateNote(
                content = params?.get("content")?.asString ?: "",
                title = params?.get("title")?.asString,
                category = params?.get("category")?.asString
            )
            "SEARCH_NOTES" -> AgentAction.SearchNotes(
                query = params?.get("query")?.asString ?: "",
                category = params?.get("category")?.asString,
                limit = params?.get("limit")?.asInt ?: 10
            )
            "DELETE_NOTE" -> AgentAction.DeleteNote(
                noteId = params?.get("noteId")?.asString,
                description = params?.get("description")?.asString
            )
            "ARCHIVE_NOTE" -> AgentAction.ArchiveNote(
                noteId = params?.get("noteId")?.asString,
                description = params?.get("description")?.asString
            )
            "UNARCHIVE_NOTE" -> AgentAction.UnarchiveNote(
                noteId = params?.get("noteId")?.asString,
                description = params?.get("description")?.asString
            )
            "UPDATE_NOTE" -> AgentAction.UpdateNote(
                noteId = params?.get("noteId")?.asString ?: "",
                newContent = params?.get("newContent")?.asString,
                newTitle = params?.get("newTitle")?.asString,
                newCategory = params?.get("newCategory")?.asString
            )
            "SUMMARIZE_NOTE" -> AgentAction.SummarizeNote(
                noteId = params?.get("noteId")?.asString ?: ""
            )
            "ADD_TODOS" -> {
                val todosArray = params?.getAsJsonArray("todos")
                val todos = todosArray?.mapNotNull { it.asString } ?: emptyList()
                AgentAction.AddTodos(
                    noteId = params?.get("noteId")?.asString ?: "",
                    todos = todos
                )
            }
            "TOGGLE_TODO" -> AgentAction.ToggleTodo(
                noteId = params?.get("noteId")?.asString ?: "",
                todoId = params?.get("todoId")?.asString ?: ""
            )
            "DELETE_TODO" -> AgentAction.DeleteTodo(
                noteId = params?.get("noteId")?.asString ?: "",
                todoId = params?.get("todoId")?.asString ?: ""
            )
            "LIST_CATEGORIES" -> AgentAction.ListCategories
            "GET_CATEGORY_NOTES" -> AgentAction.GetCategoryNotes(
                categoryName = params?.get("categoryName")?.asString ?: ""
            )
            "ANSWER_QUESTION" -> AgentAction.AnswerQuestion(
                question = params?.get("question")?.asString ?: ""
            )
            "SUGGEST_ACTIONS" -> AgentAction.SuggestActions(
                context = params?.get("context")?.asString ?: ""
            )
            "BATCH_ACTIONS" -> {
                val actionsArray = params?.getAsJsonArray("actions")
                val actions = actionsArray?.mapNotNull { actionJson ->
                    val obj = actionJson.asJsonObject
                    parseAction(obj.get("action")?.asString, obj.getAsJsonObject("params"))
                } ?: emptyList()
                AgentAction.BatchActions(actions)
            }
            else -> null
        }
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
