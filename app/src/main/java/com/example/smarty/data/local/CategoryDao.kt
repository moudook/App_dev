package com.example.smarty.data.local

import androidx.room.*
import com.example.smarty.data.model.Category
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY noteCount DESC, lastUpdated DESC")
    fun getAllCategories(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getCategoryById(id: String): Category?

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun getCategoryByName(name: String): Category?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: Category)

    @Update
    suspend fun updateCategory(category: Category)

    @Delete
    suspend fun deleteCategory(category: Category)

    @Query("UPDATE categories SET noteCount = noteCount + 1, lastUpdated = :timestamp WHERE id = :categoryId")
    suspend fun incrementNoteCount(categoryId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE categories SET noteCount = MAX(noteCount - 1, 0), lastUpdated = :timestamp WHERE id = :categoryId")
    suspend fun decrementNoteCount(categoryId: String, timestamp: Long = System.currentTimeMillis())

    // Sync/Recalculation - fixes any count mismatches by counting actual notes
    @Query("""
        UPDATE categories
        SET noteCount = (
            SELECT COUNT(*) FROM notes
            WHERE notes.categoryId = categories.id
            AND notes.isArchived = 0
        ),
        lastUpdated = :timestamp
    """)
    suspend fun recalculateAllCounts(timestamp: Long = System.currentTimeMillis())

    @Query("""
        UPDATE categories
        SET noteCount = (
            SELECT COUNT(*) FROM notes
            WHERE notes.categoryId = :categoryId
            AND notes.isArchived = 0
        ),
        lastUpdated = :timestamp
        WHERE id = :categoryId
    """)
    suspend fun recalculateCategoryCount(categoryId: String, timestamp: Long = System.currentTimeMillis())

    // Backup operations - one-shot queries
    @Query("SELECT * FROM categories ORDER BY noteCount DESC, lastUpdated DESC")
    suspend fun getAllCategoriesOnce(): List<Category>

    @Query("DELETE FROM categories")
    suspend fun deleteAllCategories()
}
