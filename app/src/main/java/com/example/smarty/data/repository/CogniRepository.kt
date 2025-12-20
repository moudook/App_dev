package com.example.smarty.data.repository

import androidx.room.Transaction
import com.example.smarty.data.local.CategoryDao
import com.example.smarty.data.local.NoteDao
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.Note
import kotlinx.coroutines.flow.Flow

class CogniRepository(
    private val noteDao: NoteDao,
    private val categoryDao: CategoryDao
) {
    // Notes
    fun getAllNotes(): Flow<List<Note>> = noteDao.getAllNotes()

    fun getNotesByCategory(categoryId: String): Flow<List<Note>> =
        noteDao.getNotesByCategory(categoryId)

    fun getArchivedNotes(): Flow<List<Note>> = noteDao.getArchivedNotes()

    suspend fun getNoteById(id: String): Note? = noteDao.getNoteById(id)

    @Transaction
    suspend fun insertNote(note: Note) {
        noteDao.insertNote(note)
        note.categoryId?.let { categoryDao.incrementNoteCount(it) }
    }

    suspend fun updateNote(note: Note) = noteDao.updateNote(note)

    @Transaction
    suspend fun deleteNote(note: Note) {
        noteDao.deleteNote(note)
        note.categoryId?.let { categoryDao.decrementNoteCount(it) }
    }

    @Transaction
    suspend fun archiveNote(noteId: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        noteDao.archiveNote(noteId)
        note.categoryId?.let { categoryDao.decrementNoteCount(it) }
    }

    @Transaction
    suspend fun unarchiveNote(noteId: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        noteDao.unarchiveNote(noteId)
        note.categoryId?.let { categoryDao.incrementNoteCount(it) }
    }

    @Transaction
    suspend fun updateNoteCategory(noteId: String, categoryId: String, categoryName: String) {
        val note = noteDao.getNoteById(noteId) ?: return
        val oldCategoryId = note.categoryId
        noteDao.updateNoteCategory(noteId, categoryId, categoryName)
        oldCategoryId?.let { categoryDao.decrementNoteCount(it) }
        categoryDao.incrementNoteCount(categoryId)
    }

    // Categories
    fun getAllCategories(): Flow<List<Category>> = categoryDao.getAllCategories()

    suspend fun getCategoryById(id: String): Category? = categoryDao.getCategoryById(id)

    suspend fun getCategoryByName(name: String): Category? = categoryDao.getCategoryByName(name)

    suspend fun insertCategory(category: Category) = categoryDao.insertCategory(category)

    suspend fun updateCategory(category: Category) = categoryDao.updateCategory(category)

    suspend fun deleteCategory(category: Category) = categoryDao.deleteCategory(category)

    /**
     * Safely delete a category with proper cleanup (BUG-028 fix).
     * Uses atomic SQL UPDATE to clear categoryId from all notes,
     * avoiding issues with stale StateFlow data.
     */
    @Transaction
    suspend fun deleteCategoryWithCleanup(category: Category) {
        // First, atomically clear categoryId from all notes in this category
        noteDao.clearCategoryFromNotes(category.id)
        // Then delete the category
        categoryDao.deleteCategory(category)
    }

    suspend fun getOrCreateCategory(name: String): Category {
        return categoryDao.getCategoryByName(name) ?: Category(name = name).also {
            categoryDao.insertCategory(it)
        }
    }

    // Sync/Recalculation - fixes any count mismatches
    suspend fun syncAllCategoryCounts() = categoryDao.recalculateAllCounts()

    suspend fun syncCategoryCount(categoryId: String) = categoryDao.recalculateCategoryCount(categoryId)
}
