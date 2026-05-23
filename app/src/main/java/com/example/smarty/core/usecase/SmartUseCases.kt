package com.example.smarty.core.usecase

import com.example.smarty.core.domain.model.*
import com.example.smarty.data.local.*
import com.example.smarty.data.repository.SmartRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Clean Architecture Use Cases for Smart Features
 */

// ============================================================
// NOTE USE CASES
// ============================================================

class GetNotesWithTagsUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        operator fun invoke(userId: String): Flow<List<NoteWithTags>> {
            return repository.getNotesWithTagsForAi(userId)
        }
    }

class CreateNoteWithAutoTaggingUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            note: Note,
            userId: String,
        ): Note {
            return repository.createNoteWithAutoTagging(note, userId)
        }
    }

class FindRelatedNotesUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            noteId: String,
            limit: Int = 10,
        ): List<Note> {
            return repository.findRelatedNotes(noteId, limit)
        }
    }

class UpdateNoteWithVersionUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            note: Note,
            changeDescription: String? = null,
        ) {
            repository.updateNoteWithVersion(note, changeDescription)
        }
    }

// ============================================================
// TAG USE CASES
// ============================================================

class GetOrCreateTagUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            userId: String,
            name: String,
            type: TagEntity.TagType = TagEntity.TagType.MANUAL,
        ): TagEntity {
            return repository.getOrCreateTag(userId, name, type)
        }
    }

class AssignTagToNoteUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            noteId: String,
            tagId: String,
            userId: String,
            assignedBy: String = "user",
            confidenceScore: Double = 1.0,
        ) {
            repository.assignTagToNote(noteId, tagId, userId, assignedBy, confidenceScore)
        }
    }

// ============================================================
// TASK USE CASES
// ============================================================

class CreateTaskFromNoteUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            noteId: String,
            userId: String,
            title: String,
            dueDate: Long? = null,
        ): TaskEntity {
            return repository.createTaskFromNote(noteId, userId, title, dueDate)
        }
    }

class CompleteTaskUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            taskId: String,
            userId: String,
        ) {
            repository.completeTask(taskId, userId)
        }
    }

// ============================================================
// REASONING USE CASES
// ============================================================

class RecordReasoningStepUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            sessionId: String,
            userId: String,
            stepType: String,
            title: String,
            content: String,
            entityType: String? = null,
            entityId: String? = null,
            inputData: String? = null,
            outputData: String? = null,
            confidenceScore: Double = 0.5,
        ): ReasoningTraceEntity {
            return repository.recordReasoningStep(
                sessionId, userId, stepType, title, content,
                entityType, entityId, inputData, outputData, confidenceScore,
            )
        }
    }

class CreateReasoningSummaryUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            sessionId: String,
            userId: String,
            oneLiner: String,
            briefSummary: String,
            detailedSummary: String,
            reasoningType: String,
        ): ReasoningSummaryEntity {
            return repository.createReasoningSummary(
                sessionId,
                userId,
                oneLiner,
                briefSummary,
                detailedSummary,
                reasoningType,
            )
        }
    }

class SaveAgentCheckpointUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            sessionId: String,
            userId: String,
            stateJson: String,
            contextJson: String? = null,
            memoryJson: String? = null,
            workflowId: String? = null,
            checkpointType: AgentCheckpointEntity.CheckpointType = AgentCheckpointEntity.CheckpointType.AUTO,
        ): AgentCheckpointEntity {
            return repository.saveAgentCheckpoint(
                sessionId,
                userId,
                stateJson,
                contextJson,
                memoryJson,
                workflowId,
                checkpointType,
            )
        }
    }

// ============================================================
// SEARCH USE CASES
// ============================================================

class RecordSearchUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            userId: String,
            query: String,
            searchScope: String = "all",
            aiEnhanced: Boolean = false,
        ): SearchHistoryEntity {
            return repository.recordSearch(userId, query, searchScope, aiEnhanced)
        }
    }

class UnifiedSearchUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            userId: String,
            query: String,
            includePrivate: Boolean = false,
        ): SmartRepository.UnifiedSearchResults {
            return repository.unifiedSearch(userId, query, includePrivate)
        }
    }

// ============================================================
// SHARING USE CASES
// ============================================================

class ShareItemUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            ownerId: String,
            itemType: String,
            itemId: String,
            sharedWithId: String? = null,
            permission: SharedItemEntity.Permission = SharedItemEntity.Permission.VIEW,
            expiresAt: Long? = null,
        ): SharedItemEntity {
            return repository.shareItem(ownerId, itemType, itemId, sharedWithId, permission, expiresAt)
        }
    }

// ============================================================
// DIGEST USE CASES
// ============================================================

class GenerateDailyDigestUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            userId: String,
            date: Long,
        ): DailyDigestEntity {
            return repository.generateDailyDigest(userId, date)
        }
    }

// ============================================================
// SYNC USE CASES
// ============================================================

class StartBackgroundSyncUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        operator fun invoke() {
            repository.startBackgroundSync()
        }
    }

class SyncNowUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(): Boolean {
            // Would trigger immediate sync
            return true
        }
    }

// ============================================================
// USER USE CASES
// ============================================================

class GetOrCreateUserUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(
            firebaseUid: String,
            email: String?,
            displayName: String?,
        ): UserEntity {
            return repository.getOrCreateUser(firebaseUid, email, displayName)
        }
    }

class GetUserWithSyncStateUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(userId: String): SmartRepository.UserWithSyncState? {
            return repository.getUserWithSyncState(userId)
        }
    }

// ============================================================
// AI CONTEXT USE CASES
// ============================================================

class GetAIContextUseCase
    @Inject
    constructor(
        private val repository: SmartRepository,
    ) {
        suspend operator fun invoke(userId: String): AIContext {
            val notes = repository.getNotesWithTagsForAi(userId).first()
            val recentReasoning = repository.smartDao.getUserRecentReasoningTraces(userId, 10)

            return AIContext(
                userId = userId,
                notes = notes,
                recentReasoning = recentReasoning,
                timestamp = System.currentTimeMillis(),
            )
        }

        data class AIContext(
            val userId: String,
            val notes: List<NoteWithTags>,
            val recentReasoning: List<ReasoningTraceEntity>,
            val timestamp: Long,
        )
    }
