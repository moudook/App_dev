package com.example.smarty.data.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.smarty.data.cache.CacheManager
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job to clean up old cache entries
 * Runs daily when battery is not low
 */
class CacheCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CacheCleanupWorker"
        const val WORK_NAME = "Smarty_cache_cleanup"
        const val MAX_CACHE_AGE_DAYS = 7L

        /**
         * Schedule the periodic cache cleanup worker
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<CacheCleanupWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.HOURS) // Don't run immediately on app start
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "Cache cleanup worker scheduled")
        }

        /**
         * Cancel the periodic cleanup worker
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cache cleanup worker cancelled")
        }

        /**
         * Run cleanup immediately (one-time)
         */
        suspend fun runNow(context: Context) {
            val cacheManager = CacheManager.getInstance(context)
            val maxAgeMs = MAX_CACHE_AGE_DAYS * 24 * 60 * 60 * 1000
            cacheManager.clearOldEntries(maxAgeMs)
            cacheManager.evictIfNeeded()
            Log.i(TAG, "Manual cache cleanup completed")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting cache cleanup...")

        return try {
            val cacheManager = CacheManager.getInstance(applicationContext)

            // Get cache size before cleanup
            val sizeBefore = cacheManager.getCacheSize()
            Log.d(TAG, "Cache size before: ${sizeBefore / 1024}KB")

            // Clear entries older than 7 days
            val maxAgeMs = MAX_CACHE_AGE_DAYS * 24 * 60 * 60 * 1000
            cacheManager.clearOldEntries(maxAgeMs)

            // Evict if still over limit
            cacheManager.evictIfNeeded()

            // Get cache size after cleanup
            val sizeAfter = cacheManager.getCacheSize()
            Log.i(TAG, "Cache cleanup complete. Size: ${sizeBefore / 1024}KB -> ${sizeAfter / 1024}KB")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Cache cleanup failed: ${e.message}", e)
            Result.retry()
        }
    }
}
