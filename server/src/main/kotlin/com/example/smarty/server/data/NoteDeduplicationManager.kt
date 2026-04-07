package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource

/**
 * Note Deduplication Manager
 *
 * Purpose: Automatically detect and remove duplicate notes based on content.
 *
 * Strategy:
 * - When saving a note, check if identical content already exists
 * - If duplicate found, return existing note ID instead of creating new one
 * - Periodic cleanup to remove existing duplicates from database
 *
 * Duplicate Detection:
 * - Content-based hashing (SHA-256)
 * - Title similarity (optional, can be disabled)
 * - User-scoped (only check within same user's notes)
 */
class NoteDeduplicationManager(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(NoteDeduplicationManager::class.java)

    /**
     * Check if a note with identical content already exists for this user.
     *
     * @param userId User ID
     * @param content Note content
     * @param title Optional title for better matching
     * @return Existing note ID if duplicate found, null otherwise
     */
    suspend fun findDuplicateNote(
        userId: String,
        content: String,
        title: String? = null,
    ): String? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                // Check by exact content match
                val contentSql =
                    """
                    SELECT id FROM notes
                    WHERE user_id = ? AND content = ? AND is_archived = false AND deleted_at IS NULL
                    ORDER BY created_at ASC
                    LIMIT 1
                    """.trimIndent()

                conn.prepareStatement(contentSql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId)) // UUID cast — v6 schema
                    stmt.setString(2, content)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            val existingId = rs.getString("id")
                            logger.info("Found duplicate note by content match: existingId={}, userId={}", existingId, userId)
                            return@withContext existingId
                        }
                    }
                }

                null
            }
        }

    /**
     * Clean up existing duplicate notes in the database.
     * Keeps the oldest note, deletes newer duplicates.
     *
     * @param userId Optional user ID to clean duplicates for (null = all users)
     * @return Number of duplicates removed
     */
    suspend fun cleanupExistingDuplicates(userId: String? = null): Int =
        withContext(Dispatchers.IO) {
            var removedCount = 0

            dataSource.connection.use { conn ->
                // Find duplicates by content
                val findDuplicatesSql =
                    """
                    SELECT content, user_id, COUNT(*) as count,
                           MIN(created_at) as oldest_created_at
                    FROM notes
                    WHERE is_archived = false AND deleted_at IS NULL
                    ${if (userId != null) "AND user_id = ?" else ""}
                    GROUP BY content, user_id
                    HAVING COUNT(*) > 1
                    """.trimIndent()

                conn.prepareStatement(findDuplicatesSql).use { findStmt ->
                    if (userId != null) {
                        findStmt.setObject(1, UUID.fromString(userId)) // UUID cast — v6 schema
                    }

                    findStmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val dupUserId = rs.getString("user_id")
                            val content = rs.getString("content")
                            val oldestCreatedAt = rs.getTimestamp("oldest_created_at")

                            // Delete all duplicates except the oldest one
                            val deleteSql =
                                """
                                DELETE FROM notes
                                WHERE user_id = ? AND content = ? 
                                  AND is_archived = false AND deleted_at IS NULL
                                  AND created_at > ?
                                """.trimIndent()

                            conn.prepareStatement(deleteSql).use { deleteStmt ->
                                // dupUserId from rs.getString is a UUID-formatted string — cast to UUID object
                                deleteStmt.setObject(1, UUID.fromString(dupUserId))
                                deleteStmt.setString(2, content)
                                deleteStmt.setTimestamp(3, oldestCreatedAt)

                                val deleted = deleteStmt.executeUpdate()
                                if (deleted > 0) {
                                    removedCount += deleted
                                    logger.info("Removed {} duplicate notes for user={}", deleted, dupUserId)
                                }
                            }
                        }
                    }
                }

                removedCount
            }
        }

    /**
     * Note: content_hash column is no longer used in v6.0.0 schema.
     * This function is kept for backwards compatibility but is a no-op.
     */
    @Deprecated("content_hash is not used in v6.0.0 schema")
    suspend fun addContentHashColumn() {
        // No-op - content_hash column doesn't exist in v6.0.0 notes table
        logger.info("addContentHashColumn called but skipped - not needed in v6.0.0 schema")
    }

    /**
     * Calculate SHA-256 hash of content.
     */
    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(this.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
