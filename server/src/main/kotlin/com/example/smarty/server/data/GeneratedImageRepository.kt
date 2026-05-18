package com.example.smarty.server.data

import java.sql.ResultSet
import java.util.UUID

data class GeneratedImage(
    val id: String,
    val userId: String,
    val sessionId: String?,
    val prompt: String,
    val kreaJobId: String,
    val status: String,
    val imageUrl: String?, // Original Krea URL
    val supabaseUrl: String?, // Supabase Storage URL
    val imageBytes: ByteArray?, // Stored image binary
    val contentType: String?, // Image MIME type
    val createdAt: Long,
    val updatedAt: Long,
)

class GeneratedImageRepository(dataSource: javax.sql.DataSource) : BaseRepository(dataSource) {
    suspend fun create(
        userId: String,
        sessionId: String?,
        prompt: String,
        kreaJobId: String,
        status: String = "queued",
    ): String =
        withConnection {
            val id = UUID.randomUUID().toString()
            val sql =
                """
                INSERT INTO generated_images (id, user_id, session_id, prompt, krea_job_id, status)
                VALUES (?::uuid, ?::uuid, ${if (sessionId != null) "?::uuid" else "NULL"}, ?, ?, ?)
                """.trimIndent()

            it.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.setString(2, userId)

                var paramIdx = 3
                if (sessionId != null) {
                    stmt.setString(paramIdx++, sessionId)
                }

                stmt.setString(paramIdx++, prompt)
                stmt.setString(paramIdx++, kreaJobId)
                stmt.setString(paramIdx++, status)

                stmt.executeUpdate()
            }
            id
        }

    suspend fun updateStatus(
        kreaJobId: String,
        status: String,
        supabaseUrl: String? = null,
    ) = withConnection {
        val sql =
            """
            UPDATE generated_images
            SET status = ?,
                supabase_url = COALESCE(?, supabase_url),
                updated_at = now()
            WHERE krea_job_id = ?
            """.trimIndent()

        it.prepareStatement(sql).use { stmt ->
            stmt.setString(1, status)
            stmt.setString(2, supabaseUrl)
            stmt.setString(3, kreaJobId)

            stmt.executeUpdate()
        }
    }

    /**
     * Update the image URLs (both Krea original and Supabase storage).
     */
    suspend fun updateImageUrls(
        kreaJobId: String,
        imageUrl: String?,
        supabaseUrl: String? = null,
    ) = withConnection {
        val sql =
            """
            UPDATE generated_images
            SET image_url = ?,
                supabase_url = COALESCE(?, supabase_url),
                status = 'completed',
                updated_at = now()
            WHERE krea_job_id = ?
            """.trimIndent()

        it.prepareStatement(sql).use { stmt ->
            stmt.setString(1, imageUrl)
            stmt.setString(2, supabaseUrl)
            stmt.setString(3, kreaJobId)

            stmt.executeUpdate()
        }
    }

    suspend fun getByJobId(kreaJobId: String): GeneratedImage? =
        withConnection {
            val sql = "SELECT * FROM generated_images WHERE krea_job_id = ?"
            it.prepareStatement(sql).use { stmt ->
                stmt.setString(1, kreaJobId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    mapRow(rs)
                } else {
                    null
                }
            }
        }

    /**
     * Get image by ID.
     */
    suspend fun getById(id: String): GeneratedImage? =
        withConnection {
            val sql = "SELECT * FROM generated_images WHERE id = ?::uuid"
            it.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    mapRow(rs)
                } else {
                    null
                }
            }
        }

    /**
     * List all generated images for a user (for sync).
     */
    suspend fun listByUser(
        userId: String,
        limit: Int = 100,
    ): List<GeneratedImage> =
        withConnection {
            val sql =
                """
                SELECT * FROM generated_images 
                WHERE user_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """.trimIndent()

            val images = mutableListOf<GeneratedImage>()
            it.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        images.add(mapRow(rs))
                    }
                }
            }
            images
        }

    suspend fun storeImageBytes(
        kreaJobId: String,
        imageBytes: ByteArray,
        contentType: String,
    ) = withConnection {
        val sql =
            """
            UPDATE generated_images
            SET image_bytes = ?,
                content_type = ?,
                updated_at = now()
            WHERE krea_job_id = ?
            """.trimIndent()

        it.prepareStatement(sql).use { stmt ->
            stmt.setBytes(1, imageBytes)
            stmt.setString(2, contentType)
            stmt.setString(3, kreaJobId)
            stmt.executeUpdate()
        }
    }

    suspend fun getImageBytes(id: String): Pair<ByteArray, String>? =
        withConnection {
            val sql = "SELECT image_bytes, content_type FROM generated_images WHERE id = ?::uuid"

            it.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    val bytes = rs.getBytes("image_bytes")
                    val contentType = rs.getString("content_type")
                    if (bytes != null && contentType != null) {
                        Pair(bytes, contentType)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }

    /**
     * Mark all queued/processing images for a user as failed (cleanup on error).
     */
    suspend fun clearQueuedForUser(userId: String): Int =
        withConnection {
            val sql = """
                UPDATE generated_images
                SET status = 'failed',
                    updated_at = now()
                WHERE user_id = ?::uuid
                AND status IN ('queued', 'processing', 'pending')
            """.trimIndent()

            it.prepareStatement(sql).use { stmt ->
                stmt.setString(1, userId)
                stmt.executeUpdate()
            }
        }

    /**
     * Mark a specific job as failed.
     */
    suspend fun markJobFailed(kreaJobId: String, errorMessage: String? = null): Unit =
        withConnection {
            val sql = """
                UPDATE generated_images
                SET status = 'failed',
                    updated_at = now()
                WHERE krea_job_id = ?
            """.trimIndent()

            it.prepareStatement(sql).use { stmt ->
                stmt.setString(1, kreaJobId)
                stmt.executeUpdate()
            }
        }

    private fun mapRow(rs: ResultSet): GeneratedImage {
        return GeneratedImage(
            id = rs.getString("id"),
            userId = rs.getString("user_id"),
            sessionId = rs.getString("session_id"),
            prompt = rs.getString("prompt"),
            kreaJobId = rs.getString("krea_job_id"),
            status = rs.getString("status"),
            imageUrl = rs.getString("image_url"),
            supabaseUrl = rs.getString("supabase_url"),
            imageBytes = rs.getBytes("image_bytes"),
            contentType = rs.getString("content_type"),
            createdAt = rs.getTimestamp("created_at").time,
            updatedAt = rs.getTimestamp("updated_at").time,
        )
    }
}
