package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/**
 * Stacks Repository (v6.0.0 schema)
 * Handles: stacks table and note_stacks junction table
 */
class StackRepository(private val dataSource: DataSource) {
    private val logger = LoggerFactory.getLogger(StackRepository::class.java)

    // ── Stack CRUD ──────────────────────────────────────────────────────────────

    suspend fun createStack(stack: Stack): String =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    INSERT INTO stacks (id, user_id, name, description, color, icon, parent_id, note_count, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                    RETURNING id
                    """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(stack.id))
                    stmt.setObject(2, UUID.fromString(stack.userId))
                    stmt.setString(3, stack.name)
                    stmt.setString(4, stack.description)
                    stmt.setString(5, stack.color)
                    stmt.setString(6, stack.icon)
                    stmt.setObject(7, stack.parentId?.let { UUID.fromString(it) })
                    stmt.setInt(8, stack.noteCount)
                    val rs = stmt.executeQuery()
                    if (rs.next()) rs.getObject("id").toString() else stack.id
                }
            }
        }

    suspend fun getStacksForUser(userId: String): List<Stack> =
        withContext(Dispatchers.IO) {
            val stacks = mutableListOf<Stack>()
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM stacks WHERE user_id = ? ORDER BY created_at DESC"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(userId))
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) stacks.add(rs.toStack())
                    }
                }
            }
            stacks
        }

    suspend fun getStackById(stackId: String): Stack? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "SELECT * FROM stacks WHERE id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(stackId))
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toStack() else null }
                }
            }
        }

    suspend fun updateStack(stack: Stack): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql =
                    """
                    UPDATE stacks SET
                        name = ?, description = ?, color = ?, icon = ?,
                        parent_id = ?, note_count = ?, updated_at = now()
                    WHERE id = ?
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, stack.name)
                    stmt.setString(2, stack.description)
                    stmt.setString(3, stack.color)
                    stmt.setString(4, stack.icon)
                    stmt.setObject(5, stack.parentId?.let { UUID.fromString(it) })
                    stmt.setInt(6, stack.noteCount)
                    stmt.setObject(7, UUID.fromString(stack.id))
                    stmt.executeUpdate() > 0
                }
            }
        }

    suspend fun deleteStack(stackId: String): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM stacks WHERE id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(stackId))
                    stmt.executeUpdate() > 0
                }
            }
        }

    // ── Note–Stack Junction ─────────────────────────────────────────────────────

    suspend fun addNoteToStack(
        noteId: String,
        stackId: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "INSERT INTO note_stacks (note_id, stack_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(noteId))
                    stmt.setObject(2, UUID.fromString(stackId))
                    stmt.executeUpdate() >= 0
                }
            }
        }

    suspend fun removeNoteFromStack(
        noteId: String,
        stackId: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                val sql = "DELETE FROM note_stacks WHERE note_id = ? AND stack_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(noteId))
                    stmt.setObject(2, UUID.fromString(stackId))
                    stmt.executeUpdate() > 0
                }
            }
        }

    suspend fun getStacksForNote(noteId: String): List<Stack> =
        withContext(Dispatchers.IO) {
            val stacks = mutableListOf<Stack>()
            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT s.* FROM stacks s
                    JOIN note_stacks ns ON s.id = ns.stack_id
                    WHERE ns.note_id = ?
                    ORDER BY s.name
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(noteId))
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) stacks.add(rs.toStack())
                    }
                }
            }
            stacks
        }

    suspend fun getNotesForStack(stackId: String): List<String> =
        withContext(Dispatchers.IO) {
            val noteIds = mutableListOf<String>()
            dataSource.connection.use { conn ->
                val sql = "SELECT note_id FROM note_stacks WHERE stack_id = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, UUID.fromString(stackId))
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) noteIds.add(rs.getObject("note_id").toString())
                    }
                }
            }
            noteIds
        }

    // ── Mapping ─────────────────────────────────────────────────────────────────

    private fun ResultSet.toStack(): Stack {
        return Stack(
            id = getObject("id").toString(),
            userId = getObject("user_id").toString(),
            name = getString("name"),
            description = getString("description"),
            color = getString("color") ?: "#03DAC6",
            icon = getString("icon") ?: "stack",
            parentId = getObject("parent_id")?.toString(),
            noteCount = getInt("note_count"),
            createdAt = getTimestamp("created_at")?.toString(),
            updatedAt = getTimestamp("updated_at")?.toString(),
        )
    }
}
