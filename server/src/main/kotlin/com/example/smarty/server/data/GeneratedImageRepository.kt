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
    val supabaseUrl: String?,
    val createdAt: Long,
    val updatedAt: Long
)

class GeneratedImageRepository(dataSource: javax.sql.DataSource) : BaseRepository(dataSource) {

    suspend fun create(
        userId: String,
        sessionId: String?,
        prompt: String,
        kreaJobId: String,
        status: String = "queued"
    ): String = withConnection {
        val id = UUID.randomUUID().toString()
        val sql = """
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
        supabaseUrl: String? = null
    ) = withConnection {
        val sql = """
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

        suspend fun getByJobId(kreaJobId: String): GeneratedImage? = withConnection {
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

    private fun mapRow(rs: ResultSet): GeneratedImage {
        return GeneratedImage(
            id = rs.getString("id"),
            userId = rs.getString("user_id"),
            sessionId = rs.getString("session_id"),
            prompt = rs.getString("prompt"),
            kreaJobId = rs.getString("krea_job_id"),
            status = rs.getString("status"),
            supabaseUrl = rs.getString("supabase_url"),
            createdAt = rs.getTimestamp("created_at").time,
            updatedAt = rs.getTimestamp("updated_at").time
        )
    }
}
