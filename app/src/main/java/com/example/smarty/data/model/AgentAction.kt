package com.example.smarty.data.model

/**
 * Sealed class representing all possible agent actions
 * The AI can return these actions to be executed by the app
 */
sealed class AgentAction {
    /**
     * Create a new note with content
     */
    data class CreateNote(
        val content: String,
        val title: String? = null,
        val category: String? = null
    ) : AgentAction()

    /**
     * Search for notes matching a query
     */
    data class SearchNotes(
        val query: String,
        val category: String? = null,
        val limit: Int = 10
    ) : AgentAction()

    /**
     * Delete a note by ID or description
     */
    data class DeleteNote(
        val noteId: String? = null,
        val description: String? = null
    ) : AgentAction()

    /**
     * Archive a note (soft delete, can be restored)
     */
    data class ArchiveNote(
        val noteId: String? = null,
        val description: String? = null
    ) : AgentAction()

    /**
     * Restore an archived note
     */
    data class UnarchiveNote(
        val noteId: String? = null,
        val description: String? = null
    ) : AgentAction()

    /**
     * Update an existing note's content, title, or category
     */
    data class UpdateNote(
        val noteId: String,
        val newContent: String? = null,
        val newTitle: String? = null,
        val newCategory: String? = null
    ) : AgentAction()

    /**
     * List all available categories with note counts
     */
    data object ListCategories : AgentAction()

    /**
     * Get all notes in a specific category
     */
    data class GetCategoryNotes(
        val categoryName: String
    ) : AgentAction()

    /**
     * Answer a question using context from notes
     */
    data class AnswerQuestion(
        val question: String,
        val contextNoteIds: List<String> = emptyList()
    ) : AgentAction()

    /**
     * Generate a concise summary of a note
     */
    data class SummarizeNote(
        val noteId: String
    ) : AgentAction()

    /**
     * Suggest actions the user might want to take based on context
     */
    data class SuggestActions(
        val context: String
    ) : AgentAction()

    /**
     * Add todo items to a note
     */
    data class AddTodos(
        val noteId: String,
        val todos: List<String>
    ) : AgentAction()

    /**
     * Toggle todo completion status
     */
    data class ToggleTodo(
        val noteId: String,
        val todoId: String
    ) : AgentAction()

    /**
     * Delete a todo from a note
     */
    data class DeleteTodo(
        val noteId: String,
        val todoId: String
    ) : AgentAction()

    /**
     * Search the web for real-time information using Tavily API.
     * Only use when information is external or time-sensitive.
     * NEVER use for casual conversation or questions answerable from notes.
     *
     * @param query The search query
     * @param reason Why this search is necessary (required for logging)
     * @param topic Search topic: "general", "news", or "finance"
     * @param maxResults Maximum results to return (1-10)
     */
    data class WebSearch(
        val query: String,
        val reason: String,
        val topic: String = "general",
        val maxResults: Int = 5
    ) : AgentAction()

    /**
     * Play audio content (music, podcast, ambient sounds).
     * Integrates with device audio playback.
     *
     * @param query What to play (song name, artist, genre, or description)
     * @param source Optional: "youtube", "spotify", "local" - defaults to best available
     */
    data class PlayAudio(
        val query: String,
        val source: String? = null
    ) : AgentAction()

    /**
     * Execute multiple actions in sequence
     */
    data class BatchActions(
        val actions: List<AgentAction>
    ) : AgentAction()

    companion object {
        /**
         * All valid action names for AI response parsing
         */
        val ACTION_NAMES = listOf(
            "CREATE_NOTE",
            "SEARCH_NOTES",
            "DELETE_NOTE",
            "ARCHIVE_NOTE",
            "UNARCHIVE_NOTE",
            "UPDATE_NOTE",
            "SUMMARIZE_NOTE",
            "ADD_TODOS",
            "TOGGLE_TODO",
            "DELETE_TODO",
            "LIST_CATEGORIES",
            "GET_CATEGORY_NOTES",
            "ANSWER_QUESTION",
            "SUGGEST_ACTIONS",
            "WEB_SEARCH",
            "PLAY_AUDIO",
            "BATCH_ACTIONS"
        )

        /**
         * JSON schema for AI response format
         */
        const val JSON_SCHEMA = """
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ["CREATE_NOTE", "SEARCH_NOTES", "DELETE_NOTE", "ARCHIVE_NOTE",
               "UNARCHIVE_NOTE", "UPDATE_NOTE", "SUMMARIZE_NOTE", "ADD_TODOS",
               "TOGGLE_TODO", "DELETE_TODO", "LIST_CATEGORIES", "GET_CATEGORY_NOTES",
               "ANSWER_QUESTION", "SUGGEST_ACTIONS", "WEB_SEARCH", "PLAY_AUDIO", "BATCH_ACTIONS"]
    },
    "params": {
      "type": "object",
      "properties": {
        "content": { "type": "string", "description": "Note content for CREATE_NOTE" },
        "title": { "type": "string", "description": "Note title" },
        "category": { "type": "string", "description": "Category name" },
        "query": { "type": "string", "description": "Search query for SEARCH_NOTES or WEB_SEARCH" },
        "noteId": { "type": "string", "description": "ID of note to operate on" },
        "description": { "type": "string", "description": "Description to find note" },
        "newContent": { "type": "string", "description": "New content for UPDATE_NOTE" },
        "newTitle": { "type": "string", "description": "New title for UPDATE_NOTE" },
        "newCategory": { "type": "string", "description": "New category for UPDATE_NOTE" },
        "categoryName": { "type": "string", "description": "Category name for GET_CATEGORY_NOTES" },
        "question": { "type": "string", "description": "Question for ANSWER_QUESTION" },
        "contextNoteIds": { "type": "array", "items": { "type": "string" } },
        "limit": { "type": "integer", "description": "Max results for SEARCH_NOTES" },
        "context": { "type": "string", "description": "Context for SUGGEST_ACTIONS" },
        "todos": { "type": "array", "items": { "type": "string" }, "description": "Todo items for ADD_TODOS" },
        "todoId": { "type": "string", "description": "Todo ID for TOGGLE_TODO/DELETE_TODO" },
        "reason": { "type": "string", "description": "Why web search is needed (required for WEB_SEARCH)" },
        "topic": { "type": "string", "enum": ["general", "news", "finance"], "description": "Search topic for WEB_SEARCH" },
        "maxResults": { "type": "integer", "description": "Max results (1-10) for WEB_SEARCH" },
        "source": { "type": "string", "enum": ["youtube", "spotify", "local"], "description": "Audio source for PLAY_AUDIO" },
        "actions": { "type": "array", "description": "Actions for BATCH_ACTIONS" }
      }
    },
    "response": {
      "type": "string",
      "description": "Human-friendly response message to display to the user"
    }
  },
  "required": ["action", "response"]
}
"""
    }
}

/**
 * Parsed agent response from AI
 */
data class AgentResponse(
    val action: AgentAction?,
    val response: String,
    val rawJson: String? = null
)
