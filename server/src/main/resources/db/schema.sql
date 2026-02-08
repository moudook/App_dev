-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Agent Memory Table
-- Stores long-term memories with vector embeddings for semantic retrieval.
CREATE TABLE IF NOT EXISTS agent_memory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    embedding VECTOR(1536),  -- 1536 dimensions for OpenAI/compatible models
    metadata JSONB,          -- Context: source, timestamp, tags, type
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- HNSW Index for fast similarity search
-- Uses cosine distance (vector_cosine_ops)
CREATE INDEX IF NOT EXISTS agent_memory_embedding_idx
ON agent_memory USING hnsw (embedding vector_cosine_ops);

-- Chat Sessions
CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Chat Messages
CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index for fast history retrieval
CREATE INDEX IF NOT EXISTS chat_messages_session_id_idx ON chat_messages(session_id);
