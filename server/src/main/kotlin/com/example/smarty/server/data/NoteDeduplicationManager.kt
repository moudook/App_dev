package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest
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
        title: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val contentHash = content.sha256()
        
        dataSource.connection.use { conn ->
            // First try exact content hash match
            val hashSql = """
                SELECT id FROM notes
                WHERE user_id = ? AND content_hash = ? AND NOT is_archived
                ORDER BY created_at ASC
                LIMIT 1
            """.trimIndent()
            
            conn.prepareStatement(hashSql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setString(2, contentHash)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val existingId = rs.getString("id")
                        logger.info("Found duplicate note by content hash: existingId={}, userId={}", existingId, userId)
                        return@withContext existingId
                    }
                }
            }
            
            // Fallback: Check by exact content match (for notes without hash)
            val contentSql = """
                SELECT id FROM notes
                WHERE user_id = ? AND content = ? AND NOT is_archived
                ORDER BY created_at ASC
                LIMIT 1
            """.trimIndent()
            
            conn.prepareStatement(contentSql).use { stmt ->
                stmt.setString(1, userId)
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
    suspend fun cleanupExistingDuplicates(userId: String? = null): Int = withContext(Dispatchers.IO) {
        var removedCount = 0
        
        dataSource.connection.use { conn ->
            // Find duplicates by content hash
            val findDuplicatesSql = """
                SELECT content_hash, user_id, COUNT(*) as count,
                       MIN(created_at) as oldest_created_at
                FROM notes
                WHERE NOT is_archived
                ${if (userId != null) "AND user_id = ?" else ""}
                GROUP BY content_hash, user_id
                HAVING COUNT(*) > 1
            """.trimIndent()
            
            conn.prepareStatement(findDuplicatesSql).use { findStmt ->
                if (userId != null) {
                    findStmt.setString(1, userId)
                }
                
                findStmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val dupUserId = rs.getString("user_id")
                        val contentHash = rs.getString("content_hash")
                        val oldestCreatedAt = rs.getTimestamp("oldest_created_at")
                        
                        // Delete all duplicates except the oldest one
                        val deleteSql = """
                            DELETE FROM notes
                            WHERE user_id = ? AND content_hash = ? 
                              AND NOT is_archived
                              AND created_at > ?
                        """.trimIndent()
                        
                        conn.prepareStatement(deleteSql).use { deleteStmt ->
                            deleteStmt.setString(1, dupUserId)
                            deleteStmt.setString(2, contentHash)
                            deleteStmt.setTimestamp(3, oldestCreatedAt)
                            
                            val deleted = deleteStmt.executeUpdate()
                            if (deleted > 0) {
                                removedCount += deleted
                                logger.info("Removed {} duplicate notes for user={} with hash={}", deleted, dupUserId, contentHash)
                            }
                        }
                    }
                }
            }
            
            // Also clean duplicates without content_hash (legacy notes)
            val findLegacyDuplicatesSql = """
                SELECT content, user_id, COUNT(*) as count,
                       MIN(created_at) as oldest_created_at
                FROM notes
                WHERE content_hash IS NULL AND NOT is_archived
                ${if (userId != null) "AND user_id = ?" else ""}
                GROUP BY content, user_id
                HAVING COUNT(*) > 1
            """.trimIndent()
            
            conn.prepareStatement(findLegacyDuplicatesSql).use { findStmt ->
                if (userId != null) {
                    findStmt.setString(1, userId)
                }
                
                findStmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val dupUserId = rs.getString("user_id")
                        val content = rs.getString("content")
                        val oldestCreatedAt = rs.getTimestamp("oldest_created_at")
                        
                        // Delete all duplicates except the oldest one
                        val deleteSql = """
                            DELETE FROM notes
                            WHERE user_id = ? AND content = ? 
                              AND content_hash IS NULL AND NOT is_archived
                              AND created_at > ?
                        """.trimIndent()
                        
                        conn.prepareStatement(deleteSql).use { deleteStmt ->
                            deleteStmt.setString(1, dupUserId)
                            deleteStmt.setString(2, content)
                            deleteStmt.setTimestamp(3, oldestCreatedAt)
                            
                            val deleted = deleteStmt.executeUpdate()
                            if (deleted > 0) {
                                removedCount += deleted
                                logger.info("Removed {} legacy duplicate notes for user={}", deleted, dupUserId)
                            }
                        }
                    }
                }
            }
        }
        
        if (removedCount > 0) {
            logger.info("Total duplicate notes removed: {}", removedCount)
        }
        
        removedCount
    }

    /**
     * Add content_hash column to notes table if it doesn't exist.
     * Call this during database migration.
     */
    suspend fun addContentHashColumn() {
        dataSource.connection.use { conn ->
            try {
                // Check if column exists
                val checkSql = """
                    SELECT column_name FROM information_schema.columns
                    WHERE table_name = 'notes' AND column_name = 'content_hash'
                """.trimIndent()
                
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(checkSql).use { rs ->
                        if (!rs.next()) {
                            // Column doesn't exist, add it
                            val alterSql = """
                                ALTER TABLE notes ADD COLUMN content_hash TEXT
                            """.trimIndent()
                            
                            stmt.execute(alterSql)
                            logger.info("Added content_hash column to notes table")
                            
                            // Create index for faster lookups
                            val indexSql = """
                                CREATE INDEX IF NOT EXISTS idx_notes_content_hash ON notes(content_hash)
                            """.trimIndent()
                            
                            stmt.execute(indexSql)
                            logger.info("Created index on content_hash column")
                            
                            // Backfill existing notes with content hash
                            val backfillSql = """
                                UPDATE notes
                                SET content_hash = ENCODE(SHA256(content::bytea), 'hex')
                                WHERE content_hash IS NULL
                            """.trimIndent()
                            
                            val updated = stmt.executeUpdate(backfillSql)
                            logger.info("Backfilled content_hash for {} existing notes", updated)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to add content_hash column", e)
            }
        }
    }

    /**
     * Calculate SHA-256 hash of content.
     */
    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(this.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
