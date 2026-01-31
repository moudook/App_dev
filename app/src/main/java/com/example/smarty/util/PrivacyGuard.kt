package com.example.smarty.util

import android.util.Log
import com.example.smarty.data.model.Note

/**
 * ============================================================================
 * PRIVACY GUARD - ABSOLUTE SECURITY BARRIER FOR AI ACCESS
 * ============================================================================
 *
 * This is the ONLY gateway for AI to access notes. Private notes are
 * COMPLETELY INVISIBLE to AI - they do not exist from AI's perspective.
 *
 * STRICT RULES (NEVER MODIFY):
 * 1. AI can NEVER read private notes
 * 2. AI can NEVER write to private notes
 * 3. AI can NEVER search private notes
 * 4. AI can NEVER reference private notes
 * 5. AI can NEVER know private notes exist
 * 6. AI can NEVER modify private notes
 * 7. AI can NEVER delete private notes
 * 8. AI can NEVER archive private notes
 * 9. AI can NEVER get count of private notes
 * 10. AI can NEVER get any metadata about private notes
 *
 * ANY ATTEMPT TO ACCESS PRIVATE NOTES THROUGH AI IS BLOCKED AND LOGGED.
 *
 * This file should NEVER be modified to grant AI access to private notes.
 * ============================================================================
 */

/**
 * Interface for entities that support privacy filtering.
 * Implement this on any entity that should be invisible to AI when private.
 *
 * Entities implementing this interface:
 * - Note (isFullPrivacy || excludeFromAiChat)
 * - CalendarEvent (future)
 * - Meeting (future)
 * - Any other entity that can be marked as private
 */
interface PrivacyAware {
    /**
     * Whether this entity is private and should be hidden from AI.
     * When true, AI cannot see, search, modify, or reference this entity.
     */
    val isPrivate: Boolean
}

object PrivacyGuard {
    private const val TAG = "PrivacyGuard"

    /**
     * Check if a note is private (blocked from AI)
     * A note is private if EITHER flag is set
     */
    fun isPrivate(note: Note): Boolean {
        return note.isFullPrivacy || note.excludeFromAiChat
    }

    /**
     * Check if a note is accessible to AI
     * Only non-private, non-archived notes are accessible
     */
    fun isAiAccessible(note: Note): Boolean {
        val accessible = !note.isFullPrivacy && !note.excludeFromAiChat && !note.isArchived
        if (!accessible && (note.isFullPrivacy || note.excludeFromAiChat)) {
            Log.d(TAG, "BLOCKED: AI access denied for private note ${note.id.take(8)}...")
        }
        return accessible
    }

    /**
     * THE ONLY WAY FOR AI TO GET NOTES
     *
     * This function filters out ALL private notes.
     * There is NO other way for AI to access notes.
     *
     * @param notes The full list of notes
     * @return Only notes that AI is allowed to see (private notes are invisible)
     */
    fun getAiVisibleNotes(notes: List<Note>): List<Note> {
        val visible = notes.filter { isAiAccessible(it) }
        val blocked = notes.size - visible.size
        if (blocked > 0) {
            Log.d(TAG, "AI ACCESS: $blocked private notes blocked, ${visible.size} notes visible to AI")
        }
        return visible
    }

    /**
     * Filter notes for AI context (chat, search, suggestions)
     * Private notes are COMPLETELY INVISIBLE
     */
    fun filterForAiContext(notes: List<Note>): List<Note> {
        return getAiVisibleNotes(notes)
    }

    /**
     * Filter notes for AI search operations
     * Private notes are COMPLETELY INVISIBLE
     */
    fun filterForAiSearch(notes: List<Note>): List<Note> {
        return getAiVisibleNotes(notes)
    }

    /**
     * Filter notes for AI modifications (delete, archive, update)
     * Private notes CANNOT be modified by AI
     */
    fun filterForAiModification(notes: List<Note>): List<Note> {
        return getAiVisibleNotes(notes)
    }

    /**
     * Find a note by ID - returns null if note is private
     * AI cannot access private notes even if it knows the ID
     */
    fun findByIdForAi(notes: List<Note>, noteId: String): Note? {
        val note = notes.find { it.id == noteId }
        if (note != null && isPrivate(note)) {
            // PRIVACY FIX (CRIT-002): Don't log note IDs to prevent data leakage via logcat
            Log.w(TAG, "SECURITY: AI attempted to access private note by ID")
            return null // Note is invisible to AI
        }
        return note?.takeIf { isAiAccessible(it) }
    }

    /**
     * Validate that a note can be processed by AI
     * Returns false for private notes - they cannot be processed
     */
    fun canAiProcess(note: Note): Boolean {
        if (isPrivate(note)) {
            // PRIVACY FIX (CRIT-002): Don't log note IDs to prevent data leakage via logcat
            Log.w(TAG, "SECURITY: AI processing blocked for private note")
            return false
        }
        return true
    }

    /**
     * Security check before any AI operation on a note
     * Throws SecurityException if note is private
     */
    fun requireAiAccess(note: Note, operation: String, context: android.content.Context) {
        if (isPrivate(note)) {
            // PRIVACY FIX (CRIT-002): Don't include note IDs in exception messages
            val message = context.getString(com.example.smarty.R.string.security_violation_detail, operation)
            Log.e(TAG, message)
            throw SecurityException(message)
        }
    }

    /**
     * Log a security event when AI tries to access private content
     * PRIVACY FIX (CRIT-002): Don't log note IDs to prevent data leakage
     */
    @Suppress("UNUSED_PARAMETER")
    fun logSecurityEvent(noteId: String, operation: String) {
        Log.w(TAG, "SECURITY EVENT: Blocked AI $operation for private note")
    }

    /**
     * Get count of AI-accessible notes (private notes not counted)
     */
    fun getAiAccessibleCount(notes: List<Note>): Int {
        return notes.count { isAiAccessible(it) }
    }

    /**
     * Check if any notes in list are private (for validation)
     */
    fun containsPrivateNotes(notes: List<Note>): Boolean {
        return notes.any { isPrivate(it) }
    }

    /**
     * Sanitize a list to ensure no private notes leak to AI
     * This is a final safety check before any AI operation
     */
    fun sanitizeForAi(notes: List<Note>): List<Note> {
        val sanitized = getAiVisibleNotes(notes)
        if (containsPrivateNotes(notes)) {
            Log.d(TAG, "Sanitized ${notes.size - sanitized.size} private notes from AI access")
        }
        return sanitized
    }

    /**
     * Filter note IDs to only include AI-accessible notes
     * Used to sanitize references before saving to chat history
     *
     * @param noteIds List of note IDs to filter
     * @param allNotes All notes to check against
     * @return Only IDs of notes that are AI-accessible
     */
    fun filterNoteIds(noteIds: List<String>, allNotes: List<Note>): List<String> {
        return noteIds.filter { noteId ->
            val note = allNotes.find { it.id == noteId }
            note != null && isAiAccessible(note)
        }
    }

    /**
     * Check if a note ID refers to a private note
     *
     * @param noteId The note ID to check
     * @param allNotes All notes to search
     * @return true if the note exists and is private
     */
    fun isPrivateNoteId(noteId: String, allNotes: List<Note>): Boolean {
        val note = allNotes.find { it.id == noteId } ?: return false
        return isPrivate(note)
    }

    /**
     * SECURITY NOTICE: Edge Case
     *
     * If a note was public, discussed with AI, and then marked private:
     * - The chat history from BEFORE may contain information about that note
     * - This is a known limitation - past conversations cannot be retroactively sanitized
     * - Recommendation: Users should delete chat history if they make notes private
     * - Future enhancement: Implement chat history scrubbing when privacy status changes
     */
    fun getSecurityNotice(): String {
        return "Chat history from before a note was marked private may contain references to that note."
    }

    // =========================================================================
    // GENERIC PRIVACY-AWARE FILTERING
    // =========================================================================
    // These functions work with ANY entity implementing PrivacyAware interface.
    // Use for CalendarEvent, Meeting, or any future privacy-enabled entities.

    /**
     * Generic filter for any PrivacyAware entity.
     * Removes all private items from the list.
     *
     * @param items List of PrivacyAware entities
     * @return Only non-private items (private items are invisible to AI)
     */
    fun <T : PrivacyAware> filterForAi(items: List<T>): List<T> {
        val visible = items.filter { !it.isPrivate }
        val blocked = items.size - visible.size
        if (blocked > 0) {
            Log.d(TAG, "GENERIC FILTER: $blocked private items blocked, ${visible.size} visible to AI")
        }
        return visible
    }

    /**
     * Generic find-by-predicate that returns null for private items.
     * AI cannot access private items even if it has a reference.
     *
     * @param item The item to check (or null)
     * @return The item if not private, null otherwise
     */
    fun <T : PrivacyAware> findForAi(item: T?): T? {
        if (item == null) return null
        if (item.isPrivate) {
            Log.d(TAG, "GENERIC FILTER: Private item blocked from AI access")
            return null
        }
        return item
    }

    /**
     * Check if AI can process a PrivacyAware entity.
     * Returns false for private entities.
     */
    fun <T : PrivacyAware> canAiProcessGeneric(item: T): Boolean {
        return !item.isPrivate
    }

    // =========================================================================
    // CHAT MESSAGE SANITIZATION
    // =========================================================================
    // These functions ensure private note IDs never leak through chat history.
    // Critical for preventing retroactive privacy leaks.

    /**
     * Sanitize referenced note IDs before storing chat messages.
     * Removes any IDs that refer to notes that are now private.
     *
     * SECURITY: This is the FINAL BARRIER before chat persistence.
     * Even if a note was public during conversation, if it's now private,
     * its ID will NOT be stored in chat history.
     *
     * @param noteIds List of note IDs to sanitize
     * @param allNotes All notes to check against (should be current state)
     * @return Only IDs of notes that are currently AI-accessible
     */
    fun sanitizeReferencedNoteIds(noteIds: List<String>, allNotes: List<Note>): List<String> {
        if (noteIds.isEmpty()) return emptyList()

        val sanitized = noteIds.filter { noteId ->
            val note = allNotes.find { it.id == noteId }
            // Only include if note exists AND is AI-accessible
            note != null && isAiAccessible(note)
        }

        val removed = noteIds.size - sanitized.size
        if (removed > 0) {
            Log.d(TAG, "SANITIZED: Removed $removed private note IDs from chat reference")
        }

        return sanitized
    }

    /**
     * Sanitize a chat message before persistence.
     * Ensures no private note references are stored.
     *
     * @param referencedNoteIds Note IDs from the message
     * @param allNotes All notes to check against
     * @return Sanitized list of note IDs (only public notes)
     */
    fun sanitizeForChatPersistence(
        referencedNoteIds: List<String>,
        allNotes: List<Note>
    ): List<String> {
        return sanitizeReferencedNoteIds(referencedNoteIds, allNotes)
    }

    /**
     * Check if any note in a list of IDs refers to a private note.
     * Used for validation and logging.
     */
    fun containsPrivateNoteIds(noteIds: List<String>, allNotes: List<Note>): Boolean {
        return noteIds.any { noteId ->
            val note = allNotes.find { it.id == noteId }
            note != null && isPrivate(note)
        }
    }

    /**
     * Assert that no private note IDs are present.
     * Throws SecurityException if validation fails.
     * Use for critical security checkpoints.
     */
    fun assertNoPrivateNoteIds(noteIds: List<String>, allNotes: List<Note>, operation: String, context: android.content.Context) {
        if (containsPrivateNoteIds(noteIds, allNotes)) {
            val message = context.getString(com.example.smarty.R.string.security_violation_ids, operation)
            Log.e(TAG, message)
            throw SecurityException(message)
        }
    }

    // =========================================================================
    // RESPONSE SANITIZATION
    // =========================================================================
    // These functions sanitize AI responses to ensure no private data leaks.

    /**
     * Sanitize AI response text to ensure no private note content leaks.
     * This is a best-effort check - AI should never have seen private notes,
     * but this provides defense in depth.
     *
     * @param responseText The AI response text
     * @param privateNotes List of private notes to check against
     * @return Sanitized response text
     */
    fun sanitizeResponseText(responseText: String, privateNotes: List<Note>): String {
        if (privateNotes.isEmpty()) return responseText

        var sanitized = responseText
        var leakDetected = false

        // Check for private note titles in response
        for (note in privateNotes) {
            if (note.title.length >= 5 && sanitized.contains(note.title, ignoreCase = true)) {
                Log.e(TAG, "SECURITY ALERT: Private note title detected in AI response!")
                leakDetected = true
            }

            // Check for private note IDs
            if (sanitized.contains(note.id)) {
                Log.e(TAG, "SECURITY ALERT: Private note ID detected in AI response!")
                sanitized = sanitized.replace(note.id, "[REDACTED]")
                leakDetected = true
            }
        }

        if (leakDetected) {
            Log.e(TAG, "PRIVACY BREACH DETECTED - Response may contain private data")
            // Note: We don't block the response as the AI shouldn't have access
            // to private notes in the first place. This is defense in depth.
        }

        return sanitized
    }

    // =========================================================================
    // CATEGORY COUNT SANITIZATION
    // =========================================================================

    /**
     * Get AI-safe category statistics.
     * Returns count of only AI-accessible notes per category.
     *
     * @param categories All categories
     * @param allNotes All notes
     * @return Map of category name to AI-visible note count
     */
    fun getAiSafeCategoryCounts(
        categories: List<com.example.smarty.data.model.Category>,
        allNotes: List<Note>
    ): Map<String, Int> {
        val aiVisibleNotes = getAiVisibleNotes(allNotes)

        return categories.associate { category ->
            val count = aiVisibleNotes.count { it.categoryId == category.id }
            category.name to count
        }
    }
}
