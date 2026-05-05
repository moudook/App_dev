package com.smarty.data.dao

import androidx.room.*
import com.smarty.data.entity.*
import com.smarty.data.relationship.*
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Main DAO with comprehensive query support for all entities and relationships
 */
@Dao
interface SmartDatabaseDao {

    // ========== USER OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity): Long

    @Update
    suspend fun updateUser(user: UserEntity)

    @Query("SELECT * FROM users WHERE supabase_id = :supabaseId")
    suspend fun getUserBySupabaseId(supabaseId: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserById(userId: Long): UserEntity?

    @Transaction
    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserWithRelations(userId: Long): UserWithRelations?

    @Transaction
    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserWithCompleteGraph(userId: Long): UserWithCompleteGraph?

    @Query("SELECT * FROM users WHERE sync_state != 'synced' OR last_modified > :since")
    suspend fun getUsersPendingSync(since: Instant? = null): List<UserEntity>

    // ========== NOTE OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity): Long

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Query("UPDATE notes SET sync_state = :syncState WHERE id = :noteId")
    suspend fun updateNoteSyncState(noteId: Long, syncState: String)

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE supabase_id = :supabaseId")
    suspend fun getNoteBySupabaseId(supabaseId: String): NoteEntity?

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteWithRelations(noteId: Long): NoteWithRelations?

    @Query("""
        SELECT * FROM notes 
        WHERE user_id = :userId 
        AND is_archived = 0 
        AND is_deleted = 0
        ORDER BY 
            CASE WHEN is_pinned = 1 THEN 0 ELSE 1 END,
            last_modified DESC
    """)
    fun getActiveNotesForUser(userId: Long): Flow<List<NoteEntity>>

    @Query("""
        SELECT * FROM notes 
        WHERE user_id = :userId 
        AND sync_state != 'synced' 
        OR last_modified > :since
    """)
    suspend fun getNotesPendingSync(userId: Long, since: Instant? = null): List<NoteEntity>

    @Query("""
        SELECT n.* FROM notes n
        JOIN note_tags nt ON n.id = nt.note_id
        WHERE nt.tag_id = :tagId
        AND n.is_deleted = 0
    """)
    suspend fun getNotesByTag(tagId: Long): List<NoteEntity>

    // ========== TAG OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: TagEntity): Long

    @Update
    suspend fun updateTag(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE id = :tagId")
    suspend fun getTagById(tagId: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE user_id = :userId AND type = :type")
    suspend fun getTagsByType(userId: Long, type: String): List<TagEntity>

    @Query("""
        SELECT t.*, nt.note_id 
        FROM tags t
        JOIN note_tags nt ON t.id = nt.tag_id
        WHERE nt.note_id = :noteId
    """)
    suspend fun getTagsForNote(noteId: Long): List<TagEntity>

    // ========== NOTE_TAG JUNCTION OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNoteTag(noteTag: NoteTagEntity): Long

    @Delete
    suspend fun deleteNoteTag(noteTag: NoteTagEntity)

    @Query("DELETE FROM note_tags WHERE note_id = :noteId")
    suspend fun deleteNoteTagsForNote(noteId: Long)

    @Query("""
        SELECT nt.*, t.* 
        FROM note_tags nt
        JOIN tags t ON nt.tag_id = t.id
        WHERE nt.note_id = :noteId
    """)
    suspend fun getNoteTagsWithDetails(noteId: Long): List<TagWithNoteTag>

    @Query("""
        SELECT COUNT(*) FROM note_tags 
        WHERE note_id = :noteId 
        AND tagging_type = 'CATEGORY'
    """)
    suspend fun getCategoryCountForNote(noteId: Long): Int

    // ========== CHAT OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity): Long

    @Update
    suspend fun updateChat(chat: ChatEntity)

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChatById(chatId: Long): ChatEntity?

    @Transaction
    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChatWithMessages(chatId: Long): ChatWithMessages?

    @Query("SELECT * FROM chats WHERE user_id = :userId ORDER BY last_modified DESC")
    suspend fun getChatsForUser(userId: Long): List<ChatEntity>

    // ========== CHAT_MESSAGE OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessage(message: ChatMessageEntity): Long

    @Update
    suspend fun updateChatMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE id = :messageId")
    suspend fun getChatMessageById(messageId: Long): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages WHERE chat_id = :chatId ORDER BY created_at ASC")
    suspend fun getMessagesForChat(chatId: Long): List<ChatMessageEntity>

    @Transaction
    @Query("SELECT * FROM chat_messages WHERE chat_id = :chatId ORDER BY created_at ASC")
    suspend fun getMessagesWithNotes(chatId: Long): List<ChatMessageWithNotes>

    // ========== CHAT_MESSAGE_NOTE JUNCTION OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessageNote(link: ChatMessageNoteEntity): Long

    @Delete
    suspend fun deleteChatMessageNote(link: ChatMessageNoteEntity)

    @Query("""
        SELECT * FROM chat_message_notes 
        WHERE chat_message_id = :messageId
    """)
    suspend fun getLinksForMessage(messageId: Long): List<ChatMessageNoteEntity>

    @Query("""
        SELECT n.* FROM notes n
        JOIN chat_message_notes cmn ON n.id = cmn.note_id
        WHERE cmn.chat_message_id = :messageId
    """)
    suspend fun getLinkedNotesForMessage(messageId: Long): List<NoteEntity>

    // ========== CALENDAR EVENT OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalendarEvent(event: CalendarEventEntity): Long

    @Update
    suspend fun updateCalendarEvent(event: CalendarEventEntity)

    @Query("SELECT * FROM calendar_events WHERE id = :eventId")
    suspend fun getCalendarEventById(eventId: Long): CalendarEventEntity?

    @Transaction
    @Query("SELECT * FROM calendar_events WHERE id = :eventId")
    suspend fun getCalendarEventWithNotes(eventId: Long): CalendarEventWithNotes?

    @Query("""
        SELECT * FROM calendar_events 
        WHERE user_id = :userId 
        AND start_time >= :startTime 
        AND end_time <= :endTime
        ORDER BY start_time ASC
    """)
    suspend fun getEventsInRange(userId: Long, startTime: Instant, endTime: Instant): List<CalendarEventEntity>

    // ========== CALENDAR_EVENT_NOTE JUNCTION OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalendarEventNote(link: CalendarEventNoteEntity): Long

    @Delete
    suspend fun deleteCalendarEventNote(link: CalendarEventNoteEntity)

    @Query("""
        SELECT * FROM calendar_event_notes 
        WHERE event_id = :eventId
    """)
    suspend fun getLinksForEvent(eventId: Long): List<CalendarEventNoteEntity>

    @Query("""
        SELECT n.* FROM notes n
        JOIN calendar_event_notes cen ON n.id = cen.note_id
        WHERE cen.event_id = :eventId
    """)
    suspend fun getLinkedNotesForEvent(eventId: Long): List<NoteEntity>

    // ========== TASK OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: Long): TaskEntity?

    @Query("""
        SELECT * FROM tasks 
        WHERE user_id = :userId 
        AND status != 'DONE' 
        AND is_deleted = 0
        ORDER BY priority DESC, due_date ASC
    """)
    suspend fun getActiveTasksForUser(userId: Long): List<TaskEntity>

    // ========== SHARED_ITEM OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSharedItem(item: SharedItemEntity): Long

    @Delete
    suspend fun deleteSharedItem(item: SharedItemEntity)

    @Query("""
        SELECT * FROM shared_items 
        WHERE owner_user_id = :ownerId 
        AND shared_with_user_id = :sharedWithId
    """)
    suspend fun getSharedItems(ownerId: Long, sharedWithId: Long): List<SharedItemEntity>

    @Query("""
        SELECT * FROM shared_items 
        WHERE item_id = :itemId 
        AND item_type = :itemType
    """)
    suspend fun getSharesForItem(itemId: Long, itemType: String): List<SharedItemEntity>

    // ========== REASONING_TRACE OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReasoningTrace(trace: ReasoningTraceEntity): Long

    @Query("SELECT * FROM reasoning_traces WHERE id = :traceId")
    suspend fun getReasoningTraceById(traceId: Long): ReasoningTraceEntity?

    @Query("""
        SELECT * FROM reasoning_traces 
        WHERE entity_type = :entityType 
        AND entity_id = :entityId
        ORDER BY created_at DESC
    """)
    suspend fun getTracesForEntity(entityType: String, entityId: Long): List<ReasoningTraceEntity>

    @Query("""
        SELECT * FROM reasoning_traces 
        WHERE user_id = :userId 
        AND created_at > :since
        ORDER BY created_at DESC
        LIMIT :limit
    """)
    suspend fun getRecentTracesForUser(userId: Long, since: Instant, limit: Int = 50): List<ReasoningTraceEntity>

    // ========== AGENT_CHECKPOINT OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgentCheckpoint(checkpoint: AgentCheckpointEntity): Long

    @Query("SELECT * FROM agent_checkpoints WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestCheckpointForSession(sessionId: String): AgentCheckpointEntity?

    @Query("""
        SELECT * FROM agent_checkpoints 
        WHERE user_id = :userId 
        AND session_id = :sessionId
        ORDER BY created_at DESC
    """)
    suspend fun getCheckpointsForSession(userId: Long, sessionId: String): List<AgentCheckpointEntity>

    // ========== SEARCH OPERATIONS ==========

    @Query("""
        SELECT * FROM search_index 
        WHERE user_id = :userId 
        AND search_content LIKE '%' || :query || '%'
        ORDER BY weight DESC, last_indexed DESC
        LIMIT :limit
    """)
    suspend fun searchIndex(userId: Long, query: String, limit: Int = 100): List<SearchIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchIndex(index: SearchIndexEntity): Long

    @Query("DELETE FROM search_index WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun deleteSearchIndexForEntity(entityType: String, entityId: Long)

    // ========== CRDT OPERATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCRDTMetadata(metadata: CRDTMetadataEntity): Long

    @Query("""
        SELECT * FROM crdt_metadata 
        WHERE entity_type = :entityType 
        AND entity_id = :entityId
    """)
    suspend fun getCRDTMetadata(entityType: String, entityId: Long): List<CRDTMetadataEntity>

    @Query("""
        SELECT * FROM crdt_metadata 
        WHERE entity_type = :entityType 
        AND entity_id = :entityId 
        AND device_id = :deviceId
    """)
    suspend fun getCRDTMetadataForDevice(entityType: String, entityId: Long, deviceId: String): CRDTMetadataEntity?

    // ========== BULK OPERATIONS ==========


    // ========== CLEANUP OPERATIONS ==========

    @Query("DELETE FROM notes WHERE is_deleted = 1 AND last_modified < :before")
    suspend fun cleanupDeletedNotes(before: Instant)

    @Query("DELETE FROM search_index WHERE last_indexed < :before")
    suspend fun cleanupOldSearchIndex(before: Instant)

    @Query("DELETE FROM crdt_metadata WHERE last_updated < :before")
    suspend fun cleanupOldCRDTMetadata(before: Instant)
}
