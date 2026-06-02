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
    fun provideSmartDatabase(context: Context): SmartyDatabase = SmartyDatabase.getDatabase(context)

    fun provideSmartDao(database: SmartyDatabase): SmartDatabaseDao = database.smartDao()

    fun provideLegacyNoteDao(database: SmartyDatabase): NoteDao = database.noteDao()

    fun provideLegacyCategoryDao(database: SmartyDatabase): CategoryDao = database.categoryDao()

    fun provideLegacyChatDao(database: SmartyDatabase): ChatDao = database.chatDao()

    fun provideLegacyCalendarDao(database: SmartyDatabase): CalendarDao = database.calendarDao()

    fun provideSyncQueueDao(database: SmartyDatabase): SyncQueueDao = database.syncQueueDao()

    fun provideLegacySyncQueueDao(database: SmartyDatabase): com.example.smarty.data.local.SyncQueueDao = database.syncQueueDao()

    fun provideCRDTManager(): CRDTManager = CRDTManager()

    fun provideOfflineFirstSyncManager(
        database: SmartyDatabase,
        crdtManager: CRDTManager,
    ): OfflineFirstSyncManager = OfflineFirstSyncManager(database, crdtManager)
}

/**
 * Repository Module
 */
object RepositoryModule {
    fun provideSmartRepository(
        database: SmartyDatabase,
        crdtManager: CRDTManager,
        offlineSyncManager: OfflineFirstSyncManager,
    ): SmartRepository = SmartRepository(database, crdtManager, offlineSyncManager)

    fun provideLegacyRepository(
        noteDao: NoteDao,
        categoryDao: CategoryDao,
        calendarDao: CalendarDao,
        context: Context,
    ): SmartyRepository =
        SmartyRepository(
            noteDao = noteDao,
            categoryDao = categoryDao,
            calendarDao = calendarDao,
            noteVersionDao = null,
            context = context,
            syncRepository = null,
        )
}

/**
 * Application Component
 */
object ApplicationModule {
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
}
