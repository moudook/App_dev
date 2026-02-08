package com.example.smarty.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.smarty.data.model.Attachment
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteAttachment
import com.example.smarty.data.model.TodoItem
import com.example.smarty.di.ServiceLocator

/**
 * ViewModel for the Dashboard/Home screen.
 * Handles Note and Category operations.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val noteOperationsManager = ServiceLocator.provideNoteOperationsManager(application)
    private val repository = ServiceLocator.provideRepository(application)

    // Exposed States
    val isProcessing = noteOperationsManager.isProcessing
    val allNotes = noteOperationsManager.getAllNotes()
    val allCategories = noteOperationsManager.getAllCategories()
    val archivedNotes = noteOperationsManager.getArchivedNotes()

    // Note Operations
    fun addNote(content: String, attachments: List<Attachment> = emptyList()) {
        noteOperationsManager.addNoteWithAttachments(content, attachments)
    }

    fun deleteNote(note: Note) {
        noteOperationsManager.deleteNote(note)
    }

    fun archiveNote(noteId: String) {
        noteOperationsManager.archiveNote(noteId)
    }

    fun unarchiveNote(noteId: String) {
        noteOperationsManager.unarchiveNote(noteId)
    }

    fun pinNote(noteId: String) {
        noteOperationsManager.pinNote(noteId)
    }

    fun unpinNote(noteId: String) {
        noteOperationsManager.unpinNote(noteId)
    }

    fun updateNoteTodos(noteId: String, todos: List<TodoItem>, activeNotes: List<Note>, archivedNotes: List<Note>) {
        noteOperationsManager.updateNoteTodos(noteId, todos, activeNotes, archivedNotes)
    }

    fun editNote(
        noteId: String,
        newTitle: String,
        newContent: String,
        newSummary: String?,
        newWhySaved: String?,
        newAttachments: List<NoteAttachment>?
    ) {
        noteOperationsManager.editNote(noteId, newTitle, newContent, newSummary, newWhySaved, newAttachments)
    }

    // Category Operations
    fun createCategory(name: String) {
        noteOperationsManager.createUserCategory(name)
    }

    fun deleteCategory(category: Category) {
        noteOperationsManager.deleteCategory(category)
    }

    fun renameCategory(category: Category, newName: String) {
        noteOperationsManager.renameCategory(category, newName)
    }

    // Data Sync
    fun refreshNotes() {
        noteOperationsManager.refreshNotes()
    }

    fun syncCategoryCounts() {
        noteOperationsManager.syncCategoryCounts()
    }
}
