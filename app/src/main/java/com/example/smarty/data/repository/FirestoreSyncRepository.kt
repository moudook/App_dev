package com.example.smarty.data.repository

import android.util.Log
import com.example.smarty.core.common.util.PrivacyGuard
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.core.domain.model.ProcessingStatus
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

class FirestoreSyncRepository : SyncRepository {
    private val firestore = FirebaseFirestore.getInstance()
    private var currentUserId: String? = null
    private var notesListener: ListenerRegistration? = null
    private var categoriesListener: ListenerRegistration? = null

    companion object {
        private const val TAG = "FirestoreSyncRepo"
        private const val COLLECTION_USERS = "users"
        private const val COLLECTION_NOTES = "notes"
        private const val COLLECTION_CATEGORIES = "categories"
    }

    override fun initializeForUser(userId: String) {
        if (currentUserId != userId) {
            currentUserId = userId
            Log.d(TAG, "Initialized Firestore Sync for user: $userId")
        }
    }

    // ============================================================================================
    // NOTES
    // ============================================================================================

    override suspend fun syncNote(note: Note): Result<Unit> {
        val userId = currentUserId ?: return Result.failure(IllegalStateException("User not initialized"))

        // PRIVACY ENFORCEMENT: Private notes must NEVER sync to cloud
        if (note.isPrivate) {
            Log.d(TAG, "PrivacyGuard: Blocking sync for private note ${note.id}")
            return deleteNote(note.id)
        }

        return try {
            val noteMap = noteToMap(note)
            firestore.collection(COLLECTION_USERS)
                .document(userId)
                .collection(COLLECTION_NOTES)
                .document(note.id)
                .set(noteMap)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing note ${note.id}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteNote(noteId: String): Result<Unit> {
        val userId = currentUserId ?: return Result.failure(IllegalStateException("User not initialized"))

        return try {
            firestore.collection(COLLECTION_USERS)
                .document(userId)
                .collection(COLLECTION_NOTES)
                .document(noteId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting note $noteId", e)
            Result.failure(e)
        }
    }

    override fun getRemoteNotesFlow(): Flow<List<Note>> {
        val userId = currentUserId ?: return flowOf(emptyList())

        return callbackFlow {
            val collectionRef = firestore.collection(COLLECTION_USERS)
                .document(userId)
                .collection(COLLECTION_NOTES)

            // Only listen to active notes to reduce bandwidth?
            // Or listen to everything and filter locally?
            // Listening to everything is safer for full sync.
            val registration = collectionRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Listen failed.", error)
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val notes = snapshot.documents.mapNotNull { doc ->
                        try {
                            mapToNote(doc.id, doc.data ?: emptyMap())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing note ${doc.id}", e)
                            null
                        }
                    }
                    trySend(notes)
                }
            }

            awaitClose { registration.remove() }
        }
    }

    // ============================================================================================
    // CATEGORIES
    // ============================================================================================

    override suspend fun syncCategory(category: Category): Result<Unit> {
        val userId = currentUserId ?: return Result.failure(IllegalStateException("User not initialized"))

        return try {
            val categoryMap = categoryToMap(category)
            firestore.collection(COLLECTION_USERS)
                .document(userId)
                .collection(COLLECTION_CATEGORIES)
                .document(category.id)
                .set(categoryMap)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing category ${category.id}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteCategory(categoryId: String): Result<Unit> {
        val userId = currentUserId ?: return Result.failure(IllegalStateException("User not initialized"))

        return try {
            firestore.collection(COLLECTION_USERS)
                .document(userId)
                .collection(COLLECTION_CATEGORIES)
                .document(categoryId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting category $categoryId", e)
            Result.failure(e)
        }
    }

    override fun getRemoteCategoriesFlow(): Flow<List<Category>> {
        val userId = currentUserId ?: return flowOf(emptyList())

        return callbackFlow {
            val collectionRef = firestore.collection(COLLECTION_USERS)
                .document(userId)
                .collection(COLLECTION_CATEGORIES)

            val registration = collectionRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Listen failed.", error)
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val categories = snapshot.documents.mapNotNull { doc ->
                        try {
                            mapToCategory(doc.id, doc.data ?: emptyMap())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing category ${doc.id}", e)
                            null
                        }
                    }
                    trySend(categories)
                }
            }

            awaitClose { registration.remove() }
        }
    }

    // ============================================================================================
    // MAPPERS
    // ============================================================================================

    private fun noteToMap(note: Note): Map<String, Any?> {
        return mapOf(
            "id" to note.id,
            "title" to note.title,
            "content" to note.content,
            "summary" to note.summary,
            "sourceUrl" to note.sourceUrl,
            "imageUri" to note.imageUri,
            "fileUri" to note.fileUri,
            "fileName" to note.fileName,
            "fileMimeType" to note.fileMimeType,
            "fileSize" to note.fileSize,
            "type" to note.type.name,
            "categoryId" to note.categoryId,
            "categoryName" to note.categoryName,
            "whySaved" to note.whySaved,
            "processingStatus" to note.processingStatus.name,
            "createdAt" to note.createdAt,
            "updatedAt" to note.updatedAt,
            "isArchived" to note.isArchived,
            "todoContent" to note.todoContent,
            "excludeFromAiChat" to note.excludeFromAiChat,
            "isFullPrivacy" to note.isFullPrivacy,
            "isAiCreated" to note.isAiCreated,
            "attachmentsJson" to note.attachmentsJson,
            "tagsJson" to note.tagsJson,
            "isViewed" to note.isViewed,
            "isPinned" to note.isPinned,
            "reminderText" to note.reminderText,
            "reminderExpiresAt" to note.reminderExpiresAt,
            "chunkAnalysesJson" to note.chunkAnalysesJson
        )
    }

    private fun mapToNote(id: String, map: Map<String, Any>): Note {
        return Note(
            id = id,
            title = map["title"] as? String ?: "",
            content = map["content"] as? String ?: "",
            summary = map["summary"] as? String,
            sourceUrl = map["sourceUrl"] as? String,
            imageUri = map["imageUri"] as? String,
            fileUri = map["fileUri"] as? String,
            fileName = map["fileName"] as? String,
            fileMimeType = map["fileMimeType"] as? String,
            fileSize = (map["fileSize"] as? Number)?.toLong(),
            type = try { NoteType.valueOf(map["type"] as? String ?: "BRAIN_DUMP") } catch(e: Exception) { NoteType.BRAIN_DUMP },
            categoryId = map["categoryId"] as? String,
            categoryName = map["categoryName"] as? String,
            whySaved = map["whySaved"] as? String,
            processingStatus = try { ProcessingStatus.valueOf(map["processingStatus"] as? String ?: "COMPLETED") } catch(e: Exception) { ProcessingStatus.COMPLETED },
            createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L,
            updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L,
            isArchived = map["isArchived"] as? Boolean ?: false,
            todoContent = map["todoContent"] as? String,
            excludeFromAiChat = map["excludeFromAiChat"] as? Boolean ?: false,
            isFullPrivacy = map["isFullPrivacy"] as? Boolean ?: false,
            isAiCreated = map["isAiCreated"] as? Boolean ?: false,
            attachmentsJson = map["attachmentsJson"] as? String,
            tagsJson = map["tagsJson"] as? String,
            isViewed = map["isViewed"] as? Boolean ?: false,
            isPinned = map["isPinned"] as? Boolean ?: false,
            reminderText = map["reminderText"] as? String,
            reminderExpiresAt = (map["reminderExpiresAt"] as? Number)?.toLong(),
            chunkAnalysesJson = map["chunkAnalysesJson"] as? String
        )
    }

    private fun categoryToMap(category: Category): Map<String, Any?> {
        return mapOf(
            "id" to category.id,
            "name" to category.name,
            "description" to category.description,
            "noteCount" to category.noteCount,
            "isAiGenerated" to category.isAiGenerated,
            "createdAt" to category.createdAt,
            "lastUpdated" to category.lastUpdated
        )
    }

    private fun mapToCategory(id: String, map: Map<String, Any>): Category {
        return Category(
            id = id,
            name = map["name"] as? String ?: "Unknown",
            description = map["description"] as? String,
            noteCount = (map["noteCount"] as? Number)?.toInt() ?: 0,
            isAiGenerated = map["isAiGenerated"] as? Boolean ?: true,
            createdAt = (map["createdAt"] as? Number)?.toLong() ?: 0L,
            lastUpdated = (map["lastUpdated"] as? Number)?.toLong() ?: 0L
        )
    }
}
