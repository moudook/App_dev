
package com.smarty.data.usecase

import com.smarty.data.repository.SmartRepository
import com.smarty.data.entity.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use cases for business logic - Clean Architecture approach
 */
class NoteUseCases @Inject constructor(
    private val repository: SmartRepository
) {
    suspend fun createNote(title: String, content: String, userId: Long): Long {
        val note = NoteEntity(
            supabaseId = "note_${userId}_${System.currentTimeMillis()}",
            userId = userId,
            title = title,
            content = content
        )
        return repository.createNote(note, autoTag = true)
    }

    suspend fun getNoteWithRelations(noteId: Long) = 
        repository.getNoteWithRelations(noteId)

    suspend fun linkNoteToChat(noteId: Long, chatMessageId: Long) =
        repository.linkNoteToChatMessage(noteId, chatMessageId)

    suspend fun linkNoteToEvent(noteId: Long, eventId: Long, autoLinked: Boolean = false) =
        repository.linkNoteToCalendarEvent(noteId, eventId, autoLinked = autoLinked)
}

class ChatUseCases @Inject constructor(
    private val repository: SmartRepository
) {
    suspend fun createSmartContextBundle(userId: Long) =
        repository.createSmartContextBundle(userId, "chat")
}

class CalendarUseCases @Inject constructor(
    private val repository: SmartRepository
) {
    suspend fun syncCalendarWithNotes(userId: Long) {
        // Smart synchronization logic
        repository.createSmartContextBundle(userId, "calendar")
    }
}

class AISyncUseCases @Inject constructor(
    private val repository: SmartRepository
) {
    suspend fun createReasoningTrace(
        entityType: String,
        entityId: Long,
        decision: String,
        confidence: Float
    ) = repository.createReasoningTrace(
        entityType = entityType,
        entityId = entityId,
        traceType = "DECISION",
        inputContext = mapOf("decision" to decision),
        reasoningSteps = listOf("AI analyzed context", "Generated recommendation", "User confirmed"),
        outputDecision = mapOf("action" to decision),
        confidenceScore = confidence
    )

    suspend fun restoreAISession(sessionId: String) =
        repository.restoreAgentContext(sessionId)
}

class CollaborationUseCases @Inject constructor(
    private val repository: SmartRepository
) {
    suspend fun shareNoteWithUser(noteId: Long, ownerId: Long, sharedWithId: Long) =
        repository.shareItem(noteId, "notes", ownerId, sharedWithId, "EDIT")
}

class SearchUseCases @Inject constructor(
    private val repository: SmartRepository
) {
    suspend fun searchEverything(userId: Long, query: String) =
        repository.unifiedSearch(userId, query)
}

class SyncUseCases @Inject constructor(
    private val repository: SmartRepository
) {
    suspend fun forceFullSync() = repository.syncAllPendingChanges()
    suspend fun getSyncStatus() = repository.getSyncStatus()
}
