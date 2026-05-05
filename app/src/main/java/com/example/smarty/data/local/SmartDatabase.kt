package com.example.smarty.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.smarty.core.domain.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Smart Database - Room database with creative integration features
 * Combines local Room with Supabase-compatible schema
 */
@Database(
    entities = [
        // Core entities
        Note::class,
        Category::class,
        ChatSession::class,
        ChatMessageEntity::class,
        ImpressedEntry::class,
        CalendarEvent::class,
        NoteVersion::class,
        NoteVersionEntity::class,
        SmartyTimer::class,
        CachedAIResponse::class,
        com.example.smarty.data.model.AIMemory::class,
        SyncQueueItem::class,
        ConflictRecord::class,
        
        // New integration entities
        UserEntity::class,
        SyncStateEntity::class,
        TagEntity::class,
        ChatFolderEntity::class,
        TaskEntity::class,
        ReasoningTraceEntity::class,
        ReasoningSummaryEntity::class,
        AgentCheckpointEntity::class,
        SearchHistoryEntity::class,
        UserFcmTokenEntity::class,
        DailyDigestEntity::class,
        SharedItemEntity::class,
        ChatMessageNote::class,
        CalendarEventNote::class,
        NoteTagEntity::class,
        NoteTaskEntity::class,
    ],
    version = 45, // Incremented from 38 to add new entities
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SmartDatabase : RoomDatabase() {
    
    // ============================================================
    // LEGACY DAOs (existing)
    // ============================================================
    abstract fun noteDao(): NoteDao
    abstract fun categoryDao(): CategoryDao
    abstract fun chatDao(): ChatDao
    abstract fun impressedLogDao(): ImpressedLogDao
    abstract fun calendarDao(): CalendarDao
    abstract fun noteVersionDao(): NoteVersionDao
    abstract fun timerDao(): TimerDao
    abstract fun aiCacheDao(): AICacheDao
    abstract fun aiMemoryDao(): com.example.smarty.features.chat.domain.memory.AIMemoryDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun chatMessageNotesDao(): ChatMessageNotesDao
    abstract fun calendarEventNotesDao(): CalendarEventNotesDao
    
    // ============================================================
    // NEW SMART DAO (creative integration)
    // ============================================================
    abstract fun smartDao(): SmartDatabaseDao
    
    // ============================================================
    // COMPANION OBJECT - Database builder with migrations
    // ============================================================
    companion object {
        private const val TAG = "SmartDatabase"
        
        @Volatile
        private var INSTANCE: SmartDatabase? = null
        
        fun getDatabase(context: android.content.Context): SmartDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SmartDatabase::class.java,
                    "Smarty_database_v45",
                )
                .addMigrations(
                    // Legacy migrations (from SmartyDatabase)
                    Migrations.MIGRATION_1_2,
                    Migrations.MIGRATION_2_3,
                    Migrations.MIGRATION_3_4,
                    Migrations.MIGRATION_4_5,
                    Migrations.MIGRATION_5_6,
                    Migrations.MIGRATION_6_7,
                    Migrations.MIGRATION_7_8,
                    Migrations.MIGRATION_8_9,
                    Migrations.MIGRATION_9_10,
                    Migrations.MIGRATION_10_11,
                    Migrations.MIGRATION_11_12,
                    Migrations.MIGRATION_12_13,
                    Migrations.MIGRATION_13_14,
                    Migrations.MIGRATION_14_15,
                    Migrations.MIGRATION_15_16,
                    Migrations.MIGRATION_16_17,
                    Migrations.MIGRATION_17_18,
                    Migrations.MIGRATION_18_19,
                    Migrations.MIGRATION_19_20,
                    Migrations.MIGRATION_20_21,
                    Migrations.MIGRATION_21_22,
                    Migrations.MIGRATION_22_23,
                    Migrations.MIGRATION_23_24,
                    Migrations.MIGRATION_24_25,
                    Migrations.MIGRATION_25_26,
                    Migrations.MIGRATION_26_27,
                    Migrations.MIGRATION_27_28,
                    Migrations.MIGRATION_28_29,
                    Migrations.MIGRATION_29_30,
                    Migrations.MIGRATION_30_31,
                    Migrations.MIGRATION_31_32,
                    Migrations.MIGRATION_32_33,
                    Migrations.MIGRATION_33_34,
                    Migrations.MIGRATION_34_35,
                    Migrations.MIGRATION_35_36,
                    Migrations.MIGRATION_36_37,
                    Migrations.MIGRATION_37_38,
                    // New migrations for v45
                    MIGRATION_38_39,
                    MIGRATION_39_40,
                    MIGRATION_40_41,
                    MIGRATION_41_42,
                    MIGRATION_42_43,
                    MIGRATION_43_44,
                    MIGRATION_44_45,
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
        
        // ============================================================
        // MIGRATION 38 -> 39: Add users table
        // ============================================================
        private val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS users (
                        id TEXT NOT NULL,
                        firebase_uid TEXT NOT NULL,
                        email TEXT,
                        display_name TEXT,
                        avatar_url TEXT,
                        is_active INTEGER NOT NULL DEFAULT 1,
                        is_premium INTEGER NOT NULL DEFAULT 0,
                        subscription_expires_at INTEGER,
                        feature_flags TEXT NOT NULL DEFAULT '{}',
                        sync_state TEXT NOT NULL DEFAULT 'PENDING',
                        device_fingerprint TEXT,
                        last_device_id TEXT,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        last_login_at INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (id)
                    )
                """)
                database.execSQL("CREATE INDEX idx_users_firebase_uid ON users(firebase_uid)")
                database.execSQL("CREATE INDEX idx_users_email ON users(email)")
                database.execSQL("CREATE INDEX idx_users_is_active ON users(is_active)")
            }
        }
        
        // ============================================================
        // MIGRATION 39 -> 40: Add sync_state table
        // ============================================================
        private val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_state (
                        user_id TEXT NOT NULL PRIMARY KEY,
                        last_sync_at INTEGER,
                        last_pull_at INTEGER,
                        last_push_at INTEGER,
                        pending_operations INTEGER NOT NULL DEFAULT 0,
                        conflict_count INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                """)
            }
        }
        
        // ============================================================
        // MIGRATION 40 -> 41: Add tags and note_tags tables
        // ============================================================
        private val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS tags (
                        id TEXT NOT NULL PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        color TEXT NOT NULL DEFAULT '#6200EE',
                        usage_count INTEGER NOT NULL DEFAULT 0,
                        tag_type TEXT NOT NULL DEFAULT 'MANUAL',
                        confidence_score REAL NOT NULL DEFAULT 1.0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX idx_tags_user ON tags(user_id)")
                database.execSQL("CREATE INDEX idx_tags_user_name ON tags(user_id, name)")
                
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS note_tags (
                        note_id TEXT NOT NULL,
                        tag_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        assigned_by TEXT NOT NULL DEFAULT 'user',
                        confidence_score REAL NOT NULL DEFAULT 1.0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (note_id, tag_id),
                        FOREIGN KEY(note_id) REFERENCES notes(id) ON DELETE CASCADE,
                        FOREIGN KEY(tag_id) REFERENCES tags(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX idx_note_tags_note ON note_tags(note_id)")
                database.execSQL("CREATE INDEX idx_note_tags_tag ON note_tags(tag_id)")
                database.execSQL("CREATE INDEX idx_note_tags_user ON note_tags(user_id)")
            }
        }
        
        // ============================================================
        // MIGRATION 41 -> 42: Add chat_folders table
        // ============================================================
        private val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS chat_folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        color TEXT NOT NULL DEFAULT '#6200EE',
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX idx_chat_folders_user ON chat_folders(user_id)")
                
                // Add folder_id to chat_sessions
                database.execSQL("ALTER TABLE chat_sessions ADD COLUMN folder_id TEXT")
            }
        }
        
        // ============================================================
        // MIGRATION 42 -> 43: Add tasks and note_tasks tables
        // ============================================================
        private val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        session_id TEXT,
                        note_id TEXT,
                        title TEXT NOT NULL,
                        description TEXT,
                        status TEXT NOT NULL DEFAULT 'TODO',
                        priority INTEGER NOT NULL DEFAULT 2,
                        due_date INTEGER,
                        completed_at INTEGER,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        is_recurring INTEGER NOT NULL DEFAULT 0,
                        recurrence_rule TEXT,
                        metadata TEXT NOT NULL DEFAULT '{}',
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        deleted_at INTEGER,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX idx_tasks_user ON tasks(user_id)")
                database.execSQL("CREATE INDEX idx_tasks_status ON tasks(status)")
                
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS note_tasks (
                        note_id TEXT NOT NULL,
                        task_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (note_id, task_id),
                        FOREIGN KEY(note_id) REFERENCES notes(id) ON DELETE CASCADE,
                        FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX idx_note_tasks_note ON note_tasks(note_id)")
                database.execSQL("CREATE INDEX idx_note_tasks_task ON note_tasks(task_id)")
            }
        }
        
        // ============================================================
        // MIGRATION 43 -> 44: Add reasoning tables
        // ============================================================
        private val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS reasoning_traces (
                        id TEXT NOT NULL PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        message_id TEXT,
                        user_id TEXT NOT NULL,
                        step_index INTEGER NOT NULL,
                        step_type TEXT NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        entity_type TEXT,
                        entity_id TEXT,
                        input_data TEXT,
                        output_data TEXT,
                        confidence_score REAL NOT NULL DEFAULT 0.5,
                        importance_score REAL NOT NULL DEFAULT 0.5,
                        is_final INTEGER NOT NULL DEFAULT 0,
                        was_revised INTEGER NOT NULL DEFAULT 0,
                        revised_by_trace_id TEXT,
                        token_count INTEGER NOT NULL DEFAULT 0,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        metadata TEXT NOT NULL DEFAULT '{}',
                        created_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX idx_reasoning_user ON reasoning_traces(user_id)")
                database.execSQL("CREATE INDEX idx_reasoning_session ON reasoning_traces(session_id)")
                database.execSQL("CREATE INDEX idx_reasoning_entity ON reasoning_traces(entity_type, entity_id)")
                
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS reasoning_summaries (
                        id TEXT NOT NULL PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        message_id TEXT,
                        user_id TEXT NOT NULL,
                        one_liner TEXT NOT NULL,
                        brief_summary TEXT NOT NULL,
                        detailed_summary TEXT NOT NULL,
                        total_steps INTEGER NOT NULL DEFAULT 0,
                        total_duration_ms INTEGER NOT NULL DEFAULT 0,
                        total_tokens INTEGER NOT NULL DEFAULT 0,
                        confidence_score REAL NOT NULL DEFAULT 0.5,
                        complexity_score REAL NOT NULL DEFAULT 0.5,
                        reasoning_type TEXT NOT NULL,
                        tags TEXT NOT NULL DEFAULT '[]',
                        linked_entities TEXT NOT NULL DEFAULT '[]',
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX idx_summary_user ON reasoning_summaries(user_id)")
                database.execSQL("CREATE INDEX idx_summary_session ON reasoning_summaries(session_id)")
            }
        }
        
        // ============================================================
        // MIGRATION 44 -> 45: Add agent checkpoints, search, FCM, digests, shared items
        // ============================================================
        private val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Agent checkpoints
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS agent_checkpoints (
                        id TEXT NOT NULL PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        workflow_id TEXT,
                        state_json TEXT NOT NULL,
                        context_json TEXT,
                        memory_json TEXT,
                        version INTEGER NOT NULL DEFAULT 1,
                        checkpoint_type TEXT NOT NULL DEFAULT 'MANUAL',
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX idx_checkpoint_user ON agent_checkpoints(user_id)")
                database.execSQL("CREATE INDEX idx_checkpoint_session ON agent_checkpoints(session_id)")
                
                // Search history
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS search_history (
                        id TEXT NOT NULL PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        query TEXT NOT NULL,
                        search_scope TEXT NOT NULL DEFAULT 'all',
                        result_count INTEGER NOT NULL DEFAULT 0,
                        entities_found TEXT NOT NULL DEFAULT '[]',
                        search_type TEXT NOT NULL DEFAULT 'TEXT',
                        ai_enhanced INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX idx_search_user ON search_history(user_id)")
                
                // FCM tokens
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_fcm_tokens (
                        id TEXT NOT NULL PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        token TEXT NOT NULL,
                        device_name TEXT,
                        device_id TEXT,
                        platform TEXT NOT NULL DEFAULT 'android',
                        last_used_at INTEGER NOT NULL DEFAULT 0,
                        is_active INTEGER NOT NULL DEFAULT 1,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX idx_fcm_user ON user_fcm_tokens(user_id)")
                database.execSQL("CREATE INDEX idx_fcm_token ON user_fcm_tokens(token)")
                
                // Daily digests
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS daily_digests (
                        id TEXT NOT NULL PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        digest_date INTEGER NOT NULL,
                        digest_type TEXT NOT NULL DEFAULT 'DAILY',
                        content TEXT NOT NULL,
                        notification_sent INTEGER NOT NULL DEFAULT 0,
                        calendar_event_id TEXT,
                        linked_note_ids TEXT NOT NULL DEFAULT '[]',
                        generated_by_ai INTEGER NOT NULL DEFAULT 1,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX idx_digest_user ON daily_digests(user_id)")
                
                // Shared items
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS shared_items (
                        id TEXT NOT NULL PRIMARY KEY,
                        owner_id TEXT NOT NULL,
                        shared_with_id TEXT,
                        item_type TEXT NOT NULL,
                        item_id TEXT NOT NULL,
                        permission TEXT NOT NULL DEFAULT 'VIEW',
                        share_token TEXT,
                        expires_at INTEGER,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(owner_id) REFERENCES users(id) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX idx_shared_owner ON shared_items(owner_id)")
                database.execSQL("CREATE INDEX idx_shared_token ON shared_items(share_token)")
            }
        }
    }
}
