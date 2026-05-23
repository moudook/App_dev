-- =====================================================
-- Database Schema v6.0.0 - Unified Production Schema
-- Optimized: vector search, FTS, sync-ready, unified constraints
-- Applied automatically on server startup
-- =====================================================

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- =====================================================
-- AUTO updated_at TRIGGER FUNCTION
-- =====================================================

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =====================================================
-- Core Tables
-- =====================================================

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    firebase_uid TEXT UNIQUE NOT NULL,
    email TEXT,
    display_name TEXT,
    avatar_url TEXT,
    is_active BOOLEAN DEFAULT true,
    is_premium BOOLEAN DEFAULT false,
    subscription_expires_at TIMESTAMPTZ,
    feature_flags JSONB DEFAULT '{}',
    sync_state TEXT DEFAULT 'PENDING',
    device_fingerprint TEXT,
    last_device_id TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    last_login_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sync_state (
    user_id      UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    last_sync_at TIMESTAMPTZ,
    last_pull_at TIMESTAMPTZ,
    last_push_at TIMESTAMPTZ,
    pending_operations INTEGER DEFAULT 0,
    conflict_count INTEGER DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS chat_folders (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    color      TEXT DEFAULT '#6200EE',
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS categories (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    description     TEXT,
    color           TEXT DEFAULT '#6200EE',
    icon            TEXT DEFAULT 'folder',
    parent_id       UUID REFERENCES categories(id) ON DELETE SET NULL,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    note_count      INTEGER NOT NULL DEFAULT 0,
    is_ai_generated BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_categories_user_name ON categories(user_id, lower(name));

CREATE TABLE IF NOT EXISTS stacks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    description TEXT,
    color       TEXT DEFAULT '#03DAC6',
    icon        TEXT DEFAULT 'stack',
    parent_id   UUID REFERENCES stacks(id) ON DELETE SET NULL,
    note_count  INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS chat_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    folder_id       UUID REFERENCES chat_folders(id) ON DELETE SET NULL,
    title           TEXT,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    is_archived     BOOLEAN NOT NULL DEFAULT false,
    is_pinned       BOOLEAN NOT NULL DEFAULT false,
    model_used      TEXT,
    temperature     NUMERIC(3,2) DEFAULT 0.7 CHECK (temperature BETWEEN 0 AND 2),
    max_tokens      INTEGER DEFAULT 4096 CHECK (max_tokens > 0),
    system_prompt   TEXT,
    token_count     INTEGER NOT NULL DEFAULT 0,
    message_count   INTEGER NOT NULL DEFAULT 0,
    last_message_preview TEXT,
    summary         TEXT,
    summary_generated_at TIMESTAMPTZ,
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    opencode_session_id VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS notes (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id             UUID REFERENCES categories(id) ON DELETE SET NULL,
    stack_id                UUID REFERENCES stacks(id) ON DELETE SET NULL,
    parent_note_id          UUID REFERENCES notes(id) ON DELETE SET NULL,
    title                   TEXT NOT NULL DEFAULT '',
    content                 TEXT NOT NULL DEFAULT '',
    summary                 TEXT,
    source_url              TEXT,
    image_uri               TEXT,
    file_uri                TEXT,
    file_name               TEXT,
    file_mime_type          TEXT,
    file_size               BIGINT,
    type                    TEXT NOT NULL DEFAULT 'BRAIN_DUMP',
    category_name           TEXT,
    why_saved               TEXT,
    processing_status       TEXT NOT NULL DEFAULT 'PENDING' CHECK (processing_status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    is_archived             BOOLEAN NOT NULL DEFAULT false,
    is_pinned               BOOLEAN NOT NULL DEFAULT false,
    is_favorite             BOOLEAN NOT NULL DEFAULT false,
    is_full_privacy         BOOLEAN NOT NULL DEFAULT false,
    exclude_from_ai_chat    BOOLEAN NOT NULL DEFAULT false,
    is_ai_created           BOOLEAN NOT NULL DEFAULT false,
    is_viewed               BOOLEAN NOT NULL DEFAULT false,
    todo_content            TEXT,
    attachments_json        JSONB DEFAULT '[]',
    tags_json               JSONB DEFAULT '[]',
    chunk_analyses_json     JSONB DEFAULT '[]',
    reminder_text           TEXT,
    reminder_expires_at     TIMESTAMPTZ,
    metadata                JSONB NOT NULL DEFAULT '{}',
    word_count              INTEGER,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at              TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id        UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    parent_message_id UUID REFERENCES chat_messages(id) ON DELETE SET NULL,
    role              TEXT NOT NULL CHECK (role IN ('user','assistant','system','tool')),
    content           TEXT NOT NULL,
    content_hash      TEXT,
    thinking          TEXT,
    tool_calls        JSONB,
    tool_call_id      TEXT,
    token_count       INTEGER DEFAULT 0,
    is_edited         BOOLEAN NOT NULL DEFAULT false,
    is_starred        BOOLEAN NOT NULL DEFAULT false,
    metadata          JSONB NOT NULL DEFAULT '{}',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tasks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id      UUID REFERENCES chat_sessions(id) ON DELETE SET NULL,
    note_id         UUID REFERENCES notes(id) ON DELETE SET NULL,
    title           TEXT NOT NULL,
    description     TEXT,
    status          TEXT NOT NULL DEFAULT 'todo',
    priority        INTEGER NOT NULL DEFAULT 2,
    due_date        TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    is_recurring    BOOLEAN NOT NULL DEFAULT false,
    recurrence_rule TEXT,
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS tags (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    color      TEXT DEFAULT '#6200EE',
    usage_count INTEGER NOT NULL DEFAULT 0,
    tag_type   TEXT NOT NULL DEFAULT 'MANUAL',
    confidence_score DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_tags_user_lower_name ON tags(user_id, lower(name));

CREATE TABLE IF NOT EXISTS note_tags (
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    tag_id UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_by TEXT NOT NULL DEFAULT 'user',
    confidence_score DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (note_id, tag_id)
);
CREATE INDEX IF NOT EXISTS idx_note_tags_user ON note_tags(user_id);
CREATE INDEX IF NOT EXISTS idx_note_tags_tag ON note_tags(tag_id);
CREATE INDEX IF NOT EXISTS idx_note_tags_note ON note_tags(note_id);

CREATE TABLE IF NOT EXISTS note_stacks (
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    stack_id UUID NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,
    PRIMARY KEY (note_id, stack_id)
);

CREATE TABLE IF NOT EXISTS chat_message_notes (
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    PRIMARY KEY (message_id, note_id)
);

-- =====================================================
-- System & AI Tables
-- =====================================================

CREATE TABLE IF NOT EXISTS notifications (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type       TEXT NOT NULL,
    title      TEXT NOT NULL,
    body       TEXT,
    data       JSONB NOT NULL DEFAULT '{}',
    is_read    BOOLEAN NOT NULL DEFAULT false,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS digest_preferences (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    enabled        BOOLEAN NOT NULL DEFAULT true,
    frequency      TEXT NOT NULL DEFAULT 'daily',
    delivery_hour  INTEGER NOT NULL DEFAULT 9,
    delivery_minute INTEGER NOT NULL DEFAULT 0,
    timezone       TEXT NOT NULL DEFAULT 'UTC',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id)
);

CREATE TABLE IF NOT EXISTS timers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    duration_ms BIGINT NOT NULL,
    trigger_at TIMESTAMPTZ NOT NULL,
    is_alarm BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    repeat TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS user_fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token TEXT NOT NULL,
    device_name TEXT,
    device_id TEXT,
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, token)
);

CREATE TABLE IF NOT EXISTS daily_digests (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    digest_date       DATE NOT NULL,
    digest_type       TEXT NOT NULL CHECK (digest_type IN ('morning','evening','weekly','daily','DAILY','WEEKLY','MONTHLY','CUSTOM')),
    summary           TEXT,
    key_insights      JSONB DEFAULT '[]',
    goals_progress    JSONB DEFAULT '[]',
    priorities        JSONB DEFAULT '[]',
    critical_info     TEXT,
    notes_analyzed    INTEGER NOT NULL DEFAULT 0,
    chats_analyzed    INTEGER NOT NULL DEFAULT 0,
    memories_analyzed INTEGER NOT NULL DEFAULT 0,
    notification_sent BOOLEAN NOT NULL DEFAULT false,
    calendar_event_id UUID,
    generated_by_ai   BOOLEAN NOT NULL DEFAULT true,
    linked_note_ids   TEXT[] DEFAULT '{}',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT daily_digests_user_date_type_uk UNIQUE (user_id, digest_date, digest_type)
);

CREATE TABLE IF NOT EXISTS calendar_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           TEXT NOT NULL,
    description     TEXT,
    start_time      TIMESTAMPTZ NOT NULL,
    end_time        TIMESTAMPTZ,
    is_all_day      BOOLEAN NOT NULL DEFAULT false,
    status          TEXT NOT NULL DEFAULT 'confirmed',
    visibility      TEXT NOT NULL DEFAULT 'private',
    reminders       JSONB NOT NULL DEFAULT '[]',
    attendees       JSONB NOT NULL DEFAULT '[]',
    location        TEXT,
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS calendar_event_notes (
    event_id UUID NOT NULL REFERENCES calendar_events(id) ON DELETE CASCADE,
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, note_id)
);

CREATE TABLE IF NOT EXISTS agent_traces (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id    UUID NOT NULL,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    step_name     TEXT NOT NULL,
    step_type     TEXT,
    content       TEXT,
    input_data    TEXT,
    output_data   TEXT,
    error_message TEXT,
    metadata      JSONB NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS reasoning_traces (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id        UUID NOT NULL,
    message_id        UUID,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    step_index        INTEGER NOT NULL,
    step_type         TEXT,
    title             TEXT,
    content           TEXT,
    confidence_score  DOUBLE PRECISION DEFAULT 0.5,
    importance_score  DOUBLE PRECISION DEFAULT 0.5,
    is_final          BOOLEAN DEFAULT false,
    was_revised       BOOLEAN DEFAULT false,
    revised_by_trace_id UUID,
    token_count       INTEGER DEFAULT 0,
    duration_ms       BIGINT DEFAULT 0,
    metadata          JSONB NOT NULL DEFAULT '{}',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS reasoning_summaries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       UUID NOT NULL,
    message_id       UUID,
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    one_liner        TEXT,
    brief_summary    TEXT,
    detailed_summary TEXT,
    total_steps      INTEGER DEFAULT 0,
    total_duration_ms BIGINT DEFAULT 0,
    total_tokens     INTEGER DEFAULT 0,
    confidence_score DOUBLE PRECISION DEFAULT 0.5,
    complexity_score DOUBLE PRECISION DEFAULT 0.5,
    reasoning_type   TEXT,
    tags             TEXT[] DEFAULT '{}',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    workflow_id UUID,
    state_json JSONB NOT NULL,
    context_json JSONB,
    memory_json JSONB,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS generated_images (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id UUID REFERENCES chat_sessions(id) ON DELETE SET NULL,
    prompt TEXT NOT NULL,
    krea_job_id TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'queued',
    image_url TEXT,
    supabase_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS search_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    query TEXT NOT NULL,
    search_scope TEXT DEFAULT 'all',
    result_count INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS user_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name TEXT,
    device_type TEXT DEFAULT 'android',
    push_token TEXT,
    last_active_at TIMESTAMPTZ,
    app_version TEXT,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, device_name)
);

CREATE TABLE IF NOT EXISTS note_versions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    note_id                UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    title                  TEXT NOT NULL,
    content                TEXT NOT NULL,
    summary                TEXT,
    version_no             INTEGER NOT NULL,
    change_description     TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(note_id, version_no)
);

CREATE TABLE IF NOT EXISTS shared_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    shared_with_id UUID REFERENCES users(id) ON DELETE CASCADE,
    item_type TEXT NOT NULL,
    item_id UUID NOT NULL,
    permission TEXT DEFAULT 'view',
    share_token TEXT UNIQUE,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS vector_embeddings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    embedding vector(1536) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ai_cache (
    content_hash       TEXT PRIMARY KEY,
    json_response      TEXT NOT NULL,
    user_id            UUID REFERENCES users(id) ON DELETE CASCADE,
    created_at         BIGINT NOT NULL,
    expires_at         BIGINT NOT NULL,
    last_accessed_at    BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS user_vaults (
    user_id         TEXT PRIMARY KEY,
    encrypted_blob  TEXT NOT NULL,
    version         INTEGER NOT NULL DEFAULT 1,
    updated_at      BIGINT NOT NULL
);

-- =====================================================
-- Indexes for Performance
-- =====================================================

CREATE INDEX IF NOT EXISTS idx_notes_user ON notes(user_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_notes_user_updated ON notes(user_id, updated_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_notes_category ON notes(category_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_notes_stack ON notes(stack_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_notes_fts ON notes USING gin (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(content,'')));
CREATE INDEX IF NOT EXISTS idx_messages_session ON chat_messages(session_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user ON chat_sessions(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_vector_embeddings_user ON vector_embeddings(user_id);
CREATE INDEX IF NOT EXISTS idx_vector_embeddings ON vector_embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX IF NOT EXISTS idx_ai_cache_expires ON ai_cache(expires_at);

-- ============================================================
-- IDEMPOTENT UPGRADES
-- ============================================================

-- Create triggers
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_users_updated_at') THEN
        CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_notes_updated_at') THEN
        CREATE TRIGGER trg_notes_updated_at BEFORE UPDATE ON notes FOR EACH ROW EXECUTE FUNCTION set_updated_at();
    END IF;
    -- (Add other triggers if needed)
END $$;

DO $$ 
BEGIN
    BEGIN
        ALTER TABLE chat_sessions ADD COLUMN opencode_session_id VARCHAR(255);
    EXCEPTION
        WHEN duplicate_column THEN RAISE NOTICE 'column opencode_session_id already exists in chat_sessions.';
    END;
END $$;
