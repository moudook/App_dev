package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.sql.DataSource

class FcmTokenRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(FcmTokenRepository::class.java)

    suspend fun upsertToken(
        userId: String,
        token: String,
        deviceName: String?,
        deviceId: String?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    INSERT INTO user_fcm_tokens (id, user_id, token, device_name, device_id, last_used_at, created_at)
                    VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                    ON CONFLICT (token) DO UPDATE SET
                        user_id = EXCLUDED.user_id,
                        device_name = EXCLUDED.device_name,
                        device_id = EXCLUDED.device_id,
                        last_used_at = NOW()
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.randomUUID())
                    stmt.setObject(2, UUID.fromString(userId)) // UUID cast — v6 schema
                    stmt.setString(3, token)
                    stmt.setString(4, deviceName)
                    stmt.setString(5, deviceId)
                    stmt.executeUpdate() > 0
                }
            }.also { success ->
                if (success) {
                    logger.info("FCM token registered for user={} device={}", userId, deviceName ?: deviceId ?: "unknown")
                }
            }
        }

    suspend fun deleteToken(
        userId: String,
        token: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM user_fcm_tokens WHERE user_id = ? AND token = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId)) // UUID cast — v6 schema
                    stmt.setString(2, token)
                    stmt.executeUpdate() > 0
                }
            }
        }

    suspend fun getTokensForUser(userId: String): List<String> =
        withContext(Dispatchers.IO) {
            val tokens = mutableListOf<String>()
            dataSource.connection.use { conn ->
                val sql = "SELECT token FROM user_fcm_tokens WHERE user_id = ? ORDER BY last_used_at DESC"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId)) // UUID cast — v6 schema
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            tokens.add(rs.getString("token"))
                        }
                    }
                }
            }
            tokens
        }
}
