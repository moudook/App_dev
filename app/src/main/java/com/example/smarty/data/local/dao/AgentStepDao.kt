package com.example.smarty.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.smarty.core.domain.model.AgentStepEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentStepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<AgentStepEntity>)

    @Query("SELECT * FROM agent_steps WHERE messageId = :messageId ORDER BY stepIndex ASC")
    fun getStepsForMessageFlow(messageId: String): Flow<List<AgentStepEntity>>

    @Query("SELECT * FROM agent_steps WHERE messageId = :messageId ORDER BY stepIndex ASC")
    suspend fun getStepsForMessage(messageId: String): List<AgentStepEntity>

    @Query("DELETE FROM agent_steps WHERE messageId = :messageId")
    suspend fun deleteStepsForMessage(messageId: String)
}
