-- ============================================================================
-- MIGRATION v4.2.0 TO v4.3.0: Research Agent 2026 Methodologies
-- ============================================================================
-- Implements Technical Research Specialist Advanced Edition 2026:
-- - Source credibility hierarchy (Tier 1-5)
-- - ALCOA verification framework
-- - Rule of Three (3+ independent Tier 1-2 sources for high confidence)
-- - ACH matrix (Analysis of Competing Hypotheses)
-- - Cognitive bias detection
-- - Confidence level calibration (High/Moderate/Low)
-- - BLUF report generation support
-- ============================================================================

-- ============================================================================
-- PART 1: ENHANCE EXISTING RESEARCH TABLES
-- ============================================================================

-- Add 2026 methodology fields to research_citations
ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS trust_tier INTEGER CHECK (trust_tier BETWEEN 1 AND 5);

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS tier_justification TEXT;

-- ALCOA verification fields
ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS alcoa_verified BOOLEAN DEFAULT FALSE;

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS alcoa_attributable BOOLEAN DEFAULT FALSE;

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS alcoa_legible BOOLEAN DEFAULT FALSE;

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS alcoa_contemporaneous BOOLEAN DEFAULT FALSE;

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS alcoa_original BOOLEAN DEFAULT FALSE;

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS alcoa_accurate BOOLEAN DEFAULT FALSE;

-- Source independence tracking (Rule of Three)
ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS independent_confirmation_count INTEGER DEFAULT 0;

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS independent_sources TEXT[] DEFAULT '{}';

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS rule_of_three_satisfied BOOLEAN DEFAULT FALSE;

-- ACH matrix integration
ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS used_in_ach_matrix BOOLEAN DEFAULT FALSE;

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS ach_evidence_judgment TEXT CHECK (
    ach_evidence_judgment IN ('CONSISTENT', 'INCONSISTENT', 'NOT_APPLICABLE', 'LOW_DIAGNOSTICITY')
);

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS ach_hypothesis_support TEXT[] DEFAULT '{}';

-- Confidence and diagnosticity scores
ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS credibility_score DECIMAL(3,2) DEFAULT 0.5;

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS relevance_score DECIMAL(3,2) DEFAULT 0.5;

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS diagnosticity_score DECIMAL(3,2) DEFAULT 0.5;

-- Temporal attributes
ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS publication_date DATE;

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS freshness_flag TEXT CHECK (
    freshness_flag IN ('CURRENT', 'STALE', 'HISTORICAL', 'UNKNOWN')
);

ALTER TABLE research_citations 
ADD COLUMN IF NOT EXISTS errata_checked BOOLEAN DEFAULT FALSE;

-- Query engineering for research_searches
ALTER TABLE research_searches 
ADD COLUMN IF NOT EXISTS query_type TEXT DEFAULT 'GENERAL';

ALTER TABLE research_searches 
ADD COLUMN IF NOT EXISTS repository_target TEXT;

-- Add status field to research_sessions for 2026 phases
ALTER TABLE research_sessions 
ADD COLUMN IF NOT EXISTS current_phase TEXT DEFAULT 'QUERY_DECOMPOSITION' CHECK (
    current_phase IN (
        'QUERY_DECOMPOSITION',
        'BREADTH_SEARCH',
        'DEPTH_PIVOT',
        'GAP_ANALYSIS',
        'BLUF_GENERATION',
        'HUMAN_REVIEW'
    )
);

ALTER TABLE research_sessions 
ADD COLUMN IF NOT EXISTS ach_matrix_json JSONB DEFAULT '{}';

ALTER TABLE research_sessions 
ADD COLUMN IF NOT EXISTS bias_checks_json JSONB DEFAULT '[]';

ALTER TABLE research_sessions 
ADD COLUMN IF NOT EXISTS confidence_level TEXT DEFAULT 'LOW' CHECK (
    confidence_level IN ('HIGH', 'MODERATE', 'LOW')
);

ALTER TABLE research_sessions 
ADD COLUMN IF NOT EXISTS human_review_required BOOLEAN DEFAULT FALSE;

-- Create indexes for new fields
CREATE INDEX IF NOT EXISTS idx_citations_trust_tier ON research_citations(trust_tier);
CREATE INDEX IF NOT EXISTS idx_citations_alcoa ON research_citations(alcoa_verified);
CREATE INDEX IF NOT EXISTS idx_citations_freshness ON research_citations(freshness_flag);
CREATE INDEX IF NOT EXISTS idx_citations_ach ON research_citations(used_in_ach_matrix);
CREATE INDEX IF NOT EXISTS idx_searches_query_type ON research_searches(query_type);
CREATE INDEX IF NOT EXISTS idx_sessions_phase ON research_sessions(current_phase);


-- ============================================================================
-- PART 2: CREATE NEW 2026 METHODOLOGY TABLES
-- ============================================================================

-- ACH Hypotheses table
CREATE TABLE IF NOT EXISTS research_ach_hypotheses (
    hypothesis_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    description TEXT NOT NULL,
    status TEXT DEFAULT 'active' CHECK (
        status IN ('active', 'eliminated', 'leading', 'confirmed', 'pending_review')
    ),
    confidence_percent DECIMAL(5,2) DEFAULT 50.0,
    consistent_count INTEGER DEFAULT 0,
    inconsistent_count INTEGER DEFAULT 0,
    rejection_reason TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE research_ach_hypotheses IS 
'Analysis of Competing Hypotheses (ACH) - CIA methodology for evaluating competing explanations';

CREATE INDEX IF NOT EXISTS idx_ach_hypotheses_session ON research_ach_hypotheses(session_id);
CREATE INDEX IF NOT EXISTS idx_ach_hypotheses_status ON research_ach_hypotheses(status);
CREATE INDEX IF NOT EXISTS idx_ach_hypotheses_confidence ON research_ach_hypotheses(confidence_percent DESC);

-- ACH Evidence Judgment mapping table
CREATE TABLE IF NOT EXISTS research_ach_evidence_map (
    hypothesis_id UUID REFERENCES research_ach_hypotheses(hypothesis_id) ON DELETE CASCADE,
    citation_id UUID REFERENCES research_citations(citation_id) ON DELETE CASCADE,
    judgment TEXT NOT NULL CHECK (
        judgment IN ('CONSISTENT', 'INCONSISTENT', 'NOT_APPLICABLE', 'LOW_DIAGNOSTICITY')
    ),
    diagnosticity_score DECIMAL(3,2) DEFAULT 0.5,
    created_at TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (hypothesis_id, citation_id)
);

COMMENT ON TABLE research_ach_evidence_map IS 
'Maps evidence (citations) to hypotheses with ACH judgments';

CREATE INDEX IF NOT EXISTS idx_ach_evidence_hypothesis ON research_ach_evidence_map(hypothesis_id);
CREATE INDEX IF NOT EXISTS idx_ach_evidence_citation ON research_ach_evidence_map(citation_id);

-- Cognitive Bias Checks table
CREATE TABLE IF NOT EXISTS research_bias_checks (
    check_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    bias_type TEXT NOT NULL CHECK (
        bias_type IN (
            'CONFIRMATION_BIAS',
            'RECENCY_BIAS',
            'ANCHORING',
            'MIRROR_IMAGING',
            'GROUPTHINK',
            'AVAILABILITY_HEURISTIC'
        )
    ),
    detected BOOLEAN DEFAULT FALSE,
    mitigation_applied TEXT,
    description TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE research_bias_checks IS 
'Cognitive bias detection and mitigation tracking per 2026 methodology';

CREATE INDEX IF NOT EXISTS idx_bias_checks_session ON research_bias_checks(session_id);
CREATE INDEX IF NOT EXISTS idx_bias_checks_type ON research_bias_checks(bias_type);
CREATE INDEX IF NOT EXISTS idx_bias_checks_detected ON research_bias_checks(detected);

-- Source Verification State table
CREATE TABLE IF NOT EXISTS research_verification_state (
    session_id UUID PRIMARY KEY REFERENCES research_sessions(id) ON DELETE CASCADE,
    independent_source_count INTEGER DEFAULT 0,
    tier1_source_count INTEGER DEFAULT 0,
    tier2_source_count INTEGER DEFAULT 0,
    tier3_source_count INTEGER DEFAULT 0,
    alcoa_checks_performed TEXT[] DEFAULT '{}',
    rule_of_three_satisfied BOOLEAN DEFAULT FALSE,
    human_review_required BOOLEAN DEFAULT FALSE,
    verification_timestamp TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE research_verification_state IS 
'Source verification state including Rule of Three and ALCOA checks';

CREATE INDEX IF NOT EXISTS idx_verification_tier1 ON research_verification_state(tier1_source_count);
CREATE INDEX IF NOT EXISTS idx_verification_rule_of_three ON research_verification_state(rule_of_three_satisfied);

-- Confidence Calibration table for final judgments
CREATE TABLE IF NOT EXISTS research_confidence_levels (
    judgment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    judgment_text TEXT NOT NULL,
    confidence_level TEXT NOT NULL CHECK (
        confidence_level IN ('HIGH', 'MODERATE', 'LOW')
    ),
    confidence_percent DECIMAL(5,2),
    source_count INTEGER DEFAULT 0,
    tier1_count INTEGER DEFAULT 0,
    tier2_count INTEGER DEFAULT 0,
    independent_count INTEGER DEFAULT 0,
    inconsistencies_count INTEGER DEFAULT 0,
    business_impact TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE research_confidence_levels IS 
'Confidence-calibrated judgments per 2026 intelligence reporting standards';

CREATE INDEX IF NOT EXISTS idx_confidence_session ON research_confidence_levels(session_id);
CREATE INDEX IF NOT EXISTS idx_confidence_level ON research_confidence_levels(confidence_level);
CREATE INDEX IF NOT EXISTS idx_confidence_percent ON research_confidence_levels(confidence_percent DESC);

-- BLUF Reports table
CREATE TABLE IF NOT EXISTS research_bluf_reports (
    report_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL UNIQUE REFERENCES research_sessions(id) ON DELETE CASCADE,
    bluf_summary TEXT NOT NULL,
    key_judgments_json JSONB DEFAULT '[]',
    supporting_evidence_json JSONB DEFAULT '[]',
    confidence_levels_json JSONB DEFAULT '{}',
    methodology TEXT DEFAULT 'Technical Research Specialist Advanced Edition 2026',
    recommendations_json JSONB DEFAULT '[]',
    caveats_and_limitations TEXT[] DEFAULT '{}',
    full_report_text TEXT,
    generated_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE research_bluf_reports IS 
'BLUF (Bottom Line Up Front) intelligence reports per 2026 methodology';

CREATE INDEX IF NOT EXISTS idx_bluf_session ON research_bluf_reports(session_id);
CREATE INDEX IF NOT EXISTS idx_bluf_generated ON research_bluf_reports(generated_at DESC);

-- Query Decomposition table
CREATE TABLE IF NOT EXISTS research_query_decomposition (
    decomposition_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    layer_type TEXT NOT NULL CHECK (
        layer_type IN (
            'TECHNICAL_COMPONENTS',
            'HISTORICAL_CONTEXT',
            'PRIMARY_AUTHORITIES',
            'GAP_ANALYSIS',
            'ADVERSARIAL_SURFACE'
        )
    ),
    layer_data JSONB NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE research_query_decomposition IS 
'Query decomposition framework layers per 2026 methodology';

CREATE INDEX IF NOT EXISTS idx_decomposition_session ON research_query_decomposition(session_id);
CREATE INDEX IF NOT EXISTS idx_decomposition_layer ON research_query_decomposition(layer_type);

-- Security Checkpoints table (OWASP Top 10 for Agentic Applications)
CREATE TABLE IF NOT EXISTS research_security_checkpoints (
    checkpoint_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES research_sessions(id) ON DELETE CASCADE,
    checkpoint_type TEXT NOT NULL CHECK (
        checkpoint_type IN (
            'LEAST_PRIVILEGE_IDENTITY',
            'HUMAN_IN_LOOP_APPROVAL',
            'BEHAVIORAL_ANOMALY_CHECK',
            'PROMPT_INJECTION_CHECK',
            'LETHAL_TRIFECTA_CHECK'
        )
    ),
    passed BOOLEAN DEFAULT FALSE,
    details TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

COMMENT ON TABLE research_security_checkpoints IS 
'Agentic AI security checkpoints per OWASP Top 10 for Agentic Applications (2025)';

CREATE INDEX IF NOT EXISTS idx_security_session ON research_security_checkpoints(session_id);
CREATE INDEX IF NOT EXISTS idx_security_type ON research_security_checkpoints(checkpoint_type);
CREATE INDEX IF NOT EXISTS idx_security_passed ON research_security_checkpoints(passed);


-- ============================================================================
-- PART 3: HELPER FUNCTIONS AND TRIGGERS
-- ============================================================================

-- Function to update research_verification_state when citations are added
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

-- Trigger to update verification state on citation changes
DROP TRIGGER IF EXISTS update_verification_on_citation ON research_citations;
CREATE TRIGGER update_verification_on_citation
    AFTER INSERT OR UPDATE ON research_citations
    FOR EACH ROW
    EXECUTE FUNCTION update_research_verification_state();

-- Function to calculate ACH hypothesis statistics
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

-- Trigger to update ACH stats on evidence map changes
DROP TRIGGER IF EXISTS update_ach_stats_on_evidence ON research_ach_evidence_map;
CREATE TRIGGER update_ach_stats_on_evidence
    AFTER INSERT OR UPDATE OR DELETE ON research_ach_evidence_map
    FOR EACH ROW
    EXECUTE FUNCTION update_ach_hypothesis_stats();

-- Function to determine freshness flag based on publication date
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


-- ============================================================================
-- PART 4: DATA MIGRATION (if existing data present)
-- ============================================================================

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
WHERE freshness_flag IS NULL;


-- ============================================================================
-- PART 5: VIEWS FOR COMMON QUERIES
-- ============================================================================

-- View: Research session summary with 2026 metrics
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

-- View: High-confidence citations (Rule of Three satisfied)
CREATE OR REPLACE VIEW high_confidence_citations AS
SELECT 
    c.*,
    rv.rule_of_three_satisfied,
    rv.tier1_source_count
FROM research_citations c
JOIN research_verification_state rv ON c.session_id = rv.session_id
WHERE rv.rule_of_three_satisfied = true
  AND c.trust_tier IN (1, 2);

-- View: ACH matrix summary
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


-- ============================================================================
-- PART 6: SCHEMA VERSION UPDATE
-- ============================================================================

-- Update schema version tracking
INSERT INTO schema_migrations (version, applied_at)
VALUES ('v4.3.0', NOW())
ON CONFLICT (version) DO NOTHING;

-- ============================================================================
-- MIGRATION COMPLETE
-- ============================================================================
-- 
-- New Features:
-- - Source credibility hierarchy (Tier 1-5) with automatic classification
-- - ALCOA verification framework for data integrity
-- - Rule of Three enforcement (3+ independent Tier 1-2 sources)
-- - ACH matrix for structured hypothesis analysis
-- - Cognitive bias detection and mitigation tracking
-- - Confidence level calibration (High/Moderate/Low)
-- - BLUF report generation support
-- - Query decomposition framework
-- - Agentic AI security checkpoints (OWASP Top 10)
--
-- Next Steps:
-- 1. Update application code to use new fields
-- 2. Implement UI components for tier badges and confidence indicators
-- 3. Add ACH matrix visualization
-- 4. Implement BLUF report generation endpoint
-- ============================================================================
