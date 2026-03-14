-- =============================================================================
-- SYNC PERFORMANCE OPTIMIZATION - Database Indexes
-- =============================================================================
-- CRITICAL: Run these indexes to achieve <300ms sync/pull latency
-- Deploy to: PostgreSQL database used by Smarty server
-- =============================================================================

-- Notes table: Index for delta-sync queries
-- Used by: NoteRepository.listByUserUpdatedAfter()
CREATE INDEX IF NOT EXISTS idx_notes_user_updated_at 
ON notes(user_id, updated_at DESC);

-- Notes table: Index for soft-delete filtering
CREATE INDEX IF NOT EXISTS idx_notes_user_deleted 
ON notes(user_id, deleted_at) WHERE deleted_at IS NULL;

-- Chat sessions table: Index for delta-sync queries
-- Used by: ChatRepository.listSessionsUpdatedAfter()
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_updated_at 
ON chat_sessions(user_id, updated_at DESC);

-- Chat messages table: Index for batch message loading
-- Used by: ChatRepository.getAllMessagesForSession()
CREATE INDEX IF NOT EXISTS idx_chat_messages_session_created 
ON chat_messages(session_id, created_at ASC);

-- Chat messages table: Index for user isolation
CREATE INDEX IF NOT EXISTS idx_chat_messages_user 
ON chat_messages(user_id);

-- Calendar events table: Index for delta-sync queries
-- Used by: CalendarRepository.listEventsUpdatedAfter()
CREATE INDEX IF NOT EXISTS idx_calendar_events_user_updated_at 
ON calendar_events(user_id, updated_at DESC);

-- Calendar events table: Index for soft-delete filtering (if applicable)
CREATE INDEX IF NOT EXISTS idx_calendar_events_user_status 
ON calendar_events(user_id, status) WHERE status <> 'cancelled';

-- Sync state table: Index for user lookup
CREATE INDEX IF NOT EXISTS idx_sync_state_user 
ON sync_state(user_id);

-- =============================================================================
-- VERIFICATION QUERIES
-- =============================================================================
-- Run these to verify index usage (should show "Index Scan" not "Seq Scan"):

-- EXPLAIN ANALYZE SELECT * FROM notes 
-- WHERE user_id = 'YOUR_USER_ID' AND updated_at > now() - interval '1 day';

-- EXPLAIN ANALYZE SELECT * FROM chat_sessions 
-- WHERE user_id = 'YOUR_USER_ID' AND updated_at > now() - interval '1 day';

-- EXPLAIN ANALYZE SELECT * FROM chat_messages 
-- WHERE session_id = 'YOUR_SESSION_ID' ORDER BY created_at;
-- =============================================================================
