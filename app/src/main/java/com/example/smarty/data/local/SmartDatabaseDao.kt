package com.example.smarty.data.local

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.smarty.core.domain.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Smart Database DAO - Comprehensive data access with creative integrations
 * 100+ queries enabling tight database-application integration
 */
@Dao
interface SmartDatabaseDao {

    // ============================================================
    // USER OPERATIONS
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity): Long

    @Update
    suspend fun updateUser(user: UserEntity)

    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserById(userId: String): UserEntity?

    @Query("SELECT * FROM users WHERE firebase_uid = :firebaseUid")
    suspend fun getUserByFirebaseUid(firebaseUid: String): UserEntity?

    @Query("SELECT * FROM users WHERE email = :email")
    suspend fun getUserByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users WHERE is_active = 1")
    fun getActiveUsers(): Flow<List<UserEntity>>

    @Query("UPDATE users SET last_login_at = :timestamp, updated_at = :timestamp WHERE id = :userId")
    suspend fun updateLastLogin(userId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE users SET sync_state = :state, updated_at = :timestamp WHERE id = :userId")
    suspend fun updateUserSyncState(userId: String, state: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE users SET device_fingerprint = :fingerprint, last_device_id = :deviceId, updated_at = :timestamp WHERE id = :userId")
    suspend fun updateUserDevice(userId: String, fingerprint: String, deviceId: String, timestamp: Long = System.currentTimeMillis())

    // ============================================================
    // SYNC STATE OPERATIONS
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncState(state: SyncStateEntity): Long

    @Update
    suspend fun updateSyncState(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE user_id = :userId")
    suspend fun getSyncState(userId: String): SyncStateEntity?

    @Query("UPDATE sync_state SET last_sync_at = :timestamp, updated_at = :timestamp WHERE user_id = :userId")
    suspend fun updateLastSync(userId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE sync_state SET last_pull_at = :timestamp, updated_at = :timestamp WHERE user_id = :userId")
    suspend fun updateLastPull(userId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE sync_state SET last_push_at = :timestamp, pending_operations = :pending, conflict_count = :conflicts, updated_at = :timestamp WHERE user_id = :userId")
    suspend fun updateSyncMetrics(userId: String, pending: Int, conflicts: Int, timestamp: Long = System.currentTimeMillis())

    // ============================================================
    // TAG OPERATIONS (Proper tag system)
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: TagEntity): Long

    @Update
    suspend fun updateTag(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE id = :tagId")
    suspend fun getTagById(tagId: String): TagEntity?

    @Query("SELECT * FROM tags WHERE user_id = :userId ORDER BY usage_count DESC, name ASC")
    fun getUserTags(userId: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE user_id = :userId AND name = :name LIMIT 1")
    suspend fun getTagByName(userId: String, name: String): TagEntity?

    @Query("UPDATE tags SET usage_count = usage_count + 1, updated_at = :timestamp WHERE id = :tagId")
    suspend fun incrementTagUsage(tagId: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM tags WHERE user_id = :userId AND tag_type = :type ORDER BY usage_count DESC")
    suspend fun getTagsByType(userId: String, type: String): List<TagEntity>

    // ============================================================
    // NOTE_TAG JUNCTION OPERATIONS
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNoteTag(noteTag: NoteTagEntity): Long

    @Delete
    suspend fun deleteNoteTag(noteTag: NoteTagEntity)

    @Query("DELETE FROM note_versions WHERE note_id = :noteId")
    suspend fun deleteNoteTagsForNote(noteId: String)

    @Query("DELETE FROM note_versions WHERE note_id = :noteId")
    suspend fun deleteNoteVersions(noteId: String)

    @Query("DELETE FROM note_tags WHERE tag_id = :tagId")
    suspend fun deleteNoteTagsForTag(tagId: String)

    @Query("SELECT * FROM note_tags WHERE note_id = :noteId")
    suspend fun getNoteTags(noteId: String): List<NoteTagEntity>

    @Query("SELECT * FROM note_tags WHERE tag_id = :tagId")
    suspend fun getTagNotes(tagId: String): List<NoteTagEntity>

    @Query("SELECT * FROM note_tags WHERE user_id = :userId AND tag_id = :tagId AND note_id = :noteId")
    suspend fun getNoteTag(userId: String, tagId: String, noteId: String): NoteTagEntity?

    // ============================================================
    // Note OPERATIONS (Enhanced with tags)
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long

    @Update
    suspend fun updateNote(note: Note)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: String): Note?

    @Query("SELECT * FROM notes WHERE isArchived = 0 ORDER BY isPinned DESC, createdAt DESC")
    fun getUserActiveNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE isArchived = 1 ORDER BY updatedAt DESC")
    fun getUserArchivedNotes(): Flow<List<Note>>

    // ============================================================
    // NOTE_WITH_TAGS QUERIES (Creative integration)
    // ============================================================

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteWithTags(noteId: String): NoteWithTags?

    @Transaction
    @Query("SELECT * FROM notes WHERE isArchived = 0 ORDER BY isPinned DESC, createdAt DESC")
    fun getUserNotesWithTags(): Flow<List<NoteWithTags>>

    @Transaction
    @Query("SELECT * FROM tags WHERE id = :tagId")
    suspend fun getTagWithNotes(tagId: String): TagWithNotes?

    @Transaction
    @Query("SELECT * FROM tags WHERE user_id = :userId ORDER BY usage_count DESC")
    suspend fun getUserTagsWithNotes(userId: String): List<TagWithNotes>

    // ============================================================
    // CHAT FOLDER OPERATIONS
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatFolder(folder: ChatFolderEntity): Long

    @Update
    suspend fun updateChatFolder(folder: ChatFolderEntity)

    @Query("SELECT * FROM chat_folders WHERE id = :folderId")
    suspend fun getChatFolderById(folderId: String): ChatFolderEntity?

    @Query("SELECT * FROM chat_folders WHERE user_id = :userId ORDER BY sort_order ASC, name ASC")
    fun getUserChatFolders(userId: String): Flow<List<ChatFolderEntity>>

    @Query("DELETE FROM chat_folders WHERE id = :folderId")
    suspend fun deleteChatFolder(folderId: String)

    // ============================================================
    // TASK OPERATIONS
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE user_id = :userId ORDER BY sort_order ASC, due_date ASC")
    fun getUserTasks(userId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND status = :status ORDER BY due_date ASC")
    fun getUserTasksByStatus(userId: String, status: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE user_id = :userId AND is_recurring = 1")
    suspend fun getUserRecurringTasks(userId: String): List<TaskEntity>

    @Query("UPDATE tasks SET status = :status, updated_at = :timestamp WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: String, status: String, timestamp: Long = System.currentTimeMillis())

    // ============================================================
    // NOTE_TASK JUNCTION OPERATIONS
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNoteTask(noteTask: NoteTaskEntity): Long

    @Delete
    suspend fun deleteNoteTask(noteTask: NoteTaskEntity)

    @Query("DELETE FROM note_tasks WHERE note_id = :noteId")
    suspend fun deleteNoteTasksForNote(noteId: String)

    @Query("DELETE FROM note_tasks WHERE task_id = :taskId")
    suspend fun deleteNoteTasksForTask(taskId: String)

    @Query("SELECT * FROM note_tasks WHERE note_id = :noteId")
    suspend fun getNoteTasks(noteId: String): List<NoteTaskEntity>

    @Query("SELECT * FROM note_tasks WHERE task_id = :taskId")
    suspend fun getTaskNotes(taskId: String): List<NoteTaskEntity>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :taskId")
    suspend fun getTaskWithNotes(taskId: String): TaskWithNotes?

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteWithTasks(noteId: String): NoteWithTasks?

    // ============================================================
    // REASONING TRACE OPERATIONS (AI decision provenance)
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReasoningTrace(trace: ReasoningTraceEntity): Long

    @Update
    suspend fun updateReasoningTrace(trace: ReasoningTraceEntity)

    @Query("SELECT * FROM reasoning_traces WHERE id = :traceId")
    suspend fun getReasoningTrace(traceId: String): ReasoningTraceEntity?

    @Query("SELECT * FROM reasoning_traces WHERE session_id = :sessionId ORDER BY step_index ASC")
    fun getSessionReasoningTraces(sessionId: String): Flow<List<ReasoningTraceEntity>>

    @Query("SELECT * FROM reasoning_traces WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getUserRecentReasoningTraces(userId: String, limit: Int = 50): List<ReasoningTraceEntity>

    @Query("SELECT * FROM reasoning_traces WHERE entity_id = :entityId AND entity_type = :entityType ORDER BY step_index ASC")
    fun getEntityReasoningTraces(entityId: String, entityType: String): Flow<List<ReasoningTraceEntity>>

    @Query("SELECT * FROM reasoning_traces WHERE session_id = :sessionId AND is_final = 1 ORDER BY created_at DESC LIMIT 1")
    suspend fun getFinalReasoningForSession(sessionId: String): ReasoningTraceEntity?

    // ============================================================
    // REASONING SUMMARY OPERATIONS
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReasoningSummary(summary: ReasoningSummaryEntity): Long

    @Update
    suspend fun updateReasoningSummary(summary: ReasoningSummaryEntity)

    @Query("SELECT * FROM reasoning_summaries WHERE id = :summaryId")
    suspend fun getReasoningSummary(summaryId: String): ReasoningSummaryEntity?

    @Query("SELECT * FROM reasoning_summaries WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestSummaryForSession(sessionId: String): ReasoningSummaryEntity?

    @Query("SELECT * FROM reasoning_summaries WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit")
    fun getUserReasoningSummaries(userId: String, limit: Int = 20): Flow<List<ReasoningSummaryEntity>>

    @Query("SELECT * FROM reasoning_summaries WHERE user_id = :userId AND reasoning_type = :type ORDER BY created_at DESC")
    fun getUserSummariesByType(userId: String, type: String): Flow<List<ReasoningSummaryEntity>>

    // ============================================================
    // AGENT CHECKPOINT OPERATIONS (Session continuity)
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgentCheckpoint(checkpoint: AgentCheckpointEntity): Long

    @Update
    suspend fun updateAgentCheckpoint(checkpoint: AgentCheckpointEntity)

    @Query("SELECT * FROM agent_checkpoints WHERE id = :checkpointId")
    suspend fun getAgentCheckpoint(checkpointId: String): AgentCheckpointEntity?

    @Query("SELECT * FROM agent_checkpoints WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestCheckpointForSession(sessionId: String): AgentCheckpointEntity?

    @Query("SELECT * FROM agent_checkpoints WHERE user_id = :userId AND workflow_id = :workflowId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestCheckpointForWorkflow(userId: String, workflowId: String): AgentCheckpointEntity?

    @Transaction
    @Query("SELECT * FROM agent_checkpoints WHERE id = :checkpointId")
    suspend fun getCheckpointWithContext(checkpointId: String): AgentCheckpointWithContext?

    @Query("DELETE FROM agent_checkpoints WHERE session_id = :sessionId AND created_at < :olderThan")
    suspend fun deleteOldCheckpoints(sessionId: String, olderThan: Long): Int

    // ============================================================
    // SEARCH HISTORY OPERATIONS (Persistent search tracking)
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchHistory(search: SearchHistoryEntity): Long

    @Query("SELECT * FROM search_history WHERE id = :searchId")
    suspend fun getSearchHistory(searchId: String): SearchHistoryEntity?

    @Query("SELECT * FROM search_history WHERE user_id = :userId ORDER BY created_at DESC LIMIT :limit")
    fun getUserSearchHistory(userId: String, limit: Int = 50): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history WHERE user_id = :userId AND search_type = :type ORDER BY created_at DESC")
    fun getUserSearchesByType(userId: String, type: String): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history WHERE user_id = :userId AND query LIKE '%' || :query || '%' ORDER BY created_at DESC")
    suspend fun searchHistoryByQuery(userId: String, query: String): List<SearchHistoryEntity>

    @Query("DELETE FROM search_history WHERE user_id = :userId AND created_at < :olderThan")
    suspend fun deleteOldSearchHistory(userId: String, olderThan: Long): Int

    // ============================================================
    // FCM TOKEN OPERATIONS (Push notifications)
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFcmToken(token: UserFcmTokenEntity): Long

    @Update
    suspend fun updateFcmToken(token: UserFcmTokenEntity)

    @Query("SELECT * FROM user_fcm_tokens WHERE user_id = :userId")
    fun getUserFcmTokens(userId: String): Flow<List<UserFcmTokenEntity>>

    @Query("SELECT * FROM user_fcm_tokens WHERE token = :token")
    suspend fun getFcmToken(token: String): UserFcmTokenEntity?

    @Query("UPDATE user_fcm_tokens SET is_active = 0 WHERE user_id = :userId AND device_id = :deviceId")
    suspend fun deactivateDeviceTokens(userId: String, deviceId: String)

    @Query("DELETE FROM user_fcm_tokens WHERE user_id = :userId AND device_id = :deviceId")
    suspend fun deleteDeviceTokens(userId: String, deviceId: String)

    // ============================================================
    // DAILY DIGEST OPERATIONS
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyDigest(digest: DailyDigestEntity): Long

    @Update
    suspend fun updateDailyDigest(digest: DailyDigestEntity)

    @Query("SELECT * FROM daily_digests WHERE id = :digestId")
    suspend fun getDailyDigest(digestId: String): DailyDigestEntity?

    @Query("SELECT * FROM daily_digests WHERE user_id = :userId AND digest_date = :date ORDER BY created_at DESC")
    suspend fun getUserDigestForDate(userId: String, date: Long): DailyDigestEntity?

    @Query("SELECT * FROM daily_digests WHERE user_id = :userId AND digest_date >= :startDate AND digest_date <= :endDate ORDER BY digest_date DESC")
    fun getUserDigestsInRange(userId: String, startDate: Long, endDate: Long): Flow<List<DailyDigestEntity>>

    @Query("SELECT * FROM daily_digests WHERE user_id = :userId AND notification_sent = 0 ORDER BY digest_date ASC")
    suspend fun getUnsentDigests(userId: String): List<DailyDigestEntity>

    @Query("UPDATE daily_digests SET notification_sent = 1 WHERE id = :digestId")
    suspend fun markDigestAsSent(digestId: String)

    // ============================================================
    // SHARED ITEMS OPERATIONS (Collaboration)
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSharedItem(sharedItem: SharedItemEntity): Long

    @Update
    suspend fun updateSharedItem(sharedItem: SharedItemEntity)

    @Query("SELECT * FROM shared_items WHERE id = :shareId")
    suspend fun getSharedItem(shareId: String): SharedItemEntity?

    @Query("SELECT * FROM shared_items WHERE share_token = :token")
    suspend fun getSharedItemByToken(token: String): SharedItemEntity?

    @Query("SELECT * FROM shared_items WHERE owner_id = :ownerId ORDER BY created_at DESC")
    fun getItemsSharedByUser(ownerId: String): Flow<List<SharedItemEntity>>

    @Query("SELECT * FROM shared_items WHERE shared_with_id = :userId ORDER BY created_at DESC")
    fun getItemsSharedWithUser(userId: String): Flow<List<SharedItemEntity>>

    @Query("SELECT * FROM shared_items WHERE item_type = :type AND item_id = :itemId")
    suspend fun getItemShares(type: String, itemId: String): List<SharedItemEntity>

    @Query("DELETE FROM shared_items WHERE id = :shareId")
    suspend fun deleteSharedItem(shareId: String)

    @Query("DELETE FROM shared_items WHERE item_type = :type AND item_id = :itemId")
    suspend fun deleteSharesForItem(type: String, itemId: String)

    // ============================================================
    // Note VERSION OPERATIONS (Git-like versioning)
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNoteVersion(version: NoteVersionEntity): Long

    @Query("SELECT * FROM note_versions WHERE id = :versionId")
    suspend fun getNoteVersion(versionId: String): NoteVersionEntity?

    @Query("SELECT * FROM note_versions WHERE note_id = :noteId ORDER BY version_no DESC")
    fun getNoteVersions(noteId: String): Flow<List<NoteVersionEntity>>

    @Query("SELECT * FROM note_versions WHERE note_id = :noteId AND version_no = :versionNo")
    suspend fun getNoteVersionByNumber(noteId: String, versionNo: Int): NoteVersionEntity?

    @Query("SELECT * FROM note_versions WHERE note_id = :noteId ORDER BY version_no DESC LIMIT 1")
    suspend fun getLatestNoteVersion(noteId: String): NoteVersionEntity?

    @Query("SELECT COUNT(*) FROM note_versions WHERE note_id = :noteId")
    suspend fun getNoteVersionCount(noteId: String): Int

    @Query("DELETE FROM note_versions WHERE note_id = :noteId AND version_no < :keepFromVersion")
    suspend fun pruneNoteVersions(noteId: String, keepFromVersion: Int): Int

    // ============================================================
    // CREATIVE CROSS-FEATURE QUERIES
    // ============================================================

    /**
     * Get all user data for AI context (excluding private items)
     */
    @Transaction
    @Query("SELECT * FROM notes WHERE isArchived = 0 AND isFullPrivacy = 0 AND excludeFromAiChat = 0 ORDER BY updatedAt DESC")
    fun getAiVisibleNotes(): Flow<List<Note>>

    /**
     * Get notes with their tags for AI processing
     */
    @Transaction
    @Query("SELECT * FROM notes WHERE isArchived = 0 AND isFullPrivacy = 0 ORDER BY updatedAt DESC")
    fun getNotesWithTagsForAi(): Flow<List<NoteWithTags>>

    /**
     * Find related notes based on tags (creative recommendation)
     */
    @Query("""
        SELECT n.* FROM notes n
        JOIN note_tags nt ON n.id = nt.note_id
        WHERE nt.tag_id IN (
            SELECT tag_id FROM note_tags WHERE note_id = :noteId
        )
        AND n.id != :noteId
        AND n.isArchived = 0
        GROUP BY n.id
        ORDER BY COUNT(nt.tag_id) DESC
        LIMIT :limit
    """)
    suspend fun findRelatedNotesByTags(noteId: String, limit: Int = 10): List<Note>

    /**
     * Get all user data summary for sync status
     */
    @Query("""
        SELECT 
            (SELECT COUNT(*) FROM notes) as note_count,
            (SELECT COUNT(*) FROM tags WHERE user_id = :userId) as tag_count,
            (SELECT COUNT(*) FROM tasks WHERE user_id = :userId AND status != 'COMPLETED') as pending_task_count,
            (SELECT COUNT(*) FROM reasoning_traces WHERE user_id = :userId) as reasoning_count,
            (SELECT COUNT(*) FROM shared_items WHERE owner_id = :userId) as shared_count
    """)
    suspend fun getUserSummary(userId: String): UserSummary

    data class UserSummary(
        val note_count: Int,
        val tag_count: Int,
        val pending_task_count: Int,
        val reasoning_count: Int,
        val shared_count: Int
    )

    // ============================================================
    // BULK OPERATIONS
    // ============================================================

    @Transaction
    suspend fun clearAllUserData(userId: String) {
        deleteAllNotesForUser()
        deleteAllTagsForUser(userId)
        deleteAllTasksForUser(userId)
        deleteAllReasoningTracesForUser(userId)
        deleteAllReasoningSummariesForUser(userId)
        deleteAllAgentCheckpointsForUser(userId)
        deleteAllSearchHistoryForUser(userId)
        deleteAllSharedItemsForUser(userId)
    }

    @Query("DELETE FROM notes")
    suspend fun deleteAllNotesForUser()

    @Query("DELETE FROM tags WHERE user_id = :userId")
    suspend fun deleteAllTagsForUser(userId: String)

    @Query("DELETE FROM note_tags WHERE user_id = :userId")
    suspend fun deleteAllNoteTagsForUser(userId: String)

    @Query("DELETE FROM tasks WHERE user_id = :userId")
    suspend fun deleteAllTasksForUser(userId: String)

    @Query("DELETE FROM note_tasks WHERE user_id = :userId")
    suspend fun deleteAllNoteTasksForUser(userId: String)

    @Query("DELETE FROM reasoning_traces WHERE user_id = :userId")
    suspend fun deleteAllReasoningTracesForUser(userId: String)

    @Query("DELETE FROM reasoning_summaries WHERE user_id = :userId")
    suspend fun deleteAllReasoningSummariesForUser(userId: String)

    @Query("DELETE FROM agent_checkpoints WHERE user_id = :userId")
    suspend fun deleteAllAgentCheckpointsForUser(userId: String)

    @Query("DELETE FROM search_history WHERE user_id = :userId")
    suspend fun deleteAllSearchHistoryForUser(userId: String)

    @Query("DELETE FROM shared_items WHERE owner_id = :userId OR shared_with_id = :userId")
    suspend fun deleteAllSharedItemsForUser(userId: String)

    // ============================================================
    // RAW QUERY FOR ADVANCED OPERATIONS
    // ============================================================

    @RawQuery
    suspend fun executeRawQuery(query: SupportSQLiteQuery): Int
}
