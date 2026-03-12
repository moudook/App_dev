-- =============================================================================
-- SMARTY - MIGRATION v4.1.0 to v4.2.0
-- =============================================================================
-- Purpose: Migrate from v4.1.0 to v4.2.0 with proper foreign keys and junction tables
-- Date: March 12, 2026
-- IMPORTANT: Backup your database before running this migration!
-- =============================================================================

-- =============================================================================
-- STEP 0: CREATE BACKUP TABLES (Safety First)
-- =============================================================================

CREATE TABLE IF NOT EXISTS chat_messages_backup AS SELECT * FROM chat_messages;
CREATE TABLE IF NOT EXISTS calendar_events_backup AS SELECT * FROM calendar_events;

-- =============================================================================
-- STEP 1: CREATE JUNCTION TABLES
-- =============================================================================

CREATE TABLE IF NOT EXISTS chat_message_notes (
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    PRIMARY KEY (message_id, note_id)
);

CREATE TABLE IF NOT EXISTS calendar_event_notes (
    event_id UUID NOT NULL,
    note_id UUID NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    PRIMARY KEY (event_id, note_id)
);

-- =============================================================================
-- STEP 2: MIGRATE DATA FROM TEXT ARRAYS TO JUNCTION TABLES
-- =============================================================================

-- Migrate chat_messages.referenced_note_ids to chat_message_notes
-- Handle comma-separated UUID strings
DO $$
DECLARE
    msg_record RECORD;
    note_id_str TEXT;
    note_id_val UUID;
BEGIN
    -- Loop through all messages with referenced_note_ids
    FOR msg_record IN 
        SELECT id, referenced_note_ids 
        FROM chat_messages 
        WHERE referenced_note_ids IS NOT NULL 
        AND referenced_note_ids != ''
    LOOP
        -- Split comma-separated values and insert each relationship
        FOR note_id_str IN SELECT unnest(string_to_array(msg_record.referenced_note_ids, ','))
        LOOP
            BEGIN
                -- Try to convert to UUID and insert
                note_id_val := note_id_str::UUID;
                INSERT INTO chat_message_notes (message_id, note_id)
                VALUES (msg_record.id, note_id_val)
                ON CONFLICT (message_id, note_id) DO NOTHING;
            EXCEPTION WHEN INVALID_TEXT_REPRESENTATION THEN
                -- Skip invalid UUIDs
                RAISE NOTICE 'Skipping invalid UUID: % for message %', note_id_str, msg_record.id;
            END;
        END LOOP;
    END LOOP;
END $$;

-- Migrate calendar_events.linked_note_ids to calendar_event_notes (if column exists)
DO $$
BEGIN
    -- Check if linked_note_ids column exists
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'calendar_events' 
        AND column_name = 'linked_note_ids'
    ) THEN
        -- Perform migration similar to chat_messages
        EXECUTE $$
        DO $$
        DECLARE
            event_record RECORD;
            note_id_str TEXT;
            note_id_val UUID;
        BEGIN
            FOR event_record IN 
                SELECT id, linked_note_ids 
                FROM calendar_events 
                WHERE linked_note_ids IS NOT NULL 
                AND linked_note_ids != ''
            LOOP
                FOR note_id_str IN SELECT unnest(string_to_array(event_record.linked_note_ids, ','))
                LOOP
                    BEGIN
                        note_id_val := note_id_str::UUID;
                        INSERT INTO calendar_event_notes (event_id, note_id)
                        VALUES (event_record.id, note_id_val)
                        ON CONFLICT (event_id, note_id) DO NOTHING;
                    EXCEPTION WHEN INVALID_TEXT_REPRESENTATION THEN
                        RAISE NOTICE 'Skipping invalid UUID: % for event %', note_id_str, event_record.id;
                    END;
                END LOOP;
            END LOOP;
        END $$;
        $$;
    END IF;
END $$;

-- =============================================================================
-- STEP 3: ADD FOREIGN KEY CONSTRAINTS TO EXISTING TABLES
-- =============================================================================

-- Add foreign key to chat_sessions -> users
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.constraint_column_usage 
        WHERE table_name = 'chat_sessions' 
        AND constraint_name = 'fk_chat_sessions_user'
    ) THEN
        ALTER TABLE chat_sessions 
        ADD CONSTRAINT fk_chat_sessions_user 
        FOREIGN KEY (user_id) REFERENCES users(firebase_uid) ON DELETE CASCADE;
    END IF;
END $$;

-- Add foreign key to chat_messages -> users
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.constraint_column_usage 
        WHERE table_name = 'chat_messages' 
        AND constraint_name = 'fk_chat_messages_user'
    ) THEN
        ALTER TABLE chat_messages 
        ADD CONSTRAINT fk_chat_messages_user 
        FOREIGN KEY (user_id) REFERENCES users(firebase_uid) ON DELETE CASCADE;
    END IF;
END $$;

-- Add foreign key to chat_sessions -> agent_workflows
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.constraint_column_usage 
        WHERE table_name = 'chat_sessions' 
        AND constraint_name = 'fk_chat_sessions_workflow'
    ) THEN
        ALTER TABLE chat_sessions 
        ADD CONSTRAINT fk_chat_sessions_workflow 
        FOREIGN KEY (current_workflow_id) REFERENCES agent_workflows(id) ON DELETE SET NULL;
    END IF;
END $$;

-- Add foreign key to calendar_event_notes -> calendar_events
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.constraint_column_usage 
        WHERE table_name = 'calendar_event_notes' 
        AND constraint_name = 'fk_calendar_event_notes_event'
    ) THEN
        ALTER TABLE calendar_event_notes 
        ADD CONSTRAINT fk_calendar_event_notes_event 
        FOREIGN KEY (event_id) REFERENCES calendar_events(id) ON DELETE CASCADE;
    END IF;
END $$;

-- =============================================================================
-- STEP 4: ADD MISSING COLUMNS (If they don't exist)
-- =============================================================================

-- Add current_workflow_id to chat_sessions if missing
ALTER TABLE chat_sessions 
ADD COLUMN IF NOT EXISTS current_workflow_id UUID;

-- Add thinking_mode to chat_messages if missing
ALTER TABLE chat_messages 
ADD COLUMN IF NOT EXISTS thinking_mode TEXT;

-- Add is_streaming to chat_messages if missing
ALTER TABLE chat_messages 
ADD COLUMN IF NOT EXISTS is_streaming BOOLEAN DEFAULT false;

-- Add is_edited to chat_messages if missing
ALTER TABLE chat_messages 
ADD COLUMN IF NOT EXISTS is_edited BOOLEAN DEFAULT false;

-- Add edit_count to chat_messages if missing
ALTER TABLE chat_messages 
ADD COLUMN IF NOT EXISTS edit_count INTEGER DEFAULT 0;

-- =============================================================================
-- STEP 5: CREATE INDEXES FOR JUNCTION TABLES
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_chat_message_notes_message ON chat_message_notes(message_id);
CREATE INDEX IF NOT EXISTS idx_chat_message_notes_note ON chat_message_notes(note_id);

CREATE INDEX IF NOT EXISTS idx_calendar_event_notes_event ON calendar_event_notes(event_id);
CREATE INDEX IF NOT EXISTS idx_calendar_event_notes_note ON calendar_event_notes(note_id);

-- =============================================================================
-- STEP 6: VERIFICATION QUERIES (Run these to verify migration success)
-- =============================================================================

-- Verify junction table row counts match migrated data
SELECT 
    'chat_message_notes' as table_name, 
    COUNT(*) as row_count 
FROM chat_message_notes
UNION ALL
SELECT 
    'calendar_event_notes' as table_name, 
    COUNT(*) as row_count 
FROM calendar_event_notes;

-- Verify foreign keys are in place
SELECT 
    conname as constraint_name,
    conrelid::regclass as table_name,
    confrelid::regclass as references_table
FROM pg_constraint
WHERE contype = 'f'
AND (conrelid::regclass::text IN ('chat_sessions', 'chat_messages', 'calendar_event_notes')
     OR conrelid::regclass::text LIKE '%notes%');

-- =============================================================================
-- STEP 7: CLEANUP (Optional - remove old columns after verification)
-- =============================================================================

-- Uncomment these lines AFTER verifying the migration was successful:

-- Remove old referenced_note_ids column from chat_messages
-- ALTER TABLE chat_messages DROP COLUMN IF EXISTS referenced_note_ids;

-- Remove old linked_note_ids column from calendar_events (if it exists)
-- ALTER TABLE calendar_events DROP COLUMN IF EXISTS linked_note_ids;

-- =============================================================================
-- MIGRATION COMPLETE
-- =============================================================================
