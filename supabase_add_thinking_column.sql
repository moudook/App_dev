-- Supabase Migration: Add thinking column to chat_messages
-- Run this in your Supabase SQL Editor if the thinking column doesn't exist

-- Check if thinking column exists, add if missing
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'chat_messages' 
        AND column_name = 'thinking'
    ) THEN
        ALTER TABLE chat_messages 
        ADD COLUMN thinking TEXT DEFAULT NULL;
        
        RAISE NOTICE 'Added thinking column to chat_messages table';
    ELSE
        RAISE NOTICE 'thinking column already exists in chat_messages table';
    END IF;
END $$;

-- Verify the column was added
SELECT column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_name = 'chat_messages'
AND column_name = 'thinking';
