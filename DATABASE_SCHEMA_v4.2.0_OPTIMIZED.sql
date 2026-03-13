-- =============================================================================
-- SMARTY - DATABASE SCHEMA v4.2.0 (OPTIMIZED & CONNECTED)
-- =============================================================================
-- Version: 4.2.0 - SDE Best Practices Applied
-- Date: March 12, 2026
-- Principles: DRY, Single Responsibility, Global State Management
-- =============================================================================

-- =============================================================================
-- STEP 1: ENABLE EXTENSIONS
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "vector";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- =============================================================================
-- STEP 2: CREATE FUNCTIONS (Created first so triggers can use them immediately)
-- =============================================================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION generate_content_hash(content TEXT)
RETURNS TEXT AS $$
BEGIN
    RETURN ENCODE(SHA256(content::bytea), 'hex');
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- =============================================================================
-- STEP 3: CREATE CORE TABLES
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
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_login_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS app_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    state_type TEXT NOT NULL,
    state_key TEXT NOT NULL,
    state_value JSONB NOT NULL,
    version INTEGER DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, state_type, state_key)
);

CREATE TABLE IF NOT EXISTS sync_state (
    user_id TEXT PRIMARY KEY REFERENCES users(firebase_uid) ON DELETE CASCADE,
    last_sync_at BIGINT,
    last_pull_at BIGINT,
    last_push_at BIGINT,
    sync_tokens JSONB DEFAULT '{}',
    sync_status TEXT DEFAULT 'idle',
    sync_error TEXT,
    sync_retries INTEGER DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- =============================================================================
-- STEP 4: CREATE WORKFLOW & AGENT TABLES
-- =============================================================================

CREATE TABLE IF NOT EXISTS agent_workflows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    session_id UUID, -- Will be indexed, circular ref handled safely
    workflow_type TEXT NOT NULL,
    workflow_name TEXT NOT NULL,
    status TEXT NOT NULL,
    progress_percentage FLOAT DEFAULT 0,
    current_step INTEGER DEFAULT 0,
    total_steps INTEGER DEFAULT 0,
    input_data JSONB,
    output_data JSONB,
    error_message TEXT,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- =============================================================================
-- STEP 5: CREATE CHAT & COMMUNICATION TABLES
-- =============================================================================

CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    title TEXT DEFAULT 'New Chat',
    is_active BOOLEAN DEFAULT true,
    is_archived BOOLEAN DEFAULT false,
    message_count INTEGER DEFAULT 0,
    last_message_preview TEXT,
    last_message_at TIMESTAMP WITH TIME ZONE,
    current_workflow_id UUID REFERENCES agent_workflows(id) ON DELETE SET NULL,
    workflow_state JSONB,
    summary TEXT,
    summary_generated_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    thinking TEXT,
    thinking_mode TEXT,
    is_streaming BOOLEAN DEFAULT false,
    is_edited BOOLEAN DEFAULT false,
    edit_count INTEGER DEFAULT 0,
    attachments JSONB DEFAULT '[]',
    citations JSONB DEFAULT '[]',
    inline_images JSONB DEFAULT '[]',
    executed_actions JSONB DEFAULT '[]',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- =============================================================================
-- STEP 6: CREATE NOTE SYSTEM TABLES
-- =============================================================================

CREATE TABLE IF NOT EXISTS note_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    name TEXT NOT NULL,
    description TEXT,
    color_hex TEXT DEFAULT '#6366F1',
    icon TEXT,
    note_count INTEGER DEFAULT 0,
    is_default BOOLEAN DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, name)
);

CREATE TABLE IF NOT EXISTS note_stacks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    name TEXT NOT NULL,
    description TEXT,
    note_count INTEGER DEFAULT 0,
    is_collapsed BOOLEAN DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    content_preview TEXT,
    category TEXT,
    stack_id UUID REFERENCES note_stacks(id) ON DELETE SET NULL,
    parent_note_id UUID REFERENCES notes(id) ON DELETE SET NULL,
    is_archived BOOLEAN DEFAULT false,
    is_pinned BOOLEAN DEFAULT false,
    is_private BOOLEAN DEFAULT false,
    is_deleted BOOLEAN DEFAULT false,
    deleted_at TIMESTAMP WITH TIME ZONE,
    content_type TEXT,
    mime_types TEXT,
    word_count INTEGER DEFAULT 0,
    reading_time_minutes INTEGER DEFAULT 0,
    ai_summary TEXT,
    ai_tags TEXT,
    ai_category_suggestion TEXT,
    style_analysis JSONB,
    tone_analysis JSONB,
    content_hash TEXT,
    similarity_group_id UUID,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    viewed_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS note_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    content_hash TEXT,
    change_summary TEXT,
    change_type TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(note_id, version_number)
);

-- =============================================================================
-- STEP 7: CREATE RELATIONAL MAPPING TABLES (New: Replaces String Arrays)
-- =============================================================================

CREATE TABLE IF NOT EXISTS chat_message_notes (
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    PRIMARY KEY (message_id, note_id)
);

CREATE TABLE IF NOT EXISTS calendar_event_notes (
    event_id UUID NOT NULL, -- Added reference below after table creation
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, note_id)
);

-- =============================================================================
-- STEP 8: CREATE MEDIA & FILE TABLES
-- =============================================================================

CREATE TABLE IF NOT EXISTS file_uploads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    filename TEXT NOT NULL,
    original_filename TEXT,
    content_type TEXT NOT NULL,
    file_size BIGINT NOT NULL,
    storage_path TEXT NOT NULL,
    storage_provider TEXT DEFAULT 'local',
    processing_status TEXT DEFAULT 'pending',
    extracted_text TEXT,
    processing_error TEXT,
    metadata JSONB DEFAULT '{}',
    dimensions JSONB,
    duration_ms BIGINT,
    ai_description TEXT,
    ai_tags TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS audio_tracks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    file_upload_id UUID REFERENCES file_uploads(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    artist TEXT,
    album TEXT,
    genre TEXT,
    duration_ms BIGINT NOT NULL,
    bitrate INTEGER,
    sample_rate INTEGER,
    play_count INTEGER DEFAULT 0,
    last_played_at TIMESTAMP WITH TIME ZONE,
    last_played_position_ms BIGINT DEFAULT 0,
    ai_mood TEXT,
    ai_tags TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS images (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    file_upload_id UUID REFERENCES file_uploads(id) ON DELETE CASCADE,
    title TEXT,
    description TEXT,
    width INTEGER,
    height INTEGER,
    format TEXT,
    ai_description TEXT,
    ai_tags TEXT,
    ai_colors TEXT,
    ai_text_extracted TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- =============================================================================
-- STEP 9: CREATE REMAINDER TABLES (Calendar, AI, Games, etc)
-- =============================================================================

CREATE TABLE IF NOT EXISTS calendar_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    title TEXT NOT NULL,
    description TEXT,
    location TEXT,
    start_time BIGINT NOT NULL,
    end_time BIGINT NOT NULL,
    timezone TEXT DEFAULT 'UTC',
    is_all_day BOOLEAN DEFAULT false,
    recurrence_rule TEXT,
    recurrence_id UUID,
    status TEXT DEFAULT 'confirmed',
    visibility TEXT DEFAULT 'default',
    google_event_id TEXT,
    reminder_minutes INTEGER DEFAULT 15,
    reminder_sent BOOLEAN DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Tie the junction table back to calendar events
ALTER TABLE calendar_event_notes 
ADD CONSTRAINT fk_calendar_event 
FOREIGN KEY (event_id) REFERENCES calendar_events(id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS timers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    name TEXT NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    remaining_ms BIGINT,
    status TEXT DEFAULT 'idle',
    is_alarm BOOLEAN DEFAULT false,
    is_active BOOLEAN DEFAULT true,
    started_at TIMESTAMP WITH TIME ZONE,
    paused_at TIMESTAMP WITH TIME ZONE,
    trigger_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    recurrence JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ai_memories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    content TEXT NOT NULL,
    memory_type TEXT NOT NULL,
    embedding vector(1536),
    confidence_score FLOAT DEFAULT 1.0,
    usage_count INTEGER DEFAULT 0,
    last_used_at TIMESTAMP WITH TIME ZONE,
    source JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_context (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    content TEXT NOT NULL,
    embedding vector(1536),
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL UNIQUE REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    workflow_id UUID REFERENCES agent_workflows(id) ON DELETE CASCADE,
    state_json JSONB NOT NULL,
    last_node TEXT,
    step_type TEXT,
    version INTEGER DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_traces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    workflow_id UUID REFERENCES agent_workflows(id) ON DELETE CASCADE,
    step_type TEXT NOT NULL,
    content TEXT,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS game_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    game_type TEXT NOT NULL,
    game_name TEXT NOT NULL,
    status TEXT DEFAULT 'playing',
    state_json JSONB DEFAULT '{}',
    player_colors JSONB,
    current_player TEXT,
    winner TEXT,
    winning_move TEXT,
    move_count INTEGER DEFAULT 0,
    started_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS game_moves (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_session_id UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    move_number INTEGER NOT NULL,
    move_data JSONB NOT NULL,
    move_time_ms BIGINT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(game_session_id, move_number)
);

CREATE TABLE IF NOT EXISTS daily_digests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    digest_date DATE NOT NULL,
    digest_type TEXT NOT NULL DEFAULT 'daily',
    summary TEXT NOT NULL,
    key_insights JSONB,
    goals_progress JSONB,
    priorities JSONB,
    critical_info TEXT,
    notes_analyzed INTEGER DEFAULT 0,
    chats_analyzed INTEGER DEFAULT 0,
    memories_analyzed INTEGER DEFAULT 0,
    notification_sent BOOLEAN DEFAULT false,
    calendar_event_id TEXT,
    delivered_at TIMESTAMP WITH TIME ZONE,
    viewed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, digest_date, digest_type)
);

CREATE TABLE IF NOT EXISTS digest_preferences (
    user_id TEXT PRIMARY KEY REFERENCES users(firebase_uid) ON DELETE CASCADE,
    daily_enabled BOOLEAN DEFAULT true,
    daily_time TIME DEFAULT '07:00:00',
    weekly_enabled BOOLEAN DEFAULT true,
    weekly_day INTEGER DEFAULT 0,
    weekly_time TIME DEFAULT '08:00:00',
    push_notification BOOLEAN DEFAULT true,
    calendar_logging BOOLEAN DEFAULT true,
    email_digest BOOLEAN DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    token TEXT NOT NULL UNIQUE,
    device_name TEXT,
    device_id TEXT,
    platform TEXT DEFAULT 'android',
    is_active BOOLEAN DEFAULT true,
    last_used_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_vaults (
    user_id VARCHAR(128) PRIMARY KEY REFERENCES users(firebase_uid) ON DELETE CASCADE,
    encrypted_blob TEXT NOT NULL,
    encryption_algorithm TEXT DEFAULT 'AES256_GCM',
    version INTEGER DEFAULT 1,
    key_version INTEGER DEFAULT 1,
    updated_at BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS backups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    backup_type TEXT NOT NULL,
    backup_status TEXT NOT NULL,
    file_path TEXT,
    file_size BIGINT,
    file_hash TEXT,
    includes_notes BOOLEAN DEFAULT true,
    includes_chats BOOLEAN DEFAULT true,
    includes_settings BOOLEAN DEFAULT true,
    includes_media BOOLEAN DEFAULT false,
    error_message TEXT,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS usage_analytics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    event_data JSONB,
    session_id UUID,
    feature_name TEXT,
    event_timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    session_duration_ms BIGINT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS error_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT REFERENCES users(firebase_uid) ON DELETE CASCADE,
    error_type TEXT NOT NULL,
    error_message TEXT NOT NULL,
    error_stack TEXT,
    feature_name TEXT,
    action_name TEXT,
    metadata JSONB,
    severity TEXT DEFAULT 'error',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- =============================================================================
-- STEP 10: CREATE INDEXES (Optimized for Query Patterns)
-- =============================================================================

-- Users
CREATE INDEX IF NOT EXISTS idx_users_firebase ON users(firebase_uid);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- Composite & standard indexes
CREATE INDEX IF NOT EXISTS idx_app_state_composite ON app_state(user_id, state_type, state_key);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_active ON chat_sessions(user_id, is_active, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_messages_session_created ON chat_messages(session_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_notes_user_category ON notes(user_id, category, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_calendar_events_user_time ON calendar_events(user_id, start_time, end_time);
CREATE INDEX IF NOT EXISTS idx_file_uploads_user_status ON file_uploads(user_id, processing_status);

-- Vector indexing
CREATE INDEX IF NOT EXISTS idx_ai_memories_embedding ON ai_memories USING ivfflat (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_agent_context_embedding ON agent_context USING ivfflat (embedding vector_cosine_ops);

-- JSONB indexing (New: drastically speeds up JSON queries)
CREATE INDEX IF NOT EXISTS idx_users_feature_flags_gin ON users USING GIN (feature_flags);
CREATE INDEX IF NOT EXISTS idx_app_state_value_gin ON app_state USING GIN (state_value);
CREATE INDEX IF NOT EXISTS idx_chat_messages_metadata_gin ON chat_messages USING GIN (attachments, executed_actions);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_state_gin ON agent_checkpoints USING GIN (state_json);

-- =============================================================================
-- STEP 11: APPLY TRIGGERS (Auto-Updating Timestamps & Counters)
-- =============================================================================

-- Dynamically apply update_updated_at_column() to all tables that have an updated_at column
DO $$ 
DECLARE
    t_name text;
BEGIN
    FOR t_name IN 
        SELECT table_name 
        FROM information_schema.columns 
        WHERE column_name = 'updated_at' 
        AND table_schema = 'public'
    LOOP
        EXECUTE format('
            CREATE OR REPLACE TRIGGER update_%I_modtime
            BEFORE UPDATE ON %I
            FOR EACH ROW
            EXECUTE FUNCTION update_updated_at_column();
        ', t_name, t_name);
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- Category is now stored as plain TEXT, no FK reference
-- Category note counter removed (category is plain TEXT, not UUID FK)

-- Stack note counter
CREATE OR REPLACE FUNCTION update_stack_note_count()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.stack_id IS NOT NULL THEN
        UPDATE note_stacks SET note_count = note_count + 1 WHERE id = NEW.stack_id;
    ELSIF TG_OP = 'DELETE' AND OLD.stack_id IS NOT NULL THEN
        UPDATE note_stacks SET note_count = note_count - 1 WHERE id = OLD.stack_id;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.stack_id IS DISTINCT FROM NEW.stack_id THEN
            IF OLD.stack_id IS NOT NULL THEN
                UPDATE note_stacks SET note_count = note_count - 1 WHERE id = OLD.stack_id;
            END IF;
            IF NEW.stack_id IS NOT NULL THEN
                UPDATE note_stacks SET note_count = note_count + 1 WHERE id = NEW.stack_id;
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_stack_note_count_trigger
    AFTER INSERT OR DELETE OR UPDATE OF stack_id ON notes
    FOR EACH ROW EXECUTE FUNCTION update_stack_note_count();

-- =============================================================================
-- MIGRATIONS: Add missing constraints to existing databases
-- =============================================================================

-- Add unique constraint to agent_checkpoints for ON CONFLICT to work
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints 
        WHERE constraint_name = 'agent_checkpoints_session_id_key' 
        AND table_name = 'agent_checkpoints'
    ) THEN
        ALTER TABLE agent_checkpoints ADD CONSTRAINT agent_checkpoints_session_id_key UNIQUE (session_id);
    END IF;
END $$;

-- Add category column to notes if missing (migrating from category_id UUID to category TEXT)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name = 'notes' AND column_name = 'category') THEN
        ALTER TABLE notes ADD COLUMN category TEXT;
    END IF;
    
    -- If category_id exists and has data, migrate it
    IF EXISTS (SELECT 1 FROM information_schema.columns 
               WHERE table_name = 'notes' AND column_name = 'category_id') THEN
        -- Try to migrate if there's data
        UPDATE notes SET category = (
            SELECT name FROM note_categories WHERE id = notes.category_id
        ) WHERE notes.category_id IS NOT NULL;
    END IF;
END $$;

-- =============================================================================
-- SCHEMA COMPLETE - 28 TABLES, ALL SDE PRINCIPLES APPLIED
-- =============================================================================
