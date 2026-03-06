-- Deep Research Agent Schema
-- Stores research sessions, searches, citations, and research logs

-- Research Sessions Table
CREATE TABLE IF NOT EXISTS research_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    topic TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'asking_questions',
    research_plan TEXT,
    final_report TEXT,
    clarification_questions JSONB DEFAULT '[]',
    user_answers JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE
);

-- Index for user's research sessions
CREATE INDEX IF NOT EXISTS idx_research_sessions_user ON research_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_research_sessions_status ON research_sessions(status);
CREATE INDEX IF NOT EXISTS idx_research_sessions_created ON research_sessions(created_at DESC);

-- Search Queries Table
CREATE TABLE IF NOT EXISTS research_searches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    query TEXT NOT NULL,
    purpose TEXT,
    results JSONB DEFAULT '[]',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_research_searches_session ON research_searches(session_id);
CREATE INDEX IF NOT EXISTS idx_research_searches_query ON research_searches(query);

-- Citations Table
CREATE TABLE IF NOT EXISTS research_citations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    title TEXT NOT NULL,
    snippet TEXT,
    date_accessed TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    relevance_score DOUBLE PRECISION DEFAULT 1.0,
    key_findings JSONB DEFAULT '[]',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_research_citations_session ON research_citations(session_id);
CREATE INDEX IF NOT EXISTS idx_research_citations_url ON research_citations(url);

-- Research Log Table (for transparency trail)
CREATE TABLE IF NOT EXISTS research_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    action TEXT NOT NULL,
    details TEXT NOT NULL,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_research_logs_session ON research_logs(session_id);
CREATE INDEX IF NOT EXISTS idx_research_logs_action ON research_logs(action);
CREATE INDEX IF NOT EXISTS idx_research_logs_created ON research_logs(created_at DESC);

-- Comments for documentation
COMMENT ON TABLE research_sessions IS 'Deep research sessions with clarification Q&A and reports';
COMMENT ON TABLE research_searches IS 'Web search queries performed during research';
COMMENT ON TABLE research_citations IS 'Cited sources with URLs and key findings';
COMMENT ON TABLE research_logs IS 'Transparent research activity trail';

COMMENT ON COLUMN research_sessions.status IS 'asking_questions|planning|researching|synthesizing|completed|waiting_user_input';
COMMENT ON COLUMN research_citations.key_findings IS 'Extracted key findings from this source';
COMMENT ON COLUMN research_logs.action IS 'asked_question|received_answer|created_plan|performed_search|scraped_page|extracted_info|changed_direction|synthesized_findings|completed_research';
