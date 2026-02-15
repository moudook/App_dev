-- Daily Digests Schema v2.1
-- Contains: daily_digests, digest_preferences, user_fcm_tokens tables

-- Daily Digests Table
CREATE TABLE IF NOT EXISTS daily_digests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    digest_date DATE NOT NULL,
    digest_type TEXT NOT NULL DEFAULT 'daily',
    summary TEXT NOT NULL,
    key_insights JSONB,
    action_items JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, digest_date, digest_type)
);

CREATE INDEX IF NOT EXISTS idx_digests_user ON daily_digests(user_id);
CREATE INDEX IF NOT EXISTS idx_digests_date ON daily_digests(digest_date);
CREATE INDEX IF NOT EXISTS idx_digests_user_date ON daily_digests(user_id, digest_date DESC);

-- Digest Preferences Table (with correct column names)
CREATE TABLE IF NOT EXISTS digest_preferences (
    user_id TEXT PRIMARY KEY,
    
    -- Daily digest settings
    daily_enabled BOOLEAN DEFAULT TRUE,
    daily_time TIME DEFAULT '07:00:00',
    
    -- Weekly digest settings
    weekly_enabled BOOLEAN DEFAULT TRUE,
    weekly_day INT DEFAULT 0,
    weekly_time TIME DEFAULT '08:00:00',
    
    -- Notification settings
    push_notification BOOLEAN DEFAULT TRUE,
    calendar_logging BOOLEAN DEFAULT TRUE,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- FCM Tokens Table
CREATE TABLE IF NOT EXISTS user_fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    token TEXT NOT NULL UNIQUE,
    device_name TEXT,
    device_id TEXT,
    last_used_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fcm_tokens_user ON user_fcm_tokens(user_id);

-- Migrations for existing databases
-- Add user_id to daily_digests if not present
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'daily_digests' AND column_name = 'user_id') THEN
        ALTER TABLE daily_digests ADD COLUMN user_id TEXT NOT NULL;
    END IF;
END $$;

-- Add user_id to digest_preferences if not present (for multi-tenant)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'digest_preferences' AND column_name = 'user_id') THEN
        ALTER TABLE digest_preferences ADD COLUMN user_id TEXT PRIMARY KEY;
    END IF;
END $$;

-- Add user_id to user_fcm_tokens if not present
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'user_fcm_tokens' AND column_name = 'user_id') THEN
        ALTER TABLE user_fcm_tokens ADD COLUMN user_id TEXT NOT NULL;
    END IF;
END $$;

-- Migration: Add missing columns to digest_preferences if not present
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'digest_preferences' AND column_name = 'daily_enabled') THEN
        ALTER TABLE digest_preferences ADD COLUMN daily_enabled BOOLEAN DEFAULT TRUE;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'digest_preferences' AND column_name = 'daily_time') THEN
        ALTER TABLE digest_preferences ADD COLUMN daily_time TIME DEFAULT '07:00:00';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'digest_preferences' AND column_name = 'weekly_enabled') THEN
        ALTER TABLE digest_preferences ADD COLUMN weekly_enabled BOOLEAN DEFAULT TRUE;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'digest_preferences' AND column_name = 'weekly_day') THEN
        ALTER TABLE digest_preferences ADD COLUMN weekly_day INT DEFAULT 0;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'digest_preferences' AND column_name = 'weekly_time') THEN
        ALTER TABLE digest_preferences ADD COLUMN weekly_time TIME DEFAULT '08:00:00';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'digest_preferences' AND column_name = 'push_notification') THEN
        ALTER TABLE digest_preferences ADD COLUMN push_notification BOOLEAN DEFAULT TRUE;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'digest_preferences' AND column_name = 'calendar_logging') THEN
        ALTER TABLE digest_preferences ADD COLUMN calendar_logging BOOLEAN DEFAULT TRUE;
    END IF;
END $$;
