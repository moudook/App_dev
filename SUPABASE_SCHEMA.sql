-- ============================================================
-- SUPABASE COMPATIBLE DATABASE SCHEMA v11.0.0
-- Optimized: vector search, FTS, sync-ready, unified constraints, RLS policies
-- ============================================================

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- DROP ALL TABLES CLEANLY (SAFE ORDER)
-- ============================================================

DROP TABLE IF EXISTS shared_items CASCADE;
DROP TABLE IF EXISTS note_versions CASCADE;
DROP TABLE IF EXISTS generated_images CASCADE;
DROP TABLE IF EXISTS notifications CASCADE;
DROP TABLE IF EXISTS agent_context CASCADE;
DROP TABLE IF EXISTS user_devices CASCADE;
DROP TABLE IF EXISTS search_history CASCADE;
DROP TABLE IF EXISTS agent_checkpoints CASCADE;
DROP TABLE IF EXISTS reasoning_summaries CASCADE;
DROP TABLE IF EXISTS reasoning_traces CASCADE;
DROP TABLE IF EXISTS agent_traces CASCADE;
DROP TABLE IF EXISTS calendar_event_notes CASCADE;
DROP TABLE IF EXISTS calendar_events CASCADE;
DROP TABLE IF EXISTS daily_digests CASCADE;
DROP TABLE IF EXISTS user_fcm_tokens CASCADE;
DROP TABLE IF EXISTS timers CASCADE;
DROP TABLE IF EXISTS chat_message_notes CASCADE;
DROP TABLE IF EXISTS chat_messages CASCADE;
DROP TABLE IF EXISTS chat_sessions CASCADE;
DROP TABLE IF EXISTS chat_folders CASCADE;
DROP TABLE IF EXISTS note_tags CASCADE;
DROP TABLE IF EXISTS tags CASCADE;
DROP TABLE IF EXISTS note_tasks CASCADE;
DROP TABLE IF EXISTS tasks CASCADE;
DROP TABLE IF EXISTS notes CASCADE;
DROP TABLE IF EXISTS note_stacks CASCADE;
DROP TABLE IF EXISTS stacks CASCADE;
DROP TABLE IF EXISTS categories CASCADE;
DROP TABLE IF EXISTS digest_preferences CASCADE;
DROP TABLE IF EXISTS sync_state CASCADE;
DROP TABLE IF EXISTS users CASCADE;
DROP TABLE IF EXISTS vector_embeddings CASCADE;
DROP TABLE IF EXISTS ai_cache CASCADE;
DROP TABLE IF EXISTS user_vaults CASCADE;
DROP TABLE IF EXISTS impressed_log CASCADE;

-- Drop trigger function if exists
DROP FUNCTION IF EXISTS set_updated_at() CASCADE;

-- ============================================================
-- AUTO updated_at TRIGGER FUNCTION
-- ============================================================

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- CORE TABLES
-- ============================================================

CREATE TABLE users (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    firebase_uid          TEXT UNIQUE NOT NULL,
    email                 TEXT,
    display_name          TEXT,
    avatar_url            TEXT,
    is_active             BOOLEAN NOT NULL DEFAULT true,
    is_premium            BOOLEAN NOT NULL DEFAULT false,
    subscription_expires_at TIMESTAMPTZ,
    feature_flags         JSONB NOT NULL DEFAULT '{}',
    sync_state            TEXT DEFAULT 'PENDING',
    device_fingerprint    TEXT,
    last_device_id        TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ────────────────────────────────────────────────────────────

CREATE TABLE sync_state (
    user_id     UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    last_sync_at TIMESTAMPTZ,
    last_pull_at TIMESTAMPTZ,
    last_push_at TIMESTAMPTZ,
    pending_operations INTEGER DEFAULT 0,
    conflict_count INTEGER DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_sync_state_updated_at BEFORE UPDATE ON sync_state FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ────────────────────────────────────────────────────────────

CREATE TABLE digest_preferences (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    enabled         BOOLEAN NOT NULL DEFAULT true,
    frequency       TEXT NOT NULL DEFAULT 'daily' CHECK (frequency IN ('daily','weekly','monthly')),
    delivery_hour   INTEGER NOT NULL DEFAULT 9 CHECK (delivery_hour BETWEEN 0 AND 23),
    delivery_minute INTEGER NOT NULL DEFAULT 0 CHECK (delivery_minute BETWEEN 0 AND 59),
    timezone        TEXT NOT NULL DEFAULT 'UTC',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id)
);
CREATE TRIGGER trg_digest_preferences_updated_at BEFORE UPDATE ON digest_preferences FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ────────────────────────────────────────────────────────────

CREATE TABLE categories (
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
CREATE UNIQUE INDEX idx_categories_user_name ON categories(user_id, lower(name));
CREATE TRIGGER trg_categories_updated_at BEFORE UPDATE ON categories FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ────────────────────────────────────────────────────────────

CREATE TABLE stacks (
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
CREATE INDEX idx_stacks_user ON stacks(user_id);
CREATE TRIGGER trg_stacks_updated_at BEFORE UPDATE ON stacks FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ────────────────────────────────────────────────────────────

CREATE TABLE notes (
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
    content_hash            TEXT,
    processed_content_hash  TEXT,
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
CREATE INDEX idx_notes_user ON notes(user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_notes_user_updated ON notes(user_id, updated_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_notes_category ON notes(category_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_notes_stack ON notes(stack_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_notes_content_hash ON notes(user_id, content_hash) WHERE content_hash IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_notes_fts ON notes USING gin (to_tsvector('english', coalesce(title,'') || ' ' || coalesce(content,'')));
CREATE TRIGGER trg_notes_updated_at BEFORE UPDATE ON notes FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ────────────────────────────────────────────────────────────

CREATE TABLE tasks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id      UUID,
    note_id         UUID REFERENCES notes(id) ON DELETE SET NULL,
    title           TEXT NOT NULL,
    description     TEXT,
    status          TEXT NOT NULL DEFAULT 'todo' CHECK (status IN ('todo','in_progress','done','cancelled','COMPLETED','BLOCKED')),
    priority        INTEGER NOT NULL DEFAULT 2 CHECK (priority BETWEEN 0 AND 5),
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
CREATE INDEX idx_tasks_user ON tasks(user_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE TRIGGER trg_tasks_updated_at BEFORE UPDATE ON tasks FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ────────────────────────────────────────────────────────────

CREATE TABLE chat_folders (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    color      TEXT DEFAULT '#6200EE',
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_chat_folders_updated_at BEFORE UPDATE ON chat_folders FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ────────────────────────────────────────────────────────────

CREATE TABLE chat_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    folder_id       UUID REFERENCES chat_folders(id) ON DELETE SET NULL,
    title           TEXT,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    is_archived     BOOLEAN NOT NULL DEFAULT false,
    is_pinned       BOOLEAN NOT NULL DEFAULT false,
    model_used      TEXT,
    personality     TEXT,
    temperature     NUMERIC(3,2) DEFAULT 0.7,
    max_tokens      INTEGER DEFAULT 4096,
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
CREATE INDEX idx_chat_sessions_user ON chat_sessions(user_id, updated_at DESC);
CREATE TRIGGER trg_chat_sessions_updated_at BEFORE UPDATE ON chat_sessions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ────────────────────────────────────────────────────────────

CREATE TABLE chat_messages (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id        UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    parent_message_id UUID REFERENCES chat_messages(id) ON DELETE SET NULL,
    role              TEXT NOT NULL CHECK (role IN ('user','assistant','system','tool')),
    content           TEXT NOT NULL,
    content_hash      TEXT,
    thinking          TEXT,
    tool_calls        JSONB DEFAULT '[]'::jsonb,
    tool_call_id      TEXT,
    agent_steps_json  JSONB DEFAULT '[]'::jsonb,
    token_count       INTEGER DEFAULT 0,
    is_edited         BOOLEAN NOT NULL DEFAULT false,
    is_starred        BOOLEAN NOT NULL DEFAULT false,
    metadata          JSONB NOT NULL DEFAULT '{}',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_messages_session ON chat_messages(session_id, created_at ASC);
CREATE TRIGGER trg_chat_messages_updated_at BEFORE UPDATE ON chat_messages FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ────────────────────────────────────────────────────────────

CREATE TABLE tags (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    color           TEXT DEFAULT '#6200EE',
    tag_type        TEXT NOT NULL DEFAULT 'MANUAL',
    confidence_score DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    usage_count     INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_tags_user_lower_name ON tags(user_id, lower(name));
CREATE TRIGGER trg_tags_updated_at BEFORE UPDATE ON tags FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ============================================================
-- SYSTEM & AI TABLES
-- ============================================================

CREATE TABLE vector_embeddings (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content     TEXT NOT NULL,
    embedding   vector(1536) NOT NULL,
    metadata    JSONB NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_vector_embeddings_user ON vector_embeddings(user_id);
CREATE INDEX idx_vector_embeddings ON vector_embeddings USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ────────────────────────────────────────────────────────────

CREATE TABLE ai_cache (
    content_hash       TEXT PRIMARY KEY,
    json_response      TEXT NOT NULL,
    user_id            UUID REFERENCES users(id) ON DELETE CASCADE,
    created_at         BIGINT NOT NULL,
    expires_at         BIGINT NOT NULL,
    last_accessed_at    BIGINT NOT NULL
);
CREATE INDEX idx_ai_cache_expires ON ai_cache(expires_at);
CREATE INDEX idx_ai_cache_user ON ai_cache(user_id);

-- ────────────────────────────────────────────────────────────

CREATE TABLE agent_checkpoints (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL UNIQUE,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    workflow_id UUID,
    state_json JSONB NOT NULL,
    context_json JSONB,
    memory_json JSONB,
    version    INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_agent_checkpoints_updated_at BEFORE UPDATE ON agent_checkpoints FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE agent_context (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id  UUID,
    content     TEXT NOT NULL,
    metadata    JSONB NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_context_user ON agent_context(user_id, created_at DESC);
CREATE INDEX idx_agent_context_session ON agent_context(session_id);

-- ────────────────────────────────────────────────────────────

CREATE TABLE agent_traces (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id    UUID NOT NULL,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    step_name     TEXT,
    step_type     TEXT,
    content       TEXT,
    input_data    TEXT,
    output_data   TEXT,
    error_message TEXT,
    metadata      JSONB NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_traces_session ON agent_traces(session_id);
CREATE INDEX idx_agent_traces_user ON agent_traces(user_id, created_at DESC);

-- ────────────────────────────────────────────────────────────

CREATE TABLE reasoning_traces (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id          UUID NOT NULL,
    message_id          UUID,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    step_index          INTEGER NOT NULL,
    step_type           TEXT,
    title               TEXT,
    content             TEXT,
    confidence_score    DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    importance_score    DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    is_final            BOOLEAN NOT NULL DEFAULT false,
    was_revised         BOOLEAN NOT NULL DEFAULT false,
    revised_by_trace_id UUID,
    token_count         INTEGER NOT NULL DEFAULT 0,
    duration_ms         BIGINT NOT NULL DEFAULT 0,
    metadata            JSONB NOT NULL DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reasoning_traces_session ON reasoning_traces(session_id, step_index ASC);
CREATE INDEX idx_reasoning_traces_user ON reasoning_traces(user_id, created_at DESC);

-- ────────────────────────────────────────────────────────────

CREATE TABLE reasoning_summaries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       UUID NOT NULL,
    message_id       UUID,
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    one_liner        TEXT,
    brief_summary    TEXT,
    detailed_summary TEXT,
    total_steps      INTEGER NOT NULL DEFAULT 0,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    total_tokens     INTEGER NOT NULL DEFAULT 0,
    confidence_score DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    complexity_score DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    reasoning_type   TEXT,
    tags             TEXT[] NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_reasoning_summaries_updated_at BEFORE UPDATE ON reasoning_summaries FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX idx_reasoning_summaries_user ON reasoning_summaries(user_id, created_at DESC);

-- ────────────────────────────────────────────────────────────

CREATE TABLE impressed_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action_type TEXT NOT NULL,
    user_signal TEXT NOT NULL,
    content     TEXT,
    timestamp   BIGINT NOT NULL,
    metadata    JSONB NOT NULL DEFAULT '{}'
);
CREATE INDEX idx_impressed_log_user ON impressed_log(user_id, timestamp DESC);

-- ============================================================
-- JUNCTION & ATTACHMENT TABLES
-- ============================================================

CREATE TABLE note_tags (
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    tag_id  UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_by TEXT NOT NULL DEFAULT 'user',
    confidence_score DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (note_id, tag_id)
);
CREATE INDEX idx_note_tags_user ON note_tags(user_id);
CREATE INDEX idx_note_tags_tag ON note_tags(tag_id);
CREATE INDEX idx_note_tags_note ON note_tags(note_id);

CREATE TABLE note_stacks (
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    stack_id UUID NOT NULL REFERENCES stacks(id) ON DELETE CASCADE,
    PRIMARY KEY (note_id, stack_id)
);

CREATE TABLE chat_message_notes (
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    note_id    UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    PRIMARY KEY (message_id, note_id)
);

CREATE TABLE note_tasks (
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (note_id, task_id)
);

CREATE TABLE calendar_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    linked_note_id   UUID REFERENCES notes(id) ON DELETE SET NULL,
    google_event_id  TEXT,
    title            TEXT NOT NULL,
    description      TEXT,
    start_time       TIMESTAMPTZ NOT NULL,
    end_time         TIMESTAMPTZ,
    is_all_day       BOOLEAN NOT NULL DEFAULT false,
    is_event_private BOOLEAN NOT NULL DEFAULT false,
    status           TEXT NOT NULL DEFAULT 'confirmed' CHECK (status IN ('confirmed','tentative','cancelled')),
    visibility       TEXT NOT NULL DEFAULT 'private' CHECK (visibility IN ('public','private')),
    reminders        JSONB NOT NULL DEFAULT '[]',
    attendees        JSONB NOT NULL DEFAULT '[]',
    location         TEXT,
    metadata         JSONB NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_calendar_events_updated_at BEFORE UPDATE ON calendar_events FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE calendar_event_notes (
    event_id UUID NOT NULL REFERENCES calendar_events(id) ON DELETE CASCADE,
    note_id  UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, note_id)
);

-- ============================================================
-- REMAINING ENTITIES
-- ============================================================

CREATE TABLE notifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        TEXT NOT NULL,
    title       TEXT NOT NULL,
    body        TEXT,
    data        JSONB NOT NULL DEFAULT '{}',
    is_read     BOOLEAN NOT NULL DEFAULT false,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications(user_id, is_read, created_at DESC);

CREATE TABLE daily_digests (
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
    UNIQUE(user_id, digest_date, digest_type)
);

CREATE TABLE generated_images (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id   UUID REFERENCES chat_sessions(id) ON DELETE SET NULL,
    prompt       TEXT NOT NULL,
    krea_job_id  TEXT NOT NULL UNIQUE,
    status       TEXT NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','processing','done','failed')),
    image_url    TEXT,
    supabase_url TEXT,
    image_bytes  BYTEA,
    content_type TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_generated_images_updated_at BEFORE UPDATE ON generated_images FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX idx_generated_images_user ON generated_images(user_id, created_at DESC);

CREATE TABLE user_vaults (
    user_id         UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    encrypted_blob  TEXT NOT NULL,
    version         INTEGER NOT NULL DEFAULT 1,
    updated_at      BIGINT NOT NULL
);

CREATE TABLE user_fcm_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token        TEXT NOT NULL,
    device_name  TEXT,
    device_id    TEXT,
    platform     TEXT NOT NULL DEFAULT 'android',
    is_active    BOOLEAN NOT NULL DEFAULT true,
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, token)
);

CREATE TABLE timers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    duration_ms BIGINT NOT NULL CHECK (duration_ms > 0),
    trigger_at  TIMESTAMPTZ NOT NULL,
    is_alarm    BOOLEAN NOT NULL DEFAULT false,
    is_active   BOOLEAN NOT NULL DEFAULT true,
    repeat      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_devices (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name    TEXT,
    device_type    TEXT NOT NULL DEFAULT 'android',
    push_token     TEXT,
    last_active_at TIMESTAMPTZ,
    app_version    TEXT,
    metadata       JSONB NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, device_name)
);
CREATE TRIGGER trg_user_devices_updated_at BEFORE UPDATE ON user_devices FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE search_history (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    query       TEXT NOT NULL,
    search_scope TEXT NOT NULL DEFAULT 'all' CHECK (search_scope IN ('all','notes','chat','research','tasks')),
    result_count INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_search_history_user ON search_history(user_id, created_at DESC);

CREATE TABLE shared_items (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    shared_with_id UUID REFERENCES users(id) ON DELETE CASCADE,
    item_type      TEXT NOT NULL CHECK (item_type IN ('note','session','calendar_event','task')),
    item_id        UUID NOT NULL,
    permission     TEXT NOT NULL DEFAULT 'view' CHECK (permission IN ('view','edit','admin')),
    share_token    TEXT UNIQUE,
    expires_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE note_versions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    note_id           UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    title             TEXT NOT NULL,
    content           TEXT NOT NULL,
    summary           TEXT,
    version_no        INTEGER NOT NULL,
    change_description TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(note_id, version_no)
);

-- ============================================================
-- ROW LEVEL SECURITY (RLS) POLICIES
-- ============================================================

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can view own data" ON users FOR SELECT USING (auth.uid()::text = firebase_uid);

ALTER TABLE sync_state ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own sync state" ON sync_state FOR ALL USING (user_id = auth.uid());

ALTER TABLE notes ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can CRUD own notes" ON notes FOR ALL USING (user_id = auth.uid());

ALTER TABLE categories ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can CRUD own categories" ON categories FOR ALL USING (user_id = auth.uid());

ALTER TABLE stacks ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can CRUD own stacks" ON stacks FOR ALL USING (user_id = auth.uid());

ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can CRUD own tasks" ON tasks FOR ALL USING (user_id = auth.uid());

ALTER TABLE chat_sessions ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can CRUD own chat sessions" ON chat_sessions FOR ALL USING (user_id = auth.uid());

ALTER TABLE chat_messages ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can CRUD own chat messages" ON chat_messages FOR ALL USING (user_id = auth.uid());

ALTER TABLE chat_folders ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can CRUD own chat folders" ON chat_folders FOR ALL USING (user_id = auth.uid());

ALTER TABLE tags ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can CRUD own tags" ON tags FOR ALL USING (user_id = auth.uid());

ALTER TABLE note_tags ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own note tags" ON note_tags FOR ALL USING (user_id = auth.uid());

ALTER TABLE note_stacks ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own note stacks" ON note_stacks FOR ALL USING (note_id IN (SELECT id FROM notes WHERE user_id = auth.uid()));

ALTER TABLE note_tasks ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own note tasks" ON note_tasks FOR ALL USING (user_id = auth.uid());

ALTER TABLE calendar_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can CRUD own calendar events" ON calendar_events FOR ALL USING (user_id = auth.uid());

ALTER TABLE calendar_event_notes ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own calendar note links" ON calendar_event_notes FOR ALL USING (event_id IN (SELECT id FROM calendar_events WHERE user_id = auth.uid()));

ALTER TABLE chat_message_notes ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own chat note links" ON chat_message_notes FOR ALL USING (message_id IN (SELECT id FROM chat_messages WHERE user_id = auth.uid()));

ALTER TABLE note_versions ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can view own note versions" ON note_versions FOR ALL USING (note_id IN (SELECT id FROM notes WHERE user_id = auth.uid()));

ALTER TABLE shared_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own shared items" ON shared_items FOR ALL USING (owner_id = auth.uid());

ALTER TABLE daily_digests ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can view own digests" ON daily_digests FOR SELECT USING (user_id = auth.uid());

ALTER TABLE digest_preferences ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own digest preferences" ON digest_preferences FOR ALL USING (user_id = auth.uid());

ALTER TABLE generated_images ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can CRUD own generated images" ON generated_images FOR ALL USING (user_id = auth.uid());

ALTER TABLE timers ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can CRUD own timers" ON timers FOR ALL USING (user_id = auth.uid());

ALTER TABLE user_devices ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own devices" ON user_devices FOR ALL USING (user_id = auth.uid());

ALTER TABLE user_fcm_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own FCM tokens" ON user_fcm_tokens FOR ALL USING (user_id = auth.uid());

ALTER TABLE vector_embeddings ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own embeddings" ON vector_embeddings FOR ALL USING (user_id = auth.uid());

ALTER TABLE ai_cache ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can view own AI cache" ON ai_cache FOR SELECT USING (user_id = auth.uid());

ALTER TABLE agent_traces ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can view own agent traces" ON agent_traces FOR SELECT USING (user_id = auth.uid());

ALTER TABLE reasoning_traces ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can view own reasoning traces" ON reasoning_traces FOR SELECT USING (user_id = auth.uid());

ALTER TABLE reasoning_summaries ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can view own reasoning summaries" ON reasoning_summaries FOR SELECT USING (user_id = auth.uid());

ALTER TABLE agent_checkpoints ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own agent checkpoints" ON agent_checkpoints FOR ALL USING (user_id = auth.uid());

ALTER TABLE agent_context ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own agent context" ON agent_context FOR ALL USING (user_id = auth.uid());

ALTER TABLE search_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own search history" ON search_history FOR ALL USING (user_id = auth.uid());

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own notifications" ON notifications FOR ALL USING (user_id = auth.uid());

ALTER TABLE impressed_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can view own impressed log" ON impressed_log FOR SELECT USING (user_id = auth.uid());

ALTER TABLE user_vaults ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own vaults" ON user_vaults FOR ALL USING (user_id = auth.uid());

-- ============================================================
-- COMPLETE
-- ============================================================
