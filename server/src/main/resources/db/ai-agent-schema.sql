-- Smarty Database Schema v2.1 (Multi-Tenant)
-- Updated with client-compatible chat fields
-- NOTE: Run migrations FIRST, then create indexes

-- 1. Enable AI Vector support
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Create tables (using IF NOT EXISTS for existing databases)

-- Long-term Memory Table (Agent Context)
CREATE TABLE IF NOT EXISTS agent_context (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL,
    embedding VECTOR(1536),
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Chat Sessions (Updated with client fields)
CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL DEFAULT '',
    title TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    message_count INTEGER DEFAULT 0,
    last_message_preview TEXT DEFAULT '',
    is_active BOOLEAN DEFAULT true,
    summary TEXT,
    summary_generated_at BIGINT
);

-- Chat Messages (Updated with client fields)
CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL DEFAULT '',
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    attachments_json TEXT DEFAULT '[]',
    executed_actions_json TEXT DEFAULT '[]',
    referenced_note_ids TEXT DEFAULT '',
    citations_json TEXT DEFAULT '[]',
    inline_images_json TEXT DEFAULT '[]'
);

-- Notes Table
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

-- Timers Table
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

-- Calendar Events Table
CREATE TABLE IF NOT EXISTS calendar_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    title TEXT NOT NULL,
    start_time BIGINT NOT NULL,
    end_time BIGINT NOT NULL,
    description TEXT,
    reminder_minutes INT DEFAULT 15,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 3. Migrations for EXISTING databases (run BEFORE indexes)
-- Add user_id to existing tables if not present
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

-- Migration: Add missing columns to chat_sessions for client compatibility
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_sessions' AND column_name = 'updated_at') THEN
        ALTER TABLE chat_sessions ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW();
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_sessions' AND column_name = 'message_count') THEN
        ALTER TABLE chat_sessions ADD COLUMN message_count INTEGER DEFAULT 0;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_sessions' AND column_name = 'last_message_preview') THEN
        ALTER TABLE chat_sessions ADD COLUMN last_message_preview TEXT DEFAULT '';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_sessions' AND column_name = 'is_active') THEN
        ALTER TABLE chat_sessions ADD COLUMN is_active BOOLEAN DEFAULT true;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_sessions' AND column_name = 'summary') THEN
        ALTER TABLE chat_sessions ADD COLUMN summary TEXT;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_sessions' AND column_name = 'summary_generated_at') THEN
        ALTER TABLE chat_sessions ADD COLUMN summary_generated_at BIGINT;
    END IF;
END $$;

-- Migration: Add missing columns to chat_messages for client compatibility
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_messages' AND column_name = 'attachments_json') THEN
        ALTER TABLE chat_messages ADD COLUMN attachments_json TEXT DEFAULT '[]';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_messages' AND column_name = 'executed_actions_json') THEN
        ALTER TABLE chat_messages ADD COLUMN executed_actions_json TEXT DEFAULT '[]';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_messages' AND column_name = 'referenced_note_ids') THEN
        ALTER TABLE chat_messages ADD COLUMN referenced_note_ids TEXT DEFAULT '';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_messages' AND column_name = 'citations_json') THEN
        ALTER TABLE chat_messages ADD COLUMN citations_json TEXT DEFAULT '[]';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'chat_messages' AND column_name = 'inline_images_json') THEN
        ALTER TABLE chat_messages ADD COLUMN inline_images_json TEXT DEFAULT '[]';
    END IF;
END $$;

-- 4. Create indexes AFTER migrations (so columns definitely exist)
CREATE INDEX IF NOT EXISTS agent_context_embedding_idx ON agent_context USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_context_user ON agent_context(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON chat_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_active ON chat_sessions(is_active);
CREATE INDEX IF NOT EXISTS idx_sessions_updated ON chat_sessions(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_user ON chat_messages(user_id);
CREATE INDEX IF NOT EXISTS idx_messages_session ON chat_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_notes_user ON notes(user_id);
CREATE INDEX IF NOT EXISTS idx_timers_user ON timers(user_id);
CREATE INDEX IF NOT EXISTS idx_calendar_user ON calendar_events(user_id);

-- 5. Agent Traces Table (for debugging/observability)
CREATE TABLE IF NOT EXISTS agent_traces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    session_id UUID,
    query TEXT,
    trace_data JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 6. Agent Checkpoints Table (for resumable agents)
CREATE TABLE IF NOT EXISTS agent_checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    session_id UUID NOT NULL,
    checkpoint_data JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Indexes for agent tables
CREATE INDEX IF NOT EXISTS idx_agent_traces_user ON agent_traces(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_session ON agent_checkpoints(session_id);

-- 7. Hybrid Search Function (Vector + Text)
CREATE OR REPLACE FUNCTION match_documents_hybrid(
  query_user_id TEXT,
  query_text TEXT,
  query_embedding VECTOR(1536),
  match_threshold FLOAT,
  match_count INT
)
RETURNS TABLE (id UUID, content TEXT, metadata JSONB, similarity FLOAT)
LANGUAGE plpgsql AS $$
BEGIN
  RETURN QUERY
  SELECT
    agent_context.id, agent_context.content, agent_context.metadata,
    ((1 - (agent_context.embedding <=> query_embedding)) * 0.7 +
     ts_rank_cd(to_tsvector('english', agent_context.content), websearch_to_tsquery('english', query_text)) * 0.3)::FLOAT as similarity
  FROM agent_context
  WHERE agent_context.user_id = query_user_id
    AND ((1 - (agent_context.embedding <=> query_embedding)) > match_threshold
     OR to_tsvector('english', agent_context.content) @@ websearch_to_tsquery('english', query_text))
  ORDER BY similarity DESC LIMIT match_count;
END;
$$;
