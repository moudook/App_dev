package com.example.smarty.agent.tools.todos

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.tools.base.CogniToolUtils
import com.example.smarty.agent.tools.base.TodoOperationResult
import com.example.smarty.data.model.getTodos
import com.example.smarty.data.model.withTodos
import com.example.smarty.data.repository.CogniRepository
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class ToggleTodoArgs(
    @property:LLMDescription("The unique ID of the note containing the todo")
    val noteId: String,
    @property:LLMDescription("The unique ID of the todo item to toggle complete/incomplete")
    val todoId: String
)

/**
 * Tool for toggling todo completion status.
 * Respects PrivacyGuard - private notes' todos cannot be modified by AI.
 */
class ToggleTodoTool(
    private val repository: CogniRepository
) : Tool<ToggleTodoArgs, TodoOperationResult>() {

    override val argsSerializer: KSerializer<ToggleTodoArgs> = ToggleTodoArgs.serializer()
    override val resultSerializer: KSerializer<TodoOperationResult> = TodoOperationResult.serializer()

    override val name = "toggle_todo"

    override val description = """
        Toggles a todo item's completion status (done/not done).
        Use when the user wants to mark a task as complete or incomplete.
        Private notes cannot be modified by AI.
    """.trimIndent()

    override suspend fun execute(args: ToggleTodoArgs): TodoOperationResult {
        return try {
            val note = CogniToolUtils.getFreshAiAccessibleNote(repository, args.noteId)
                ?: return TodoOperationResult(
                    success = false,
                    message = "Note not found",  // SECURITY: Generic message - don't leak private note existence
                    error = "Note inaccessible"
                )

            val todos = note.getTodos().toMutableList()
            val todoIndex = todos.indexOfFirst { it.id == args.todoId }

            if (todoIndex < 0) {
                return TodoOperationResult(
                    success = false,
                    noteId = note.id,
                    message = "Todo not found",
                    error = "Todo ID not found"
                )
            }

            val todo = todos[todoIndex]
            todos[todoIndex] = todo.copy(isCompleted = !todo.isCompleted)

            val updatedNote = note.withTodos(todos)
            repository.updateNote(updatedNote)

            val status = if (todos[todoIndex].isCompleted) "completed" else "uncompleted"
            TodoOperationResult(
                success = true,
                noteId = note.id,
                todoId = args.todoId,
                message = "Todo marked as $status"
            )
        } catch (e: Exception) {
            TodoOperationResult(
                success = false,
                message = "Failed to toggle todo",
                error = e.message
            )
        }
    }
}
