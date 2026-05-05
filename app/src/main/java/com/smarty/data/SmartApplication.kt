
package com.smarty.data

import android.content.Context
import com.smarty.data.database.SmartDatabase
import com.smarty.data.di.DataModule
import com.smarty.data.repository.SmartRepository

/**
 * Main Application class
 */
class SmartApplication : android.app.Application() {

    override fun onCreate() {
        super.onCreate()
        initializeDataLayer()
    }

    private fun initializeDataLayer() {
        // Initialize background sync workers
        setupPeriodicSync()
        // Initialize real-time event listeners
        setupRealtimeListeners()
        // Initialize AI prediction models
        initializePredictionModels()
    }

    private fun setupPeriodicSync() {
        // Setup WorkManager for periodic background sync
        // This would sync every 15 minutes when online
    }

    private fun setupRealtimeListeners() {
        // Setup Supabase real-time listeners
        // Listen for changes to notes, chats, calendar events, etc.
    }

    private fun initializePredictionModels() {
        // Load ML models for:
        // - Tag prediction
        // - Content categorization
        // - Next action prediction
        // - Smart linking
    }

    companion object {
        fun getRepository(context: Context): SmartRepository {
            val database = SmartDatabase.getInstance(context)
            val dao = DataModule.provideDao(database)
            val crdtManager = DataModule.provideCRDTManager(dao)
            val eventStreamer = DataModule.provideEventStreamer()
            val syncManager = DataModule.provideSyncManager(
                crdtManager = crdtManager,
                eventStreamer = eventStreamer,
                dao = dao,
                serverBaseUrl = com.example.smarty.BuildConfig.SERVER_URL,
            )
            return DataModule.provideRepository(dao, crdtManager, syncManager, eventStreamer)

        }
    }
}
