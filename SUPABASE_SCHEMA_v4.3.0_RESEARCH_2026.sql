-- =============================================================================
-- SMARTY - SUPABASE SCHEMA v4.3.0 (RESEARCH AGENT 2026)
-- =============================================================================
-- COMPLETE SCHEMA - Copy and paste into Supabase SQL Editor
-- Builds on v4.2.0 with 2026 Research Methodologies
-- Date: March 13, 2026
-- =============================================================================

-- Enable extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- =============================================================================
-- PART 1: CORE TABLES
-- =============================================================================

-- Users (if not exists)
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    firebase_uid TEXT UNIQUE NOT NULL,
    email TEXT,
    display_name TEXT,
    avatar_url TEXT,
    is_active BOOLEAN DEFAULT true,
    is_premium BOOLEAN DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Chat Sessions (if not exists)
CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    title TEXT,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user ON chat_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_active ON chat_sessions(is_active);

-- =============================================================================
-- PART 2: RESEARCH AGENT TABLES (v4.3.0 Enhanced)
-- =============================================================================

-- Research Sessions
CREATE TABLE IF NOT EXISTS research_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    topic TEXT NOT NULL,
    original_question TEXT,
    status TEXT NOT NULL DEFAULT 'asking_questions',
    current_phase TEXT DEFAULT 'QUERY_DECOMPOSITION',
    research_plan TEXT,
    ach_matrix_json JSONB DEFAULT '{}',
    bias_checks_json JSONB DEFAULT '[]',
    confidence_level TEXT DEFAULT 'LOW',
    human_review_required BOOLEAN DEFAULT FALSE,
    security_checkpoints_json JSONB DEFAULT '[]',
    started_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT valid_research_topic CHECK (length(topic) > 0)
);

CREATE INDEX IF NOT EXISTS idx_research_sessions_user ON research_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_research_sessions_status ON research_sessions(status);
CREATE INDEX IF NOT EXISTS idx_research_sessions_phase ON research_sessions(current_phase);

-- Research Searches
CREATE TABLE IF NOT EXISTS research_searches (
    search_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    query TEXT NOT NULL,
    query_type TEXT DEFAULT 'GENERAL',
    repository_target TEXT,
    results_count INTEGER DEFAULT 0,
    results_json JSONB DEFAULT '{}',
    execution_time_ms BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_research_searches_session ON research_searches(session_id);
CREATE INDEX IF NOT EXISTS idx_research_searches_query ON research_searches(query);

-- Research Citations (2026 Enhanced)
CREATE TABLE IF NOT EXISTS research_citations (
    citation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    title TEXT NOT NULL,
    domain TEXT,
    snippet TEXT,
    full_text TEXT,
    trust_tier INTEGER,
    tier_justification TEXT,
    alcoa_verified BOOLEAN DEFAULT FALSE,
    alcoa_attributable BOOLEAN DEFAULT FALSE,
    alcoa_legible BOOLEAN DEFAULT FALSE,
    alcoa_contemporaneous BOOLEAN DEFAULT FALSE,
    alcoa_original BOOLEAN DEFAULT FALSE,
    alcoa_accurate BOOLEAN DEFAULT FALSE,
    independent_confirmation_count INTEGER DEFAULT 0,
    independent_sources TEXT[] DEFAULT '{}',
    rule_of_three_satisfied BOOLEAN DEFAULT FALSE,
    used_in_ach_matrix BOOLEAN DEFAULT FALSE,
    ach_evidence_judgment TEXT,
    ach_hypothesis_support TEXT[] DEFAULT '{}',
    credibility_score DECIMAL(3,2) DEFAULT 0.5,
    relevance_score DECIMAL(3,2) DEFAULT 0.5,
    diagnosticity_score DECIMAL(3,2) DEFAULT 0.5,
    publication_date DATE,
    freshness_flag TEXT DEFAULT 'UNKNOWN',
    errata_checked BOOLEAN DEFAULT FALSE,
    search_query TEXT,
    doc_index INTEGER DEFAULT 0,
    used_in_claims TEXT[] DEFAULT '{}',
    retrieved_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_research_citations_session ON research_citations(session_id);
CREATE INDEX IF NOT EXISTS idx_research_citations_url ON research_citations(url);
CREATE INDEX IF NOT EXISTS idx_research_citations_tier ON research_citations(trust_tier);
CREATE INDEX IF NOT EXISTS idx_research_citations_ach ON research_citations(used_in_ach_matrix);

-- =============================================================================
-- PART 3: ACH MATRIX TABLES
-- =============================================================================

-- ACH Hypotheses
CREATE TABLE IF NOT EXISTS research_ach_hypotheses (
    hypothesis_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    description TEXT NOT NULL,
    status TEXT DEFAULT 'active',
    confidence_percent DECIMAL(5,2) DEFAULT 50.0,
    consistent_count INTEGER DEFAULT 0,
    inconsistent_count INTEGER DEFAULT 0,
    rejection_reason TEXT,
    probability DECIMAL(3,2) DEFAULT 0.5,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ach_hypotheses_session ON research_ach_hypotheses(session_id);
CREATE INDEX IF NOT EXISTS idx_ach_hypotheses_status ON research_ach_hypotheses(status);
CREATE INDEX IF NOT EXISTS idx_ach_hypotheses_confidence ON research_ach_hypotheses(confidence_percent DESC);

-- ACH Evidence Map
CREATE TABLE IF NOT EXISTS research_ach_evidence_map (
    hypothesis_id UUID REFERENCES research_ach_hypotheses(hypothesis_id) ON DELETE CASCADE,
    citation_id UUID REFERENCES research_citations(citation_id) ON DELETE CASCADE,
    judgment TEXT NOT NULL,
    diagnosticity_score DECIMAL(3,2) DEFAULT 0.5,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    PRIMARY KEY (hypothesis_id, citation_id)
);

CREATE INDEX IF NOT EXISTS idx_ach_evidence_hypothesis ON research_ach_evidence_map(hypothesis_id);
CREATE INDEX IF NOT EXISTS idx_ach_evidence_citation ON research_ach_evidence_map(citation_id);

-- =============================================================================
-- PART 4: VERIFICATION & BIAS TABLES
-- =============================================================================

-- Source Verification State
CREATE TABLE IF NOT EXISTS research_verification_state (
    session_id UUID PRIMARY KEY REFERENCES research_sessions(id) ON DELETE CASCADE,
    independent_source_count INTEGER DEFAULT 0,
    tier1_source_count INTEGER DEFAULT 0,
    tier2_source_count INTEGER DEFAULT 0,
    tier3_source_count INTEGER DEFAULT 0,
    alcoa_checks_performed TEXT[] DEFAULT '{}',
    rule_of_three_satisfied BOOLEAN DEFAULT FALSE,
    human_review_required BOOLEAN DEFAULT FALSE,
    verification_timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_verification_tier1 ON research_verification_state(tier1_source_count);
CREATE INDEX IF NOT EXISTS idx_verification_rule_of_three ON research_verification_state(rule_of_three_satisfied);

-- Cognitive Bias Checks
CREATE TABLE IF NOT EXISTS research_bias_checks (
    check_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    bias_type TEXT NOT NULL,
    detected BOOLEAN DEFAULT FALSE,
    mitigation_applied TEXT,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bias_checks_session ON research_bias_checks(session_id);
CREATE INDEX IF NOT EXISTS idx_bias_checks_type ON research_bias_checks(bias_type);

-- Confidence Levels
CREATE TABLE IF NOT EXISTS research_confidence_levels (
    judgment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    judgment_text TEXT NOT NULL,
    confidence_level TEXT NOT NULL,
    confidence_percent DECIMAL(5,2),
    source_count INTEGER DEFAULT 0,
    tier1_count INTEGER DEFAULT 0,
    tier2_count INTEGER DEFAULT 0,
    independent_count INTEGER DEFAULT 0,
    inconsistencies_count INTEGER DEFAULT 0,
    business_impact TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_confidence_session ON research_confidence_levels(session_id);
CREATE INDEX IF NOT EXISTS idx_confidence_level ON research_confidence_levels(confidence_level);

-- =============================================================================
-- PART 5: REPORTING & DECOMPOSITION TABLES
-- =============================================================================

-- BLUF Reports
CREATE TABLE IF NOT EXISTS research_bluf_reports (
    report_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL UNIQUE REFERENCES research_sessions(id) ON DELETE CASCADE,
    bluf_summary TEXT NOT NULL,
    key_judgments_json JSONB DEFAULT '[]',
    supporting_evidence_json JSONB DEFAULT '[]',
    confidence_levels_json JSONB DEFAULT '{}',
    methodology TEXT DEFAULT 'Technical Research Specialist 2026',
    recommendations_json JSONB DEFAULT '[]',
    caveats_and_limitations TEXT[] DEFAULT '{}',
    full_report_text TEXT,
    generated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bluf_session ON research_bluf_reports(session_id);
CREATE INDEX IF NOT EXISTS idx_bluf_generated ON research_bluf_reports(generated_at DESC);

-- Query Decomposition
CREATE TABLE IF NOT EXISTS research_query_decomposition (
    decomposition_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    layer_type TEXT NOT NULL,
    layer_data JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_decomposition_session ON research_query_decomposition(session_id);
CREATE INDEX IF NOT EXISTS idx_decomposition_layer ON research_query_decomposition(layer_type);

-- Security Checkpoints
CREATE TABLE IF NOT EXISTS research_security_checkpoints (
    checkpoint_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    checkpoint_type TEXT NOT NULL,
    passed BOOLEAN DEFAULT FALSE,
    details TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_security_session ON research_security_checkpoints(session_id);
CREATE INDEX IF NOT EXISTS idx_security_type ON research_security_checkpoints(checkpoint_type);

-- =============================================================================
-- PART 6: HELPER FUNCTIONS
-- =============================================================================

-- Update timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Freshness flag
CREATE OR REPLACE FUNCTION determine_freshness_flag(pub_date DATE)
RETURNS TEXT AS $$
DECLARE
    days_old INTEGER;
BEGIN
    IF pub_date IS NULL THEN RETURN 'UNKNOWN'; END IF;
    days_old := EXTRACT(DAY FROM (NOW() - pub_date));
    IF days_old <= 180 THEN RETURN 'CURRENT';
    ELSIF days_old <= 730 THEN RETURN 'STALE';
    ELSE RETURN 'HISTORICAL';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Update verification state
CREATE OR REPLACE FUNCTION update_research_verification_state()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' OR TG_OP = 'UPDATE' THEN
        INSERT INTO research_verification_state (
            session_id,
            tier1_source_count,
            tier2_source_count,
            tier3_source_count,
            independent_source_count,
            rule_of_three_satisfied,
            human_review_required,
            updated_at
        )
        SELECT 
            session_id,
            COUNT(*) FILTER (WHERE trust_tier = 1),
            COUNT(*) FILTER (WHERE trust_tier = 2),
            COUNT(*) FILTER (WHERE trust_tier = 3),
            COUNT(DISTINCT domain),
            (COUNT(*) FILTER (WHERE trust_tier = 1) >= 3),
            (COUNT(*) FILTER (WHERE trust_tier = 1) < 3),
            NOW()
        FROM research_citations
        WHERE session_id = NEW.session_id
        GROUP BY session_id
        ON CONFLICT (session_id) DO UPDATE SET
            tier1_source_count = EXCLUDED.tier1_source_count,
            tier2_source_count = EXCLUDED.tier2_source_count,
            tier3_source_count = EXCLUDED.tier3_source_count,
            independent_source_count = EXCLUDED.independent_source_count,
            rule_of_three_satisfied = EXCLUDED.rule_of_three_satisfied,
            human_review_required = EXCLUDED.human_review_required,
            updated_at = NOW();
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Update ACH stats
CREATE OR REPLACE FUNCTION update_ach_hypothesis_stats()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' OR TG_OP = 'UPDATE' OR TG_OP = 'DELETE' THEN
        UPDATE research_ach_hypotheses h
        SET 
            consistent_count = (
                SELECT COUNT(*) FROM research_ach_evidence_map m
                WHERE m.hypothesis_id = h.hypothesis_id AND m.judgment = 'CONSISTENT'
            ),
            inconsistent_count = (
                SELECT COUNT(*) FROM research_ach_evidence_map m
                WHERE m.hypothesis_id = h.hypothesis_id AND m.judgment = 'INCONSISTENT'
            ),
            confidence_percent = GREATEST(0, 100 - (
                SELECT COUNT(*) * 20 FROM research_ach_evidence_map m
                WHERE m.hypothesis_id = h.hypothesis_id AND m.judgment = 'INCONSISTENT'
            )),
            status = CASE 
                WHEN (SELECT COUNT(*) FROM research_ach_evidence_map m
                      WHERE m.hypothesis_id = h.hypothesis_id AND m.judgment = 'INCONSISTENT') > 
                     (SELECT COUNT(*) FROM research_ach_evidence_map m
                      WHERE m.hypothesis_id = h.hypothesis_id AND m.judgment = 'CONSISTENT') * 2
                THEN 'eliminated'
                WHEN (SELECT COUNT(*) FROM research_ach_evidence_map m
                      WHERE m.hypothesis_id = h.hypothesis_id AND m.judgment = 'CONSISTENT') > 
                     (SELECT COUNT(*) FROM research_ach_evidence_map m
                      WHERE m.hypothesis_id = h.hypothesis_id AND m.judgment = 'INCONSISTENT') * 2
                THEN 'leading'
                ELSE 'active'
            END,
            updated_at = NOW()
        WHERE h.session_id = COALESCE(NEW.session_id, OLD.session_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- PART 7: TRIGGERS
-- =============================================================================

DROP TRIGGER IF EXISTS update_research_sessions_updated_at ON research_sessions;
CREATE TRIGGER update_research_sessions_updated_at
    BEFORE UPDATE ON research_sessions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS update_research_verification_state_trigger ON research_citations;
CREATE TRIGGER update_research_verification_state_trigger
    AFTER INSERT OR UPDATE ON research_citations
    FOR EACH ROW
    EXECUTE FUNCTION update_research_verification_state();

DROP TRIGGER IF EXISTS update_ach_stats_on_evidence ON research_ach_evidence_map;
CREATE TRIGGER update_ach_stats_on_evidence
    AFTER INSERT OR UPDATE OR DELETE ON research_ach_evidence_map
    FOR EACH ROW
    EXECUTE FUNCTION update_ach_hypothesis_stats();

-- =============================================================================
-- PART 8: VIEWS
-- =============================================================================

-- Research session summary
CREATE OR REPLACE VIEW research_session_summary AS
SELECT 
    rs.id, rs.topic, rs.status, rs.current_phase, rs.confidence_level,
    rs.human_review_required, rv.tier1_source_count, rv.tier2_source_count,
    rv.independent_source_count, rv.rule_of_three_satisfied,
    COUNT(DISTINCT c.citation_id) as total_citations,
    COUNT(DISTINCT h.hypothesis_id) as total_hypotheses,
    MAX(h.confidence_percent) as leading_hypothesis_confidence,
    COUNT(DISTINCT bc.check_id) FILTER (WHERE bc.detected = true) as biases_detected,
    rs.started_at, rs.updated_at
FROM research_sessions rs
LEFT JOIN research_verification_state rv ON rs.id = rv.session_id
LEFT JOIN research_citations c ON rs.id = c.session_id
LEFT JOIN research_ach_hypotheses h ON rs.id = h.session_id
LEFT JOIN research_bias_checks bc ON rs.id = bc.session_id
GROUP BY rs.id, rv.tier1_source_count, rv.tier2_source_count, 
         rv.independent_source_count, rv.rule_of_three_satisfied;

-- High-confidence citations
CREATE OR REPLACE VIEW high_confidence_citations AS
SELECT c.*, rv.rule_of_three_satisfied, rv.tier1_source_count
FROM research_citations c
JOIN research_verification_state rv ON c.session_id = rv.session_id
WHERE rv.rule_of_three_satisfied = true AND c.trust_tier IN (1, 2);

-- ACH matrix summary
CREATE OR REPLACE VIEW ach_matrix_summary AS
SELECT 
    h.session_id, h.hypothesis_id, h.description, h.status, h.confidence_percent,
    h.consistent_count, h.inconsistent_count,
    COUNT(m.citation_id) as evidence_count,
    COUNT(m.citation_id) FILTER (WHERE m.judgment = 'CONSISTENT') as consistent_evidence,
    COUNT(m.citation_id) FILTER (WHERE m.judgment = 'INCONSISTENT') as inconsistent_evidence,
    AVG(m.diagnosticity_score) as avg_diagnosticity
FROM research_ach_hypotheses h
LEFT JOIN research_ach_evidence_map m ON h.hypothesis_id = m.hypothesis_id
GROUP BY h.hypothesis_id, h.session_id;

-- =============================================================================
-- PART 9: SCHEMA VERSION
-- =============================================================================

CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    description TEXT
);

INSERT INTO schema_migrations (version, description)
VALUES ('v4.3.0', 'Research Agent 2026 - ACH, ALCOA, Rule of Three, BLUF')
ON CONFLICT (version) DO NOTHING;

-- =============================================================================
-- SCHEMA COMPLETE - Ready for Supabase SQL Editor
-- =============================================================================
