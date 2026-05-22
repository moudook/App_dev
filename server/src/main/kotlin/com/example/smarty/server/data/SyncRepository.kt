package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

data class SyncStatusRecord(
    val userId: String,
    val lastSyncAt: Long?,
    val lastPullAt: Long?,
)

class SyncRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(SyncRepository::class.java)

    suspend fun getSyncStatus(userId: String): SyncStatusRecord? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "SELECT user_id, last_sync_at, last_pull_at FROM sync_state WHERE user_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            SyncStatusRecord(
                                userId = rs.getString("user_id"),
                                lastSyncAt = rs.getTimestamp("last_sync_at")?.time,
                                lastPullAt = rs.getTimestamp("last_pull_at")?.time,
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }

    suspend fun updateSyncStatus(userId: String): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                syncStatusSql(conn, userId)
            }
        }

    /**
     * Update sync status using a pre-existing connection (for transactional pushes).
     * Does NOT manage connection lifecycle — caller must commit/rollback.
     */
    suspend fun updateSyncStatus(userId: String, connection: Connection): Unit {
        syncStatusSql(connection, userId)
    }

    private fun syncStatusSql(conn: Connection, userId: String) {
        val now = Timestamp(System.currentTimeMillis())
        val sql =
            """
            INSERT INTO sync_state (user_id, last_sync_at, last_pull_at)
            VALUES (?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET
                last_sync_at = EXCLUDED.last_sync_at,
                last_pull_at = EXCLUDED.last_pull_at
            """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setObject(1, UUID.fromString(userId))
            stmt.setTimestamp(2, now)
            stmt.setTimestamp(3, now)
            stmt.executeUpdate()
        }
    }

    suspend fun updatePullAt(userId: String): Unit =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val now = Timestamp(System.currentTimeMillis())
                val sql =
                    """
                    INSERT INTO sync_state (user_id, last_pull_at)
                    VALUES (?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET last_pull_at = EXCLUDED.last_pull_at
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.setTimestamp(2, now)
                    stmt.executeUpdate()
                }
            }
        }
}
