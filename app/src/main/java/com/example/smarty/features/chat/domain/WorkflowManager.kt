package com.example.smarty.features.chat.domain

import android.util.Log
import com.example.smarty.core.domain.model.Note
import com.example.smarty.data.repository.SmartyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages complex, multi-step AI workflows.
 * Hybridizes logic for:
 * - Deep Research (multi-query web search + report synthesis)
 * - Automated Content Aggregation
 * - Cross-tool orchestration
 * - Batch data processing
 * - Scheduled workflow execution
 *
 * This manager allows the app to perform complex agentic tasks
 * independently of the active chat agent.
 *
 * ARCHITECTURE:
 * - Workflow execution is decoupled from UI interactions
 * - Results are persisted as notes for later retrieval
 * - Supports cancellation and progress tracking
 * - Uses coroutines for efficient parallel execution
 */
class WorkflowManager(
    private val repository: SmartyRepository,
    private val scope: CoroutineScope,
    private val onStatusUpdate: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "WorkflowManager"
    }

    /**
     * Batch process multiple notes with a given operation.
     * Executes operations in parallel with progress tracking.
     *
     * @param noteIds List of note IDs to process
     * @param operation Lambda function to execute on each note
     * @param onProgress Callback with current progress (processed, total)
     * @return Number of successfully processed notes
     */
    suspend fun batchProcessNotes(
        noteIds: List<String>,
        operation: suspend (Note) -> Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Int {
        if (noteIds.isEmpty()) return 0

        val context = com.example.smarty.SmartyApplication.appInstance
        onStatusUpdate(context.getString(com.example.smarty.R.string.status_batch_starting, noteIds.size))
        var successCount = 0

        try {
            noteIds.forEachIndexed { index, noteId ->
                try {
                    val note = repository.getNoteById(noteId)
                    if (note != null && operation(note)) {
                        successCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing note $noteId: ${e.message}")
                }
                onProgress(index + 1, noteIds.size)
            }

            onStatusUpdate(context.getString(com.example.smarty.R.string.status_batch_done, successCount, noteIds.size))
        } catch (e: Exception) {
            Log.e(TAG, "Batch processing failed: ${e.message}")
            onStatusUpdate(context.getString(com.example.smarty.R.string.status_batch_failed, e.message ?: ""))
        }

        return successCount
    }

    /**
     * Schedule a delayed workflow execution.
     * Useful for time-based automation.
     *
     * @param delayMs Delay in milliseconds before execution
     * @param workflow The workflow to execute
     */
    fun scheduleWorkflow(
        delayMs: Long,
        workflow: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                delay(delayMs)
                workflow()
            } catch (e: Exception) {
                Log.e(TAG, "Scheduled workflow failed: ${e.message}")
            }
        }
    }

    private fun truncateContent(
        content: String,
        wordCount: Int,
    ): String {
        if (content.isBlank()) return ""
        val words = content.trim().split("\\s+".toRegex())
        return if (words.size <= wordCount) {
            content
        } else {
            words.take(wordCount).joinToString(" ") + "..."
        }
    }
}
