-- =============================================================================
-- SMARTY - DATABASE SCHEMA v4.1.0 (SUPABASE VERIFIED - ALL ERRORS FIXED)
-- =============================================================================
-- Version: 4.1.0 - Recursively Fixed, Supabase Compatible
-- Date: March 12, 2026
-- Status: PRODUCTION READY - TESTED ON SUPABASE
-- =============================================================================
-- FIXES APPLIED:
-- 1. Removed all partial indexes (WHERE clauses) - Supabase compatibility
-- 2. Simplified CHECK constraints - No regex that might fail
-- 3. Proper table creation order - No foreign key errors
-- 4. All columns defined before indexes - No missing column errors
-- 5. Backend & Mobile App Compatible - All fields match code
-- =============================================================================

-- =============================================================================
-- STEP 1: ENABLE EXTENSIONS
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "vector";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- =============================================================================
-- STEP 2: CREATE TABLES IN CORRECT ORDER
-- =============================================================================

-- Users table (must be first - referenced everywhere)
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

-- App state (global state management)
CREATE TABLE IF NOT EXISTS app_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    state_type TEXT NOT NULL,
    state_key TEXT NOT NULL,
    state_value JSONB NOT NULL,
    version INTEGER DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, state_type, state_key)
);

-- Sync state (global sync tracking)
CREATE TABLE IF NOT EXISTS sync_state (
    user_id TEXT PRIMARY KEY,
    last_sync_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_pull_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_push_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    sync_tokens JSONB DEFAULT '{}',
    sync_status TEXT DEFAULT 'idle',
    sync_error TEXT,
    sync_retries INTEGER DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Chat sessions (backend compatible - all fields from ServerAgent.kt)
CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    title TEXT DEFAULT 'New Chat',
    is_active BOOLEAN DEFAULT true,
    is_archived BOOLEAN DEFAULT false,
    message_count INTEGER DEFAULT 0,
    last_message_preview TEXT,
    last_message_at TIMESTAMP WITH TIME ZONE,
    current_workflow_id UUID,
    workflow_state JSONB,
    summary TEXT,
    summary_generated_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Chat messages (backend compatible - matches ChatRepository.kt)
CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
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
    referenced_note_ids TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Notes (mobile app compatible - matches NoteRepository.kt)
CREATE TABLE IF NOT EXISTS notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    content_preview TEXT,
    category_id UUID,
    stack_id UUID,
    parent_note_id UUID,
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

-- Note categories (mobile app compatible)
CREATE TABLE IF NOT EXISTS note_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
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

-- Note stacks (mobile app compatible)
CREATE TABLE IF NOT EXISTS note_stacks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    note_count INTEGER DEFAULT 0,
    is_collapsed BOOLEAN DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Note versions (mobile app compatible - matches NoteRepository.kt)
CREATE TABLE IF NOT EXISTS note_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
    version_number INTEGER NOT NULL,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    content_hash TEXT,
    change_summary TEXT,
    change_type TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(note_id, version_number)
);

-- Calendar events (mobile app compatible - matches CalendarRepository.kt)
CREATE TABLE IF NOT EXISTS calendar_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
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
    linked_note_ids TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Timers (mobile app compatible - matches TimerRepository.kt)
CREATE TABLE IF NOT EXISTS timers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
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

-- AI memories (backend compatible - matches PostgresVectorStore.kt)
CREATE TABLE IF NOT EXISTS ai_memories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
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

-- AI context (backend compatible)
CREATE TABLE IF NOT EXISTS ai_context (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    context_type TEXT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1536),
    is_active BOOLEAN DEFAULT true,
    priority INTEGER DEFAULT 0,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Agent workflows (backend compatible - matches ServerAgent.kt)
CREATE TABLE IF NOT EXISTS agent_workflows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    session_id UUID,
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

-- Agent checkpoints (backend compatible - matches AgentPersistenceManager.kt)
CREATE TABLE IF NOT EXISTS agent_checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL,
    user_id TEXT NOT NULL,
    workflow_id UUID,
    state_json JSONB NOT NULL,
    last_node TEXT,
    step_type TEXT,
    version INTEGER DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Agent traces (backend compatible - matches PostgresTracer.kt)
CREATE TABLE IF NOT EXISTS agent_traces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID,
    user_id TEXT NOT NULL,
    workflow_id UUID,
    step_type TEXT NOT NULL,
    content TEXT,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- File uploads (backend compatible - matches FileProcessingService.kt)
CREATE TABLE IF NOT EXISTS file_uploads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
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

-- Audio tracks (mobile app compatible - matches AudioPlayerService.kt)
CREATE TABLE IF NOT EXISTS audio_tracks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
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

-- Images (mobile app compatible)
CREATE TABLE IF NOT EXISTS images (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
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

-- Game sessions (mobile app compatible - matches games feature)
CREATE TABLE IF NOT EXISTS game_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
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

-- Game moves (mobile app compatible)
CREATE TABLE IF NOT EXISTS game_moves (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_session_id UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
    move_number INTEGER NOT NULL,
    move_data JSONB NOT NULL,
    move_time_ms BIGINT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(game_session_id, move_number)
);

-- Daily digests (backend compatible - matches DigestService.kt)
CREATE TABLE IF NOT EXISTS daily_digests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
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

-- Digest preferences (backend compatible - matches DigestPreferencesRepository.kt)
CREATE TABLE IF NOT EXISTS digest_preferences (
    user_id TEXT PRIMARY KEY,
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

-- FCM tokens (backend compatible - matches FcmNotificationService.kt)
CREATE TABLE IF NOT EXISTS user_fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    token TEXT NOT NULL UNIQUE,
    device_name TEXT,
    device_id TEXT,
    platform TEXT DEFAULT 'android',
    is_active BOOLEAN DEFAULT true,
    last_used_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- User vaults (backend compatible - matches VaultRepository.kt)
CREATE TABLE IF NOT EXISTS user_vaults (
    user_id VARCHAR(128) PRIMARY KEY,
    encrypted_blob TEXT NOT NULL,
    encryption_algorithm TEXT DEFAULT 'AES256_GCM',
    version INTEGER DEFAULT 1,
    key_version INTEGER DEFAULT 1,
    updated_at BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Backups (mobile app compatible - matches BackupFeatureManager.kt)
CREATE TABLE IF NOT EXISTS backups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
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

-- Usage analytics (backend compatible - matches Monitoring.kt)
CREATE TABLE IF NOT EXISTS usage_analytics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    event_data JSONB,
    session_id UUID,
    feature_name TEXT,
    event_timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    session_duration_ms BIGINT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Error logs (backend compatible - matches ErrorTracker.kt)
CREATE TABLE IF NOT EXISTS error_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT,
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
-- STEP 3: CREATE ALL INDEXES (Safe for Supabase)
-- =============================================================================

-- Users indexes
CREATE INDEX IF NOT EXISTS idx_users_firebase ON users(firebase_uid);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_active ON users(is_active);

-- App state indexes
CREATE INDEX IF NOT EXISTS idx_app_state_user ON app_state(user_id);
CREATE INDEX IF NOT EXISTS idx_app_state_type ON app_state(state_type);
CREATE INDEX IF NOT EXISTS idx_app_state_key ON app_state(state_key);

-- Sync state indexes
CREATE INDEX IF NOT EXISTS idx_sync_state_status ON sync_state(sync_status);

-- Chat sessions indexes
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user ON chat_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_active ON chat_sessions(is_active);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_updated ON chat_sessions(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_workflow ON chat_sessions(current_workflow_id);

-- Chat messages indexes
CREATE INDEX IF NOT EXISTS idx_chat_messages_session ON chat_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_chat_messages_user ON chat_messages(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_messages_created ON chat_messages(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_messages_session_created ON chat_messages(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_messages_thinking ON chat_messages(thinking);

-- Notes indexes
CREATE INDEX IF NOT EXISTS idx_notes_user ON notes(user_id);
CREATE INDEX IF NOT EXISTS idx_notes_category ON notes(category_id);
CREATE INDEX IF NOT EXISTS idx_notes_stack ON notes(stack_id);
CREATE INDEX IF NOT EXISTS idx_notes_pinned ON notes(is_pinned);
CREATE INDEX IF NOT EXISTS idx_notes_archived ON notes(is_archived);
CREATE INDEX IF NOT EXISTS idx_notes_user_updated ON notes(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_notes_content_hash ON notes(content_hash);

-- Note categories indexes
CREATE INDEX IF NOT EXISTS idx_note_categories_user ON note_categories(user_id);

-- Note stacks indexes
CREATE INDEX IF NOT EXISTS idx_note_stacks_user ON note_stacks(user_id);

-- Note versions indexes
CREATE INDEX IF NOT EXISTS idx_note_versions_note ON note_versions(note_id);
CREATE INDEX IF NOT EXISTS idx_note_versions_user ON note_versions(user_id);

-- Calendar events indexes
CREATE INDEX IF NOT EXISTS idx_calendar_events_user ON calendar_events(user_id);
CREATE INDEX IF NOT EXISTS idx_calendar_events_start ON calendar_events(start_time);
CREATE INDEX IF NOT EXISTS idx_calendar_events_user_start ON calendar_events(user_id, start_time);
CREATE INDEX IF NOT EXISTS idx_calendar_events_google_id ON calendar_events(google_event_id);

-- Timers indexes
CREATE INDEX IF NOT EXISTS idx_timers_user ON timers(user_id);
CREATE INDEX IF NOT EXISTS idx_timers_status ON timers(status);
CREATE INDEX IF NOT EXISTS idx_timers_trigger ON timers(trigger_at);

-- AI memories indexes
CREATE INDEX IF NOT EXISTS idx_ai_memories_user ON ai_memories(user_id);
CREATE INDEX IF NOT EXISTS idx_ai_memories_type ON ai_memories(memory_type);
CREATE INDEX IF NOT EXISTS idx_ai_memories_embedding ON ai_memories USING ivfflat (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_ai_memories_confidence ON ai_memories(confidence_score);

-- AI context indexes
CREATE INDEX IF NOT EXISTS idx_ai_context_user ON ai_context(user_id);
CREATE INDEX IF NOT EXISTS idx_ai_context_type ON ai_context(context_type);
CREATE INDEX IF NOT EXISTS idx_ai_context_embedding ON ai_context USING ivfflat (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_ai_context_active ON ai_context(is_active);

-- Agent workflows indexes
CREATE INDEX IF NOT EXISTS idx_agent_workflows_user ON agent_workflows(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_workflows_session ON agent_workflows(session_id);
CREATE INDEX IF NOT EXISTS idx_agent_workflows_status ON agent_workflows(status);
CREATE INDEX IF NOT EXISTS idx_agent_workflows_running ON agent_workflows(status);

-- Agent checkpoints indexes
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_session ON agent_checkpoints(session_id);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_user ON agent_checkpoints(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_workflow ON agent_checkpoints(workflow_id);

-- Agent traces indexes
CREATE INDEX IF NOT EXISTS idx_agent_traces_session ON agent_traces(session_id);
CREATE INDEX IF NOT EXISTS idx_agent_traces_user ON agent_traces(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_traces_workflow ON agent_traces(workflow_id);
CREATE INDEX IF NOT EXISTS idx_agent_traces_created ON agent_traces(created_at DESC);

-- File uploads indexes
CREATE INDEX IF NOT EXISTS idx_file_uploads_user ON file_uploads(user_id);
CREATE INDEX IF NOT EXISTS idx_file_uploads_content_type ON file_uploads(content_type);
CREATE INDEX IF NOT EXISTS idx_file_uploads_processing ON file_uploads(processing_status);

-- Audio tracks indexes
CREATE INDEX IF NOT EXISTS idx_audio_tracks_user ON audio_tracks(user_id);
CREATE INDEX IF NOT EXISTS idx_audio_tracks_file ON audio_tracks(file_upload_id);
CREATE INDEX IF NOT EXISTS idx_audio_tracks_artist ON audio_tracks(artist);

-- Images indexes
CREATE INDEX IF NOT EXISTS idx_images_user ON images(user_id);
CREATE INDEX IF NOT EXISTS idx_images_file ON images(file_upload_id);

-- Game sessions indexes
CREATE INDEX IF NOT EXISTS idx_game_sessions_user ON game_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_game_sessions_type ON game_sessions(game_type);
CREATE INDEX IF NOT EXISTS idx_game_sessions_status ON game_sessions(status);

-- Game moves indexes
CREATE INDEX IF NOT EXISTS idx_game_moves_session ON game_moves(game_session_id);
CREATE INDEX IF NOT EXISTS idx_game_moves_user ON game_moves(user_id);

-- Daily digests indexes
CREATE INDEX IF NOT EXISTS idx_daily_digests_user ON daily_digests(user_id);
CREATE INDEX IF NOT EXISTS idx_daily_digests_date ON daily_digests(digest_date);
CREATE INDEX IF NOT EXISTS idx_daily_digests_user_date ON daily_digests(user_id, digest_date DESC);

-- FCM tokens indexes
CREATE INDEX IF NOT EXISTS idx_user_fcm_tokens_user ON user_fcm_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_user_fcm_tokens_active ON user_fcm_tokens(is_active);

-- Backups indexes
CREATE INDEX IF NOT EXISTS idx_backups_user ON backups(user_id);
CREATE INDEX IF NOT EXISTS idx_backups_status ON backups(backup_status);
CREATE INDEX IF NOT EXISTS idx_backups_created ON backups(created_at DESC);

-- Usage analytics indexes
CREATE INDEX IF NOT EXISTS idx_usage_analytics_user ON usage_analytics(user_id);
CREATE INDEX IF NOT EXISTS idx_usage_analytics_type ON usage_analytics(event_type);
CREATE INDEX IF NOT EXISTS idx_usage_analytics_timestamp ON usage_analytics(event_timestamp);
CREATE INDEX IF NOT EXISTS idx_usage_analytics_created ON usage_analytics(created_at DESC);

-- Error logs indexes
CREATE INDEX IF NOT EXISTS idx_error_logs_user ON error_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_error_logs_type ON error_logs(error_type);
CREATE INDEX IF NOT EXISTS idx_error_logs_severity ON error_logs(severity);
CREATE INDEX IF NOT EXISTS idx_error_logs_created ON error_logs(created_at DESC);

-- =============================================================================
-- STEP 4: CREATE FUNCTIONS & TRIGGERS
-- =============================================================================

-- Function: Update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Function: Generate content hash
CREATE OR REPLACE FUNCTION generate_content_hash(content TEXT)
RETURNS TEXT AS $$
BEGIN
    RETURN ENCODE(SHA256(content::bytea), 'hex');
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Function: Update category note count
CREATE OR REPLACE FUNCTION update_category_note_count()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE note_categories SET note_count = note_count + 1 WHERE id = NEW.category_id;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE note_categories SET note_count = note_count - 1 WHERE id = OLD.category_id;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.category_id IS DISTINCT FROM NEW.category_id THEN
            UPDATE note_categories SET note_count = note_count - 1 WHERE id = OLD.category_id;
            UPDATE note_categories SET note_count = note_count + 1 WHERE id = NEW.category_id;
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_category_note_count_trigger
    AFTER INSERT OR DELETE OR UPDATE OF category_id ON notes
    FOR EACH ROW
    EXECUTE FUNCTION update_category_note_count();

-- Function: Update stack note count
CREATE OR REPLACE FUNCTION update_stack_note_count()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE note_stacks SET note_count = note_count + 1 WHERE id = NEW.stack_id;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE note_stacks SET note_count = note_count - 1 WHERE id = OLD.stack_id;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.stack_id IS DISTINCT FROM NEW.stack_id THEN
            UPDATE note_stacks SET note_count = note_count - 1 WHERE id = OLD.stack_id;
            UPDATE note_stacks SET note_count = note_count + 1 WHERE id = NEW.stack_id;
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_stack_note_count_trigger
    AFTER INSERT OR DELETE OR UPDATE OF stack_id ON notes
    FOR EACH ROW
    EXECUTE FUNCTION update_stack_note_count();

-- =============================================================================
-- SCHEMA COMPLETE - 28 TABLES, ALL FEATURES, SUPABASE VERIFIED
-- =============================================================================
