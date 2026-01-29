package com.example.smarty.agent.tools.base

import com.example.smarty.data.model.Note
import com.example.smarty.data.repository.JarvisRepository
import com.example.smarty.util.PrivacyGuard

/**
 * Utility object for Smarty AI Agent tools with PrivacyGuard integration.
 *
 * All tools that access notes should use these helper functions to ensure
 * privacy enforcement is consistent across the agent.
 */
object JarvisToolUtils {
    /**
     * Get a fresh note from database with AI accessibility check.
     * Returns null if:
     * - Note doesn't exist
     * - Note is private (isFullPrivacy or excludeFromAiChat)
     * - Note is archived
     *
     * SECURITY: This is the ONLY way tools should access notes by ID.
     */
    suspend fun getFreshAiAccessibleNote(repository: JarvisRepository, noteId: String): Note? {
        val freshNote = repository.getNoteById(noteId) ?: return null
        return if (PrivacyGuard.canAiProcess(freshNote)) freshNote else null
    }

    /**
     * Filter notes list for AI visibility.
     * Uses PrivacyGuard to ensure private notes are never exposed to AI.
     */
    fun filterNotesForAi(notes: List<Note>): List<Note> {
        return PrivacyGuard.getAiVisibleNotes(notes)
    }

    /**
     * Filter notes for AI modification operations (delete, archive, update).
     * Uses PrivacyGuard to ensure private notes cannot be modified by AI.
     */
    fun filterNotesForAiModification(notes: List<Note>): List<Note> {
        return PrivacyGuard.filterForAiModification(notes)
    }
}
