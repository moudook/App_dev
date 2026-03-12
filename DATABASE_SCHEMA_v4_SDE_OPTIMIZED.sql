-- =============================================================================
-- SMARTY - OPTIMIZED DATABASE SCHEMA v4.0.0
-- =============================================================================
-- Version: 4.0.0 (SDE Best Practices Applied)
-- Date: March 12, 2026
-- Author: Smarty Team
-- Principles: DRY, Single Responsibility, Global State Management
-- =============================================================================
-- IMPROVEMENTS:
-- 1. DRY: Common columns extracted to reusable patterns (created_at, updated_at, user_id)
-- 2. Single Responsibility: Each table has one clear purpose
-- 3. Global State: Centralized state tracking (sync, sessions, feature flags)
-- 4. Missing Fields: Added fields for games, media, workflows, style analysis
-- 5. Compatibility: Supports all existing features + future extensibility
-- =============================================================================

-- =============================================================================
-- PART 0: DATABASE SCHEMA DOCUMENTATION (Single Source of Truth)
-- =============================================================================

COMMENT ON DATABASE smarty IS '
Smarty Database v4.0.0 - SDE Best Practices Applied

SCHEMA ORGANIZATION:
1. Core Tables (chat, notes, calendar, timers) - User content
2. AI Tables (agent_*, memory_*, context_*) - AI intelligence
3. Media Tables (audio_*, images_*, files_*) - Media management
4. System Tables (sync_*, users_*, feature_*) - Global state
5. Analytics Tables (usage_*, events_*, metrics_*) - Observability

PRINCIPLES:
- DRY: Common columns via inheritance patterns
- Single Responsibility: One purpose per table
- Global State: Centralized tracking and coordination
';

-- =============================================================================
-- PART 1: EXTENSIONS (Enable once, use everywhere - DRY)
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "vector";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "btree_gin";

-- =============================================================================
-- PART 2: GLOBAL STATE TABLES (Single source of truth for app state)
-- =============================================================================

-- Users table (centralized user management)
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    firebase_uid TEXT UNIQUE NOT NULL,
    email TEXT,
    display_name TEXT,
    avatar_url TEXT,
    
    -- Global state
    is_active BOOLEAN DEFAULT true,
    is_premium BOOLEAN DEFAULT false,
    subscription_expires_at TIMESTAMP WITH TIME ZONE,
    
    -- Feature flags (global state for features)
    feature_flags JSONB DEFAULT '{}',
    
    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_login_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_email CHECK (email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

CREATE INDEX IF NOT EXISTS idx_users_firebase ON users(firebase_uid);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_active ON users(is_active);

COMMENT ON TABLE users IS 'Single source of truth for user data (Single Responsibility)';

-- Global application state (feature flags, settings, preferences)
CREATE TABLE IF NOT EXISTS app_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Global state management
    state_type TEXT NOT NULL CHECK (state_type IN ('preferences', 'settings', 'cache', 'workflow')),
    state_key TEXT NOT NULL,
    state_value JSONB NOT NULL,
    
    -- Metadata
    version INTEGER DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    UNIQUE(user_id, state_type, state_key)
);

CREATE INDEX IF NOT EXISTS idx_app_state_user ON app_state(user_id);
CREATE INDEX IF NOT EXISTS idx_app_state_type ON app_state(state_type);
CREATE INDEX IF NOT EXISTS idx_app_state_key ON app_state(state_key);

COMMENT ON TABLE app_state IS 'Centralized global state management for all features';

-- Sync state (global sync tracking)
CREATE TABLE IF NOT EXISTS sync_state (
    user_id TEXT PRIMARY KEY REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Global sync state
    last_sync_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_pull_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_push_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- Per-entity sync tokens
    sync_tokens JSONB DEFAULT '{}',
    
    -- Sync status
    sync_status TEXT DEFAULT 'idle' CHECK (sync_status IN ('idle', 'syncing', 'error')),
    sync_error TEXT,
    sync_retries INTEGER DEFAULT 0,
    
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sync_state_status ON sync_state(sync_status);

COMMENT ON TABLE sync_state IS 'Global state management for synchronization';

-- =============================================================================
-- PART 3: CHAT SYSTEM (Single Responsibility: Conversation management)
-- =============================================================================

CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Session metadata
    title TEXT DEFAULT 'New Chat',
    is_active BOOLEAN DEFAULT true,
    is_archived BOOLEAN DEFAULT false,
    
    -- State tracking
    message_count INTEGER DEFAULT 0,
    last_message_preview TEXT,
    last_message_at TIMESTAMP WITH TIME ZONE,
    
    -- AI state
    current_workflow_id UUID,
    workflow_state JSONB,
    
    -- Summary state
    summary TEXT,
    summary_generated_at TIMESTAMP WITH TIME ZONE,
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_title CHECK (length(title) > 0 AND length(title) <= 500)
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user ON chat_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_active ON chat_sessions(is_active);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_updated ON chat_sessions(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_workflow ON chat_sessions(current_workflow_id) WHERE current_workflow_id IS NOT NULL;

COMMENT ON TABLE chat_sessions IS 'Single Responsibility: Manage conversation sessions';

CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Message content
    role TEXT NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    content TEXT NOT NULL,
    
    -- Thinking/reasoning (for AI models with reasoning capability)
    thinking TEXT,
    thinking_mode TEXT CHECK (thinking_mode IN ('disabled', 'enabled', 'deep')),
    
    -- Message state
    is_streaming BOOLEAN DEFAULT false,
    is_edited BOOLEAN DEFAULT false,
    edit_count INTEGER DEFAULT 0,
    
    -- Attachments and references (DRY: JSON for flexibility)
    attachments JSONB DEFAULT '[]',
    citations JSONB DEFAULT '[]',
    inline_images JSONB DEFAULT '[]',
    executed_actions JSONB DEFAULT '[]',
    referenced_note_ids TEXT[],
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_content CHECK (length(content) > 0)
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_session ON chat_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_chat_messages_user ON chat_messages(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_messages_created ON chat_messages(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_messages_session_created ON chat_messages(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_messages_thinking ON chat_messages(thinking) WHERE thinking IS NOT NULL;

COMMENT ON TABLE chat_messages IS 'Single Responsibility: Store individual messages';

-- =============================================================================
-- PART 4: NOTES SYSTEM (Single Responsibility: Note management)
-- =============================================================================

CREATE TABLE IF NOT EXISTS notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Note content
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    content_preview TEXT,
    
    -- Organization (Single Responsibility: categorization)
    category_id UUID,
    stack_id UUID,
    parent_note_id UUID,
    
    -- State tracking
    is_archived BOOLEAN DEFAULT false,
    is_pinned BOOLEAN DEFAULT false,
    is_private BOOLEAN DEFAULT false,  -- Exclude from AI processing
    is_deleted BOOLEAN DEFAULT false,
    deleted_at TIMESTAMP WITH TIME ZONE,
    
    -- Content metadata (DRY: JSON for extensibility)
    content_type TEXT[] DEFAULT '{}',  -- ['text', 'image', 'audio', 'video', 'code']
    mime_types TEXT[] DEFAULT '{}',
    word_count INTEGER DEFAULT 0,
    reading_time_minutes INTEGER DEFAULT 0,
    
    -- AI analysis state
    ai_summary TEXT,
    ai_tags TEXT[],
    ai_category_suggestion TEXT,
    style_analysis JSONB,  -- Writing style fingerprint
    tone_analysis JSONB,   -- Tone detection results
    
    -- Deduplication (DRY: content_hash for comparison)
    content_hash TEXT,
    similarity_group_id UUID,  -- Group similar notes
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    viewed_at TIMESTAMP WITH TIME ZONE,
    
    CONSTRAINT valid_title CHECK (length(title) > 0 AND length(title) <= 1000),
    CONSTRAINT valid_content CHECK (length(content) > 0)
);

CREATE INDEX IF NOT EXISTS idx_notes_user ON notes(user_id);
CREATE INDEX IF NOT EXISTS idx_notes_category ON notes(category_id);
CREATE INDEX IF NOT EXISTS idx_notes_stack ON notes(stack_id);
CREATE INDEX IF NOT EXISTS idx_notes_pinned ON notes(is_pinned) WHERE is_pinned = true;
CREATE INDEX IF NOT EXISTS idx_notes_archived ON notes(is_archived) WHERE is_archived = true;
CREATE INDEX IF NOT EXISTS idx_notes_user_updated ON notes(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_notes_content_hash ON notes(content_hash);
CREATE INDEX IF NOT EXISTS idx_notes_content_type ON notes USING GIN (content_type);

COMMENT ON TABLE notes IS 'Single Responsibility: Manage note content and metadata';

-- Note categories (Single Responsibility: Organization)
CREATE TABLE IF NOT EXISTS note_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Category definition
    name TEXT NOT NULL,
    description TEXT,
    color_hex TEXT DEFAULT '#6366F1',
    icon TEXT,
    
    -- State
    note_count INTEGER DEFAULT 0,
    is_default BOOLEAN DEFAULT false,
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    UNIQUE(user_id, name),
    CONSTRAINT valid_name CHECK (length(name) > 0 AND length(name) <= 100)
);

CREATE INDEX IF NOT EXISTS idx_note_categories_user ON note_categories(user_id);

COMMENT ON TABLE note_categories IS 'Single Responsibility: Note categorization';

-- Note stacks (Single Responsibility: Group related notes)
CREATE TABLE IF NOT EXISTS note_stacks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Stack definition
    name TEXT NOT NULL,
    description TEXT,
    
    -- State
    note_count INTEGER DEFAULT 0,
    is_collapsed BOOLEAN DEFAULT false,
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_name CHECK (length(name) > 0 AND length(name) <= 200)
);

CREATE INDEX IF NOT EXISTS idx_note_stacks_user ON note_stacks(user_id);

COMMENT ON TABLE note_stacks IS 'Single Responsibility: Group related notes together';

-- Note versioning (Single Responsibility: Version history)
CREATE TABLE IF NOT EXISTS note_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Version tracking
    version_number INTEGER NOT NULL,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    content_hash TEXT,
    
    -- Change tracking
    change_summary TEXT,
    change_type TEXT CHECK (change_type IN ('create', 'edit', 'restore', 'ai_update')),
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    UNIQUE(note_id, version_number)
);

CREATE INDEX IF NOT EXISTS idx_note_versions_note ON note_versions(note_id);
CREATE INDEX IF NOT EXISTS idx_note_versions_user ON note_versions(user_id);

COMMENT ON TABLE note_versions IS 'Single Responsibility: Track note version history';

-- =============================================================================
-- PART 5: CALENDAR & TIMERS (Single Responsibility: Time management)
-- =============================================================================

CREATE TABLE IF NOT EXISTS calendar_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Event definition
    title TEXT NOT NULL,
    description TEXT,
    location TEXT,
    
    -- Time management
    start_time BIGINT NOT NULL,
    end_time BIGINT NOT NULL,
    timezone TEXT DEFAULT 'UTC',
    is_all_day BOOLEAN DEFAULT false,
    
    -- Recurrence (DRY: JSON for complex rules)
    recurrence_rule TEXT,  -- iCal RRULE
    recurrence_id UUID,
    
    -- State
    status TEXT DEFAULT 'confirmed' CHECK (status IN ('tentative', 'confirmed', 'cancelled')),
    visibility TEXT DEFAULT 'default' CHECK (visibility IN ('default', 'public', 'private')),
    
    -- Integration
    google_event_id TEXT,
    reminder_minutes INTEGER DEFAULT 15,
    reminder_sent BOOLEAN DEFAULT false,
    
    -- Linked entities
    linked_note_ids UUID[],
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_title CHECK (length(title) > 0 AND length(title) <= 500),
    CONSTRAINT valid_time_range CHECK (end_time >= start_time)
);

CREATE INDEX IF NOT EXISTS idx_calendar_events_user ON calendar_events(user_id);
CREATE INDEX IF NOT EXISTS idx_calendar_events_start ON calendar_events(start_time);
CREATE INDEX IF NOT EXISTS idx_calendar_events_user_start ON calendar_events(user_id, start_time);
CREATE INDEX IF NOT EXISTS idx_calendar_events_google_id ON calendar_events(google_event_id) WHERE google_event_id IS NOT NULL;

COMMENT ON TABLE calendar_events IS 'Single Responsibility: Manage calendar events';

CREATE TABLE IF NOT EXISTS timers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Timer definition
    name TEXT NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    remaining_ms BIGINT,
    
    -- State tracking
    status TEXT DEFAULT 'idle' CHECK (status IN ('idle', 'running', 'paused', 'completed', 'cancelled')),
    is_alarm BOOLEAN DEFAULT false,
    is_active BOOLEAN DEFAULT true,
    
    -- Timing
    started_at TIMESTAMP WITH TIME ZONE,
    paused_at TIMESTAMP WITH TIME ZONE,
    trigger_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    
    -- Recurrence (DRY: JSON for flexibility)
    recurrence JSONB,
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_name CHECK (length(name) > 0 AND length(name) <= 200),
    CONSTRAINT valid_duration CHECK (duration_ms >= 0)
);

CREATE INDEX IF NOT EXISTS idx_timers_user ON timers(user_id);
CREATE INDEX IF NOT EXISTS idx_timers_status ON timers(status);
CREATE INDEX IF NOT EXISTS idx_timers_trigger ON timers(trigger_at) WHERE trigger_at IS NOT NULL;

COMMENT ON TABLE timers IS 'Single Responsibility: Manage timers and alarms';

-- =============================================================================
-- PART 6: AI & AGENT SYSTEM (Single Responsibility: AI intelligence)
-- =============================================================================

-- AI Memory (Single Responsibility: Long-term memory storage)
CREATE TABLE IF NOT EXISTS ai_memories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Memory content
    content TEXT NOT NULL,
    memory_type TEXT NOT NULL CHECK (memory_type IN ('preference', 'factual', 'episodic', 'procedural')),
    
    -- Vector embedding for similarity search
    embedding vector(1536),
    
    -- State tracking
    confidence_score FLOAT DEFAULT 1.0,
    usage_count INTEGER DEFAULT 0,
    last_used_at TIMESTAMP WITH TIME ZONE,
    
    -- Source tracking (DRY: JSON for flexibility)
    source JSONB,  -- {type: 'note', 'chat', 'user_input', id: '...'}
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_content CHECK (length(content) > 0 AND length(content) <= 10000)
);

CREATE INDEX IF NOT EXISTS idx_ai_memories_user ON ai_memories(user_id);
CREATE INDEX IF NOT EXISTS idx_ai_memories_type ON ai_memories(memory_type);
CREATE INDEX IF NOT EXISTS idx_ai_memories_embedding ON ai_memories USING ivfflat (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_ai_memories_confidence ON ai_memories(confidence_score) WHERE confidence_score < 0.5;

COMMENT ON TABLE ai_memories IS 'Single Responsibility: Store long-term AI memories';

-- AI Context (Single Responsibility: Context management)
CREATE TABLE IF NOT EXISTS ai_context (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Context storage
    context_type TEXT NOT NULL CHECK (context_type IN ('user_fact', 'preference', 'goal', 'constraint')),
    content TEXT NOT NULL,
    
    -- Vector embedding
    embedding vector(1536),
    
    -- State
    is_active BOOLEAN DEFAULT true,
    priority INTEGER DEFAULT 0,
    
    -- Metadata (DRY: JSON for extensibility)
    metadata JSONB DEFAULT '{}',
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_content CHECK (length(content) > 0 AND length(content) <= 5000)
);

CREATE INDEX IF NOT EXISTS idx_ai_context_user ON ai_context(user_id);
CREATE INDEX IF NOT EXISTS idx_ai_context_type ON ai_context(context_type);
CREATE INDEX IF NOT EXISTS idx_ai_context_embedding ON ai_context USING ivfflat (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_ai_context_active ON ai_context(is_active) WHERE is_active = true;

COMMENT ON TABLE ai_context IS 'Single Responsibility: Manage AI context and user facts';

-- Agent Workflows (Single Responsibility: Workflow orchestration)
CREATE TABLE IF NOT EXISTS agent_workflows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    session_id UUID REFERENCES chat_sessions(id) ON DELETE CASCADE,
    
    -- Core responsibility: Workflow definition
    workflow_type TEXT NOT NULL CHECK (workflow_type IN ('deep_research', 'batch_process', 'scheduled_task', 'analysis')),
    workflow_name TEXT NOT NULL,
    
    -- State management (Global state for workflows)
    status TEXT NOT NULL CHECK (status IN ('pending', 'running', 'paused', 'completed', 'failed', 'cancelled')),
    progress_percentage FLOAT DEFAULT 0,
    current_step INTEGER DEFAULT 0,
    total_steps INTEGER DEFAULT 0,
    
    -- Workflow data (DRY: JSON for flexibility)
    input_data JSONB,
    output_data JSONB,
    error_message TEXT,
    
    -- Timing
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_name CHECK (length(workflow_name) > 0 AND length(workflow_name) <= 300)
);

CREATE INDEX IF NOT EXISTS idx_agent_workflows_user ON agent_workflows(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_workflows_session ON agent_workflows(session_id);
CREATE INDEX IF NOT EXISTS idx_agent_workflows_status ON agent_workflows(status);
CREATE INDEX IF NOT EXISTS idx_agent_workflows_running ON agent_workflows(status) WHERE status = 'running';

COMMENT ON TABLE agent_workflows IS 'Single Responsibility: Orchestrate AI workflows';

-- Agent Checkpoints (Single Responsibility: Checkpoint storage)
CREATE TABLE IF NOT EXISTS agent_checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL,
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    workflow_id UUID REFERENCES agent_workflows(id) ON DELETE CASCADE,
    
    -- Core responsibility: State checkpoint
    state_json JSONB NOT NULL,
    last_node TEXT,
    step_type TEXT,
    
    -- Versioning
    version INTEGER DEFAULT 1,
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_state CHECK (jsonb_typeof(state_json) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_session ON agent_checkpoints(session_id);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_user ON agent_checkpoints(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_checkpoints_workflow ON agent_checkpoints(workflow_id);

COMMENT ON TABLE agent_checkpoints IS 'Single Responsibility: Store agent execution checkpoints';

-- Agent Traces (Single Responsibility: Observability)
CREATE TABLE IF NOT EXISTS agent_traces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID,
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    workflow_id UUID REFERENCES agent_workflows(id) ON DELETE CASCADE,
    
    -- Core responsibility: Trace recording
    step_type TEXT NOT NULL,
    content TEXT,
    metadata JSONB,
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_step CHECK (length(step_type) > 0)
);

CREATE INDEX IF NOT EXISTS idx_agent_traces_session ON agent_traces(session_id);
CREATE INDEX IF NOT EXISTS idx_agent_traces_user ON agent_traces(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_traces_workflow ON agent_traces(workflow_id);
CREATE INDEX IF NOT EXISTS idx_agent_traces_created ON agent_traces(created_at DESC);

COMMENT ON TABLE agent_traces IS 'Single Responsibility: Agent execution observability';

-- =============================================================================
-- PART 7: MEDIA & FILES (Single Responsibility: Media management)
-- =============================================================================

-- File uploads (Single Responsibility: File tracking)
CREATE TABLE IF NOT EXISTS file_uploads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: File metadata
    filename TEXT NOT NULL,
    original_filename TEXT,
    content_type TEXT NOT NULL,
    file_size BIGINT NOT NULL,
    
    -- Storage
    storage_path TEXT NOT NULL,
    storage_provider TEXT DEFAULT 'local' CHECK (storage_provider IN ('local', 's3', 'gcs', 'firebase')),
    
    -- Processing state
    processing_status TEXT DEFAULT 'pending' CHECK (processing_status IN ('pending', 'processing', 'completed', 'failed')),
    extracted_text TEXT,
    processing_error TEXT,
    
    -- Metadata (DRY: JSON for extensibility)
    metadata JSONB DEFAULT '{}',
    dimensions JSONB,  -- For images: {width, height}
    duration_ms BIGINT,  -- For audio/video
    
    -- AI analysis
    ai_description TEXT,
    ai_tags TEXT[],
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_filename CHECK (length(filename) > 0 AND length(filename) <= 500),
    CONSTRAINT valid_size CHECK (file_size > 0 AND file_size <= 1073741824)  -- Max 1GB
);

CREATE INDEX IF NOT EXISTS idx_file_uploads_user ON file_uploads(user_id);
CREATE INDEX IF NOT EXISTS idx_file_uploads_content_type ON file_uploads(content_type);
CREATE INDEX IF NOT EXISTS idx_file_uploads_processing ON file_uploads(processing_status);

COMMENT ON TABLE file_uploads IS 'Single Responsibility: Track uploaded files';

-- Audio tracks (Single Responsibility: Audio management)
CREATE TABLE IF NOT EXISTS audio_tracks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    file_upload_id UUID REFERENCES file_uploads(id) ON DELETE CASCADE,
    
    -- Core responsibility: Audio metadata
    title TEXT NOT NULL,
    artist TEXT,
    album TEXT,
    genre TEXT,
    
    -- Audio properties
    duration_ms BIGINT NOT NULL,
    bitrate INTEGER,
    sample_rate INTEGER,
    
    -- Playback state (Global state for audio)
    play_count INTEGER DEFAULT 0,
    last_played_at TIMESTAMP WITH TIME ZONE,
    last_played_position_ms BIGINT DEFAULT 0,
    
    -- AI analysis
    ai_mood TEXT,
    ai_tags TEXT[],
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_title CHECK (length(title) > 0 AND length(title) <= 500),
    CONSTRAINT valid_duration CHECK (duration_ms > 0)
);

CREATE INDEX IF NOT EXISTS idx_audio_tracks_user ON audio_tracks(user_id);
CREATE INDEX IF NOT EXISTS idx_audio_tracks_file ON audio_tracks(file_upload_id);
CREATE INDEX IF NOT EXISTS idx_audio_tracks_artist ON audio_tracks(artist);

COMMENT ON TABLE audio_tracks IS 'Single Responsibility: Manage audio track metadata';

-- Images (Single Responsibility: Image management)
CREATE TABLE IF NOT EXISTS images (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    file_upload_id UUID REFERENCES file_uploads(id) ON DELETE CASCADE,
    
    -- Core responsibility: Image metadata
    title TEXT,
    description TEXT,
    
    -- Image properties
    width INTEGER,
    height INTEGER,
    format TEXT,  -- JPEG, PNG, WEBP, etc.
    
    -- AI analysis
    ai_description TEXT,
    ai_tags TEXT[],
    ai_colors TEXT[],
    ai_text_extracted TEXT,  -- OCR results
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_dimensions CHECK (width > 0 AND height > 0)
);

CREATE INDEX IF NOT EXISTS idx_images_user ON images(user_id);
CREATE INDEX IF NOT EXISTS idx_images_file ON images(file_upload_id);

COMMENT ON TABLE images IS 'Single Responsibility: Manage image metadata';

-- =============================================================================
-- PART 8: GAMES & ENTERTAINMENT (Single Responsibility: Game state)
-- =============================================================================

-- Game sessions (Single Responsibility: Game state tracking)
CREATE TABLE IF NOT EXISTS game_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Game definition
    game_type TEXT NOT NULL CHECK (game_type IN ('tic_tac_toe', 'coin_toss', 'chess', 'checkers')),
    game_name TEXT NOT NULL,
    
    -- Game state (Global state for games)
    status TEXT DEFAULT 'playing' CHECK (status IN ('playing', 'paused', 'completed', 'abandoned')),
    state_json JSONB NOT NULL DEFAULT '{}',
    
    -- Players
    player_colors JSONB,  -- {player1: 'X', player2: 'O'}
    current_player TEXT,
    
    -- Results
    winner TEXT,
    winning_move TEXT,
    move_count INTEGER DEFAULT 0,
    
    -- Timing
    started_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_game CHECK (length(game_name) > 0 AND length(game_name) <= 200)
);

CREATE INDEX IF NOT EXISTS idx_game_sessions_user ON game_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_game_sessions_type ON game_sessions(game_type);
CREATE INDEX IF NOT EXISTS idx_game_sessions_status ON game_sessions(status);

COMMENT ON TABLE game_sessions IS 'Single Responsibility: Track game sessions and state';

-- Game moves (Single Responsibility: Move history)
CREATE TABLE IF NOT EXISTS game_moves (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_session_id UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Move recording
    move_number INTEGER NOT NULL,
    move_data JSONB NOT NULL,  -- {position: [row, col], piece: 'X'}
    move_time_ms BIGINT,
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    UNIQUE(game_session_id, move_number)
);

CREATE INDEX IF NOT EXISTS idx_game_moves_session ON game_moves(game_session_id);
CREATE INDEX IF NOT EXISTS idx_game_moves_user ON game_moves(user_id);

COMMENT ON TABLE game_moves IS 'Single Responsibility: Record game move history';

-- =============================================================================
-- PART 9: DIGEST & NOTIFICATIONS (Single Responsibility: Content delivery)
-- =============================================================================

CREATE TABLE IF NOT EXISTS daily_digests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Digest content
    digest_date DATE NOT NULL,
    digest_type TEXT NOT NULL DEFAULT 'daily' CHECK (digest_type IN ('daily', 'weekly', 'monthly')),
    
    -- AI-generated content
    summary TEXT NOT NULL,
    key_insights JSONB,
    goals_progress JSONB,
    priorities JSONB,
    critical_info TEXT,
    
    -- Analytics
    notes_analyzed INTEGER DEFAULT 0,
    chats_analyzed INTEGER DEFAULT 0,
    memories_analyzed INTEGER DEFAULT 0,
    
    -- Delivery state
    notification_sent BOOLEAN DEFAULT false,
    calendar_event_id TEXT,
    delivered_at TIMESTAMP WITH TIME ZONE,
    viewed_at TIMESTAMP WITH TIME ZONE,
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    UNIQUE(user_id, digest_date, digest_type)
);

CREATE INDEX IF NOT EXISTS idx_daily_digests_user ON daily_digests(user_id);
CREATE INDEX IF NOT EXISTS idx_daily_digests_date ON daily_digests(digest_date);
CREATE INDEX IF NOT EXISTS idx_daily_digests_user_date ON daily_digests(user_id, digest_date DESC);

COMMENT ON TABLE daily_digests IS 'Single Responsibility: Store AI-generated digests';

CREATE TABLE IF NOT EXISTS digest_preferences (
    user_id TEXT PRIMARY KEY REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Preference storage
    daily_enabled BOOLEAN DEFAULT true,
    daily_time TIME DEFAULT '07:00:00',
    
    weekly_enabled BOOLEAN DEFAULT true,
    weekly_day INTEGER DEFAULT 0 CHECK (weekly_day BETWEEN 0 AND 6),  -- 0=Sunday
    weekly_time TIME DEFAULT '08:00:00',
    
    -- Notification settings
    push_notification BOOLEAN DEFAULT true,
    calendar_logging BOOLEAN DEFAULT true,
    email_digest BOOLEAN DEFAULT false,
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_daily_time CHECK (daily_time IS NULL OR daily_time BETWEEN '00:00:00' AND '23:59:59'),
    CONSTRAINT valid_weekly_time CHECK (weekly_time IS NULL OR weekly_time BETWEEN '00:00:00' AND '23:59:59')
);

COMMENT ON TABLE digest_preferences IS 'Single Responsibility: Store digest delivery preferences';

-- FCM tokens (Single Responsibility: Push notification tokens)
CREATE TABLE IF NOT EXISTS user_fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Token storage
    token TEXT NOT NULL UNIQUE,
    device_name TEXT,
    device_id TEXT,
    platform TEXT DEFAULT 'android' CHECK (platform IN ('android', 'ios', 'web')),
    
    -- State
    is_active BOOLEAN DEFAULT true,
    last_used_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_token CHECK (length(token) > 0 AND length(token) <= 2048)
);

CREATE INDEX IF NOT EXISTS idx_user_fcm_tokens_user ON user_fcm_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_user_fcm_tokens_active ON user_fcm_tokens(is_active) WHERE is_active = true;

COMMENT ON TABLE user_fcm_tokens IS 'Single Responsibility: Store FCM push tokens';

-- =============================================================================
-- PART 10: BACKUP & VAULT (Single Responsibility: Data protection)
-- =============================================================================

-- User vault (Single Responsibility: Encrypted storage)
CREATE TABLE IF NOT EXISTS user_vaults (
    user_id VARCHAR(128) PRIMARY KEY REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Encrypted blob storage
    encrypted_blob TEXT NOT NULL,
    encryption_algorithm TEXT DEFAULT 'AES256_GCM',
    
    -- State
    version INTEGER DEFAULT 1,
    key_version INTEGER DEFAULT 1,
    
    -- DRY: Common timestamps
    updated_at BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_blob CHECK (length(encrypted_blob) > 0)
);

COMMENT ON TABLE user_vaults IS 'Single Responsibility: Secure encrypted storage';

-- Backups (Single Responsibility: Backup tracking)
CREATE TABLE IF NOT EXISTS backups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Backup metadata
    backup_type TEXT NOT NULL CHECK (backup_type IN ('local', 'google_drive', 'manual', 'automatic')),
    backup_status TEXT NOT NULL CHECK (backup_status IN ('pending', 'in_progress', 'completed', 'failed')),
    
    -- Backup details
    file_path TEXT,
    file_size BIGINT,
    file_hash TEXT,
    
    -- Contents
    includes_notes BOOLEAN DEFAULT true,
    includes_chats BOOLEAN DEFAULT true,
    includes_settings BOOLEAN DEFAULT true,
    includes_media BOOLEAN DEFAULT false,
    
    -- State
    error_message TEXT,
    completed_at TIMESTAMP WITH TIME ZONE,
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_path CHECK (file_path IS NULL OR length(file_path) <= 1000)
);

CREATE INDEX IF NOT EXISTS idx_backups_user ON backups(user_id);
CREATE INDEX IF NOT EXISTS idx_backups_status ON backups(backup_status);
CREATE INDEX IF NOT EXISTS idx_backups_created ON backups(created_at DESC);

COMMENT ON TABLE backups IS 'Single Responsibility: Track backup operations';

-- =============================================================================
-- PART 11: ANALYTICS & OBSERVABILITY (Single Responsibility: Monitoring)
-- =============================================================================

-- Usage analytics (Single Responsibility: Usage tracking)
CREATE TABLE IF NOT EXISTS usage_analytics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL REFERENCES users(firebase_uid) ON DELETE CASCADE,
    
    -- Core responsibility: Event tracking
    event_type TEXT NOT NULL,
    event_data JSONB,
    
    -- Context
    session_id UUID,
    feature_name TEXT,
    
    -- Timing
    event_timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    session_duration_ms BIGINT,
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_event CHECK (length(event_type) > 0 AND length(event_type) <= 200)
);

CREATE INDEX IF NOT EXISTS idx_usage_analytics_user ON usage_analytics(user_id);
CREATE INDEX IF NOT EXISTS idx_usage_analytics_type ON usage_analytics(event_type);
CREATE INDEX IF NOT EXISTS idx_usage_analytics_timestamp ON usage_analytics(event_timestamp);
CREATE INDEX IF NOT EXISTS idx_usage_analytics_created ON usage_analytics(created_at DESC);

COMMENT ON TABLE usage_analytics IS 'Single Responsibility: Track usage analytics';

-- Error logs (Single Responsibility: Error tracking)
CREATE TABLE IF NOT EXISTS error_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT REFERENCES users(firebase_uid) ON DELETE SET NULL,
    
    -- Core responsibility: Error recording
    error_type TEXT NOT NULL,
    error_message TEXT NOT NULL,
    error_stack TEXT,
    
    -- Context
    feature_name TEXT,
    action_name TEXT,
    metadata JSONB,
    
    -- Severity
    severity TEXT DEFAULT 'error' CHECK (severity IN ('debug', 'info', 'warning', 'error', 'critical')),
    
    -- DRY: Common timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT valid_error CHECK (length(error_message) > 0 AND length(error_message) <= 2000)
);

CREATE INDEX IF NOT EXISTS idx_error_logs_user ON error_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_error_logs_type ON error_logs(error_type);
CREATE INDEX IF NOT EXISTS idx_error_logs_severity ON error_logs(severity);
CREATE INDEX IF NOT EXISTS idx_error_logs_created ON error_logs(created_at DESC);

COMMENT ON TABLE error_logs IS 'Single Responsibility: Track application errors';

-- =============================================================================
-- PART 12: TRIGGERS & FUNCTIONS (DRY: Reusable database logic)
-- =============================================================================

-- Function: Update updated_at timestamp (DRY: Reusable across all tables)
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION update_updated_at_column IS 'DRY: Reusable trigger to update updated_at timestamp';

-- Apply updated_at trigger to all tables with updated_at column
DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOR table_name IN 
        SELECT tablename FROM pg_tables 
        WHERE schemaname = 'public' 
        AND tablename NOT LIKE 'pg_%'
        AND tablename NOT LIKE 'sql_%'
    LOOP
        EXECUTE format('
            DROP TRIGGER IF EXISTS update_updated_at_trigger ON %I.%I;
            CREATE TRIGGER update_updated_at_trigger
                BEFORE UPDATE ON %I.%I
                FOR EACH ROW
                EXECUTE FUNCTION update_updated_at_column();
        ', 'public', table_name, 'public', table_name);
    END LOOP;
END $$;

-- Function: Generate content hash for deduplication (DRY)
CREATE OR REPLACE FUNCTION generate_content_hash(content TEXT)
RETURNS TEXT AS $$
BEGIN
    RETURN ENCODE(SHA256(content::bytea), 'hex');
END;
$$ LANGUAGE plpgsql IMMUTABLE;

COMMENT ON FUNCTION generate_content_hash IS 'DRY: Reusable function for content deduplication';

-- Function: Update note count for categories (Single Responsibility automation)
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

-- Function: Update stack note count (Single Responsibility automation)
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
-- PART 13: INITIALIZATION & SEED DATA
-- =============================================================================

-- Insert default data for testing (optional - comment out in production)
-- INSERT INTO users (firebase_uid, email, display_name)
-- VALUES ('test-user-123', 'test@example.com', 'Test User')
-- ON CONFLICT (firebase_uid) DO NOTHING;

-- =============================================================================
-- END OF SCHEMA
-- =============================================================================
