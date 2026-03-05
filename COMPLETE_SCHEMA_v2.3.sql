-- =============================================================================
-- SMARTY SERVER - COMPLETE DATABASE SCHEMA
-- Version: 2.3 (Thinking Persistence Support)
-- Date: 2026-03-05
-- =============================================================================
-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
-- =============================================================================
-- CHAT SYSTEM TABLES
-- =============================================================================
-- Chat Sessions (Conversation containers)
CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    title TEXT DEFAULT 'New Chat',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    message_count INTEGER DEFAULT 0,
    last_message_preview TEXT DEFAULT '',
    is_active BOOLEAN DEFAULT true,
    summary TEXT,
    summary_generated_at BIGINT
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON chat_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_active ON chat_sessions(is_active);
CREATE INDEX IF NOT EXISTS idx_sessions_updated ON chat_sessions(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_sessions_user_updated ON chat_sessions(user_id, updated_at DESC);
-- Chat Messages (Individual messages in conversations)
-- UPDATED: Added thinking column for AI reasoning persistence
CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    thinking TEXT DEFAULT NULL,  -- ✅ ADDED: AI reasoning/thinking content for collapsible display
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    attachments_json TEXT DEFAULT '[]',
    executed_actions_json TEXT DEFAULT '[]',
    referenced_note_ids TEXT DEFAULT '',
    citations_json TEXT DEFAULT '[]',
    inline_images_json TEXT DEFAULT '[]'
);
CREATE INDEX IF NOT EXISTS idx_messages_session ON chat_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_messages_user ON chat_messages(user_id);
CREATE INDEX IF NOT EXISTS idx_messages_created ON chat_messages(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_session_created ON chat_messages(session_id, created_at);
-- =============================================================================
-- AGENT MEMORY & CONTEXT TABLES
-- =============================================================================
-- Agent Context (User facts, preferences, episodic memories)
CREATE TABLE IF NOT EXISTS agent_context (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_context_user ON agent_context(user_id);
CREATE INDEX IF NOT EXISTS idx_context_type ON agent_context((metadata->>'type'));
CREATE INDEX IF NOT EXISTS idx_context_content ON agent_context USING GIN (to_tsvector('english', content));
-- =============================================================================
-- NOTES TABLES
-- =============================================================================
-- Notes (User-created notes)
CREATE TABLE IF NOT EXISTS notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    category TEXT,
    is_archived BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notes_user ON notes(user_id);
CREATE INDEX IF NOT EXISTS idx_notes_archived ON notes(is_archived);
CREATE INDEX IF NOT EXISTS idx_notes_category ON notes(category);
CREATE INDEX IF NOT EXISTS idx_notes_content ON notes USING GIN (to_tsvector('english', content));
CREATE INDEX IF NOT EXISTS idx_notes_user_updated ON notes(user_id, updated_at DESC);
-- =============================================================================
-- TIMERS & ALARMS TABLES
-- =============================================================================
-- Timers and Alarms
CREATE TABLE IF NOT EXISTS timers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    trigger_at TIMESTAMP WITH TIME ZONE,
    is_alarm BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_timers_user ON timers(user_id);
CREATE INDEX IF NOT EXISTS idx_timers_active ON timers(is_active);
CREATE INDEX IF NOT EXISTS idx_timers_trigger ON timers(trigger_at);
-- =============================================================================
-- CALENDAR TABLES
-- =============================================================================
-- Calendar Events
CREATE TABLE IF NOT EXISTS calendar_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    title TEXT NOT NULL,
    start_time BIGINT NOT NULL,
    end_time BIGINT NOT NULL,
    description TEXT,
    reminder_minutes INT DEFAULT 15,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_calendar_user ON calendar_events(user_id);
CREATE INDEX IF NOT EXISTS idx_calendar_start ON calendar_events(start_time);
CREATE INDEX IF NOT EXISTS idx_calendar_user_start ON calendar_events(user_id, start_time);
-- =============================================================================
-- AGENT OBSERVABILITY TABLES
-- =============================================================================
-- Agent Traces (For debugging and observability)
CREATE TABLE IF NOT EXISTS agent_traces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID,
    user_id TEXT NOT NULL,
    step_type TEXT NOT NULL,
    content TEXT,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_traces_session ON agent_traces(session_id);
CREATE INDEX IF NOT EXISTS idx_traces_user ON agent_traces(user_id);
CREATE INDEX IF NOT EXISTS idx_traces_type ON agent_traces(step_type);
-- Agent Checkpoints (Session persistence for recovery)
CREATE TABLE IF NOT EXISTS agent_checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL UNIQUE,
    user_id TEXT NOT NULL,
    state_json JSONB NOT NULL,
    last_node TEXT,
    version INTEGER DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_checkpoints_session ON agent_checkpoints(session_id);
CREATE INDEX IF NOT EXISTS idx_checkpoints_user ON agent_checkpoints(user_id);
-- =============================================================================
-- DIGEST SYSTEM TABLES
-- =============================================================================
-- Daily Digests
CREATE TABLE IF NOT EXISTS daily_digests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    digest_date DATE NOT NULL,
    digest_type TEXT NOT NULL DEFAULT 'daily',
    summary TEXT NOT NULL,
    key_insights JSONB,
    action_items JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, digest_date, digest_type)
);
CREATE INDEX IF NOT EXISTS idx_digests_user ON daily_digests(user_id);
CREATE INDEX IF NOT EXISTS idx_digests_date ON daily_digests(digest_date);
-- Digest Preferences
CREATE TABLE IF NOT EXISTS digest_preferences (
    user_id TEXT PRIMARY KEY,
    daily_enabled BOOLEAN DEFAULT TRUE,
    daily_time TIME DEFAULT '07:00:00',
    weekly_enabled BOOLEAN DEFAULT TRUE,
    weekly_day INT DEFAULT 0,
    weekly_time TIME DEFAULT '08:00:00',
    push_notification BOOLEAN DEFAULT TRUE,
    calendar_logging BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
-- =============================================================================
-- SECURITY & AUTHENTICATION TABLES
-- =============================================================================
-- User Vault (Encrypted sensitive data - zero-knowledge)
CREATE TABLE IF NOT EXISTS user_vaults (
    user_id VARCHAR(128) PRIMARY KEY,
    encrypted_blob TEXT NOT NULL,
    version INT DEFAULT 1,
    updated_at BIGINT NOT NULL
);
-- FCM Tokens (Push notifications)
CREATE TABLE IF NOT EXISTS user_fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    token TEXT NOT NULL UNIQUE,
    device_name TEXT,
    device_id TEXT,
    last_used_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_fcm_tokens_user ON user_fcm_tokens(user_id);
-- =============================================================================
-- SYNC SYSTEM TABLES
-- =============================================================================
-- Sync Tokens (Track last sync timestamps per user)
CREATE TABLE IF NOT EXISTS sync_tokens (
    user_id TEXT PRIMARY KEY,
    last_sync_at TIMESTAMP WITH TIME ZONE,
    last_pull_at TIMESTAMP WITH TIME ZONE
);
-- =============================================================================
-- TEXT SEARCH FUNCTIONS
-- =============================================================================
-- Text search function for context
CREATE OR REPLACE FUNCTION search_context_text(
    query_user_id TEXT,
    query_text TEXT,
    match_count INT
)
RETURNS TABLE (
    id UUID,
    content TEXT,
    metadata JSONB,
    similarity FLOAT
)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT
        agent_context.id,
        agent_context.content,
        agent_context.metadata,
        ts_rank_cd(
            to_tsvector('english', agent_context.content),
            websearch_to_tsquery('english', query_text)
        )::FLOAT as similarity
    FROM agent_context
    WHERE agent_context.user_id = query_user_id
      AND to_tsvector('english', agent_context.content) @@ websearch_to_tsquery('english', query_text)
    ORDER BY similarity DESC
    LIMIT match_count;
END;
$$;
-- =============================================================================
-- TRIGGERS FOR AUTO-UPDATE updated_at
-- =============================================================================
-- Function to auto-update updated_at column
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
-- Apply triggers to tables with updated_at
DROP TRIGGER IF EXISTS update_chat_sessions_updated_at ON chat_sessions;
CREATE TRIGGER update_chat_sessions_updated_at
    BEFORE UPDATE ON chat_sessions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
DROP TRIGGER IF EXISTS update_chat_messages_updated_at ON chat_messages;
CREATE TRIGGER update_chat_messages_updated_at
    BEFORE UPDATE ON chat_messages
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
DROP TRIGGER IF EXISTS update_notes_updated_at ON notes;
CREATE TRIGGER update_notes_updated_at
    BEFORE UPDATE ON notes
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
DROP TRIGGER IF EXISTS update_calendar_events_updated_at ON calendar_events;
CREATE TRIGGER update_calendar_events_updated_at
    BEFORE UPDATE ON calendar_events
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
DROP TRIGGER IF EXISTS update_agent_context_updated_at ON agent_context;
CREATE TRIGGER update_agent_context_updated_at
    BEFORE UPDATE ON agent_context
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
DROP TRIGGER IF EXISTS update_digest_preferences_updated_at ON digest_preferences;
CREATE TRIGGER update_digest_preferences_updated_at
    BEFORE UPDATE ON digest_preferences
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
-- =============================================================================
-- ROW LEVEL SECURITY (RLS) - Optional for multi-tenant isolation
-- =============================================================================
-- Enable RLS on all tables that have user_id (uncomment if needed)
-- ALTER TABLE chat_sessions ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE chat_messages ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE notes ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE timers ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE calendar_events ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE agent_context ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE agent_traces ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE agent_checkpoints ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE daily_digests ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE digest_preferences ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE user_fcm_tokens ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE sync_tokens ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE user_vaults ENABLE ROW LEVEL SECURITY;
-- =============================================================================
-- CHANGELOG
-- =============================================================================
-- v2.3 (2026-03-05): Added thinking column to chat_messages for AI reasoning persistence
-- v2.2 (2026-02-23): Sync Support + Offline-Capable Thin Client
-- =============================================================================
