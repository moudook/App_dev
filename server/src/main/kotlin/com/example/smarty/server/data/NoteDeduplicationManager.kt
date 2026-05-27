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
 * Purpose: Automatically detect and prevent duplicate notes using content hashing.
 *
 * Strategy:
 * - When saving a note, compute SHA-256 hash of content
 * - Check content_hash index for existing match (fast indexed lookup)
 * - If hash matches, do a full content comparison to avoid false positives
 * - If duplicate found, return existing note ID instead of creating new one
 * - Periodic cleanup to remove existing duplicates from database
 *
 * Duplicate Detection:
 * - Primary: content_hash (SHA-256) with indexed lookup
 * - Secondary: exact content match (fallback for notes without hash)
 * - User-scoped (only check within same user's notes)
 */
class NoteDeduplicationManager(
    private val dataSource: DataSource,
) {
    private val logger = LoggerFactory.getLogger(NoteDeduplicationManager::class.java)

    /**
     * Check if a note with identical content already exists for this user.
     * Uses content_hash index for fast lookup, falls back to exact content match.
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
            val contentHash = content.sha256()

            dataSource.connection.use { conn ->
                // Primary: Check by content_hash (fast indexed lookup)
                val hashSql =
                    """
                    SELECT id, content FROM notes
                    WHERE user_id = ? AND content_hash = ? AND is_archived = false AND deleted_at IS NULL
                    ORDER BY created_at ASC
                    """.trimIndent()

                conn.prepareStatement(hashSql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setString(2, contentHash)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            // Verify full content match to avoid hash collision false positives
                            val existingContent = rs.getString("content")
                            if (existingContent == content) {
                                val existingId = rs.getString("id")
                                logger.info("Found duplicate note by content_hash: id={}, userId={}", existingId, userId)
                                return@withContext existingId
                            }
                        }
                    }
                }

                // Fallback: Check by exact content match (for notes created before content_hash was added)
                val contentSql =
                    """
                    SELECT id FROM notes
                    WHERE user_id = ? AND content = ? AND content_hash IS NULL AND is_archived = false AND deleted_at IS NULL
                    ORDER BY created_at ASC
                    LIMIT 1
                    """.trimIndent()

                conn.prepareStatement(contentSql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setString(2, content)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            val existingId = rs.getString("id")
                            logger.info("Found duplicate note by content match (fallback): id={}, userId={}", existingId, userId)
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
                // Find duplicates by content_hash (fast)
                val findDuplicatesSql =
                    """
                    SELECT content_hash, user_id, COUNT(*) as count,
                           MIN(created_at) as oldest_created_at
                    FROM notes
                    WHERE content_hash IS NOT NULL AND is_archived = false AND deleted_at IS NULL
                    ${if (userId != null) "AND user_id = ?" else ""}
                    GROUP BY content_hash, user_id
                    HAVING COUNT(*) > 1
                    """.trimIndent()

                conn.prepareStatement(findDuplicatesSql).use { findStmt ->
                    if (userId != null) {
                        findStmt.setObject(1, UUID.fromString(userId))
                    }

                    findStmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val dupUserId = rs.getString("user_id")
                            val contentHash = rs.getString("content_hash")
                            val oldestCreatedAt = rs.getTimestamp("oldest_created_at")

                            // Delete all duplicates except the oldest one
                            val deleteSql =
                                """
                                DELETE FROM notes
                                WHERE user_id = ? AND content_hash = ? 
                                  AND is_archived = false AND deleted_at IS NULL
                                  AND created_at > ?
                                """.trimIndent()

                            conn.prepareStatement(deleteSql).use { deleteStmt ->
                                deleteStmt.setObject(1, UUID.fromString(dupUserId))
                                deleteStmt.setString(2, contentHash)
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
            }

            removedCount
        }

    /**
     * Calculate SHA-256 hash of content.
     */
    fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(this.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
