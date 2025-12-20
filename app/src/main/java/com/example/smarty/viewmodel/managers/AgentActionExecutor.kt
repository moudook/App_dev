package com.example.smarty.viewmodel.managers

import android.util.Log
import com.example.smarty.data.model.AgentAction
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.model.TodoItem
import com.example.smarty.data.model.getTodos
import com.example.smarty.data.model.withTodos
import com.example.smarty.data.remote.AgentService
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.util.ContentTypeDetector
import com.example.smarty.util.PrivacyGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Result of executing an agent action.
 */
data class ActionExecutionResult(
    val success: Boolean,
    val summary: String,
    val affectedNoteIds: List<String> = emptyList(),
    val needsContinuation: Boolean = false,
    val resultData: String? = null
)

/**
 * Executes AI agent actions on notes and other data.
 *
 * Responsibilities:
 * - Execute all 17 action types
 * - Enforce privacy guards on note operations
 * - Coordinate with repository for persistence
 * - Handle web search via Tavily API
 *
 * @property repository Note repository
 * @property agentService Agent service for note matching
 * @property tavilySearchProvider Web search provider
 * @property scope Coroutine scope
 */
class AgentActionExecutor(
    private val repository: CogniRepository,
    private val agentService: AgentService,
    private val tavilySearchProvider: TavilySearchProvider,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AgentActionExecutor"
    }

    /**
     * Callback for actions that need ViewModel state.
     */
    interface ActionCallback {
        fun getActiveNotes(): List<Note>
        fun getArchivedNotes(): List<Note>
        fun getTavilyApiKey(): String?
        suspend fun processNoteWithAi(note: Note)
        fun requestAudioPlayback(query: String)
    }

    private var callback: ActionCallback? = null

    fun setCallback(cb: ActionCallback) {
        callback = cb
    }

    /**
     * Execute an action and return structured result.
     */
    suspend fun executeWithResult(action: AgentAction): ActionExecutionResult {
        Log.d(TAG, "Executing action: ${action.javaClass.simpleName}")

        return when (action) {
            is AgentAction.WebSearch -> executeWebSearch(action)
            is AgentAction.PlayAudio -> executePlayAudio(action)
            is AgentAction.CreateNote -> executeCreateNote(action)
            is AgentAction.SearchNotes -> ActionExecutionResult(true, "Search completed")
            is AgentAction.DeleteNote -> executeDeleteNote(action)
            is AgentAction.ArchiveNote -> executeArchiveNote(action)
            is AgentAction.UnarchiveNote -> executeUnarchiveNote(action)
            is AgentAction.UpdateNote -> executeUpdateNote(action)
            is AgentAction.SummarizeNote -> executeSummarizeNote(action)
            is AgentAction.AddTodos -> executeAddTodos(action)
            is AgentAction.ToggleTodo -> executeToggleTodo(action)
            is AgentAction.DeleteTodo -> executeDeleteTodo(action)
            is AgentAction.ListCategories -> ActionExecutionResult(true, "Categories listed")
            is AgentAction.AnswerQuestion -> ActionExecutionResult(true, "Question answered")
            is AgentAction.BatchActions -> executeBatchActions(action)
            is AgentAction.GetCategoryNotes -> ActionExecutionResult(true, "Category notes retrieved")
            is AgentAction.SuggestActions -> ActionExecutionResult(true, "Actions suggested")
        }
    }

    /**
     * Get fresh note from database with privacy check.
     */
    private suspend fun getFreshAiAccessibleNote(noteId: String): Note? {
        val freshNote = repository.getNoteById(noteId) ?: return null
        return if (PrivacyGuard.canAiProcess(freshNote)) freshNote else null
    }

    // ==================== Action Implementations ====================

    private suspend fun executeWebSearch(action: AgentAction.WebSearch): ActionExecutionResult {
        val apiKey = callback?.getTavilyApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "WebSearch: No Tavily API key configured")
            return ActionExecutionResult(false, "No Tavily API key configured")
        }

        return try {
            Log.d(TAG, "WebSearch: Searching for '${action.query}'")
            val searchResult = tavilySearchProvider.search(
                apiKey = apiKey,
                query = action.query,
                maxResults = action.maxResults,
                topic = action.topic
            )

            if (searchResult.success) {
                val resultsJson = formatSearchResults(action, searchResult)
                ActionExecutionResult(
                    success = true,
                    summary = "Web search completed",
                    needsContinuation = true,
                    resultData = resultsJson
                )
            } else {
                ActionExecutionResult(false, searchResult.error ?: "Search failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebSearch error: ${e.message}", e)
            ActionExecutionResult(false, "Search error: ${e.message}")
        }
    }

    private fun formatSearchResults(
        action: AgentAction.WebSearch,
        searchResult: TavilySearchProvider.SearchResponse
    ): String {
        return buildString {
            append("{\"status\":\"success\",")
            append("\"query\":\"${action.query.replace("\"", "\\\"")}\",")
            append("\"reason\":\"${action.reason.replace("\"", "\\\"")}\",")

            searchResult.answer?.let { answer ->
                append("\"ai_summary\":\"${answer.replace("\"", "\\\"").replace("\n", "\\n")}\",")
            }

            append("\"results\":[")
            searchResult.results.forEachIndexed { index, result ->
                if (index > 0) append(",")
                append("{")
                append("\"title\":\"${result.title.replace("\"", "\\\"")}\",")
                append("\"url\":\"${result.url}\",")
                append("\"snippet\":\"${result.snippet.replace("\"", "\\\"").replace("\n", " ")}\"")
                append("}")
            }
            append("],\"total_results\":${searchResult.results.size}}")
        }
    }

    private fun executePlayAudio(action: AgentAction.PlayAudio): ActionExecutionResult {
        callback?.requestAudioPlayback(action.query)
        return ActionExecutionResult(true, "Audio playback requested", needsContinuation = false)
    }

    private suspend fun executeCreateNote(action: AgentAction.CreateNote): ActionExecutionResult {
        val detectedType = ContentTypeDetector.detectContentType(action.content)
        val title = action.title ?: ContentTypeDetector.extractTitle(action.content, detectedType)

        val note = Note(
            title = title,
            content = action.content,
            type = detectedType,
            processingStatus = if (action.category != null) ProcessingStatus.COMPLETED else ProcessingStatus.PROCESSING,
            isAiCreated = true
        )
        repository.insertNote(note)

        if (action.category != null) {
            val category = repository.getOrCreateCategory(action.category)
            repository.updateNote(note.copy(
                categoryId = category.id,
                categoryName = category.name,
                processingStatus = ProcessingStatus.COMPLETED
            ))
        } else {
            try {
                callback?.processNoteWithAi(note)
            } catch (e: Exception) {
                Log.e(TAG, "AI processing failed: ${e.message}")
                repository.updateNote(note.copy(processingStatus = ProcessingStatus.COMPLETED))
            }
        }

        return ActionExecutionResult(true, "Note created", listOf(note.id))
    }

    private suspend fun executeDeleteNote(action: AgentAction.DeleteNote): ActionExecutionResult {
        val noteToDelete = when {
            action.noteId != null -> getFreshAiAccessibleNote(action.noteId)
            action.description != null -> {
                val notes = callback?.getActiveNotes() ?: emptyList()
                val aiAccessible = PrivacyGuard.filterForAiModification(notes)
                agentService.findNoteByDescription(action.description, aiAccessible)?.let {
                    getFreshAiAccessibleNote(it.id)
                }
            }
            else -> null
        }

        return if (noteToDelete != null) {
            repository.deleteNote(noteToDelete)
            Log.d(TAG, "Deleted note: ${noteToDelete.title}")
            ActionExecutionResult(true, "Note deleted", listOf(noteToDelete.id))
        } else {
            ActionExecutionResult(false, "Note not found")
        }
    }

    private suspend fun executeArchiveNote(action: AgentAction.ArchiveNote): ActionExecutionResult {
        val noteToArchive = when {
            action.noteId != null -> getFreshAiAccessibleNote(action.noteId)
            action.description != null -> {
                val notes = callback?.getActiveNotes() ?: emptyList()
                val aiAccessible = PrivacyGuard.filterForAiModification(notes)
                agentService.findNoteByDescription(action.description, aiAccessible)?.let {
                    getFreshAiAccessibleNote(it.id)
                }
            }
            else -> null
        }

        return if (noteToArchive != null) {
            repository.archiveNote(noteToArchive.id)
            Log.d(TAG, "Archived note: ${noteToArchive.title}")
            ActionExecutionResult(true, "Note archived", listOf(noteToArchive.id))
        } else {
            ActionExecutionResult(false, "Note not found")
        }
    }

    private suspend fun executeUnarchiveNote(action: AgentAction.UnarchiveNote): ActionExecutionResult {
        val noteToUnarchive = when {
            action.noteId != null -> getFreshAiAccessibleNote(action.noteId)
            action.description != null -> {
                val notes = callback?.getArchivedNotes() ?: emptyList()
                val aiAccessible = PrivacyGuard.filterForAiModification(notes)
                agentService.findNoteByDescription(action.description, aiAccessible)?.let {
                    getFreshAiAccessibleNote(it.id)
                }
            }
            else -> null
        }

        return if (noteToUnarchive != null) {
            repository.unarchiveNote(noteToUnarchive.id)
            Log.d(TAG, "Unarchived note: ${noteToUnarchive.title}")
            ActionExecutionResult(true, "Note unarchived", listOf(noteToUnarchive.id))
        } else {
            ActionExecutionResult(false, "Note not found")
        }
    }

    private suspend fun executeUpdateNote(action: AgentAction.UpdateNote): ActionExecutionResult {
        val noteToUpdate = getFreshAiAccessibleNote(action.noteId)

        return if (noteToUpdate != null) {
            val updatedNote = noteToUpdate.copy(
                title = action.newTitle ?: noteToUpdate.title,
                content = action.newContent ?: noteToUpdate.content,
                categoryName = action.newCategory ?: noteToUpdate.categoryName,
                updatedAt = System.currentTimeMillis()
            )

            if (action.newCategory != null && action.newCategory != noteToUpdate.categoryName) {
                val category = repository.getOrCreateCategory(action.newCategory)
                repository.updateNote(updatedNote.copy(categoryId = category.id))
            } else {
                repository.updateNote(updatedNote)
            }
            Log.d(TAG, "Updated note: ${updatedNote.title}")
            ActionExecutionResult(true, "Note updated", listOf(noteToUpdate.id))
        } else {
            ActionExecutionResult(false, "Note not found")
        }
    }

    private suspend fun executeSummarizeNote(action: AgentAction.SummarizeNote): ActionExecutionResult {
        val note = getFreshAiAccessibleNote(action.noteId)
        return if (note != null) {
            Log.d(TAG, "Summarized note: ${note.title}")
            ActionExecutionResult(true, "Note summarized", listOf(note.id))
        } else {
            ActionExecutionResult(false, "Note not found")
        }
    }

    private suspend fun executeAddTodos(action: AgentAction.AddTodos): ActionExecutionResult {
        val note = getFreshAiAccessibleNote(action.noteId)

        return if (note != null) {
            val currentTodos = note.getTodos().toMutableList()
            val newTodos = action.todos.map { TodoItem(text = it) }
            currentTodos.addAll(newTodos)
            val updatedNote = note.withTodos(currentTodos)
            repository.updateNote(updatedNote)
            Log.d(TAG, "Added ${action.todos.size} todos to note: ${note.title}")
            ActionExecutionResult(true, "Todos added", listOf(note.id))
        } else {
            ActionExecutionResult(false, "Note not found")
        }
    }

    private suspend fun executeToggleTodo(action: AgentAction.ToggleTodo): ActionExecutionResult {
        val note = getFreshAiAccessibleNote(action.noteId)

        return if (note != null) {
            val todos = note.getTodos().toMutableList()
            val todoIndex = todos.indexOfFirst { it.id == action.todoId }
            if (todoIndex >= 0) {
                val todo = todos[todoIndex]
                todos[todoIndex] = todo.copy(isCompleted = !todo.isCompleted)
                val updatedNote = note.withTodos(todos)
                repository.updateNote(updatedNote)
                Log.d(TAG, "Toggled todo: ${todo.text}")
                ActionExecutionResult(true, "Todo toggled", listOf(note.id))
            } else {
                ActionExecutionResult(false, "Todo not found")
            }
        } else {
            ActionExecutionResult(false, "Note not found")
        }
    }

    private suspend fun executeDeleteTodo(action: AgentAction.DeleteTodo): ActionExecutionResult {
        val note = getFreshAiAccessibleNote(action.noteId)

        return if (note != null) {
            val todos = note.getTodos().toMutableList()
            val removed = todos.removeAll { it.id == action.todoId }
            if (removed) {
                val updatedNote = note.withTodos(todos)
                repository.updateNote(updatedNote)
                Log.d(TAG, "Deleted todo from note: ${note.title}")
                ActionExecutionResult(true, "Todo deleted", listOf(note.id))
            } else {
                ActionExecutionResult(false, "Todo not found")
            }
        } else {
            ActionExecutionResult(false, "Note not found")
        }
    }

    private suspend fun executeBatchActions(action: AgentAction.BatchActions): ActionExecutionResult {
        val allAffectedIds = mutableListOf<String>()
        var allSuccess = true

        for (subAction in action.actions) {
            val result = executeWithResult(subAction)
            allAffectedIds.addAll(result.affectedNoteIds)
            if (!result.success) allSuccess = false
        }

        return ActionExecutionResult(
            success = allSuccess,
            summary = "Batch actions completed (${action.actions.size} actions)",
            affectedNoteIds = allAffectedIds
        )
    }
}
