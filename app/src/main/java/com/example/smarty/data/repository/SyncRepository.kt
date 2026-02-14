package com.example.smarty.data.repository

import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.Note
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for synchronizing data with remote cloud storage (Firestore).
 */
interface SyncRepository {
    /**
     * Upload or update a note in the cloud.
     */
    suspend fun syncNote(note: Note): Result<Unit>

    /**
     * Delete a note from the cloud.
     */
    suspend fun deleteNote(noteId: String): Result<Unit>

    /**
     * Upload or update a category in the cloud.
     */
    suspend fun syncCategory(category: Category): Result<Unit>

    /**
     * Delete a category from the cloud.
     */
    suspend fun deleteCategory(categoryId: String): Result<Unit>

    /**
     * Observe remote notes for the current user.
     * Emits lists of notes that have changed/added.
     */
    fun getRemoteNotesFlow(): Flow<List<Note>>

    /**
     * Observe remote categories for the current user.
     */
    fun getRemoteCategoriesFlow(): Flow<List<Category>>

    /**
     * Initialize synchronization for the given user.
     */
    fun initializeForUser(userId: String)
}
