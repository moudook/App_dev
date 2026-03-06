-- =============================================================================
-- SMARTY - COMPLETE SUPABASE DATABASE SCHEMA v3.2.1
-- =============================================================================
-- Version: 3.2.1 (Thinking Column Fix)
-- Date: March 6, 2026
-- Author: Smarty Team
-- License: MIT
-- =============================================================================
-- DESCRIPTION:
-- Complete database schema for Smarty AI Research Agent
-- Includes: Chat, Notes, Research Agent, Calendar, Timers, Sync, Security
-- =============================================================================
-- INSTRUCTIONS:
-- 1. Go to Supabase → SQL Editor
-- 2. Copy this entire file
-- 3. Paste and run all at once
-- 4. All tables, indexes, triggers, and functions will be created
-- =============================================================================

-- =============================================================================
-- PART 1: ENABLE EXTENSIONS
-- =============================================================================

-- UUID generation
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Cryptographic functions
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Vector similarity search (for AI embeddings)
CREATE EXTENSION IF NOT EXISTS "vector";

-- Full-text search improvements
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- =============================================================================
-- PART 2: CHAT SYSTEM TABLES
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
    summary_generated_at BIGINT,
    CONSTRAINT valid_user_id CHECK (length(user_id) > 0)
);

-- Indexes for chat sessions
CREATE INDEX IF NOT EXISTS idx_sessions_user ON chat_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_active ON chat_sessions(is_active);
CREATE INDEX IF NOT EXISTS idx_sessions_updated ON chat_sessions(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_sessions_user_updated ON chat_sessions(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_sessions_created ON chat_sessions(created_at DESC);

-- Chat Messages (Individual messages with thinking persistence)
-- v3.2.1 FIX: thinking column included from start
CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('USER', 'SMARTY', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    content TEXT NOT NULL,
    thinking TEXT DEFAULT NULL,  -- ✅ v3.2.1: AI reasoning/thinking content for collapsible display
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    attachments_json TEXT DEFAULT '[]',
    executed_actions_json TEXT DEFAULT '[]',
    referenced_note_ids TEXT DEFAULT '',
    citations_json TEXT DEFAULT '[]',
    inline_images_json TEXT DEFAULT '[]',
    CONSTRAINT valid_message_role CHECK (length(role) > 0),
    CONSTRAINT valid_content CHECK (length(content) > 0)
);

-- Indexes for chat messages
CREATE INDEX IF NOT EXISTS idx_messages_session ON chat_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_messages_user ON chat_messages(user_id);
CREATE INDEX IF NOT EXISTS idx_messages_created ON chat_messages(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_session_created ON chat_messages(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_messages_user_created ON chat_messages(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_thinking ON chat_messages(thinking) WHERE thinking IS NOT NULL;

-- =============================================================================
-- PART 3: AGENT MEMORY & CONTEXT TABLES
-- =============================================================================

-- Agent Context (User facts, preferences, episodic memories)
CREATE TABLE IF NOT EXISTS agent_context (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT valid_context_content CHECK (length(content) > 0)
);

-- Indexes for agent context
CREATE INDEX IF NOT EXISTS idx_context_user ON agent_context(user_id);
CREATE INDEX IF NOT EXISTS idx_context_type ON agent_context((metadata->>'type'));
CREATE INDEX IF NOT EXISTS idx_context_content ON agent_context USING GIN (to_tsvector('english', content));
CREATE INDEX IF NOT EXISTS idx_context_metadata ON agent_context USING GIN (metadata);

-- AI Memories (Persistent learnings about user)
CREATE TABLE IF NOT EXISTS ai_memories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('fact', 'preference', 'episodic', 'goal')),
    content TEXT NOT NULL,
    confidence REAL NOT NULL DEFAULT 1.0 CHECK (confidence >= 0 AND confidence <= 1),
    source TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_used_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    usage_count INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT valid_memory_type CHECK (length(type) > 0),
    CONSTRAINT valid_memory_content CHECK (length(content) > 0)
);

-- Indexes for AI memories
CREATE INDEX IF NOT EXISTS idx_memories_user ON ai_memories(user_id);
CREATE INDEX IF NOT EXISTS idx_memories_type ON ai_memories(type);
CREATE INDEX IF NOT EXISTS idx_memories_confidence ON ai_memories(confidence DESC);
CREATE INDEX IF NOT EXISTS idx_memories_user_type ON ai_memories(user_id, type);

-- =============================================================================
-- PART 4: NOTES TABLES
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
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    file_uri TEXT,
    file_name TEXT,
    file_mime_type TEXT,
    file_size BIGINT DEFAULT 0,
    image_uri TEXT,
    type TEXT DEFAULT 'brain_dump',
    processing_status TEXT DEFAULT 'pending',
    category_id TEXT,
    category_name TEXT,
    source_url TEXT,
    exclude_from_ai_chat BOOLEAN DEFAULT FALSE,
    is_full_privacy BOOLEAN DEFAULT FALSE,
    summary TEXT,
    why_saved TEXT,
    attachments_json TEXT DEFAULT '[]',
    CONSTRAINT valid_note_title CHECK (length(title) > 0),
    CONSTRAINT valid_note_content CHECK (length(content) > 0)
);

-- Indexes for notes
CREATE INDEX IF NOT EXISTS idx_notes_user ON notes(user_id);
CREATE INDEX IF NOT EXISTS idx_notes_archived ON notes(is_archived);
CREATE INDEX IF NOT EXISTS idx_notes_category ON notes(category);
CREATE INDEX IF NOT EXISTS idx_notes_content ON notes USING GIN (to_tsvector('english', content));
CREATE INDEX IF NOT EXISTS idx_notes_user_updated ON notes(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_notes_user_created ON notes(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notes_type ON notes(type);
CREATE INDEX IF NOT EXISTS idx_notes_processing_status ON notes(processing_status);

-- Categories (Note organization)
CREATE TABLE IF NOT EXISTS categories (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    note_count INTEGER DEFAULT 0,
    color TEXT,
    icon TEXT,
    is_system BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT valid_category_name CHECK (length(name) > 0)
);

-- Indexes for categories
CREATE INDEX IF NOT EXISTS idx_categories_user ON categories(user_id);
CREATE INDEX IF NOT EXISTS idx_categories_name ON categories(name);

-- Note Versions (Version history for notes)
CREATE TABLE IF NOT EXISTS note_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    content TEXT NOT NULL,
    title TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    change_summary TEXT,
    CONSTRAINT valid_version_number CHECK (version_number > 0)
);

-- Indexes for note versions
CREATE INDEX IF NOT EXISTS idx_versions_note ON note_versions(note_id);
CREATE INDEX IF NOT EXISTS idx_versions_note_version ON note_versions(note_id, version_number DESC);

-- =============================================================================
-- PART 5: TIMERS & ALARMS TABLES
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
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT valid_timer_name CHECK (length(name) > 0),
    CONSTRAINT valid_duration CHECK (duration_ms >= 0)
);

-- Indexes for timers
CREATE INDEX IF NOT EXISTS idx_timers_user ON timers(user_id);
CREATE INDEX IF NOT EXISTS idx_timers_active ON timers(is_active);
CREATE INDEX IF NOT EXISTS idx_timers_trigger ON timers(trigger_at);
CREATE INDEX IF NOT EXISTS idx_timers_user_active ON timers(user_id, is_active);

-- =============================================================================
-- PART 6: CALENDAR TABLES
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
    location TEXT,
    attendees_json TEXT DEFAULT '[]',
    recurrence_rule TEXT,
    google_event_id TEXT,
    is_all_day BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT valid_event_title CHECK (length(title) > 0),
    CONSTRAINT valid_event_times CHECK (end_time >= start_time)
);

-- Indexes for calendar events
CREATE INDEX IF NOT EXISTS idx_calendar_user ON calendar_events(user_id);
CREATE INDEX IF NOT EXISTS idx_calendar_start ON calendar_events(start_time);
CREATE INDEX IF NOT EXISTS idx_calendar_user_start ON calendar_events(user_id, start_time);
CREATE INDEX IF NOT EXISTS idx_calendar_end ON calendar_events(end_time);
CREATE INDEX IF NOT EXISTS idx_calendar_google_id ON calendar_events(google_event_id) WHERE google_event_id IS NOT NULL;

-- =============================================================================
-- PART 7: RESEARCH AGENT TABLES (v3.0+)
-- =============================================================================

-- Research Sessions (Deep research tracking)
CREATE TABLE IF NOT EXISTS research_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    topic TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'asking_questions' CHECK (status IN ('asking_questions', 'planning', 'researching', 'synthesizing', 'completed', 'waiting_user_input')),
    research_plan TEXT,
    final_report TEXT,
    clarification_questions JSONB DEFAULT '[]',
    user_answers JSONB DEFAULT '{}',
    context_token_count INTEGER DEFAULT 0,
    started_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    timeout_at TIMESTAMP WITH TIME ZONE,
    progress_file_path TEXT,
    CONSTRAINT valid_research_topic CHECK (length(topic) > 0)
);

-- Indexes for research sessions
CREATE INDEX IF NOT EXISTS idx_research_sessions_user ON research_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_research_sessions_status ON research_sessions(status);
CREATE INDEX IF NOT EXISTS idx_research_sessions_created ON research_sessions(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_research_sessions_user_status ON research_sessions(user_id, status);

-- Research Search Queries (Track all web searches)
CREATE TABLE IF NOT EXISTS research_searches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    query TEXT NOT NULL,
    purpose TEXT,
    results JSONB DEFAULT '[]',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT valid_search_query CHECK (length(query) > 0)
);

-- Indexes for research searches
CREATE INDEX IF NOT EXISTS idx_research_searches_session ON research_searches(session_id);
CREATE INDEX IF NOT EXISTS idx_research_searches_query ON research_searches(query);
CREATE INDEX IF NOT EXISTS idx_research_searches_created ON research_searches(created_at DESC);

-- Research Citations (All sourced information)
CREATE TABLE IF NOT EXISTS research_citations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    title TEXT NOT NULL,
    snippet TEXT,
    date_accessed TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    relevance_score DOUBLE PRECISION DEFAULT 1.0 CHECK (relevance_score >= 0 AND relevance_score <= 1),
    key_findings JSONB DEFAULT '[]',
    CONSTRAINT valid_citation_url CHECK (length(url) > 0),
    CONSTRAINT valid_citation_title CHECK (length(title) > 0)
);

-- Indexes for research citations
CREATE INDEX IF NOT EXISTS idx_research_citations_session ON research_citations(session_id);
CREATE INDEX IF NOT EXISTS idx_research_citations_url ON research_citations(url);
CREATE INDEX IF NOT EXISTS idx_research_citations_relevance ON research_citations(relevance_score DESC);

-- Research Progress Logs (Transparent activity trail)
CREATE TABLE IF NOT EXISTS research_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    action TEXT NOT NULL,
    details TEXT NOT NULL,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT valid_log_action CHECK (length(action) > 0),
    CONSTRAINT valid_log_details CHECK (length(details) > 0)
);

-- Indexes for research logs
CREATE INDEX IF NOT EXISTS idx_research_logs_session ON research_logs(session_id);
CREATE INDEX IF NOT EXISTS idx_research_logs_action ON research_logs(action);
CREATE INDEX IF NOT EXISTS idx_research_logs_created ON research_logs(created_at DESC);

-- Research Progress Files (Persistent findings storage)
CREATE TABLE IF NOT EXISTS research_progress_files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL UNIQUE REFERENCES research_sessions(id) ON DELETE CASCADE,
    findings JSONB DEFAULT '[]',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Indexes for research progress files
CREATE INDEX IF NOT EXISTS idx_research_progress_session ON research_progress_files(session_id);

-- =============================================================================
-- PART 8: DIGEST SYSTEM TABLES
-- =============================================================================

-- Daily Digests (Automated summaries)
CREATE TABLE IF NOT EXISTS daily_digests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    digest_date DATE NOT NULL,
    digest_type TEXT NOT NULL DEFAULT 'daily' CHECK (digest_type IN ('daily', 'weekly', 'monthly')),
    summary TEXT NOT NULL,
    key_insights JSONB,
    action_items JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT valid_digest_summary CHECK (length(summary) > 0),
    UNIQUE(user_id, digest_date, digest_type)
);

-- Indexes for daily digests
CREATE INDEX IF NOT EXISTS idx_digests_user ON daily_digests(user_id);
CREATE INDEX IF NOT EXISTS idx_digests_date ON daily_digests(digest_date);
CREATE INDEX IF NOT EXISTS idx_digests_user_date ON daily_digests(user_id, digest_date DESC);

-- Digest Preferences (User settings for digests)
CREATE TABLE IF NOT EXISTS digest_preferences (
    user_id TEXT PRIMARY KEY,
    daily_enabled BOOLEAN DEFAULT TRUE,
    daily_time TIME DEFAULT '07:00:00',
    weekly_enabled BOOLEAN DEFAULT TRUE,
    weekly_day INT DEFAULT 0 CHECK (weekly_day >= 0 AND weekly_day <= 6),
    weekly_time TIME DEFAULT '08:00:00',
    push_notification BOOLEAN DEFAULT TRUE,
    calendar_logging BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- =============================================================================
-- PART 9: SECURITY & AUTHENTICATION TABLES
-- =============================================================================

-- User Vault (Encrypted sensitive data - zero-knowledge)
CREATE TABLE IF NOT EXISTS user_vaults (
    user_id VARCHAR(128) PRIMARY KEY,
    encrypted_blob TEXT NOT NULL,
    version INT DEFAULT 1,
    updated_at BIGINT NOT NULL,
    CONSTRAINT valid_encrypted_blob CHECK (length(encrypted_blob) > 0)
);

-- FCM Tokens (Push notifications)
CREATE TABLE IF NOT EXISTS user_fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    token TEXT NOT NULL UNIQUE,
    device_name TEXT,
    device_id TEXT,
    last_used_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT valid_fcm_token CHECK (length(token) > 0)
);

-- Indexes for FCM tokens
CREATE INDEX IF NOT EXISTS idx_fcm_tokens_user ON user_fcm_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_fcm_tokens_device ON user_fcm_tokens(device_id);

-- =============================================================================
-- PART 10: SYNC SYSTEM TABLES
-- =============================================================================

-- Sync Tokens (Track last sync timestamps per user)
CREATE TABLE IF NOT EXISTS sync_tokens (
    user_id TEXT PRIMARY KEY,
    last_sync_at TIMESTAMP WITH TIME ZONE,
    last_pull_at TIMESTAMP WITH TIME ZONE
);

-- Sync Queue (Pending changes to sync)
CREATE TABLE IF NOT EXISTS sync_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE')),
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'synced', 'failed')),
    payload JSONB,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    synced_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT valid_entity_type CHECK (length(entity_type) > 0)
);

-- Indexes for sync queue
CREATE INDEX IF NOT EXISTS idx_sync_queue_user ON sync_queue(user_id);
CREATE INDEX IF NOT EXISTS idx_sync_queue_status ON sync_queue(status);
CREATE INDEX IF NOT EXISTS idx_sync_queue_created ON sync_queue(created_at);
CREATE INDEX IF NOT EXISTS idx_sync_queue_user_status ON sync_queue(user_id, status);

-- =============================================================================
-- PART 11: AI CACHE TABLES (Performance optimization)
-- =============================================================================

-- AI Cache (Cached AI responses)
CREATE TABLE IF NOT EXISTS ai_cache (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT,
    query_hash TEXT NOT NULL,
    response JSONB NOT NULL,
    model TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE,
    hit_count INTEGER DEFAULT 0,
    CONSTRAINT valid_query_hash CHECK (length(query_hash) > 0),
    CONSTRAINT valid_model CHECK (length(model) > 0)
);

-- Indexes for AI cache
CREATE INDEX IF NOT EXISTS idx_cache_query_hash ON ai_cache(query_hash);
CREATE INDEX IF NOT EXISTS idx_cache_user ON ai_cache(user_id);
CREATE INDEX IF NOT EXISTS idx_cache_expires ON ai_cache(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_cache_model ON ai_cache(model);

-- =============================================================================
-- PART 12: TEXT SEARCH FUNCTIONS
-- =============================================================================

-- Text search function for agent context
CREATE OR REPLACE FUNCTION search_context_text(
    query_user_id TEXT,
    query_text TEXT,
    match_count INT DEFAULT 10
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

-- Text search function for notes
CREATE OR REPLACE FUNCTION search_notes_text(
    query_user_id TEXT,
    query_text TEXT,
    match_count INT DEFAULT 20
)
RETURNS TABLE (
    id UUID,
    title TEXT,
    content TEXT,
    similarity FLOAT
)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT
        notes.id,
        notes.title,
        notes.content,
        ts_rank_cd(
            to_tsvector('english', notes.content),
            websearch_to_tsquery('english', query_text)
        )::FLOAT as similarity
    FROM notes
    WHERE notes.user_id = query_user_id
      AND notes.is_archived = FALSE
      AND to_tsvector('english', notes.content) @@ websearch_to_tsquery('english', query_text)
    ORDER BY similarity DESC
    LIMIT match_count;
END;
$$;

-- =============================================================================
-- PART 13: TRIGGERS FOR AUTO-UPDATE updated_at
-- =============================================================================

-- Function to auto-update updated_at column
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Apply triggers to all tables with updated_at
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

DROP TRIGGER IF EXISTS update_categories_updated_at ON categories;
CREATE TRIGGER update_categories_updated_at
    BEFORE UPDATE ON categories
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

DROP TRIGGER IF EXISTS update_research_progress_files_updated_at ON research_progress_files;
CREATE TRIGGER update_research_progress_files_updated_at
    BEFORE UPDATE ON research_progress_files
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- =============================================================================
-- PART 14: CLEANUP FUNCTIONS
-- =============================================================================

-- Function to delete old empty chat sessions
CREATE OR REPLACE FUNCTION delete_old_empty_sessions()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM chat_sessions
    WHERE is_active = FALSE
      AND message_count = 0
      AND created_at < NOW() - INTERVAL '1 hour';
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Function to delete old AI cache entries
CREATE OR REPLACE FUNCTION delete_expired_cache()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM ai_cache
    WHERE expires_at IS NOT NULL
      AND expires_at < NOW();
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Function to cleanup old research sessions (completed > 30 days)
CREATE OR REPLACE FUNCTION cleanup_old_research_sessions()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM research_sessions
    WHERE status = 'completed'
      AND completed_at < NOW() - INTERVAL '30 days';
    
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- PART 15: ROW LEVEL SECURITY (RLS) - Optional for multi-tenant isolation
-- =============================================================================

-- Uncomment to enable RLS (recommended for production)
-- ALTER TABLE chat_sessions ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE chat_messages ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE notes ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE categories ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE timers ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE calendar_events ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE agent_context ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE ai_memories ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE research_sessions ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE research_searches ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE research_citations ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE research_logs ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE daily_digests ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE user_fcm_tokens ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE sync_queue ENABLE ROW LEVEL SECURITY;
-- ALTER TABLE ai_cache ENABLE ROW LEVEL SECURITY;

-- Example RLS policy for chat_sessions
-- CREATE POLICY "Users can view own chat sessions"
--     ON chat_sessions FOR SELECT
--     USING (user_id = current_setting('app.current_user_id')::TEXT);

-- =============================================================================
-- PART 16: INITIAL DATA (System categories)
-- =============================================================================

-- Insert system categories if they don't exist
INSERT INTO categories (id, user_id, name, description, is_system, color, icon)
VALUES 
    ('quick_notes', 'system', 'Quick Notes', 'Temporary notes and reminders', TRUE, '#FFB74D', 'note'),
    ('ideas', 'system', 'Ideas', 'Creative ideas and insights', TRUE, '#64B5F6', 'lightbulb'),
    ('tasks', 'system', 'Tasks', 'Action items and to-dos', TRUE, '#81C784', 'checklist'),
    ('research', 'system', 'Research', 'Research findings and references', TRUE, '#BA68C8', 'search'),
    ('personal', 'system', 'Personal', 'Personal notes and journals', TRUE, '#E57373', 'person')
ON CONFLICT (id) DO NOTHING;

-- =============================================================================
-- CHANGELOG
-- =============================================================================
-- v3.2.1 (2026-03-06): 
--   - Added thinking column to base chat_messages table (CRITICAL FIX)
--   - Added indexes for thinking column
--   - Improved constraint validation
--
-- v3.0.0 (2026-03-06): 
--   - Added Research Agent tables (research_sessions, searches, citations, logs, progress_files)
--   - Added AI cache tables
--   - Added sync queue for cloud-first sync
--
-- v2.3 (2026-03-05): 
--   - Added thinking column via migration
--
-- v2.2 (2026-02-23): 
--   - Added sync tokens
--   - Added FCM tokens for push notifications
--
-- v2.0 (2026-02-01): 
--   - Initial complete schema
-- =============================================================================

-- =============================================================================
-- END OF SCHEMA
-- =============================================================================
-- Your Supabase database is now ready for Smarty!
-- Next steps:
-- 1. Set up environment variables (DB_URL, DB_USER, DB_PASSWORD)
-- 2. Configure API keys (TAVILY_API_KEY, GEMINI_API_KEY or OPENAI_API_KEY)
-- 3. Deploy server to Hugging Face
-- 4. Connect Android app to your server
-- =============================================================================
