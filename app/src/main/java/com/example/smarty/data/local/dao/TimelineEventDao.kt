package com.example.smarty.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.smarty.data.local.entity.TimelineEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: TimelineEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<TimelineEventEntity>)

    @Query("SELECT * FROM timeline_events WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getEventsForSession(sessionId: String): List<TimelineEventEntity>

    @Query("SELECT * FROM timeline_events WHERE traceId IN (:messageIds) ORDER BY timestamp ASC")
    suspend fun getEventsForMessageIds(messageIds: List<String>): List<TimelineEventEntity>

    @Query("SELECT * FROM timeline_events WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getEventsForSessionFlow(sessionId: String): Flow<List<TimelineEventEntity>>

    @Query("DELETE FROM timeline_events WHERE sessionId = :sessionId")
    suspend fun deleteEventsForSession(sessionId: String)
}
