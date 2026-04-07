package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.core.domain.model.Category
import kotlinx.coroutines.flow.Flow

/**
 * Data class for AI-visible category info.
 * Contains category with count of only AI-accessible notes (private notes excluded).
 */
data class CategoryWithAiCount(
    @Embedded val category: Category,
    @ColumnInfo(name = "aiVisibleCount") val aiVisibleCount: Int,
)

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY noteCount DESC, lastUpdated DESC")
    fun getAllCategories(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getCategoryById(id: String): Category?

    @Query("SELECT * FROM categories WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getCategoryByName(name: String): Category?

    @Query("SELECT * FROM categories WHERE name LIKE '%' || :query || '%' ORDER BY noteCount DESC")
    suspend fun searchCategories(query: String): List<Category>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: Category)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<Category>)

    @Update
    suspend fun updateCategory(category: Category)

    @Delete
    suspend fun deleteCategory(category: Category)

    @Query("UPDATE categories SET noteCount = noteCount + 1, lastUpdated = :timestamp WHERE id = :categoryId")
    suspend fun incrementNoteCount(
        categoryId: String,
        timestamp: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE categories SET noteCount = MAX(noteCount - 1, 0), lastUpdated = :timestamp WHERE id = :categoryId")
    suspend fun decrementNoteCount(
        categoryId: String,
        timestamp: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE categories SET noteCount = MAX(noteCount + :delta, 0), lastUpdated = :timestamp WHERE id = :categoryId")
    suspend fun updateNoteCount(
        categoryId: String,
        delta: Int,
        timestamp: Long = System.currentTimeMillis(),
    )

    // Sync/Recalculation - fixes any count mismatches by counting actual notes
    @Query(
        """
        UPDATE categories
        SET noteCount = (
            SELECT COUNT(*) FROM notes
            WHERE notes.categoryId = categories.id
            AND notes.isArchived = 0
        ),
        lastUpdated = :timestamp
    """,
    )
    suspend fun recalculateAllCounts(timestamp: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE categories
        SET noteCount = (
            SELECT COUNT(*) FROM notes
            WHERE notes.categoryId = :categoryId
            AND notes.isArchived = 0
        ),
        lastUpdated = :timestamp
        WHERE id = :categoryId
    """,
    )
    suspend fun recalculateCategoryCount(
        categoryId: String,
        timestamp: Long = System.currentTimeMillis(),
    )

    // Backup operations - one-shot queries
    @Query("SELECT * FROM categories ORDER BY noteCount DESC, lastUpdated DESC")
    suspend fun getAllCategoriesOnce(): List<Category>

    @Query("DELETE FROM categories")
    suspend fun deleteAllCategories()

    // =========================================================================
    // AI-SPECIFIC QUERIES (Private notes excluded)
    // =========================================================================
    // These queries return counts that exclude private notes.
    // Use these when building context for AI/Agent services.
    // The regular queries (getAllCategories, etc.) include all notes for UI display.

    /**
     * Get AI-visible note count for a single category.
     * Excludes: archived, isFullPrivacy=true, excludeFromAiChat=true
     * Use when building AI context for a specific category.
     */
    @Query(
        """
        SELECT COUNT(*) FROM notes
        WHERE categoryId = :categoryId
        AND isArchived = 0
        AND isFullPrivacy = 0
        AND excludeFromAiChat = 0
    """,
    )
    suspend fun getAiVisibleNoteCount(categoryId: String): Int

    /**
     * Get all categories with AI-visible note counts.
     * Returns categories sorted by AI-accessible note count.
     * Private notes are COMPLETELY excluded from counts.
     * Use when providing category list to AI/Agent services.
     */
    @Query(
        """
        SELECT
            c.*,
            (SELECT COUNT(*) FROM notes n
             WHERE n.categoryId = c.id
             AND n.isArchived = 0
             AND n.isFullPrivacy = 0
             AND n.excludeFromAiChat = 0
            ) as aiVisibleCount
        FROM categories c
        ORDER BY aiVisibleCount DESC, c.lastUpdated DESC
    """,
    )
    suspend fun getCategoriesWithAiVisibleCounts(): List<CategoryWithAiCount>

    /**
     * Get total AI-visible notes across all categories.
     * Use for AI context summary/statistics.
     */
    @Query(
        """
        SELECT COUNT(*) FROM notes
        WHERE isArchived = 0
        AND isFullPrivacy = 0
        AND excludeFromAiChat = 0
    """,
    )
    suspend fun getTotalAiVisibleNoteCount(): Int
}
