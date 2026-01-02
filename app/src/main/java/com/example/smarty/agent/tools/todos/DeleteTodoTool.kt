package com.example.smarty.agent.tools.todos

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.tools.base.CogniToolUtils
import com.example.smarty.agent.tools.base.TodoOperationResult
import com.example.smarty.data.model.getTodos
import com.example.smarty.data.model.withTodos
import com.example.smarty.data.repository.CogniRepository
import kotlinx.serialization.Serializable

@Serializable
data class DeleteTodoArgs(
    @property:LLMDescription("The unique ID of the note containing the todo")
    val noteId: String,
    @property:LLMDescription("The unique ID of the todo item to delete")
    val todoId: String
)

/**
 * Tool for deleting todo items from a note.
 * Respects PrivacyGuard - private notes' todos cannot be modified by AI.
 */
class DeleteTodoTool(
    private val repository: CogniRepository
) : Tool<DeleteTodoArgs, TodoOperationResult>(
    argsSerializer = DeleteTodoArgs.serializer(),
    resultSerializer = TodoOperationResult.serializer(),
    name = "delete_todo",
    description = """
        Deletes a todo item from a note.
        Use when the user wants to remove a task from a checklist.
        Private notes cannot be modified by AI.
    """.trimIndent()
) {
    override suspend fun execute(args: DeleteTodoArgs): TodoOperationResult {
        return try {
            val note = CogniToolUtils.getFreshAiAccessibleNote(repository, args.noteId)
                ?: return TodoOperationResult(
                    success = false,
                    message = "Note not found",  // SECURITY: Generic message - don't leak private note existence
                    error = "Note inaccessible"
                )

            val todos = note.getTodos().toMutableList()
            val removed = todos.removeAll { it.id == args.todoId }

            if (!removed) {
                return TodoOperationResult(
                    success = false,
                    noteId = note.id,
                    message = "Todo not found",
                    error = "Todo ID not found"
                )
            }

            val updatedNote = note.withTodos(todos)
            repository.updateNote(updatedNote)

            TodoOperationResult(
                success = true,
                noteId = note.id,
                todoId = args.todoId,
                message = "Todo deleted from note '${note.title}'"
            )
        } catch (e: Exception) {
            TodoOperationResult(
                success = false,
                message = "Failed to delete todo",
                error = e.message
            )
        }
    }
}
