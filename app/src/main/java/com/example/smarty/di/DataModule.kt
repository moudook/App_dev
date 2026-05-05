package com.example.smarty.di

import android.content.Context
import androidx.room.Room
import com.example.smarty.data.local.*
import com.example.smarty.data.remote.RemoteAgentService
import com.example.smarty.data.repository.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Dependency Injection Module for Database Components
 */
object DatabaseModule {

    fun provideSmartDatabase(context: Context): SmartDatabase {
        return SmartDatabase.getDatabase(context)
    }

    fun provideSmartDao(database: SmartDatabase): SmartDatabaseDao {
        return database.smartDao()
    }

    fun provideLegacyNoteDao(database: SmartDatabase): NoteDao {
        return database.noteDao()
    }

    fun provideLegacyCategoryDao(database: SmartDatabase): CategoryDao {
        return database.categoryDao()
    }

    fun provideLegacyChatDao(database: SmartDatabase): ChatDao {
        return database.chatDao()
    }

    fun provideLegacyCalendarDao(database: SmartDatabase): CalendarDao {
        return database.calendarDao()
    }

    fun provideSyncQueueDao(database: SmartDatabase): SyncQueueDao {
        return database.syncQueueDao()
    }

    fun provideLegacySyncQueueDao(database: SmartDatabase): com.example.smarty.data.local.SyncQueueDao {
        return database.syncQueueDao()
    }

    fun provideCRDTManager(): CRDTManager {
        return CRDTManager()
    }

    fun provideOfflineFirstSyncManager(
        database: SmartDatabase,
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
        database: SmartDatabase,
        crdtManager: CRDTManager,
        offlineSyncManager: OfflineFirstSyncManager,
        context: Context,
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
