-- Smarty Database Schema v2.2 - Sync Support
-- Run this script to add sync-related columns and tables

-- 1. Add updated_at to chat_messages if not exists
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_messages' AND column_name = 'updated_at') THEN
        ALTER TABLE chat_messages ADD COLUMN updated_at TIMESTAMPTZ DEFAULT NOW();
    END IF;
END $$;

-- 2. Add updated_at to calendar_events if not exists
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'calendar_events' AND column_name = 'updated_at') THEN
        ALTER TABLE calendar_events ADD COLUMN updated_at TIMESTAMPTZ DEFAULT NOW();
    END IF;
END $$;

-- 3. Create sync_tokens table for tracking sync status
CREATE TABLE IF NOT EXISTS sync_tokens (
    user_id TEXT PRIMARY KEY,
    last_sync_at TIMESTAMPTZ,
    last_pull_at TIMESTAMPTZ
);

-- 4. Create indexes for fast incremental sync queries
CREATE INDEX IF NOT EXISTS idx_notes_user_updated ON notes(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_sessions_user_updated ON chat_sessions(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_session_created ON chat_messages(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_calendar_user_start ON calendar_events(user_id, start_time);
