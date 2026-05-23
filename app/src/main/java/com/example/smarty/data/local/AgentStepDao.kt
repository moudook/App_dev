package com.example.smarty.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.smarty.core.domain.model.AgentStepEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for agent_steps table.
 * Provides CRUD operations for persistent agent step traces.
 */
@Dao
interface AgentStepDao {
    /**
     * Get all agent steps for a message, ordered by stepIndex.
     */
    @Query("SELECT * FROM agent_steps WHERE messageId = :messageId ORDER BY stepIndex ASC")
    fun getStepsForMessage(messageId: String): Flow<List<AgentStepEntity>>

    /**
     * Get all agent steps for a message (one-shot, no Flow).
     */
    @Query("SELECT * FROM agent_steps WHERE messageId = :messageId ORDER BY stepIndex ASC")
    suspend fun getStepsForMessageOnce(messageId: String): List<AgentStepEntity>

    /**
     * Get a single agent step by its ID.
     */
    @Query("SELECT * FROM agent_steps WHERE stepId = :stepId")
    suspend fun getStepById(stepId: String): AgentStepEntity?

    /**
     * Insert a single agent step.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(step: AgentStepEntity)

    /**
     * Bulk-insert multiple agent steps atomically.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<AgentStepEntity>)

    /**
     * Update the status of a specific step (e.g., streaming -> completed).
     */
    @Query("UPDATE agent_steps SET stepStatus = :status WHERE stepId = :stepId")
    suspend fun updateStepStatus(
        stepId: String,
        status: String,
    )

    /**
     * Update the content of a streaming step (live token append).
     */
    @Query("UPDATE agent_steps SET stepContent = :content WHERE stepId = :stepId")
    suspend fun updateStepContent(
        stepId: String,
        content: String,
    )

    /**
     * Delete all steps for a message (cascade handles this automatically, but explicit for safety).
     */
    @Query("DELETE FROM agent_steps WHERE messageId = :messageId")
    suspend fun deleteStepsForMessage(messageId: String)

    /**
     * Get count of steps for a message.
     */
    @Query("SELECT COUNT(*) FROM agent_steps WHERE messageId = :messageId")
    suspend fun getStepCountForMessage(messageId: String): Int

    /**
     * Get all steps of a specific type for a message (e.g., only tool calls).
     */
    @Query("SELECT * FROM agent_steps WHERE messageId = :messageId AND stepType = :stepType ORDER BY stepIndex ASC")
    suspend fun getStepsByType(
        messageId: String,
        stepType: String,
    ): List<AgentStepEntity>

    /**
     * Get failed steps for debugging/diagnostics.
     */
    @Query("SELECT * FROM agent_steps WHERE stepStatus = 'failed' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getFailedSteps(limit: Int = 50): List<AgentStepEntity>

    /**
     * Delete all agent steps (full cleanup).
     */
    @Query("DELETE FROM agent_steps")
    suspend fun deleteAllSteps()
}
