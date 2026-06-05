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

    /** Batch upsert — alias of insertEvents for semantic clarity at call sites. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvents(events: List<TimelineEventEntity>)

    @Query("SELECT * FROM timeline_events WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getEventsForSession(sessionId: String): List<TimelineEventEntity>

    @Query("SELECT * FROM timeline_events WHERE traceId IN (:messageIds) ORDER BY timestamp ASC")
    suspend fun getEventsForMessageIds(messageIds: List<String>): List<TimelineEventEntity>

    @Query("SELECT * FROM timeline_events WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getEventsForSessionFlow(sessionId: String): Flow<List<TimelineEventEntity>>

    /** Returns direct children of a parent event, ordered by their sequence field. */
    @Query("SELECT * FROM timeline_events WHERE parentId = :parentEventId ORDER BY sequence ASC")
    suspend fun getChildEvents(parentEventId: String): List<TimelineEventEntity>

    /** Returns all events at a specific nesting depth within a session. */
    @Query("SELECT * FROM timeline_events WHERE sessionId = :sessionId AND depth = :depth ORDER BY sequence ASC")
    suspend fun getEventsAtDepth(sessionId: String, depth: Int): List<TimelineEventEntity>

    /** Toggle the collapsed state for a single event (e.g. on user tap). */
    @Query("UPDATE timeline_events SET collapsed = :collapsed WHERE eventId = :eventId")
    suspend fun setCollapsed(eventId: String, collapsed: Boolean)

    @Query("DELETE FROM timeline_events WHERE sessionId = :sessionId")
    suspend fun deleteEventsForSession(sessionId: String)
}

