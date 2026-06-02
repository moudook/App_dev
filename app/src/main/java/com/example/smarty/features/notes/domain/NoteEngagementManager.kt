package com.example.smarty.features.notes.domain

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.data.repository.SmartyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.Calendar

class NoteEngagementManager(
    private val repository: SmartyRepository,
    private val context: Context,
) {
    companion object {
        private const val TAG = "NoteEngagement"
        private const val PREFS_NAME = "note_engagement_prefs"
        private const val KEY_STREAK_COUNT = "note_streak_count"
        private const val KEY_LAST_NOTE_DATE = "last_note_date"
        private const val KEY_NOTE_OF_THE_DAY_DATE = "note_of_the_day_date"
        private const val KEY_NOTE_OF_THE_DAY_ID = "note_of_the_day_id"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _streakCount = MutableStateFlow(getCurrentStreak())
    val streakCount: StateFlow<Int> = _streakCount.asStateFlow()
    private val _noteOfTheDay = MutableStateFlow<Note?>(null)
    val noteOfTheDay: StateFlow<Note?> = _noteOfTheDay.asStateFlow()
    private val _smartSuggestions = MutableStateFlow<List<Note>>(emptyList())
    val smartSuggestions: StateFlow<List<Note>> = _smartSuggestions.asStateFlow()

    fun onNoteCreated() {
        val today = getTodayDateKey()
        val lastDate = prefs.getString(KEY_LAST_NOTE_DATE, null)
        if (lastDate != today) {
            val yesterday = getYesterdayDateKey()
            val currentStreak = prefs.getInt(KEY_STREAK_COUNT, 0)
            val newStreak = if (lastDate == yesterday) currentStreak + 1 else 1
            prefs
                .edit()
                .putInt(KEY_STREAK_COUNT, newStreak)
                .putString(KEY_LAST_NOTE_DATE, today)
                .apply()
            _streakCount.value = newStreak
            Log.d(TAG, "Note streak updated: $newStreak days")
        }
    }

    suspend fun getNoteOfTheDay(): Note? {
        val today = getTodayDateKey()
        if (prefs.getString(KEY_NOTE_OF_THE_DAY_DATE, null) == today) return _noteOfTheDay.value
        val suggestedNote = findResurfacedNote()
        if (suggestedNote != null) {
            prefs
                .edit()
                .putString(KEY_NOTE_OF_THE_DAY_DATE, today)
                .putString(KEY_NOTE_OF_THE_DAY_ID, suggestedNote.id)
                .apply()
            _noteOfTheDay.value = suggestedNote
            Log.d(TAG, "Note of the Day: ${suggestedNote.title}")
        }
        return suggestedNote
    }

    suspend fun getSmartSuggestions(): List<Note> {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val contextTypes = getTimeContextTypes(hour)
        val allNotes = repository.getAllNotes().first()
        val recentNotes =
            allNotes
                .filter { note ->
                    val age = System.currentTimeMillis() - note.updatedAt
                    age in (24 * 60 * 60 * 1000L)..(30 * 24 * 60 * 60 * 1000L)
                }.filter { note ->
                    contextTypes.any { type ->
                        note.type == type ||
                            note.content.lowercase().contains(type.name.lowercase()) ||
                            note.title.lowercase().contains(type.name.lowercase())
                    }
                }.sortedByDescending { it.updatedAt }
                .take(3)
        _smartSuggestions.value = recentNotes
        return recentNotes
    }

    fun getSuggestedCategory(content: String): String? {
        val keywords =
            mapOf(
                "meeting" to "Meetings",
                "deadline" to "Deadlines",
                "idea" to "Ideas",
                "thought" to "Reflections",
                "recipe" to "Recipes",
                "book" to "Reading",
                "movie" to "Entertainment",
                "workout" to "Health",
                "exercise" to "Health",
                "budget" to "Finance",
                "expense" to "Finance",
                "password" to "Private",
                "secret" to "Private",
            )
        val lowerContent = content.lowercase()
        for ((keyword, category) in keywords) {
            if (lowerContent.contains(keyword)) return category
        }
        return null
    }

    fun resetStreak() {
        prefs
            .edit()
            .putInt(KEY_STREAK_COUNT, 0)
            .putString(KEY_LAST_NOTE_DATE, null)
            .apply()
        _streakCount.value = 0
    }

    private fun getCurrentStreak(): Int = prefs.getInt(KEY_STREAK_COUNT, 0)

    private fun getTodayDateKey(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
    }

    private fun getYesterdayDateKey(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, -1)
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
    }

    private fun getTimeContextTypes(hour: Int): List<NoteType> =
        when (hour) {
            in 6..11 -> listOf(NoteType.BRAIN_DUMP, NoteType.DOCUMENT)
            in 12..17 -> listOf(NoteType.DOCUMENT, NoteType.WEBSITE, NoteType.CODE)
            in 18..21 -> listOf(NoteType.BRAIN_DUMP, NoteType.IMAGE, NoteType.WEB_CLIPPING)
            else -> listOf(NoteType.BRAIN_DUMP)
        }

    private suspend fun findResurfacedNote(): Note? {
        val allNotes = repository.getAllNotes().first()
        val viewedNotes = allNotes.filter { it.isViewed && !it.isArchived }
        if (viewedNotes.isEmpty()) return null
        return viewedNotes.sortedBy { it.updatedAt }.firstOrNull()
    }
}
