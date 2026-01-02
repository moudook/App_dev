package com.example.smarty.agent.tools.todos

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.tools.base.CogniToolUtils
import com.example.smarty.agent.tools.base.TodoOperationResult
import com.example.smarty.data.model.TodoItem
import com.example.smarty.data.model.getTodos
import com.example.smarty.data.model.withTodos
import com.example.smarty.data.repository.CogniRepository
import kotlinx.serialization.Serializable

@Serializable
data class AddTodosArgs(
    @property:LLMDescription("The unique ID of the note to add todos to")
    val noteId: String,
    @property:LLMDescription("List of todo item texts to add (e.g., ['Buy milk', 'Call mom'])")
    val todos: List<String>
)

/**
 * Tool for adding todo items to a note.
 * Respects PrivacyGuard - private notes cannot have todos added by AI.
 */
class AddTodosTool(
    private val repository: CogniRepository
) : Tool<AddTodosArgs, TodoOperationResult>(
    argsSerializer = AddTodosArgs.serializer(),
    resultSerializer = TodoOperationResult.serializer(),
    name = "add_todos",
    description = """
        Adds todo/checklist items to an existing note.
        Use when the user wants to add tasks or checklist items to a note.
        Private notes cannot have todos added by AI.
    """.trimIndent()
) {
    override suspend fun execute(args: AddTodosArgs): TodoOperationResult {
        return try {
            if (args.todos.isEmpty()) {
                return TodoOperationResult(
                    success = false,
                    message = "No todos provided",
                    error = "Empty todos list"
                )
            }

            val note = CogniToolUtils.getFreshAiAccessibleNote(repository, args.noteId)
                ?: return TodoOperationResult(
                    success = false,
                    message = "Note not found",  // SECURITY: Generic message - don't leak private note existence
                    error = "Note inaccessible"
                )

            val currentTodos = note.getTodos().toMutableList()
            val newTodos = args.todos.map { TodoItem(text = it) }
            currentTodos.addAll(newTodos)

            val updatedNote = note.withTodos(currentTodos)
            repository.updateNote(updatedNote)

            TodoOperationResult(
                success = true,
                noteId = note.id,
                message = "Added ${args.todos.size} todos to note '${note.title}'"
            )
        } catch (e: Exception) {
            TodoOperationResult(
                success = false,
                message = "Failed to add todos",
                error = e.message
            )
        }
    }
}
