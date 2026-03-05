# Supabase Schema Update Guide

## Do You Need to Update Supabase?

### ✅ You DON'T need to update if:
- Your server's runtime migration already ran successfully
- The `chat_messages` table already has a `thinking` column
- You're using the server's built-in migration (DatabaseFactory.kt)

### ⚠️ You DO need to update if:
- You see errors about missing `thinking` column
- You're doing a fresh Supabase installation
- You want to ensure the schema is correct from the start

---

## How to Check if thinking Column Exists

### Option 1: Via Supabase SQL Editor
1. Go to your Supabase project: https://supabase.com/dashboard
2. Select your project
3. Go to **SQL Editor** (left sidebar)
4. Run this query:
   ```sql
   SELECT column_name, data_type, is_nullable
   FROM information_schema.columns
   WHERE table_name = 'chat_messages'
   AND column_name = 'thinking';
   ```

**Expected Result**:
- If you see a row with `thinking | text | YES` → Column exists ✅
- If you see **no rows** → Column missing, needs migration ⚠️

### Option 2: Via Supabase Table Editor
1. Go to **Table Editor** (left sidebar)
2. Click on `chat_messages` table
3. Look for `thinking` column in the column list
4. If it exists → You're good ✅
5. If missing → Run migration below ⚠️

---

## Migration Script

### File: `supabase_add_thinking_column.sql`

I've created a migration script for you at:
**`C:\Users\gbust\Smarty\supabase_add_thinking_column.sql`**

**Contents**:
```sql
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
```

---

## How to Run the Migration

### Step 1: Open Supabase Dashboard
1. Go to: https://supabase.com/dashboard
2. Select your project

### Step 2: Open SQL Editor
1. Click **SQL Editor** in left sidebar
2. Click **New Query**

### Step 3: Run Migration
1. Copy contents of `supabase_add_thinking_column.sql`
2. Paste into SQL Editor
3. Click **Run** or press `Ctrl + Enter`

### Step 4: Verify Result
You should see:
```
NOTICE: thinking column already exists in chat_messages table
```
OR
```
NOTICE: Added thinking column to chat_messages table
```

And a result table showing:
```
column_name | data_type | is_nullable | column_default
thinking    | text      | YES         | NULL
```

---

## Alternative: Manual SQL

If you prefer to run SQL manually:

```sql
-- Simple version
ALTER TABLE chat_messages 
ADD COLUMN IF NOT EXISTS thinking TEXT DEFAULT NULL;
```

This is safe to run multiple times - it will only add the column if it doesn't exist.

---

## What About init-db.sql?

The `init-db.sql` file in your project already includes the `thinking` column:

```sql
CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL DEFAULT '',
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    thinking TEXT DEFAULT NULL,  -- ✅ Included
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

So if you're setting up a **new** Supabase database, just run `init-db.sql` and you're good!

---

## Server Runtime Migration

Your server also has automatic migration in `DatabaseFactory.kt`:

```kotlin
"ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS thinking TEXT",
```

This runs automatically when the server starts, so the column should already exist if your server has been running.

---

## Summary

| Scenario | Action Needed |
|----------|---------------|
| Existing Supabase with running server | ❌ No action (runtime migration already ran) |
| Fresh Supabase installation | ✅ Run `init-db.sql` OR manual migration |
| Seeing "thinking column" errors | ✅ Run migration script |
| Want to be 100% sure | ✅ Run verification query |

---

## Quick Commands

### Check if column exists:
```sql
SELECT EXISTS (
    SELECT 1 
    FROM information_schema.columns 
    WHERE table_name = 'chat_messages' 
    AND column_name = 'thinking'
) AS thinking_column_exists;
```

**Expected**: `thinking_column_exists = true`

### Add column if missing:
```sql
ALTER TABLE chat_messages 
ADD COLUMN IF NOT EXISTS thinking TEXT DEFAULT NULL;
```

### Verify after adding:
```sql
\d chat_messages  -- If using psql
-- OR
SELECT column_name, data_type, is_nullable 
FROM information_schema.columns 
WHERE table_name = 'chat_messages'
ORDER BY ordinal_position;
```

---

## TL;DR

**Most likely you DON'T need to do anything** because:
1. Server runtime migration already added the column, OR
2. You can run the migration script to be safe

The migration is **safe to run multiple times** - it won't break anything if the column already exists.

---

*Generated: March 5, 2026*
*Migration Status: ✅ Ready to run*
*Safe to run: YES (idempotent)*
