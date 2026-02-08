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

-- GIN Index for Text Search (Hybrid Search)
CREATE INDEX IF NOT EXISTS agent_memory_content_idx ON agent_memory USING GIN (to_tsvector('english', content));

-- Hybrid Search Function
-- Combines Vector Similarity (Cosine) + Keyword Match (Full Text Search)
CREATE OR REPLACE FUNCTION match_documents_hybrid(
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
    agent_memory.id,
    agent_memory.content,
    agent_memory.metadata,
    ((1 - (agent_memory.embedding <=> query_embedding)) * 0.7 +
     ts_rank_cd(to_tsvector('english', agent_memory.content), websearch_to_tsquery('english', query_text)) * 0.3)::FLOAT as similarity
  FROM agent_memory
  WHERE (1 - (agent_memory.embedding <=> query_embedding)) > match_threshold
     OR to_tsvector('english', agent_memory.content) @@ websearch_to_tsquery('english', query_text)
  ORDER BY similarity DESC
  LIMIT match_count;
END;
$$;
