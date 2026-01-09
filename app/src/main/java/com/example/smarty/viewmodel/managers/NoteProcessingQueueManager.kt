package com.example.smarty.viewmodel.managers

import android.util.Log
import com.example.smarty.data.local.NoteDao
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.remote.AIService
import com.example.smarty.data.repository.JarvisRepository
import com.example.smarty.util.ContentTypeDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Manages background processing queue for notes.
 *
 * Features:
 * - Automatic timeout for stuck notes (30 seconds)
 * - Background queue processing when AI is available
 * - Auto-recovery on app start for stuck PROCESSING notes
 * - Rate-limited processing to prevent API overload
 *
 * Flow:
 * 1. Note created with PENDING status → added to queue
 * 2. Queue processor checks AI availability
 * 3. If AI available: process note, update status to COMPLETED
 * 4. If AI unavailable: note stays PENDING, retry later
 * 5. If processing times out: mark as COMPLETED with default category
 */
class NoteProcessingQueueManager(
    private val noteDao: NoteDao,
    private val repository: JarvisRepository,
    private val aiService: AIService,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "NoteProcessingQueue"

        // Timeout for stuck notes (5 minutes - increased for slow local LLMs)
        private const val PROCESSING_TIMEOUT_MS = 300_000L

        // Check interval for queue processing (5 seconds)
        private const val QUEUE_CHECK_INTERVAL_MS = 5_000L

        // Delay between processing notes to avoid API rate limits
        private const val PROCESSING_DELAY_MS = 1_000L

        // Max retries before giving up
        private const val MAX_RETRY_ATTEMPTS = 3

        // Delay between retry attempts (5 seconds)
        private const val RETRY_DELAY_MS = 5_000L
    }

    /**
     * Events emitted during note processing for user notifications.
     */
    sealed class NoteProcessingEvent {
        data class Retry(val noteId: String, val attempt: Int) : NoteProcessingEvent()
        data class Failed(val noteId: String, val reason: String) : NoteProcessingEvent()
        data class Completed(val noteId: String, val noteTitle: String?) : NoteProcessingEvent()
    }

    // Queue state (thread-safe)
    private val processingMutex = Mutex()
    private val isProcessing = AtomicBoolean(false)
    private var queueJob: Job? = null

    // Retry tracking for timed-out notes (thread-safe: accessed from multiple coroutines)
    private val retryCount = ConcurrentHashMap<String, Int>()

    // Observable state
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    private val _isQueueActive = MutableStateFlow(false)
    val isQueueActive: StateFlow<Boolean> = _isQueueActive

    // Event flow for user notifications about processing failures/retries
    private val _processingEvents = MutableSharedFlow<NoteProcessingEvent>()
    val processingEvents: SharedFlow<NoteProcessingEvent> = _processingEvents.asSharedFlow()

    /**
     * Initialize the queue manager.
     * Call this on app start to:
     * 1. Force complete notes stuck for too long (prevents UI stuck in processing)
     * 2. Recover stuck PROCESSING notes
     * 3. Start the background queue processor
     */
    suspend fun initialize() {
        Log.d(TAG, "Initializing queue manager...")

        // CRITICAL FIX: Force complete notes that have been stuck for over 5 minutes
        // This prevents the UI from showing "processing" indefinitely
        forceCompleteVeryOldStuckNotes()

        // Recover stuck notes (crashed during processing)
        recoverStuckNotes()

        // Update pending count
        updatePendingCount()

        // Start background processing
        startQueueProcessor()

        Log.d(TAG, "Queue manager initialized. Pending: ${_pendingCount.value}")
    }

    /**
     * Force complete notes that have been stuck in PROCESSING/PENDING for over 5 minutes.
     * This ensures the UI never shows "processing" indefinitely.
     */
    private suspend fun forceCompleteVeryOldStuckNotes() {
        val veryOldThreshold = System.currentTimeMillis() - (5 * 60 * 1000L) // 5 minutes

        // Get notes stuck in PROCESSING state for too long
        val stuckProcessing = noteDao.getStuckProcessingNotes(veryOldThreshold)

        // Also get PENDING notes that are very old (shouldn't happen but safety net)
        val stuckPending = noteDao.getNotesByProcessingStatus(ProcessingStatus.PENDING)
            .filter { it.updatedAt < veryOldThreshold }

        val allStuck = stuckProcessing + stuckPending

        if (allStuck.isNotEmpty()) {
            Log.w(TAG, "Force completing ${allStuck.size} notes stuck for over 5 minutes")
            for (note in allStuck) {
                saveWithDefaultCategory(note)
            }
        }
    }

    /**
     * Add a note to the processing queue.
     * The note should already have PENDING status.
     */
    suspend fun enqueue(note: Note) {
        Log.d(TAG, "Enqueuing note: ${note.id}")

        // Ensure note has PENDING status
        if (note.processingStatus != ProcessingStatus.PENDING) {
            noteDao.updateProcessingStatus(note.id, ProcessingStatus.PENDING)
        }

        updatePendingCount()

        // Trigger immediate processing if queue is idle
        triggerProcessing()
    }

    /**
     * Force process a specific note immediately.
     * Bypasses queue order.
     */
    suspend fun processNow(noteId: String): Boolean {
        val note = noteDao.getNoteById(noteId) ?: return false
        return processNote(note)
    }

    /**
     * Recover notes stuck in PROCESSING state.
     * Called on app start to handle crash recovery.
     */
    private suspend fun recoverStuckNotes() {
        val timeoutThreshold = System.currentTimeMillis() - PROCESSING_TIMEOUT_MS
        val resetCount = noteDao.resetStuckNotes(timeoutThreshold)

        if (resetCount > 0) {
            Log.w(TAG, "Recovered $resetCount stuck notes")
        }
    }

    /**
     * Start the background queue processor.
     */
    private fun startQueueProcessor() {
        if (queueJob?.isActive == true) return

        queueJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Queue processor started")
            _isQueueActive.value = true

            while (isActive) {
                try {
                    processQueue()
                } catch (e: Exception) {
                    Log.e(TAG, "Queue processing error: ${e.message}", e)
                }

                delay(QUEUE_CHECK_INTERVAL_MS)
            }

            _isQueueActive.value = false
            Log.d(TAG, "Queue processor stopped")
        }
    }

    /**
     * Trigger immediate queue processing.
     */
    private fun triggerProcessing() {
        scope.launch(Dispatchers.IO) {
            processQueue()
        }
    }

    /**
     * Called when a provider becomes available (API key added, provider enabled, etc.)
     * Triggers immediate queue processing instead of waiting for next poll cycle.
     */
    fun onProviderAvailable() {
        Log.d(TAG, "Provider became available, triggering immediate queue processing")
        scope.launch(Dispatchers.IO) {
            // Small delay to let provider config settle
            delay(500)
            processQueue()
        }
    }

    /**
     * Process pending notes in the queue.
     */
    private suspend fun processQueue() {
        // Prevent concurrent processing using atomic compareAndSet
        if (!isProcessing.compareAndSet(false, true)) return

        try {
            // Check if AI is available
            if (!aiService.isAiAvailable()) {
                Log.d(TAG, "AI not available, skipping queue processing")
                return
            }

            // Check for timed-out notes first
            handleTimedOutNotes()

            // Process pending notes
            var processedCount = 0
            while (true) {
                val note = noteDao.getNextPendingNote() ?: break

                // Mark as processing
                noteDao.updateProcessingStatus(note.id, ProcessingStatus.PROCESSING)

                val success = processNote(note)
                if (success) {
                    processedCount++
                    // Clear retry count on success
                    retryCount.remove(note.id)
                } else {
                    // If AI failed, stop processing queue (might be rate limited)
                    Log.w(TAG, "Note processing failed, pausing queue")
                    break
                }

                // Rate limiting delay
                delay(PROCESSING_DELAY_MS)

                // Update count
                updatePendingCount()
            }

            if (processedCount > 0) {
                Log.d(TAG, "Processed $processedCount notes")
            }
        } finally {
            isProcessing.set(false)
        }
    }

    /**
     * Process a single note with AI.
     * Returns true if successful, false otherwise.
     */
    private suspend fun processNote(note: Note): Boolean {
        Log.d(TAG, "Processing note: ${note.id}")

        return try {
            val result = aiService.analyzeContent(note.content)

            if (result.success) {
                // Get or create category
                val category = repository.getOrCreateCategory(result.category)

                // Update note with AI results
                val updatedNote = note.copy(
                    title = result.title,
                    summary = result.summary,
                    categoryId = category.id,
                    categoryName = category.name,
                    whySaved = result.whySaved,
                    processingStatus = ProcessingStatus.COMPLETED,
                    updatedAt = System.currentTimeMillis()
                )

                noteDao.updateNote(updatedNote)
                Log.d(TAG, "Note processed successfully: ${note.id}")

                // Emit completion event for sound notification
                _processingEvents.emit(NoteProcessingEvent.Completed(note.id, result.title))
                true
            } else {
                // AI failed - save with default category
                saveWithDefaultCategory(note)
                Log.w(TAG, "AI analysis failed, saved with default category: ${note.id}")
                true // Consider this "processed" to move on
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing note ${note.id}: ${e.message}", e)

            // Save with default category on error
            saveWithDefaultCategory(note)
            false
        }
    }

    /**
     * Handle notes that have timed out while processing.
     * Implements retry with exponential backoff before giving up.
     */
    private suspend fun handleTimedOutNotes() {
        val timeoutThreshold = System.currentTimeMillis() - PROCESSING_TIMEOUT_MS
        val stuckNotes = noteDao.getStuckProcessingNotes(timeoutThreshold)

        for (note in stuckNotes) {
            val currentRetries = retryCount.getOrDefault(note.id, 0)

            if (currentRetries < MAX_RETRY_ATTEMPTS) {
                // Retry with backoff
                val nextRetry = currentRetries + 1
                retryCount[note.id] = nextRetry

                Log.w(TAG, "Note timed out, retry $nextRetry/$MAX_RETRY_ATTEMPTS: ${note.id}")

                // Emit retry event for UI notification
                _processingEvents.emit(NoteProcessingEvent.Retry(note.id, nextRetry))

                // Reset to PENDING for retry (will be picked up in next queue cycle)
                noteDao.updateProcessingStatus(note.id, ProcessingStatus.PENDING)

                // Add exponential backoff delay before next retry
                val backoffDelay = RETRY_DELAY_MS * (1L shl (nextRetry - 1)) // 5s, 10s, 20s
                delay(backoffDelay.coerceAtMost(30_000L)) // Cap at 30 seconds
            } else {
                // Max retries exceeded - save with default category and emit failure event
                Log.e(TAG, "Note failed after $MAX_RETRY_ATTEMPTS retries, saving with default: ${note.id}")

                _processingEvents.emit(
                    NoteProcessingEvent.Failed(note.id, "Processing failed after $MAX_RETRY_ATTEMPTS attempts")
                )

                saveWithDefaultCategory(note)
                retryCount.remove(note.id) // Clean up retry tracking
            }
        }

        if (stuckNotes.isNotEmpty()) {
            Log.w(TAG, "Handled ${stuckNotes.size} timed-out notes")
        }
    }

    /**
     * Save note with default category (used when AI fails or times out).
     */
    private suspend fun saveWithDefaultCategory(note: Note) {
        // Use smart keyword-based categorization instead of just type-based "Saved Files"
        val fallbackResponse = com.example.smarty.data.remote.AIResponseParser.smartFallbackCategorization(note.content)
        val categoryName = fallbackResponse.category
        val category = repository.getOrCreateCategory(categoryName)

        val updatedNote = note.copy(
            categoryId = category.id,
            categoryName = category.name,
            summary = fallbackResponse.summary.takeIf { it.isNotBlank() },
            whySaved = fallbackResponse.whySaved.takeIf { it.isNotBlank() },
            processingStatus = ProcessingStatus.COMPLETED,
            updatedAt = System.currentTimeMillis()
        )

        noteDao.updateNote(updatedNote)
        Log.d(TAG, "Saved note ${note.id} with smart fallback category: $categoryName")
    }

    /**
     * Update the pending count state.
     */
    private suspend fun updatePendingCount() {
        _pendingCount.value = noteDao.getPendingProcessingCount()
    }

    /**
     * Stop the queue processor.
     * Call this when the app is being destroyed.
     */
    fun stop() {
        queueJob?.cancel()
        queueJob = null
        _isQueueActive.value = false
        retryCount.clear()
        Log.d(TAG, "Queue manager stopped")
    }

    /**
     * Force mark all pending/processing notes as completed.
     * Use this for debugging or emergency recovery.
     */
    suspend fun forceCompleteAll() {
        val pendingNotes = noteDao.getNotesByProcessingStatus(ProcessingStatus.PENDING)
        val processingNotes = noteDao.getNotesByProcessingStatus(ProcessingStatus.PROCESSING)

        val allNotes = pendingNotes + processingNotes
        for (note in allNotes) {
            saveWithDefaultCategory(note)
        }

        updatePendingCount()
        Log.w(TAG, "Force completed ${allNotes.size} notes")
    }
}
