package com.example.smarty.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.data.remote.RemoteDataSource
import com.example.smarty.data.sync.SyncCoordinator
import com.example.smarty.data.sync.NetworkMonitor
import com.example.smarty.data.sync.MigrationManager
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting sync worker...")
        
        return try {
            val networkMonitor = NetworkMonitor(applicationContext)
            
            if (!networkMonitor.isOnline.value) {
                Log.d(TAG, "Device offline, skipping sync")
                return Result.success()
            }
            
            val database = SmartyDatabase.getDatabase(applicationContext)
            val securePrefs = com.example.smarty.data.local.SecurePreferences.getInstance(applicationContext)
            
            val remoteDataSource = RemoteDataSource(
                client = createHttpClient(),
                serverUrlProvider = { securePrefs.getSmartyServerUrl() },
                deviceIdProvider = { securePrefs.getDeviceId() }
            )
            
            val syncCoordinator = SyncCoordinator(
                context = applicationContext,
                remoteDataSource = remoteDataSource,
                noteDao = database.noteDao(),
                calendarDao = database.calendarDao(),
                chatDao = database.chatDao(),
                syncQueueDao = database.syncQueueDao(),
                networkMonitor = networkMonitor
            )
            
            val migrationManager = MigrationManager(
                context = applicationContext,
                remoteDataSource = remoteDataSource,
                noteDao = database.noteDao(),
                calendarDao = database.calendarDao(),
                chatDao = database.chatDao(),
                syncQueueDao = database.syncQueueDao()
            )
            
            when (migrationManager.migrateIfNeeded()) {
                is com.example.smarty.data.sync.MigrationResult.Success -> {
                    Log.i(TAG, "Migration completed")
                }
                is com.example.smarty.data.sync.MigrationResult.AlreadyMigrated -> {
                    Log.d(TAG, "Already migrated")
                }
                is com.example.smarty.data.sync.MigrationResult.Error -> {
                    Log.e(TAG, "Migration failed")
                }
            }
            
            when (val result = syncCoordinator.pullFromServer()) {
                is com.example.smarty.data.sync.PullResult.Success -> {
                    Log.i(TAG, "Pull complete: ${result.notes} notes, ${result.sessions} sessions, ${result.events} events")
                }
                is com.example.smarty.data.sync.PullResult.Offline -> {
                    Log.d(TAG, "Pull skipped: offline")
                }
                is com.example.smarty.data.sync.PullResult.Error -> {
                    Log.e(TAG, "Pull failed: ${result.message}")
                }
            }
            
            when (val result = syncCoordinator.pushPendingChanges()) {
                is com.example.smarty.data.sync.PushResult.Success -> {
                    Log.i(TAG, "Push complete: ${result.notes} notes, ${result.sessions} sessions, ${result.events} events")
                }
                is com.example.smarty.data.sync.PushResult.Offline -> {
                    Log.d(TAG, "Push skipped: offline")
                }
                is com.example.smarty.data.sync.PushResult.Error -> {
                    Log.e(TAG, "Push failed: ${result.message}")
                }
            }
            
            Log.i(TAG, "Sync worker completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync worker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "sync_worker"
        
        private fun createHttpClient(): io.ktor.client.HttpClient {
            return io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
                engine {
                    preconfigured = com.example.smarty.core.common.util.HttpClientProvider.default
                }
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json(
                        kotlinx.serialization.json.Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        }
                    )
                }
            }
        }

        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        suspend fun syncNow(context: Context): Boolean {
            return try {
                val networkMonitor = NetworkMonitor(context)
                
                if (!networkMonitor.isOnline.value) {
                    Log.d(TAG, "Device offline, skipping sync")
                    return false
                }
                
                val database = SmartyDatabase.getDatabase(context)
                val securePrefs = com.example.smarty.data.local.SecurePreferences.getInstance(context)
                
                val remoteDataSource = RemoteDataSource(
                    client = createHttpClient(),
                    serverUrlProvider = { securePrefs.getSmartyServerUrl() },
                    deviceIdProvider = { securePrefs.getDeviceId() }
                )
                
                val syncCoordinator = SyncCoordinator(
                    context = context,
                    remoteDataSource = remoteDataSource,
                    noteDao = database.noteDao(),
                    calendarDao = database.calendarDao(),
                    chatDao = database.chatDao(),
                    syncQueueDao = database.syncQueueDao(),
                    networkMonitor = networkMonitor
                )
                
                syncCoordinator.pullFromServer()
                syncCoordinator.pushPendingChanges()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Sync now failed", e)
                false
            }
        }
    }
}
