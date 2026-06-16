package com.example.smarty.server.agent2

import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ChatMessageDeserializer
import dev.langchain4j.data.message.ChatMessageSerializer
import dev.langchain4j.store.memory.chat.ChatMemoryStore
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

data class ChatMemoryRow(
    val sessionId: String,
    val messageJson: String,
    val createdAt: Instant = Instant.now(),
)

class PostgresChatMemoryStore(
    private val getConnection: () -> Connection?,
) : ChatMemoryStore {
    private val logger = LoggerFactory.getLogger(PostgresChatMemoryStore::class.java)

    init {
        ensureTable()
    }

    private fun ensureTable() {
        val conn = getConnection() ?: return
        try {
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS agent_chat_memory (
                        id BIGSERIAL PRIMARY KEY,
                        session_id VARCHAR(255) NOT NULL,
                        message_json TEXT NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT NOW()
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_agent_chat_memory_session_id ON agent_chat_memory(session_id)",
                )
            }
        } catch (e: Exception) {
            logger.error("[PostgresChatMemoryStore] Failed to ensure table: ${e.message}")
        }
    }

    override fun getMessages(memoryId: Any): List<ChatMessage> {
        val sessionId = memoryId.toString()
        val conn = getConnection() ?: return emptyList()
        try {
            val sql = "SELECT message_json FROM agent_chat_memory WHERE session_id = ? ORDER BY id ASC"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, sessionId)
                stmt.executeQuery().use { rs ->
                    val messages = mutableListOf<ChatMessage>()
                    while (rs.next()) {
                        val json = rs.getString("message_json")
                        val msg = ChatMessageDeserializer.messageFromJson(json)
                        messages.add(msg)
                    }
                    return messages
                }
            }
        } catch (e: Exception) {
            logger.error("[PostgresChatMemoryStore] Failed to get messages for $sessionId: ${e.message}")
            return emptyList()
        }
    }

    override fun updateMessages(memoryId: Any, messages: List<ChatMessage>) {
        val sessionId = memoryId.toString()
        val conn = getConnection() ?: return
        try {
            conn.autoCommit = false
            conn.prepareStatement("DELETE FROM agent_chat_memory WHERE session_id = ?").use { stmt ->
                stmt.setString(1, sessionId)
                stmt.executeUpdate()
            }
            val sql = "INSERT INTO agent_chat_memory (session_id, message_json, created_at) VALUES (?, ?, ?)"
            conn.prepareStatement(sql).use { stmt ->
                for (msg in messages) {
                    val json = ChatMessageSerializer.messageToJson(msg)
                    stmt.setString(1, sessionId)
                    stmt.setString(2, json)
                    stmt.setTimestamp(3, Timestamp.from(Instant.now()))
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        } catch (e: Exception) {
            logger.error("[PostgresChatMemoryStore] Failed to update messages for $sessionId: ${e.message}")
            try { conn.rollback() } catch (_: Exception) {}
        } finally {
            try { conn.autoCommit = true } catch (_: Exception) {}
        }
    }

    override fun deleteMessages(memoryId: Any) {
        val sessionId = memoryId.toString()
        val conn = getConnection() ?: return
        try {
            conn.prepareStatement("DELETE FROM agent_chat_memory WHERE session_id = ?").use { stmt ->
                stmt.setString(1, sessionId)
                stmt.executeUpdate()
            }
        } catch (e: Exception) {
            logger.error("[PostgresChatMemoryStore] Failed to delete messages for $sessionId: ${e.message}")
        }
    }
}
