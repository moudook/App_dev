package com.example.smarty.features.notes.domain

import android.util.Log
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.ProcessingStatus
import com.example.smarty.data.remote.AIService
import com.example.smarty.data.repository.SmartyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class NoteProcessingQueueManager(
    private val repository: SmartyRepository,
    private val aiService: AIService,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "NoteProcessingQueue"
        private const val PROCESSING_TIMEOUT_MS = 300_000L
        private const val QUEUE_CHECK_INTERVAL_MS = 5_000L
        private const val PROCESSING_DELAY_MS = 1_000L
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 5_000L
        private const val MAX_CONCURRENT_SMALL_NOTES = 3
        private const val SMALL_NOTE_THRESHOLD_BYTES = 10_000

        private fun String.sha256(): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(this.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    sealed class NoteProcessingEvent {
        data class Retry(val noteId: String, val attempt: Int) : NoteProcessingEvent()
        data class Failed(val noteId: String, val reason: String) : NoteProcessingEvent()
        data class Completed(val noteId: String, val noteTitle: String?) : NoteProcessingEvent()
    }

    private val processingMutex = Mutex()
    private val isProcessing = AtomicBoolean(false)
    private var queueJob: Job? = null
    private val retryCount = ConcurrentHashMap<String, Int>()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount
    private val _isQueueActive = MutableStateFlow(false)
    val isQueueActive: StateFlow<Boolean> = _isQueueActive
    private val _processingEvents = MutableSharedFlow<NoteProcessingEvent>()
    val processingEvents: SharedFlow<NoteProcessingEvent> = _processingEvents.asSharedFlow()

    suspend fun initialize() {
        Log.d(TAG, "Initializing queue manager...")
        forceCompleteVeryOldStuckNotes()
        recoverStuckNotes()
        updatePendingCount()
        startQueueProcessor()
        Log.d(TAG, "Queue manager initialized. Pending: ${_pendingCount.value}")
    }

    private suspend fun forceCompleteVeryOldStuckNotes() {
        val veryOldThreshold = System.currentTimeMillis() - (5 * 60 * 1000L)
        val stuckProcessing = repository.getStuckProcessingNotes(veryOldThreshold)
        val stuckPending = repository.getNotesByProcessingStatus(ProcessingStatus.PENDING)
            .filter { it.updatedAt < veryOldThreshold }
        val allStuck = stuckProcessing + stuckPending
        if (allStuck.isNotEmpty()) {
            Log.w(TAG, "Force completing ${allStuck.size} notes stuck for over 5 minutes")
            for (note in allStuck) {
                val contentHash = note.content.sha256()
                saveWithDefaultCategory(note, contentHash)
            }
        }
    }

    suspend fun enqueue(note: Note) {
        Log.d(TAG, "Enqueuing note: ${note.id}")
        if (note.processingStatus != ProcessingStatus.PENDING) {
            repository.updateProcessingStatus(note.id, ProcessingStatus.PENDING)
        }
        updatePendingCount()
        triggerProcessing()
    }

    suspend fun processNow(noteId: String): Boolean {
        val note = repository.getNoteById(noteId) ?: return false
        return processNote(note)
    }

    private suspend fun recoverStuckNotes() {
        val timeoutThreshold = System.currentTimeMillis() - PROCESSING_TIMEOUT_MS
        val resetCount = repository.resetStuckNotes(timeoutThreshold)
        if (resetCount > 0) {
            Log.w(TAG, "Recovered $resetCount stuck notes")
        }
    }

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

    private fun triggerProcessing() {
        scope.launch(Dispatchers.IO) { processQueue() }
    }

    fun onProviderAvailable() {
        Log.d(TAG, "Provider became available, triggering immediate queue processing")
        scope.launch(Dispatchers.IO) {
            delay(500)
            processQueue()
        }
    }

    private suspend fun processQueue() {
        if (!isProcessing.compareAndSet(false, true)) return
        try {
            if (!aiService.isAiAvailable()) {
                Log.d(TAG, "AI not available, skipping queue processing")
                return
            }
            handleTimedOutNotes()
            val pendingNotes = mutableListOf<Note>()
            while (true) {
                val note = repository.getNextPendingNote() ?: break
                pendingNotes.add(note)
                repository.updateProcessingStatus(note.id, ProcessingStatus.PROCESSING)
                if (pendingNotes.size >= MAX_CONCURRENT_SMALL_NOTES * 2) break
            }
            if (pendingNotes.isEmpty()) return
            val (smallNotes, largeNotes) = pendingNotes.partition { note ->
                note.content.length < SMALL_NOTE_THRESHOLD_BYTES &&
                note.fileUri.isNullOrEmpty() &&
                note.imageUri.isNullOrEmpty() &&
                note.attachmentsJson.isNullOrEmpty()
            }
            var processedCount = 0
            if (smallNotes.isNotEmpty()) {
                Log.d(TAG, "Processing ${smallNotes.size} small notes in parallel (batch size: $MAX_CONCURRENT_SMALL_NOTES)")
                smallNotes.chunked(MAX_CONCURRENT_SMALL_NOTES).forEach { batch ->
                    processedCount += processNoteBatch(batch)
                    delay(PROCESSING_DELAY_MS / 2)
                }
            }
            for (note in largeNotes) {
                val success = processNote(note)
                if (success) {
                    processedCount++
                    retryCount.remove(note.id)
                } else {
                    Log.w(TAG, "Large note processing failed: ${note.id}")
                }
                delay(PROCESSING_DELAY_MS)
            }
            if (processedCount > 0) {
                Log.d(TAG, "Processed $processedCount notes (${smallNotes.size} parallel, ${largeNotes.size} sequential)")
            }
            updatePendingCount()
        } finally {
            isProcessing.set(false)
        }
    }

    private suspend fun processNoteBatch(notes: List<Note>): Int {
        return supervisorScope {
            val results = notes.map { note ->
                async {
                    try {
                        val success = processNote(note)
                        if (success) retryCount.remove(note.id)
                        success
                    } catch (e: Exception) {
                        Log.e(TAG, "Parallel processing error for ${note.id}: ${e.message}")
                        try { saveWithDefaultCategory(note) }
                        catch (e: Exception) { Log.e(TAG, "Failed to save note ${note.id} with default category", e) }
                        false
                    }
                }
            }
            results.awaitAll().count { it }
        }
    }

    private suspend fun processNote(note: Note): Boolean {
        Log.d(TAG, "Processing note: ${note.id}")
        val currentContentHash = note.content.sha256()
        if (note.processedContentHash != null && note.processedContentHash == currentContentHash) {
            Log.d(TAG, "Note already processed (content hash match), skipping: ${note.id}")
            val updatedNote = note.copy(
                processingStatus = ProcessingStatus.COMPLETED,
                contentHash = currentContentHash,
                updatedAt = System.currentTimeMillis()
            )
            repository.updateNote(updatedNote)
            return true
        }
        return try {
            val result = aiService.analyzeContent(note.content)
            if (result.success) {
                val category = repository.getOrCreateCategory(result.category)
                val updatedNote = note.copy(
                    title = result.title,
                    summary = result.summary,
                    categoryId = category.id,
                    categoryName = category.name,
                    whySaved = result.whySaved,
                    processingStatus = ProcessingStatus.COMPLETED,
                    contentHash = currentContentHash,
                    processedContentHash = currentContentHash,
                    updatedAt = System.currentTimeMillis()
                )
                repository.updateNote(updatedNote)
                Log.d(TAG, "Note processed successfully: ${note.id}")
                _processingEvents.emit(NoteProcessingEvent.Completed(note.id, result.title))
                true
            } else {
                saveWithDefaultCategory(note, currentContentHash)
                Log.w(TAG, "AI analysis failed, saved with default category: ${note.id}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing note ${note.id}: ${e.message}", e)
            saveWithDefaultCategory(note, currentContentHash)
            false
        }
    }

    private suspend fun handleTimedOutNotes() {
        val timeoutThreshold = System.currentTimeMillis() - PROCESSING_TIMEOUT_MS
        val stuckNotes = repository.getStuckProcessingNotes(timeoutThreshold)
        for (note in stuckNotes) {
            val currentRetries = retryCount.getOrDefault(note.id, 0)
            if (currentRetries < MAX_RETRY_ATTEMPTS) {
                val nextRetry = currentRetries + 1
                retryCount[note.id] = nextRetry
                Log.w(TAG, "Note timed out, retry $nextRetry/$MAX_RETRY_ATTEMPTS: ${note.id}")
                _processingEvents.emit(NoteProcessingEvent.Retry(note.id, nextRetry))
                repository.updateProcessingStatus(note.id, ProcessingStatus.PENDING)
                val backoffDelay = RETRY_DELAY_MS * (1L shl (nextRetry - 1))
                delay(backoffDelay.coerceAtMost(30_000L))
            } else {
                Log.e(TAG, "Note failed after $MAX_RETRY_ATTEMPTS retries, saving with default: ${note.id}")
                _processingEvents.emit(
                    NoteProcessingEvent.Failed(
                        note.id,
                        repository.getApplicationContext().getString(
                            com.example.smarty.R.string.processing_error_retries_exceeded,
                            MAX_RETRY_ATTEMPTS
                        )
                    )
                )
                val contentHash = note.content.sha256()
                saveWithDefaultCategory(note, contentHash)
                retryCount.remove(note.id)
            }
        }
        if (stuckNotes.isNotEmpty()) {
            Log.w(TAG, "Handled ${stuckNotes.size} timed-out notes")
        }
    }

    private suspend fun saveWithDefaultCategory(note: Note, contentHash: String? = null) {
        val fallbackResponse = com.example.smarty.data.remote.AIResponseParser.smartFallbackCategorization(repository.getApplicationContext(), note.content)
        val categoryName = fallbackResponse.category
        val category = repository.getOrCreateCategory(categoryName)
        val updatedNote = note.copy(
            categoryId = category.id,
            categoryName = category.name,
            summary = fallbackResponse.summary.takeIf { it.isNotBlank() },
            whySaved = fallbackResponse.whySaved.takeIf { it.isNotBlank() },
            processingStatus = ProcessingStatus.COMPLETED,
            contentHash = contentHash ?: note.content.sha256(),
            processedContentHash = contentHash ?: note.content.sha256(),
            updatedAt = System.currentTimeMillis()
        )
        repository.updateNote(updatedNote)
        Log.d(TAG, "Saved note ${note.id} with smart fallback category: $categoryName")
    }

    private suspend fun updatePendingCount() {
        _pendingCount.value = repository.getPendingProcessingCount()
    }

    fun stop() {
        queueJob?.cancel()
        queueJob = null
        _isQueueActive.value = false
        retryCount.clear()
        Log.d(TAG, "Queue manager stopped")
    }

    suspend fun forceCompleteAll() {
        val pendingNotes = repository.getNotesByProcessingStatus(ProcessingStatus.PENDING)
        val processingNotes = repository.getNotesByProcessingStatus(ProcessingStatus.PROCESSING)
        val allNotes = pendingNotes + processingNotes
        for (note in allNotes) {
            val contentHash = note.content.sha256()
            saveWithDefaultCategory(note, contentHash)
        }
        updatePendingCount()
        Log.w(TAG, "Force completed ${allNotes.size} notes")
    }
}
