package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.data.model.CalendarEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarDao {

    // =========================================================================
    // BASIC CRUD OPERATIONS
    // =========================================================================

    @Query("SELECT * FROM calendar_events ORDER BY startTime ASC")
    fun getAllEvents(): Flow<List<CalendarEvent>>

    @Query("SELECT * FROM calendar_events WHERE id = :id")
    suspend fun getEventById(id: String): CalendarEvent?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: CalendarEvent)

    @Update
    suspend fun updateEvent(event: CalendarEvent)

    @Delete
    suspend fun deleteEvent(event: CalendarEvent)

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteEventById(id: String)

    @Query("DELETE FROM calendar_events WHERE id IN (:ids)")
    suspend fun deleteEventsByIds(ids: List<String>)

    // =========================================================================
    // DATE RANGE QUERIES
    // =========================================================================

    /**
     * Get events within a date range (inclusive)
     * Used for calendar view filtering
     */
    @Query("""
        SELECT * FROM calendar_events
        WHERE startTime >= :startMillis AND startTime < :endMillis
        ORDER BY startTime ASC
    """)
    fun getEventsInRange(startMillis: Long, endMillis: Long): Flow<List<CalendarEvent>>

    /**
     * Get events for a specific day
     */
    @Query("""
        SELECT * FROM calendar_events
        WHERE startTime >= :dayStartMillis AND startTime < :dayEndMillis
        ORDER BY startTime ASC
    """)
    suspend fun getEventsForDay(dayStartMillis: Long, dayEndMillis: Long): List<CalendarEvent>

    /**
     * Get upcoming events (from now onwards)
     */
    @Query("""
        SELECT * FROM calendar_events
        WHERE endTime >= :nowMillis
        ORDER BY startTime ASC
        LIMIT :limit
    """)
    fun getUpcomingEvents(nowMillis: Long = System.currentTimeMillis(), limit: Int = 20): Flow<List<CalendarEvent>>

    /**
     * Get today's events
     */
    @Query("""
        SELECT * FROM calendar_events
        WHERE startTime >= :dayStart AND startTime < :dayEnd
        ORDER BY startTime ASC
    """)
    suspend fun getTodayEvents(dayStart: Long, dayEnd: Long): List<CalendarEvent>

    // =========================================================================
    // LINKED NOTE QUERIES
    // =========================================================================

    /**
     * Get events linked to a specific note
     */
    @Query("SELECT * FROM calendar_events WHERE linkedNoteId = :noteId ORDER BY startTime ASC")
    fun getEventsForNote(noteId: String): Flow<List<CalendarEvent>>

    /**
     * Clear note link when a note is deleted
     */
    @Query("UPDATE calendar_events SET linkedNoteId = NULL, updatedAt = :timestamp WHERE linkedNoteId = :noteId")
    suspend fun clearNoteLinkForNote(noteId: String, timestamp: Long = System.currentTimeMillis())

    // =========================================================================
    // AI-SAFE QUERIES (Private events excluded)
    // =========================================================================

    /**
     * Get AI-visible events (excludes private events)
     * Use when building context for AI/Agent services
     */
    @Query("""
        SELECT * FROM calendar_events
        WHERE isEventPrivate = 0
        ORDER BY startTime ASC
    """)
    fun getAiVisibleEvents(): Flow<List<CalendarEvent>>

    /**
     * Get AI-visible upcoming events
     */
    @Query("""
        SELECT * FROM calendar_events
        WHERE isEventPrivate = 0 AND endTime >= :nowMillis
        ORDER BY startTime ASC
        LIMIT :limit
    """)
    suspend fun getAiVisibleUpcomingEvents(nowMillis: Long = System.currentTimeMillis(), limit: Int = 10): List<CalendarEvent>

    // =========================================================================
    // UTILITY QUERIES
    // =========================================================================

    /**
     * Get all events as a one-shot query (for backup)
     */
    @Query("SELECT * FROM calendar_events ORDER BY startTime ASC")
    suspend fun getAllEventsOnce(): List<CalendarEvent>

    /**
     * Delete all events
     */
    @Query("DELETE FROM calendar_events")
    suspend fun deleteAllEvents()

    /**
     * Count total events
     */
    @Query("SELECT COUNT(*) FROM calendar_events")
    suspend fun getEventCount(): Int

    /**
     * Check if there are events on a specific day
     */
    @Query("""
        SELECT COUNT(*) > 0 FROM calendar_events
        WHERE startTime >= :dayStart AND startTime < :dayEnd
    """)
    suspend fun hasEventsOnDay(dayStart: Long, dayEnd: Long): Boolean
}
