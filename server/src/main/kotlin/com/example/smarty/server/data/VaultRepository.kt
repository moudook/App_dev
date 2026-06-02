package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.sql.DataSource

@Serializable
data class E2EVaultData(
    val userId: String,
    val encryptedBlob: String,
    val version: Int,
    val updatedAt: Long,
)

class VaultRepository(
    private val dataSource: DataSource,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(userId: String): E2EVaultData? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val stmt = conn.prepareStatement("SELECT user_id, encrypted_blob, version, updated_at FROM user_vaults WHERE user_id = ?")
                stmt.setString(1, userId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        E2EVaultData(
                            userId = rs.getString("user_id"),
                            encryptedBlob = rs.getString("encrypted_blob"),
                            version = rs.getInt("version"),
                            updatedAt = rs.getLong("updated_at"),
                        )
                    } else {
                        null
                    }
                }
            }
        }

    suspend fun store(
        userId: String,
        encryptedBlob: String,
        version: Int,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                // Check if exists
                val checkStmt = conn.prepareStatement("SELECT COUNT(*) FROM user_vaults WHERE user_id = ?")
                checkStmt.setString(1, userId)
                val exists = checkStmt.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
                if (exists) {
                    val updateStmt =
                        conn.prepareStatement(
                            "UPDATE user_vaults SET encrypted_blob = ?, version = ?, updated_at = ? WHERE user_id = ?",
                        )
                    updateStmt.setString(1, encryptedBlob)
                    updateStmt.setInt(2, version)
                    updateStmt.setLong(3, System.currentTimeMillis())
                    updateStmt.setString(4, userId)
                    updateStmt.executeUpdate() > 0
                } else {
                    val insertStmt =
                        conn.prepareStatement(
                            "INSERT INTO user_vaults (user_id, encrypted_blob, version, updated_at) VALUES (?, ?, ?, ?)",
                        )
                    insertStmt.setString(1, userId)
                    insertStmt.setString(2, encryptedBlob)
                    insertStmt.setInt(3, version)
                    insertStmt.setLong(4, System.currentTimeMillis())
                    insertStmt.executeUpdate() > 0
                }
            }
        }

    suspend fun delete(userId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val stmt = conn.prepareStatement("DELETE FROM user_vaults WHERE user_id = ?")
                stmt.setString(1, userId)
                stmt.executeUpdate() > 0
            }
        }
}
