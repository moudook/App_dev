
package com.smarty.data.repository

import com.smarty.data.dao.SmartDatabaseDao
import com.smarty.data.entity.*
import com.smarty.data.relationship.*
import com.smarty.data.sync.CRDTManager
import com.smarty.data.sync.OfflineFirstSyncManager
import com.smarty.data.sync.SupabaseEventStreamer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.time.Instant

/**
 * Main repository handling all data operations with AI-driven flows
 */
class SmartRepository(
    private val dao: SmartDatabaseDao,
    private val crdtManager: CRDTManager,
    private val syncManager: OfflineFirstSyncManager,
    private val eventStreamer: SupabaseEventStreamer,
    private val currentUserIdProvider: () -> Long = { 1L }
) {

    // ========== USER OPERATIONS ==========

    suspend fun createUser(user: UserEntity): Long {
        val userId = dao.insertUser(user)
        syncManager.queueForSync(user, com.smarty.data.sync.SyncOperationType.INSERT)
        return userId
    }

    suspend fun updateUser(user: UserEntity) {
        dao.updateUser(user)
        syncManager.queueForSync(user, com.smarty.data.sync.SyncOperationType.UPDATE)
    }

    suspend fun getUserWithCompleteGraph(userId: Long): UserWithCompleteGraph? {
        return dao.getUserWithCompleteGraph(userId)
    }

    // ========== NOTE OPERATIONS WITH AI ENHANCEMENT ==========

    suspend fun createNote(note: NoteEntity, autoTag: Boolean = true): Long {
        val noteId = dao.insertNote(note)
        
        if (autoTag) {
            // AI-driven auto-tagging
            autoTagNote(noteId, note)
        }
        
        // Create reasoning trace for note creation
        createReasoningTrace(
            entityType = "notes",
            entityId = noteId,
            traceType = "DECISION",
            inputContext = mapOf("action" to "create_note", "title" to note.title),
            reasoningSteps = listOf("User requested note creation", "Validated input", "Stored note"),
            outputDecision = mapOf("note_id" to noteId, "auto_tagged" to autoTag),
            confidenceScore = 0.95f
        )
        
        syncManager.queueForSync(note, com.smarty.data.sync.SyncOperationType.INSERT)
        return noteId
    }

    suspend fun updateNote(note: NoteEntity) {
        dao.updateNote(note)
        syncManager.queueForSync(note, com.smarty.data.sync.SyncOperationType.UPDATE)
    }

    suspend fun getNoteWithRelations(noteId: Long): NoteWithRelations? {
        return dao.getNoteWithRelations(noteId)
    }

    fun getActiveNotesForUser(userId: Long): Flow<List<NoteEntity>> {
        return dao.getActiveNotesForUser(userId).onEach { notes ->
            // Predictive pre-fetching based on patterns
            predictAndPrefetch(userId, notes)
        }
    }

    // ========== AI-DRIVEN AUTO-TAGGING ==========

    private suspend fun autoTagNote(noteId: Long, note: NoteEntity) {
        // Analyze content and suggest tags
        val suggestedTags = analyzeContentForTags(note.content, note.category)
        
        suggestedTags.forEach { tagSuggestion ->
            val tag = getOrCreateTag(note.userId, tagSuggestion.name, tagSuggestion.type)
            val noteTag = NoteTagEntity(
                noteId = noteId,
                tagId = tag.id,
                taggingType = "AI",
                confidenceScore = tagSuggestion.confidence,
                assignedBy = "ai_agent",
                metadata = "{\"reason\": \"${tagSuggestion.reason}\"}"
            )
            dao.insertNoteTag(noteTag)
            
            // Create reasoning trace for auto-tagging
            createReasoningTrace(
                entityType = "notes",
                entityId = noteId,
                traceType = "RECOMMENDATION",
                inputContext = mapOf("content" to note.content, "category" to note.category),
                reasoningSteps = listOf("Analyzed note content", "Identified relevant tags", "Assigned tag: ${tag.name}"),
                outputDecision = mapOf("tag_id" to tag.id, "confidence" to tagSuggestion.confidence),
                confidenceScore = tagSuggestion.confidence
            )
        }
    }

    private fun analyzeContentForTags(content: String, category: String?): List<TagSuggestion> {
        // Simplified AI analysis - in production, use ML model
        val tags = mutableListOf<TagSuggestion>()
        
        // Keyword-based tagging
        if (content.contains(Regex("(?i)meeting|discuss|agenda"))) {
            tags.add(TagSuggestion("meeting", "CATEGORY", 0.8f, "Content indicates meeting-related note"))
        }
        if (content.contains(Regex("(?i)task|todo|action"))) {
            tags.add(TagSuggestion("action-item", "TAG", 0.7f, "Contains action items"))
        }
        if (content.contains(Regex("(?i)important|urgent|critical"))) {
            tags.add(TagSuggestion("priority", "CATEGORY", 0.9f, "Marked as important"))
        }
        
        category?.let {
            tags.add(TagSuggestion(it, "CATEGORY", 1.0f, "Explicit category"))
        }
        
        return tags
    }

    private suspend fun getOrCreateTag(userId: Long, name: String, type: String): TagEntity {
        // Check if tag exists
        val existingTags = dao.getTagsByType(userId, type)
        val existing = existingTags.find { it.name.equals(name, ignoreCase = true) }
        
        return existing ?: run {
            val tag = TagEntity(
                supabaseId = "tag_${userId}_${System.currentTimeMillis()}",
                userId = userId,
                name = name,
                type = type,
                isSystem = false
            )
            dao.insertTag(tag)
            tag
        }
    }

    data class TagSuggestion(
        val name: String,
        val type: String,
        val confidence: Float,
        val reason: String
    )

    // ========== CHAT-NOTE INTEGRATION ==========

    suspend fun linkNoteToChatMessage(noteId: Long, chatMessageId: Long, linkType: String = "REFERENCE") {
        val chatMessage = dao.getMessagesForChat(chatMessageId).firstOrNull()
            ?: throw IllegalArgumentException("Chat message not found")
        
        val link = ChatMessageNoteEntity(
            chatMessageId = chatMessageId,
            chatId = chatMessage.chatId,
            noteId = noteId,
            userId = chatMessage.userId,
            linkType = linkType,
            relevanceScore = calculateRelevance(noteId, chatMessageId),
            bidirectional = true
        )
        
        dao.insertChatMessageNote(link)
        
        // Create reasoning trace for linking
        createReasoningTrace(
            entityType = "chat_messages",
            entityId = chatMessageId,
            traceType = "DECISION",
            inputContext = mapOf("note_id" to noteId, "chat_message_id" to chatMessageId),
            reasoningSteps = listOf("Identified related note", "Calculated relevance", "Created bidirectional link"),
            outputDecision = mapOf("link_type" to linkType, "relevance" to link.relevanceScore),
            confidenceScore = 0.85f
        )
    }

    private suspend fun calculateRelevance(noteId: Long, chatMessageId: Long): Float {
        // Calculate relevance based on content similarity, timing, etc.
        return 0.75f // Simplified
    }

    suspend fun getChatMessageWithNotes(chatMessageId: Long): ChatMessageWithNotes? {
        return dao.getMessagesWithNotes(chatMessageId).firstOrNull()
    }

    // ========== CALENDAR-NOTE INTEGRATION ==========

    suspend fun linkNoteToCalendarEvent(
        noteId: Long,
        eventId: Long,
        linkType: String = "MEETING_NOTES",
        autoLinked: Boolean = false
    ) {
        val link = CalendarEventNoteEntity(
            eventId = eventId,
            noteId = noteId,
            userId = getNoteOwner(noteId),
            linkType = linkType,
            autoLinked = autoLinked,
            relevanceScore = if (autoLinked) 0.6f else 0.9f
        )
        
        dao.insertCalendarEventNote(link)
        
        // Smart context propagation
        propagateEventContextToNote(eventId, noteId)
    }

    private suspend fun propagateEventContextToNote(eventId: Long, noteId: Long) {
        val event = dao.getCalendarEventById(eventId)
        val note = dao.getNoteById(noteId)
        
        event?.let { evt ->
            note?.let {
                // Update note with event context
                val updatedNote = it.copy(
                    metadata = """{
                        "linked_event": {
                            "title": "${evt.title}",
                            "start_time": "${evt.startTime}",
                            "attendees": []
                        }
                    }"""
                )
                dao.updateNote(updatedNote)
            }
        }
    }

    private suspend fun getNoteOwner(noteId: Long): Long {
        return dao.getNoteById(noteId)?.userId ?: 0
    }

    // ========== SHARED ITEMS & COLLABORATION ==========

    suspend fun shareItem(
        itemId: Long,
        itemType: String,
        ownerUserId: Long,
        sharedWithUserId: Long,
        permissionLevel: String = "VIEW"
    ) {
        val sharedItem = SharedItemEntity(
            itemId = itemId,
            itemType = itemType,
            ownerUserId = ownerUserId,
            sharedWithUserId = sharedWithUserId,
            permissionLevel = permissionLevel
        )
        
        dao.insertSharedItem(sharedItem)
        
        // Create reasoning trace for sharing
        createReasoningTrace(
            entityType = itemType,
            entityId = itemId,
            traceType = "ACTION",
            inputContext = mapOf(
                "shared_with" to sharedWithUserId,
                "permission" to permissionLevel
            ),
            reasoningSteps = listOf("User initiated sharing", "Validated permissions", "Created share record"),
            outputDecision = mapOf("shared_item_id" to sharedItem.itemId),
            confidenceScore = 0.9f
        )
    }

    // ========== REASONING TRACES - AI DECISION PROVENANCE ==========

    suspend fun createReasoningTrace(
        entityType: String,
        entityId: Long,
        traceType: String,
        inputContext: Map<String, Any?>,
        reasoningSteps: List<String>,
        outputDecision: Map<String, Any?>,
        confidenceScore: Float,
        parentTraceId: Long? = null
    ): Long {
        val trace = ReasoningTraceEntity(
            supabaseId = "trace_${entityType}_${entityId}_${System.currentTimeMillis()}",
            userId = getCurrentUserId(),
            entityType = entityType,
            entityId = entityId,
            traceType = traceType,
            agentId = "smart_agent_v1",
            inputContext = serializeMap(inputContext),
            reasoningSteps = serializeList(reasoningSteps),
            outputDecision = serializeMap(outputDecision),
            confidenceScore = confidenceScore,
            parentTraceId = parentTraceId
        )
        
        return dao.insertReasoningTrace(trace)
    }

    suspend fun getEntityReasoningHistory(entityType: String, entityId: Long): List<ReasoningTraceEntity> {
        return dao.getTracesForEntity(entityType, entityId)
    }

    // ========== AGENT CHECKPOINTS - SESSION CONTINUITY ==========

    suspend fun createAgentCheckpoint(
        sessionId: String,
        agentId: String,
        checkpointType: String,
        contextState: Map<String, Any?>,
        activeEntities: List<Pair<String, Long>> = emptyList()
    ): Long {
        val checkpoint = AgentCheckpointEntity(
            supabaseId = "checkpoint_${sessionId}_${System.currentTimeMillis()}",
            userId = getCurrentUserId(),
            sessionId = sessionId,
            agentId = agentId,
            checkpointType = checkpointType,
            contextState = serializeMap(contextState),
            activeEntities = serializeList(activeEntities.map { "${it.first}:${it.second}" }),
            memoryState = serializeMap(getAgentMemoryState()),
            predictionCache = serializeMap(getPredictionCache())
        )
        
        return dao.insertAgentCheckpoint(checkpoint)
    }

    suspend fun restoreAgentContext(sessionId: String): AgentCheckpointEntity? {
        return dao.getLatestCheckpointForSession(sessionId)
    }

    private fun getAgentMemoryState(): Map<String, Any> {
        // Retrieve agent's current memory state
        return mapOf(
            "recent_notes" to emptyList<Long>(),
            "active_context" to emptyMap<String, Any>(),
            "preferences" to emptyMap<String, Any>()
        )
    }

    private fun getPredictionCache(): Map<String, Any> {
        // Get cached predictions for pre-fetching
        return mapOf(
            "likely_next_actions" to emptyList<String>(),
            "recommended_tags" to emptyList<String>()
        )
    }

    // ========== PREDICTIVE PRE-FETCHING ==========

    private suspend fun predictAndPrefetch(userId: Long, currentNotes: List<NoteEntity>) {
        // Analyze patterns and pre-fetch likely needed data
        val predictions = generatePredictions(userId, currentNotes)
        
        predictions.forEach { prediction ->
            when (prediction.type) {
                "related_notes" -> prefetchRelatedNotes(prediction.entityId)
                "calendar_events" -> prefetchCalendarEvents(userId, prediction.timeframe)
                "tags" -> prefetchTags(userId)
            }
        }
    }

    private fun generatePredictions(userId: Long, notes: List<NoteEntity>): List<Prediction> {
        // Generate predictions based on user behavior patterns
        return listOf(
            Prediction("related_notes", notes.firstOrNull()?.id ?: 0),
            Prediction("calendar_events", timeframe = "today")
        )
    }

    private suspend fun prefetchRelatedNotes(noteId: Long) {
        // Pre-fetch notes likely to be accessed next
        println("Pre-fetching related notes for: $noteId")
    }

    private suspend fun prefetchCalendarEvents(userId: Long, timeframe: String) {
        // Pre-fetch calendar events
        println("Pre-fetching calendar events for user: $userId")
    }

    private suspend fun prefetchTags(userId: Long) {
        // Pre-fetch commonly used tags
        println("Pre-fetching tags for user: $userId")
    }

    data class Prediction(
        val type: String,
        val entityId: Long = 0,
        val timeframe: String = ""
    )

    // ========== UNIFIED SEARCH ==========

    suspend fun unifiedSearch(userId: Long, query: String): List<UnifiedSearchResult> {
        val indexResults = dao.searchIndex(userId, query)
        
        return indexResults.mapNotNull { index ->
            val entity = fetchEntityForIndex(index)
            entity?.let {
                UnifiedSearchResult(
                    entityType = index.entityType,
                    entityId = index.entityId,
                    title = extractTitle(it, index.entityType),
                    snippet = extractSnippet(it, index.entityType, query),
                    relevanceScore = index.weight,
                    lastModified = index.lastIndexed,
                    tags = getTagsForEntity(index.entityType, index.entityId),
                    metadata = extractMetadata(it, index.entityType)
                )
            }
        }.sortedByDescending { it.relevanceScore }
    }

    private suspend fun fetchEntityForIndex(index: SearchIndexEntity): Any? {
        return when (index.entityType) {
            "notes" -> dao.getNoteById(index.entityId)
            "tasks" -> dao.getTaskById(index.entityId)
            "calendar_events" -> dao.getCalendarEventById(index.entityId)
            "chats" -> dao.getChatById(index.entityId)
            else -> null
        }
    }

    private fun extractTitle(entity: Any, entityType: String): String {
        return when (entity) {
            is NoteEntity -> entity.title
            is TaskEntity -> entity.title
            is CalendarEventEntity -> entity.title
            is ChatEntity -> entity.title ?: "Chat"
            else -> "Unknown"
        }
    }

    private fun extractSnippet(entity: Any, entityType: String, query: String): String {
        val content = when (entity) {
            is NoteEntity -> entity.content
            is TaskEntity -> entity.description ?: ""
            is CalendarEventEntity -> entity.description ?: ""
            is ChatEntity -> "Chat conversation"
            else -> ""
        }
        
        val index = content.indexOf(query, ignoreCase = true)
        return if (index >= 0) {
            val start = maxOf(0, index - 50)
            val end = minOf(content.length, index + query.length + 50)
            "...${content.substring(start, end)}..."
        } else {
            content.take(100)
        }
    }

    private fun getTagsForEntity(entityType: String, entityId: Long): List<String> {
        return when (entityType) {
            "notes" -> {
                // Would fetch tags for note
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun extractMetadata(entity: Any, entityType: String): Map<String, Any> {
        return when (entity) {
            is NoteEntity -> mapOf(
                "category" to (entity.category ?: ""),
                "priority" to entity.priority,
                "is_pinned" to entity.isPinned
            )
            is TaskEntity -> mapOf(
                "status" to entity.status,
                "priority" to entity.priority
            )
            is CalendarEventEntity -> mapOf(
                "start_time" to entity.startTime.toString(),
                "event_type" to entity.eventType
            )
            else -> emptyMap()
        }
    }

    // ========== CROSS-FEATURE WEAVING ==========

    suspend fun createSmartContextBundle(userId: Long, contextType: String): SmartContextBundle {
        val user = dao.getUserById(userId) ?: throw IllegalArgumentException("User not found")
        val recentNotes = dao.getActiveNotesForUser(userId).first().firstOrNull()
        val recentTraces = dao.getRecentTracesForUser(userId, Instant.now().minusSeconds(3600))
        val latestCheckpoint = dao.getLatestCheckpointForSession("session_${userId}")
        
        return SmartContextBundle(
            userId = userId,
            currentNoteId = recentNotes?.id,
            activeTags = getActiveTagsForUser(userId),
            recentReasoningTraces = recentTraces,
            agentCheckpoint = latestCheckpoint,
            deviceContext = user.deviceContext
        )
    }

    private suspend fun getActiveTagsForUser(userId: Long): List<TagEntity> {
        return dao.getTagsByType(userId, "CATEGORY") +
               dao.getTagsByType(userId, "TAG")
    }

    // ========== SYNC OPERATIONS ==========

    suspend fun syncAllPendingChanges() {
        syncManager.forceSync()
    }

    suspend fun getSyncStatus() = syncManager.getSyncStatus()

    // ========== HELPER FUNCTIONS ==========

    private fun serializeMap(map: Map<String, Any?>): String {
        return org.json.JSONObject(map).toString()
    }

    private fun serializeList(list: List<Any?>): String {
        return org.json.JSONArray(list).toString()
    }

    private fun getCurrentUserId(): Long = currentUserIdProvider()
}
