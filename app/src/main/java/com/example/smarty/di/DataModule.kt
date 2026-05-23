package com.example.smarty.di

import android.content.Context
import com.example.smarty.data.local.*
import com.example.smarty.data.repository.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Dependency Injection Module for Database Components
 */
object DatabaseModule {
    fun provideSmartDatabase(context: Context): SmartyDatabase {
        return SmartyDatabase.getDatabase(context)
    }

    fun provideSmartDao(database: SmartyDatabase): SmartDatabaseDao {
        return database.smartDao()
    }

    fun provideLegacyNoteDao(database: SmartyDatabase): NoteDao {
        return database.noteDao()
    }

    fun provideLegacyCategoryDao(database: SmartyDatabase): CategoryDao {
        return database.categoryDao()
    }

    fun provideLegacyChatDao(database: SmartyDatabase): ChatDao {
        return database.chatDao()
    }

    fun provideLegacyCalendarDao(database: SmartyDatabase): CalendarDao {
        return database.calendarDao()
    }

    fun provideSyncQueueDao(database: SmartyDatabase): SyncQueueDao {
        return database.syncQueueDao()
    }

    fun provideLegacySyncQueueDao(database: SmartyDatabase): com.example.smarty.data.local.SyncQueueDao {
        return database.syncQueueDao()
    }

    fun provideCRDTManager(): CRDTManager {
        return CRDTManager()
    }

    fun provideOfflineFirstSyncManager(
        database: SmartyDatabase,
        crdtManager: CRDTManager,
    ): OfflineFirstSyncManager {
        return OfflineFirstSyncManager(database, crdtManager)
    }
}

/**
 * Repository Module
 */
object RepositoryModule {
    fun provideSmartRepository(
        database: SmartyDatabase,
        crdtManager: CRDTManager,
        offlineSyncManager: OfflineFirstSyncManager,
    ): SmartRepository {
        return SmartRepository(database, crdtManager, offlineSyncManager)
    }

    fun provideLegacyRepository(
        noteDao: NoteDao,
        categoryDao: CategoryDao,
        calendarDao: CalendarDao,
        context: Context,
    ): SmartyRepository {
        return SmartyRepository(
            noteDao = noteDao,
            categoryDao = categoryDao,
            calendarDao = calendarDao,
            noteVersionDao = null,
            context = context,
            syncRepository = null,
        )
    }
}

/**
 * Application Component
 */
object ApplicationModule {
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}
