package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.data.local.NoteVersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteVersionDao {
    /**
     * Get all versions of a note, ordered by version number descending (newest first)
     */
    @Query("SELECT * FROM note_versions WHERE note_id = :noteId ORDER BY version_no DESC")
    fun getVersionsForNote(noteId: String): Flow<List<NoteVersionEntity>>

    /**
     * Get all versions of a note as a one-shot query
     */
    @Query("SELECT * FROM note_versions WHERE note_id = :noteId ORDER BY version_no DESC")
    suspend fun getVersionsForNoteOnce(noteId: String): List<NoteVersionEntity>

    /**
     * Get a specific version by ID
     */
    @Query("SELECT * FROM note_versions WHERE id = :versionId")
    suspend fun getVersionById(versionId: String): NoteVersionEntity?

    /**
     * Get the latest version number for a note
     */
    @Query("SELECT MAX(version_no) FROM note_versions WHERE note_id = :noteId")
    suspend fun getLatestVersionNumber(noteId: String): Int?

    /**
     * Get the count of versions for a note
     */
    @Query("SELECT COUNT(*) FROM note_versions WHERE note_id = :noteId")
    suspend fun getVersionCount(noteId: String): Int

    /**
     * Insert a new version
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVersion(version: NoteVersionEntity)

    /**
     * Delete all versions for a note (called when note is deleted - handled by FK CASCADE)
     */
    @Query("DELETE FROM note_versions WHERE note_id = :noteId")
    suspend fun deleteVersionsForNote(noteId: String)

    /**
     * Delete old versions, keeping only the most recent N versions
     * Useful for limiting storage usage
     */
    @Query(
        """
        DELETE FROM note_versions
        WHERE note_id = :noteId
        AND id NOT IN (
            SELECT id FROM note_versions
            WHERE note_id = :noteId
            ORDER BY version_no DESC
            LIMIT :keepCount
        )
    """,
    )
    suspend fun pruneOldVersions(
        noteId: String,
        keepCount: Int = 10,
    )

    /**
     * Get the most recent version for a note
     */
    @Query("SELECT * FROM note_versions WHERE note_id = :noteId ORDER BY version_no DESC LIMIT 1")
    suspend fun getLatestVersion(noteId: String): NoteVersionEntity?

    /**
     * Delete all note versions.
     * Used for user data cleanup on sign-out.
     */
    @Query("DELETE FROM note_versions")
    suspend fun deleteAllVersions()
}
