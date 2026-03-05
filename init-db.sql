-- Smarty Database Schema v2.0 (Multi-Tenant)
-- Run this script to initialize or migrate the database

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Agent Context Table (with multi-tenant user isolation)
CREATE TABLE IF NOT EXISTS agent_context (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL,
    embedding VECTOR(1536),  -- 1536 dimensions for standardized embedding models
    metadata JSONB,          -- Context: source, timestamp, tags, type
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- HNSW Index for fast similarity search
CREATE INDEX IF NOT EXISTS agent_context_embedding_idx
ON agent_context USING hnsw (embedding vector_cosine_ops);

-- Index for user isolation
CREATE INDEX IF NOT EXISTS idx_context_user ON agent_context(user_id);

-- Chat Sessions (with multi-tenant user isolation)
CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL DEFAULT '',
    title TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index for user isolation on sessions
CREATE INDEX IF NOT EXISTS idx_sessions_user ON chat_sessions(user_id);

-- Chat Messages (with multi-tenant user isolation)
CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL DEFAULT '',
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    thinking TEXT DEFAULT NULL,  -- AI reasoning/thinking content for collapsible display
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index for fast history retrieval
CREATE INDEX IF NOT EXISTS chat_messages_session_id_idx ON chat_messages(session_id);

-- Index for user isolation on messages
CREATE INDEX IF NOT EXISTS idx_messages_user ON chat_messages(user_id);

-- File uploads table for tracking uploaded files
CREATE TABLE IF NOT EXISTS file_uploads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    filename TEXT NOT NULL,
    content_type TEXT,
    file_size BIGINT,
    storage_path TEXT NOT NULL,
    extracted_text TEXT,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index for user file queries
CREATE INDEX IF NOT EXISTS idx_uploads_user ON file_uploads(user_id);

-- AI Cache table for persistent response caching
CREATE TABLE IF NOT EXISTS ai_cache (
    content_hash TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    json_response TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Index for cache cleanup
CREATE INDEX IF NOT EXISTS idx_cache_expires ON ai_cache(expires_at);
CREATE INDEX IF NOT EXISTS idx_cache_user ON ai_cache(user_id);

-- GIN Index for Text Search (Hybrid Search)
CREATE INDEX IF NOT EXISTS agent_context_content_idx ON agent_context USING GIN (to_tsvector('english', content));

-- Hybrid Search Function (with user isolation)
CREATE OR REPLACE FUNCTION match_documents_hybrid(
  query_user_id TEXT,
  query_text TEXT,
  query_embedding VECTOR(1536),
  match_threshold FLOAT,
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
    ((1 - (agent_context.embedding <=> query_embedding)) * 0.7 +
     ts_rank_cd(to_tsvector('english', agent_context.content), websearch_to_tsquery('english', query_text)) * 0.3)::FLOAT as similarity
  FROM agent_context
  WHERE agent_context.user_id = query_user_id
    AND ((1 - (agent_context.embedding <=> query_embedding)) > match_threshold
     OR to_tsvector('english', agent_context.content) @@ websearch_to_tsquery('english', query_text))
  ORDER BY similarity DESC
  LIMIT match_count;
END;
$$;

-- Migration: Add user_id to existing tables if not present
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'agent_context' AND column_name = 'user_id') THEN
        ALTER TABLE agent_context ADD COLUMN user_id TEXT NOT NULL DEFAULT '';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_sessions' AND column_name = 'user_id') THEN
        ALTER TABLE chat_sessions ADD COLUMN user_id TEXT NOT NULL DEFAULT '';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_messages' AND column_name = 'user_id') THEN
        ALTER TABLE chat_messages ADD COLUMN user_id TEXT NOT NULL DEFAULT '';
    END IF;
END $$;

-- ============================================================================
-- DAILY & WEEKLY DIGEST SYSTEM
-- ============================================================================

-- Daily Digests Table
-- Stores AI-generated daily summaries delivered each morning
CREATE TABLE IF NOT EXISTS daily_digests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    digest_date DATE NOT NULL,                    -- The date this digest covers
    digest_type TEXT NOT NULL DEFAULT 'daily',    -- 'daily' or 'weekly'
    
    -- AI-generated content
    summary TEXT NOT NULL,                        -- Main digest summary
    key_insights JSONB,                           -- Array of key insights discovered
    goals_progress JSONB,                         -- Goal progress updates
    priorities JSONB,                             -- Priority items identified
    critical_info TEXT,                           -- Any critical information flagged by AI
    
    -- Metadata
    notes_analyzed INT DEFAULT 0,                 -- Number of notes analyzed
    chats_analyzed INT DEFAULT 0,                 -- Number of chat sessions analyzed
    memories_analyzed INT DEFAULT 0,              -- Number of memories analyzed
    
    -- Delivery status
    notification_sent BOOLEAN DEFAULT FALSE,      -- Whether FCM notification was sent
    calendar_event_id TEXT,                       -- Reference to calendar event (if created)
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- Ensure one digest per user per date per type
    UNIQUE(user_id, digest_date, digest_type)
);

-- Index for user digest queries
CREATE INDEX IF NOT EXISTS idx_digests_user ON daily_digests(user_id);
CREATE INDEX IF NOT EXISTS idx_digests_date ON daily_digests(digest_date);
CREATE INDEX IF NOT EXISTS idx_digests_user_date ON daily_digests(user_id, digest_date DESC);

-- Digest Preferences Table
-- User preferences for digest delivery
CREATE TABLE IF NOT EXISTS digest_preferences (
    user_id TEXT PRIMARY KEY,
    
    -- Daily digest settings
    daily_enabled BOOLEAN DEFAULT TRUE,
    daily_time TIME DEFAULT '07:00:00',           -- Time to deliver daily digest
    
    -- Weekly digest settings
    weekly_enabled BOOLEAN DEFAULT TRUE,
    weekly_day INT DEFAULT 0,                     -- 0=Sunday, 1=Monday, etc.
    weekly_time TIME DEFAULT '08:00:00',          -- Time to deliver weekly digest
    
    -- Notification settings
    push_notification BOOLEAN DEFAULT TRUE,       -- Send FCM push notification
    calendar_logging BOOLEAN DEFAULT TRUE,        -- Create calendar events
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Function to get user's digest time (with timezone support)
CREATE OR REPLACE FUNCTION get_digest_time(
    p_user_id TEXT,
    p_digest_type TEXT
)
RETURNS TIME
LANGUAGE plpgsql
AS $$
DECLARE
    v_prefs RECORD;
BEGIN
    SELECT * INTO v_prefs FROM digest_preferences WHERE user_id = p_user_id;
    
    IF NOT FOUND THEN
        -- Default times
        IF p_digest_type = 'weekly' THEN
            RETURN '08:00:00';
        ELSE
            RETURN '07:00:00';
        END IF;
    END IF;
    
    IF p_digest_type = 'weekly' THEN
        RETURN v_prefs.weekly_time;
    ELSE
        RETURN v_prefs.daily_time;
    END IF;
END;
$$;

-- ============================================================================
-- FCM TOKEN STORAGE (for push notifications)
-- ============================================================================

-- Store FCM tokens for each user's devices
CREATE TABLE IF NOT EXISTS user_fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    token TEXT NOT NULL UNIQUE,
    device_name TEXT,
    device_id TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_used_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index for user token queries
CREATE INDEX IF NOT EXISTS idx_fcm_tokens_user ON user_fcm_tokens(user_id);

-- Function to register/update FCM token
CREATE OR REPLACE FUNCTION register_fcm_token(
    p_user_id TEXT,
    p_token TEXT,
    p_device_name TEXT DEFAULT NULL,
    p_device_id TEXT DEFAULT NULL
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO user_fcm_tokens (user_id, token, device_name, device_id, last_used_at)
    VALUES (p_user_id, p_token, p_device_name, p_device_id, NOW())
    ON CONFLICT (token) 
    DO UPDATE SET 
        last_used_at = NOW(),
        device_name = COALESCE(p_device_name, user_fcm_tokens.device_name);
END;
$$;

-- Function to get all tokens for a user
CREATE OR REPLACE FUNCTION get_user_fcm_tokens(p_user_id TEXT)
RETURNS TABLE(token TEXT)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT t.token FROM user_fcm_tokens t
    WHERE t.user_id = p_user_id
    ORDER BY t.last_used_at DESC;
END;
$$;
