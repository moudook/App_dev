-- =============================================================================
-- SMARTY - DATABASE SCHEMA v4.3.0 (RESEARCH AGENT 2026 COMPLETE)
-- =============================================================================
-- Version: 4.3.0 - Technical Research Specialist Advanced Edition 2026
-- Date: March 13, 2026
-- 
-- Implements:
-- - Analysis of Competing Hypotheses (ACH) - 7-stage CIA methodology
-- - Source Credibility Hierarchy (Tier 1-5)
-- - ALCOA Verification Framework
-- - Rule of Three (3+ independent Tier 1-2 sources)
-- - Cognitive Bias Detection & Mitigation
-- - Confidence Level Calibration (High/Moderate/Low)
-- - BLUF Intelligence Reporting
-- - Query Decomposition Framework (5 layers)
-- - OWASP Top 10 for Agentic Applications Security
-- =============================================================================

-- =============================================================================
-- STEP 1: ENABLE EXTENSIONS
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "vector";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- =============================================================================
-- STEP 2: CORE TABLES (Users, Sessions, etc.)
-- =============================================================================

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    firebase_uid TEXT UNIQUE NOT NULL,
    email TEXT,
    display_name TEXT,
    avatar_url TEXT,
    is_active BOOLEAN DEFAULT true,
    is_premium BOOLEAN DEFAULT false,
    subscription_expires_at TIMESTAMP WITH TIME ZONE,
    feature_flags JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Chat Sessions
CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    title TEXT,
    is_active BOOLEAN DEFAULT true,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sessions_user ON chat_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_active ON chat_sessions(is_active);
CREATE INDEX IF NOT EXISTS idx_sessions_updated ON chat_sessions(updated_at DESC);

-- =============================================================================
-- STEP 3: RESEARCH AGENT CORE TABLES (v4.3.0 Enhanced)
-- =============================================================================

-- Research Sessions (Enhanced with 2026 fields)
CREATE TABLE IF NOT EXISTS research_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    topic TEXT NOT NULL,
    original_question TEXT,
    
    -- Status tracking
    status TEXT NOT NULL DEFAULT 'asking_questions' CHECK (
        status IN ('asking_questions', 'planning', 'researching', 'synthesizing', 
                   'completed', 'waiting_user_input', 'human_review_required')
    ),
    
    -- 2026 Methodology: Phase tracking
    current_phase TEXT DEFAULT 'QUERY_DECOMPOSITION' CHECK (
        current_phase IN (
            'QUERY_DECOMPOSITION', 'BREADTH_SEARCH', 'DEPTH_PIVOT',
            'GAP_ANALYSIS', 'BLUF_GENERATION', 'HUMAN_REVIEW'
        )
    ),
    
    -- Research plan
    research_plan TEXT,
    
    -- 2026: ACH Matrix storage
    ach_matrix_json JSONB DEFAULT '{}',
    
    -- 2026: Cognitive bias checks
    bias_checks_json JSONB DEFAULT '[]',
    
    -- 2026: Confidence calibration
    confidence_level TEXT DEFAULT 'LOW' CHECK (
        confidence_level IN ('HIGH', 'MODERATE', 'LOW')
    ),
    
    -- 2026: Human review flag
    human_review_required BOOLEAN DEFAULT FALSE,
    
    -- Security checkpoints (OWASP Agentic AI)
    security_checkpoints_json JSONB DEFAULT '[]',
    
    -- Metadata
    started_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT valid_research_topic CHECK (length(topic) > 0)
);

CREATE INDEX IF NOT EXISTS idx_research_sessions_user ON research_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_research_sessions_status ON research_sessions(status);
CREATE INDEX IF NOT EXISTS idx_research_sessions_phase ON research_sessions(current_phase);
CREATE INDEX IF NOT EXISTS idx_research_sessions_confidence ON research_sessions(confidence_level);
CREATE INDEX IF NOT EXISTS idx_research_sessions_created ON research_sessions(started_at DESC);

-- Research Searches (Enhanced with query engineering)
CREATE TABLE IF NOT EXISTS research_searches (
    search_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    
    -- Query information
    query TEXT NOT NULL,
    query_type TEXT DEFAULT 'GENERAL' CHECK (
        query_type IN (
            'GENERAL', 'PRIMARY_DOCUMENTATION', 'VERSION_SPECIFIC',
            'EXPERT_COMMUNITY', 'CONFIG_DISCOVERY', 'CONFLICT_RESOLUTION',
            'TEMPORAL_PRECISION', 'RESEARCHER_LINEAGE'
        )
    ),
    repository_target TEXT,
    
    -- Results
    results_count INTEGER DEFAULT 0,
    results_json JSONB DEFAULT '{}',
    
    -- Execution metadata
    execution_time_ms BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_research_searches_session ON research_searches(session_id);
CREATE INDEX IF NOT EXISTS idx_research_searches_query ON research_searches(query);
CREATE INDEX IF NOT EXISTS idx_research_searches_type ON research_searches(query_type);

-- Research Citations (Enhanced with 2026 verification fields)
CREATE TABLE IF NOT EXISTS research_citations (
    citation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    
    -- Source information
    url TEXT NOT NULL,
    title TEXT NOT NULL,
    domain TEXT,
    snippet TEXT,
    full_text TEXT,
    
    -- 2026: Source credibility hierarchy
    trust_tier INTEGER CHECK (trust_tier BETWEEN 1 AND 5),
    tier_justification TEXT,
    
    -- 2026: ALCOA verification
    alcoa_verified BOOLEAN DEFAULT FALSE,
    alcoa_attributable BOOLEAN DEFAULT FALSE,
    alcoa_legible BOOLEAN DEFAULT FALSE,
    alcoa_contemporaneous BOOLEAN DEFAULT FALSE,
    alcoa_original BOOLEAN DEFAULT FALSE,
    alcoa_accurate BOOLEAN DEFAULT FALSE,
    
    -- 2026: Source independence (Rule of Three)
    independent_confirmation_count INTEGER DEFAULT 0,
    independent_sources TEXT[] DEFAULT '{}',
    rule_of_three_satisfied BOOLEAN DEFAULT FALSE,
    
    -- 2026: ACH matrix integration
    used_in_ach_matrix BOOLEAN DEFAULT FALSE,
    ach_evidence_judgment TEXT CHECK (
        ach_evidence_judgment IN ('CONSISTENT', 'INCONSISTENT', 'NOT_APPLICABLE', 'LOW_DIAGNOSTICITY')
    ),
    ach_hypothesis_support TEXT[] DEFAULT '{}',
    
    -- Scoring
    credibility_score DECIMAL(3,2) DEFAULT 0.5,
    relevance_score DECIMAL(3,2) DEFAULT 0.5,
    diagnosticity_score DECIMAL(3,2) DEFAULT 0.5,
    
    -- Temporal attributes
    publication_date DATE,
    freshness_flag TEXT CHECK (
        freshness_flag IN ('CURRENT', 'STALE', 'HISTORICAL', 'UNKNOWN')
    ),
    errata_checked BOOLEAN DEFAULT FALSE,
    
    -- Search metadata
    search_query TEXT,
    doc_index INTEGER DEFAULT 0,
    
    -- Usage tracking
    used_in_claims TEXT[] DEFAULT '{}',
    retrieved_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_research_citations_session ON research_citations(session_id);
CREATE INDEX IF NOT EXISTS idx_research_citations_url ON research_citations(url);
CREATE INDEX IF NOT EXISTS idx_research_citations_tier ON research_citations(trust_tier);
CREATE INDEX IF NOT EXISTS idx_research_citations_alcoa ON research_citations(alcoa_verified);
CREATE INDEX IF NOT EXISTS idx_research_citations_freshness ON research_citations(freshness_flag);
CREATE INDEX IF NOT EXISTS idx_research_citations_ach ON research_citations(used_in_ach_matrix);

-- =============================================================================
-- STEP 4: ACH MATRIX TABLES (NEW in v4.3.0)
-- =============================================================================

-- ACH Hypotheses
CREATE TABLE IF NOT EXISTS research_ach_hypotheses (
    hypothesis_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    
    -- Hypothesis information
    description TEXT NOT NULL,
    status TEXT DEFAULT 'active' CHECK (
        status IN ('active', 'eliminated', 'leading', 'confirmed', 'pending_review')
    ),
    
    -- ACH statistics
    confidence_percent DECIMAL(5,2) DEFAULT 50.0,
    consistent_count INTEGER DEFAULT 0,
    inconsistent_count INTEGER DEFAULT 0,
    rejection_reason TEXT,
    
    -- Probability (for ACH-SL extension)
    probability DECIMAL(3,2) DEFAULT 0.5,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

COMMENT ON TABLE research_ach_hypotheses IS 
'Analysis of Competing Hypotheses (ACH) - CIA methodology (Richards Heuer, 1970s)';

CREATE INDEX IF NOT EXISTS idx_ach_hypotheses_session ON research_ach_hypotheses(session_id);
CREATE INDEX IF NOT EXISTS idx_ach_hypotheses_status ON research_ach_hypotheses(status);
CREATE INDEX IF NOT EXISTS idx_ach_hypotheses_confidence ON research_ach_hypotheses(confidence_percent DESC);
CREATE INDEX IF NOT EXISTS idx_ach_hypotheses_inconsistent ON research_ach_hypotheses(inconsistent_count);

-- ACH Evidence Judgment Mapping
CREATE TABLE IF NOT EXISTS research_ach_evidence_map (
    hypothesis_id UUID REFERENCES research_ach_hypotheses(hypothesis_id) ON DELETE CASCADE,
    citation_id UUID REFERENCES research_citations(citation_id) ON DELETE CASCADE,
    
    -- ACH judgment
    judgment TEXT NOT NULL CHECK (
        judgment IN ('CONSISTENT', 'INCONSISTENT', 'NOT_APPLICABLE', 'LOW_DIAGNOSTICITY')
    ),
    diagnosticity_score DECIMAL(3,2) DEFAULT 0.5,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    PRIMARY KEY (hypothesis_id, citation_id)
);

COMMENT ON TABLE research_ach_evidence_map IS 
'Maps evidence (citations) to hypotheses with ACH judgments';

CREATE INDEX IF NOT EXISTS idx_ach_evidence_hypothesis ON research_ach_evidence_map(hypothesis_id);
CREATE INDEX IF NOT EXISTS idx_ach_evidence_citation ON research_ach_evidence_map(citation_id);
CREATE INDEX IF NOT EXISTS idx_ach_evidence_judgment ON research_ach_evidence_map(judgment);

-- =============================================================================
-- STEP 5: COGNITIVE BIAS & VERIFICATION TABLES (NEW in v4.3.0)
-- =============================================================================

-- Cognitive Bias Checks
CREATE TABLE IF NOT EXISTS research_bias_checks (
    check_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    
    -- Bias information
    bias_type TEXT NOT NULL CHECK (
        bias_type IN (
            'CONFIRMATION_BIAS', 'RECENCY_BIAS', 'ANCHORING',
            'MIRROR_IMAGING', 'GROUPTHINK', 'AVAILABILITY_HEURISTIC'
        )
    ),
    detected BOOLEAN DEFAULT FALSE,
    mitigation_applied TEXT,
    description TEXT,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

COMMENT ON TABLE research_bias_checks IS 
'Cognitive bias detection and mitigation per 2026 methodology';

CREATE INDEX IF NOT EXISTS idx_bias_checks_session ON research_bias_checks(session_id);
CREATE INDEX IF NOT EXISTS idx_bias_checks_type ON research_bias_checks(bias_type);
CREATE INDEX IF NOT EXISTS idx_bias_checks_detected ON research_bias_checks(detected);

-- Source Verification State
CREATE TABLE IF NOT EXISTS research_verification_state (
    session_id UUID PRIMARY KEY REFERENCES research_sessions(id) ON DELETE CASCADE,
    
    -- Source counts by tier
    independent_source_count INTEGER DEFAULT 0,
    tier1_source_count INTEGER DEFAULT 0,
    tier2_source_count INTEGER DEFAULT 0,
    tier3_source_count INTEGER DEFAULT 0,
    
    -- ALCOA checks
    alcoa_checks_performed TEXT[] DEFAULT '{}',
    
    -- Rule of Three status
    rule_of_three_satisfied BOOLEAN DEFAULT FALSE,
    
    -- Human review flag
    human_review_required BOOLEAN DEFAULT FALSE,
    
    verification_timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

COMMENT ON TABLE research_verification_state IS 
'Source verification state including Rule of Three and ALCOA checks';

CREATE INDEX IF NOT EXISTS idx_verification_tier1 ON research_verification_state(tier1_source_count);
CREATE INDEX IF NOT EXISTS idx_verification_rule_of_three ON research_verification_state(rule_of_three_satisfied);

-- Confidence Calibration
CREATE TABLE IF NOT EXISTS research_confidence_levels (
    judgment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    
    -- Judgment information
    judgment_text TEXT NOT NULL,
    confidence_level TEXT NOT NULL CHECK (
        confidence_level IN ('HIGH', 'MODERATE', 'LOW')
    ),
    confidence_percent DECIMAL(5,2),
    
    -- Source basis
    source_count INTEGER DEFAULT 0,
    tier1_count INTEGER DEFAULT 0,
    tier2_count INTEGER DEFAULT 0,
    independent_count INTEGER DEFAULT 0,
    inconsistencies_count INTEGER DEFAULT 0,
    
    -- Business impact
    business_impact TEXT,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

COMMENT ON TABLE research_confidence_levels IS 
'Confidence-calibrated judgments per 2026 intelligence reporting standards';

CREATE INDEX IF NOT EXISTS idx_confidence_session ON research_confidence_levels(session_id);
CREATE INDEX IF NOT EXISTS idx_confidence_level ON research_confidence_levels(confidence_level);
CREATE INDEX IF NOT EXISTS idx_confidence_percent ON research_confidence_levels(confidence_percent DESC);

-- =============================================================================
-- STEP 6: BLUF REPORTS & QUERY DECOMPOSITION (NEW in v4.3.0)
-- =============================================================================

-- BLUF Intelligence Reports
CREATE TABLE IF NOT EXISTS research_bluf_reports (
    report_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL UNIQUE REFERENCES research_sessions(id) ON DELETE CASCADE,
    
    -- BLUF components
    bluf_summary TEXT NOT NULL,
    key_judgments_json JSONB DEFAULT '[]',
    supporting_evidence_json JSONB DEFAULT '[]',
    confidence_levels_json JSONB DEFAULT '{}',
    
    -- Methodology tracking
    methodology TEXT DEFAULT 'Technical Research Specialist Advanced Edition 2026',
    recommendations_json JSONB DEFAULT '[]',
    caveats_and_limitations TEXT[] DEFAULT '{}',
    
    -- Full report
    full_report_text TEXT,
    
    generated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

COMMENT ON TABLE research_bluf_reports IS 
'BLUF (Bottom Line Up Front) intelligence reports per 2026 methodology';

CREATE INDEX IF NOT EXISTS idx_bluf_session ON research_bluf_reports(session_id);
CREATE INDEX IF NOT EXISTS idx_bluf_generated ON research_bluf_reports(generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_bluf_confidence ON research_bluf_reports((confidence_levels_json->>'overall'));

-- Query Decomposition
CREATE TABLE IF NOT EXISTS research_query_decomposition (
    decomposition_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    
    -- Decomposition layer
    layer_type TEXT NOT NULL CHECK (
        layer_type IN (
            'TECHNICAL_COMPONENTS', 'HISTORICAL_CONTEXT', 'PRIMARY_AUTHORITIES',
            'GAP_ANALYSIS', 'ADVERSARIAL_SURFACE'
        )
    ),
    layer_data JSONB NOT NULL,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

COMMENT ON TABLE research_query_decomposition IS 
'Query decomposition framework layers per 2026 methodology';

CREATE INDEX IF NOT EXISTS idx_decomposition_session ON research_query_decomposition(session_id);
CREATE INDEX IF NOT EXISTS idx_decomposition_layer ON research_query_decomposition(layer_type);

-- =============================================================================
-- STEP 7: SECURITY CHECKPOINTS (OWASP Agentic AI - NEW in v4.3.0)
-- =============================================================================

-- Security Checkpoints (OWASP Top 10 for Agentic Applications)
CREATE TABLE IF NOT EXISTS research_security_checkpoints (
    checkpoint_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    
    -- Checkpoint information
    checkpoint_type TEXT NOT NULL CHECK (
        checkpoint_type IN (
            'LEAST_PRIVILEGE_IDENTITY', 'HUMAN_IN_LOOP_APPROVAL',
            'BEHAVIORAL_ANOMALY_CHECK', 'PROMPT_INJECTION_CHECK',
            'LETHAL_TRIFECTA_CHECK'
        )
    ),
    passed BOOLEAN DEFAULT FALSE,
    details TEXT,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

COMMENT ON TABLE research_security_checkpoints IS 
'Agentic AI security checkpoints per OWASP Top 10 for Agentic Applications (2025)';

CREATE INDEX IF NOT EXISTS idx_security_session ON research_security_checkpoints(session_id);
CREATE INDEX IF NOT EXISTS idx_security_type ON research_security_checkpoints(checkpoint_type);
CREATE INDEX IF NOT EXISTS idx_security_passed ON research_security_checkpoints(passed);

-- =============================================================================
-- STEP 8: HELPER FUNCTIONS AND TRIGGERS
-- =============================================================================

-- Update timestamp function
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Freshness flag determination
CREATE OR REPLACE FUNCTION determine_freshness_flag(pub_date DATE)
RETURNS TEXT AS $$
DECLARE
    days_old INTEGER;
BEGIN
    IF pub_date IS NULL THEN
        RETURN 'UNKNOWN';
    END IF;
    
    days_old := EXTRACT(DAY FROM (NOW() - pub_date));
    
    IF days_old <= 180 THEN
        RETURN 'CURRENT';
    ELSIF days_old <= 730 THEN
        RETURN 'STALE';
    ELSE
        RETURN 'HISTORICAL';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Update verification state trigger function
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
            updated_at
        )
        SELECT 
            session_id,
            COUNT(*) FILTER (WHERE trust_tier = 1) AS tier1_count,
            COUNT(*) FILTER (WHERE trust_tier = 2) AS tier2_count,
            COUNT(*) FILTER (WHERE trust_tier = 3) AS tier3_count,
            COUNT(DISTINCT domain) AS independent_count,
            (COUNT(*) FILTER (WHERE trust_tier = 1) >= 3) AS rule_of_three,
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
            updated_at = NOW();
        
        -- Update human review requirement
        UPDATE research_sessions
        SET human_review_required = (
            SELECT NOT rule_of_three_satisfied
            FROM research_verification_state
            WHERE session_id = NEW.session_id
        )
        WHERE id = NEW.session_id;
    END IF;
    
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Update ACH hypothesis statistics trigger function
CREATE OR REPLACE FUNCTION update_ach_hypothesis_stats()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' OR TG_OP = 'UPDATE' OR TG_OP = 'DELETE' THEN
        UPDATE research_ach_hypotheses h
        SET 
            consistent_count = (
                SELECT COUNT(*) FROM research_ach_evidence_map m
                WHERE m.hypothesis_id = h.hypothesis_id
                AND m.judgment = 'CONSISTENT'
            ),
            inconsistent_count = (
                SELECT COUNT(*) FROM research_ach_evidence_map m
                WHERE m.hypothesis_id = h.hypothesis_id
                AND m.judgment = 'INCONSISTENT'
            ),
            confidence_percent = GREATEST(0, 100 - (
                SELECT COUNT(*) * 20 FROM research_ach_evidence_map m
                WHERE m.hypothesis_id = h.hypothesis_id
                AND m.judgment = 'INCONSISTENT'
            )),
            status = CASE 
                WHEN (SELECT COUNT(*) FROM research_ach_evidence_map m
                      WHERE m.hypothesis_id = h.hypothesis_id
                      AND m.judgment = 'INCONSISTENT') > 
                     (SELECT COUNT(*) FROM research_ach_evidence_map m
                      WHERE m.hypothesis_id = h.hypothesis_id
                      AND m.judgment = 'CONSISTENT') * 2
                THEN 'eliminated'
                WHEN (SELECT COUNT(*) FROM research_ach_evidence_map m
                      WHERE m.hypothesis_id = h.hypothesis_id
                      AND m.judgment = 'CONSISTENT') > 
                     (SELECT COUNT(*) FROM research_ach_evidence_map m
                      WHERE m.hypothesis_id = h.hypothesis_id
                      AND m.judgment = 'INCONSISTENT') * 2
                THEN 'leading'
                ELSE 'active'
            END,
            updated_at = NOW()
        WHERE h.session_id = COALESCE(NEW.session_id, OLD.session_id);
    END IF;
    
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Create triggers
DROP TRIGGER IF EXISTS update_research_sessions_updated_at ON research_sessions;
CREATE TRIGGER update_research_sessions_updated_at
    BEFORE UPDATE ON research_sessions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS update_verification_on_citation ON research_citations;
CREATE TRIGGER update_verification_on_citation
    AFTER INSERT OR UPDATE ON research_citations
    FOR EACH ROW
    EXECUTE FUNCTION update_research_verification_state();

DROP TRIGGER IF EXISTS update_ach_stats_on_evidence ON research_ach_evidence_map;
CREATE TRIGGER update_ach_stats_on_evidence
    AFTER INSERT OR UPDATE OR DELETE ON research_ach_evidence_map
    FOR EACH ROW
    EXECUTE FUNCTION update_ach_hypothesis_stats();

-- =============================================================================
-- STEP 9: VIEWS FOR COMMON QUERIES
-- =============================================================================

-- Research session summary with 2026 metrics
CREATE OR REPLACE VIEW research_session_summary AS
SELECT 
    rs.id,
    rs.topic,
    rs.status,
    rs.current_phase,
    rs.confidence_level,
    rs.human_review_required,
    rv.tier1_source_count,
    rv.tier2_source_count,
    rv.independent_source_count,
    rv.rule_of_three_satisfied,
    COUNT(DISTINCT c.citation_id) as total_citations,
    COUNT(DISTINCT h.hypothesis_id) as total_hypotheses,
    MAX(h.confidence_percent) as leading_hypothesis_confidence,
    COUNT(DISTINCT bc.check_id) FILTER (WHERE bc.detected = true) as biases_detected,
    rs.started_at,
    rs.updated_at
FROM research_sessions rs
LEFT JOIN research_verification_state rv ON rs.id = rv.session_id
LEFT JOIN research_citations c ON rs.id = c.session_id
LEFT JOIN research_ach_hypotheses h ON rs.id = h.session_id
LEFT JOIN research_bias_checks bc ON rs.id = bc.session_id
GROUP BY rs.id, rv.tier1_source_count, rv.tier2_source_count, 
         rv.independent_source_count, rv.rule_of_three_satisfied;

-- High-confidence citations (Rule of Three satisfied)
CREATE OR REPLACE VIEW high_confidence_citations AS
SELECT 
    c.*,
    rv.rule_of_three_satisfied,
    rv.tier1_source_count
FROM research_citations c
JOIN research_verification_state rv ON c.session_id = rv.session_id
WHERE rv.rule_of_three_satisfied = true
  AND c.trust_tier IN (1, 2);

-- ACH matrix summary
CREATE OR REPLACE VIEW ach_matrix_summary AS
SELECT 
    h.session_id,
    h.hypothesis_id,
    h.description,
    h.status,
    h.confidence_percent,
    h.consistent_count,
    h.inconsistent_count,
    COUNT(m.citation_id) as evidence_count,
    COUNT(m.citation_id) FILTER (WHERE m.judgment = 'CONSISTENT') as consistent_evidence,
    COUNT(m.citation_id) FILTER (WHERE m.judgment = 'INCONSISTENT') as inconsistent_evidence,
    AVG(m.diagnosticity_score) as avg_diagnosticity
FROM research_ach_hypotheses h
LEFT JOIN research_ach_evidence_map m ON h.hypothesis_id = m.hypothesis_id
GROUP BY h.hypothesis_id, h.session_id;

-- =============================================================================
-- STEP 10: DATA MIGRATION (for existing databases)
-- =============================================================================

-- Migrate existing citations to have trust tiers based on domain
UPDATE research_citations
SET 
    trust_tier = CASE
        WHEN url LIKE '%.gov%' OR url LIKE '%.mil%' THEN 1
        WHEN url LIKE '%nist.gov%' OR url LIKE '%cisa.gov%' OR url LIKE '%ietf.org%' THEN 1
        WHEN url LIKE '%rfc-editor.org%' OR url LIKE '%ieee.org%' THEN 1
        WHEN url LIKE '%crowdstrike.com%' OR url LIKE '%mandiant.com%' THEN 2
        WHEN url LIKE '%usenix.org%' OR url LIKE '%ieee-security.org%' THEN 2
        WHEN url LIKE '%lists.ietf.org%' OR url LIKE '%github.com/security%' THEN 3
        WHEN url LIKE '%stackoverflow.com%' OR url LIKE '%medium.com%' THEN 4
        ELSE 4
    END,
    tier_justification = CASE
        WHEN url LIKE '%.gov%' THEN 'Government domain'
        WHEN url LIKE '%nist.gov%' THEN 'NIST primary authority'
        WHEN url LIKE '%ietf.org%' THEN 'IETF standards body'
        WHEN url LIKE '%github.com%' THEN 'GitHub community source'
        ELSE 'Domain-based classification'
    END
WHERE trust_tier IS NULL;

-- Set freshness flags for existing citations
UPDATE research_citations
SET freshness_flag = determine_freshness_flag(publication_date)
WHERE freshness_flag IS NULL AND publication_date IS NOT NULL;

-- =============================================================================
-- STEP 11: SCHEMA VERSION TRACKING
-- =============================================================================

CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    description TEXT
);

INSERT INTO schema_migrations (version, applied_at, description)
VALUES 
    ('v4.3.0', NOW(), 'Technical Research Specialist 2026 - ACH, ALCOA, Rule of Three, BLUF'),
    ('v4.2.0', NOW(), 'Optimized schema with SDE best practices'),
    ('v4.1.0', NOW(), 'Research agent support'),
    ('v4.0.0', NOW(), 'Initial production schema')
ON CONFLICT (version) DO NOTHING;

-- =============================================================================
-- SCHEMA COMPLETE
-- =============================================================================
-- 
-- Tables Created: 16
-- - Core: users, chat_sessions, research_sessions, research_searches, research_citations
-- - ACH: research_ach_hypotheses, research_ach_evidence_map
-- - Verification: research_verification_state, research_confidence_levels
-- - Bias: research_bias_checks
-- - Reports: research_bluf_reports
-- - Decomposition: research_query_decomposition
-- - Security: research_security_checkpoints
-- - Tracking: schema_migrations
--
-- Views Created: 3
-- - research_session_summary
-- - high_confidence_citations
-- - ach_matrix_summary
--
-- Triggers Created: 3
-- - update_research_sessions_updated_at
-- - update_verification_on_citation
-- - update_ach_stats_on_evidence
--
-- Functions Created: 3
-- - update_updated_at_column()
-- - determine_freshness_flag()
-- - update_research_verification_state()
-- ============================================================================
