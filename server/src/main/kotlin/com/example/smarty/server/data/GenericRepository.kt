package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/**
 * Generic repository base class providing common CRUD operations.
 *
 * This class eliminates duplication across NoteRepository, ChatRepository,
 * CalendarRepository, TimerRepository, and other repositories by providing
 * reusable database operation templates.
 *
 * Usage:
 * ```
 * class NoteRepository(dataSource: DataSource) : GenericRepository<NoteEntity>(dataSource, "notes") {
 *     // Override only methods that need custom behavior
 *     // Inherit getAll, getById, create, update, delete
 * }
 * ```
 */
abstract class GenericRepository<T : Entity>(
    protected val dataSource: DataSource,
    protected val tableName: String,
) {
    protected val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Execute a block with a database connection.
     * Connection is automatically closed after execution.
     */
    protected suspend fun <R> withConnection(block: (Connection) -> R): R =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                block(conn)
            }
        }

    /**
     * Execute a block with a prepared statement.
     */
    protected fun <R> withStatement(
        conn: Connection,
        sql: String,
        block: (PreparedStatement) -> R,
    ): R =
        conn.prepareStatement(sql).use { stmt ->
            block(stmt)
        }

    /**
     * Execute a query and process the result set.
     */
    protected fun <R> withResultSet(
        stmt: PreparedStatement,
        block: (ResultSet) -> R,
    ): R =
        stmt.executeQuery().use { rs ->
            block(rs)
        }

    /**
     * Get all entities for a user with pagination.
     */
    suspend fun getAll(
        userId: String,
        limit: Int = 50,
        offset: Int = 0,
        orderBy: String = "updated_at DESC",
    ): List<T> =
        withConnection { conn ->
            val sql =
                """
                SELECT * FROM $tableName
                WHERE user_id = ? AND deleted_at IS NULL
                ORDER BY $orderBy
                LIMIT ? OFFSET ?
                """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.setInt(2, limit)
                stmt.setInt(3, offset)
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<T>()
                    while (rs.next()) {
                        results.add(mapRow(rs))
                    }
                    results
                }
            }
        }

    /**
     * Get entities updated after a timestamp (for delta sync).
     */
    suspend fun getUpdatedAfter(
        userId: String,
        timestamp: Long,
        limit: Int = 50,
    ): List<T> =
        withConnection { conn ->
            val sql =
                """
                SELECT * FROM $tableName
                WHERE user_id = ? AND updated_at > to_timestamp(? / 1000.0) AND deleted_at IS NULL
                ORDER BY updated_at DESC
                LIMIT ?
                """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(userId))
                stmt.setLong(2, timestamp)
                stmt.setInt(3, limit)
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<T>()
                    while (rs.next()) {
                        results.add(mapRow(rs))
                    }
                    results
                }
            }
        }

    /**
     * Get a single entity by ID.
     */
    suspend fun getById(
        userId: String,
        id: String,
    ): T? =
        withConnection { conn ->
            val sql =
                """
                SELECT * FROM $tableName
                WHERE id = ? AND user_id = ? AND deleted_at IS NULL
                """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(id))
                stmt.setObject(2, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    if (rs.next()) mapRow(rs) else null
                }
            }
        }

    /**
     * Check if an entity exists.
     */
    suspend fun exists(
        userId: String,
        id: String,
    ): Boolean =
        withConnection { conn ->
            val sql = "SELECT 1 FROM $tableName WHERE id = ? AND user_id = ? AND deleted_at IS NULL"

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(id))
                stmt.setObject(2, UUID.fromString(userId))
                stmt.executeQuery().use { rs ->
                    rs.next()
                }
            }
        }

    /**
     * Soft delete an entity.
     */
    suspend fun delete(
        userId: String,
        id: String,
    ): Boolean =
        withConnection { conn ->
            val sql = "UPDATE $tableName SET deleted_at = now(), updated_at = now() WHERE id = ? AND user_id = ? AND deleted_at IS NULL"

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(id))
                stmt.setObject(2, UUID.fromString(userId))
                stmt.executeUpdate() > 0
            }
        }

    /**
     * Hard delete an entity (use with caution).
     */
    suspend fun hardDelete(
        userId: String,
        id: String,
    ): Boolean =
        withConnection { conn ->
            val sql = "DELETE FROM $tableName WHERE id = ? AND user_id = ?"

            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(id))
                stmt.setObject(2, UUID.fromString(userId))
                stmt.executeUpdate() > 0
            }
        }

    /**
     * Execute a transaction.
     * Commits on success, rolls back on failure.
     */
    protected suspend fun <R> withTransaction(block: (Connection) -> R): R =
        withConnection { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }

    /**
     * Map a ResultSet row to an entity.
     * Must be implemented by subclasses.
     */
    protected abstract fun mapRow(rs: ResultSet): T
}

/**
 * Base entity interface for all repository entities.
 */
interface Entity {
    val id: String
    val userId: String
    val createdAt: Long
    val updatedAt: Long
    val deletedAt: Long?
}

/**
 * Extension function to safely convert string to UUID.
 */
fun String.toUuid(): UUID =
    try {
        UUID.fromString(this)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid UUID format: $this", e)
    }

/**
 * Extension function to safely convert string to UUID?, handling nulls.
 */
fun String?.toUuidOrNull(): UUID? = this?.toUuid()
