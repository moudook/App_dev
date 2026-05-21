package com.example.smarty.core.domain.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent Room entity for individual agent steps.
 *
 * Each row represents one discrete phase in the AI's execution timeline:
 * thinking, tool_call, tool_result, opencode_tool, checkpoint.
 *
 * Linked to chat_messages via messageId (CASCADE DELETE).
 * When a message is deleted, all its agent steps are automatically removed.
 *
 * PERFORMANCE:
 * - Indexed by (messageId, stepIndex) for fast ordered retrieval
 * - Indexed by stepType for filtering (e.g., show only tool calls)
 * - Indexed by stepStatus for finding incomplete/failed steps
 */
@Entity(
    tableName = "agent_steps",
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["messageId"]),
        Index(value = ["messageId", "stepIndex"]),
        Index(value = ["stepType"]),
        Index(value = ["stepStatus"]),
    ],
)
data class AgentStepEntity(
    @PrimaryKey
    val stepId: String,
    val messageId: String,
    val stepType: String,       // "thinking", "tool_call", "tool_result", "opencode_tool", "checkpoint"
    val stepTitle: String,
    val stepContent: String,
    val stepStatus: String,     // "started", "streaming", "completed", "failed"
    val stepIndex: Int,
    val toolName: String?,
    val durationMs: Long?,
    val timestamp: Long = System.currentTimeMillis(),
) {
    /**
     * Convert to domain model AgentStepEntry.
     */
    fun toAgentStepEntry(): AgentStepEntry = AgentStepEntry(
        stepType = stepType,
        stepTitle = stepTitle,
        stepContent = stepContent,
        stepStatus = stepStatus,
        stepIndex = stepIndex,
        toolName = toolName,
        durationMs = durationMs,
    )

    companion object {
        /**
         * Create entity from domain model.
         */
        fun fromAgentStepEntry(
            entry: AgentStepEntry,
            messageId: String,
            stepId: String = java.util.UUID.randomUUID().toString(),
        ): AgentStepEntity = AgentStepEntity(
            stepId = stepId,
            messageId = messageId,
            stepType = entry.stepType,
            stepTitle = entry.stepTitle,
            stepContent = entry.stepContent,
            stepStatus = entry.stepStatus,
            stepIndex = entry.stepIndex,
            toolName = entry.toolName,
            durationMs = entry.durationMs,
        )

        /**
         * Bulk-convert a list of AgentStepEntry to entities.
         */
        fun fromAgentStepEntries(
            entries: List<AgentStepEntry>,
            messageId: String,
        ): List<AgentStepEntity> = entries.map { entry ->
            fromAgentStepEntry(entry, messageId)
        }
    }
}
