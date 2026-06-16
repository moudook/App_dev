package com.example.smarty.server.agent2.tools

import com.example.smarty.agent.permissions.ToolPermissionDecision
import com.example.smarty.server.agent.ToolPermissionEnforcer
import dev.langchain4j.agent.tool.Tool
import dev.langchain4j.agent.tool.P
import org.slf4j.LoggerFactory

class NoteTool(
    private val userId: String = "dev-user",
    private val permissionEnforcer: ToolPermissionEnforcer = ToolPermissionEnforcer(),
    private val section: String? = null,
) {
    private val logger = LoggerFactory.getLogger(NoteTool::class.java)

    @Tool("Save a note to the user's personal knowledge base. Title and content are required.")
    suspend fun saveNote(
        @P("Note title") title: String,
        @P("Note content") content: String,
        @P("Optional category") category: String? = null,
    ): String {
        if (section?.lowercase() == "notes") return """{"error":"Notes section is refinement-only. The agent cannot create new notes here. Use updateNote to refine the existing note."}"""
        logger.info("[NoteTool] Saving: $title")
        return "Saved: '$title'"
    }

    @Tool("Search and find notes in the user's personal knowledge base by query.")
    suspend fun findNotes(
        @P("Search query to find relevant notes") query: String,
        @P("Maximum results (default: 20)") limit: Int = 20,
    ): String {
        logger.info("[NoteTool] Finding: $query")
        return "No notes found for '$query'."
    }

    @Tool("Update an existing note by its ID. Provide at least one field to update.")
    suspend fun updateNote(
        @P("ID of the note to update") id: String,
        @P("New title (optional)") title: String? = null,
        @P("New content (optional)") content: String? = null,
    ): String {
        logger.info("[NoteTool] Updating: $id")
        return "Updated note $id"
    }

    @Tool("Delete a note by its ID.")
    suspend fun deleteNote(
        @P("ID of the note to delete") id: String,
    ): String {
        if (section?.lowercase() == "notes") return """{"error":"Notes section is refinement-only. Notes cannot be deleted from here."}"""
        logger.info("[NoteTool] Deleting: $id")
        return "Deleted note $id"
    }
}
