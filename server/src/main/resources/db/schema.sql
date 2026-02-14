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
