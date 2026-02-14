package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.core.domain.model.NoteVersion
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteVersionDao {

    /**
     * Get all versions of a note, ordered by version number descending (newest first)
     */
    @Query("SELECT * FROM note_versions WHERE noteId = :noteId ORDER BY versionNumber DESC")
    fun getVersionsForNote(noteId: String): Flow<List<NoteVersion>>

    /**
     * Get all versions of a note as a one-shot query
     */
    @Query("SELECT * FROM note_versions WHERE noteId = :noteId ORDER BY versionNumber DESC")
    suspend fun getVersionsForNoteOnce(noteId: String): List<NoteVersion>

    /**
     * Get a specific version by ID
     */
    @Query("SELECT * FROM note_versions WHERE id = :versionId")
    suspend fun getVersionById(versionId: String): NoteVersion?

    /**
     * Get the latest version number for a note
     */
    @Query("SELECT MAX(versionNumber) FROM note_versions WHERE noteId = :noteId")
    suspend fun getLatestVersionNumber(noteId: String): Int?

    /**
     * Get the count of versions for a note
     */
    @Query("SELECT COUNT(*) FROM note_versions WHERE noteId = :noteId")
    suspend fun getVersionCount(noteId: String): Int

    /**
     * Insert a new version
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVersion(version: NoteVersion)

    /**
     * Delete all versions for a note (called when note is deleted - handled by FK CASCADE)
     */
    @Query("DELETE FROM note_versions WHERE noteId = :noteId")
    suspend fun deleteVersionsForNote(noteId: String)

    /**
     * Delete old versions, keeping only the most recent N versions
     * Useful for limiting storage usage
     */
    @Query("""
        DELETE FROM note_versions
        WHERE noteId = :noteId
        AND id NOT IN (
            SELECT id FROM note_versions
            WHERE noteId = :noteId
            ORDER BY versionNumber DESC
            LIMIT :keepCount
        )
    """)
    suspend fun pruneOldVersions(noteId: String, keepCount: Int = 10)

    /**
     * Get the most recent version for a note
     */
    @Query("SELECT * FROM note_versions WHERE noteId = :noteId ORDER BY versionNumber DESC LIMIT 1")
    suspend fun getLatestVersion(noteId: String): NoteVersion?

    /**
     * Delete all note versions.
     * Used for user data cleanup on sign-out.
     */
    @Query("DELETE FROM note_versions")
    suspend fun deleteAllVersions()
}
