package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * Base Repository for database operations.
 *
 * Single Responsibility: Only handles database connection management.
 * DRY: Replaces repeated connection/statement patterns in 10+ repository files.
 *
 * Usage:
 * ```
 * class NoteRepository(dataSource: DataSource) : BaseRepository(dataSource) {
 *     suspend fun getNotes(userId: String): List<Note> = withConnection { conn ->
 *         // Use conn
 *     }
 * }
 * ```
 */
abstract class BaseRepository(protected val dataSource: DataSource) {
    /**
     * Execute a block with a database connection.
     * Connection is automatically closed after execution.
     */
    protected suspend fun <T> withConnection(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                block(conn)
            }
        }

    /**
     * Execute a block with a prepared statement.
     * Statement is automatically closed after execution.
     */
    protected fun <T> withStatement(
        conn: Connection,
        sql: String,
        block: (PreparedStatement) -> T,
    ): T =
        conn.prepareStatement(sql).use { stmt ->
            block(stmt)
        }

    /**
     * Execute a query and process the result set.
     * ResultSet is automatically closed after execution.
     */
    protected fun <T> withResultSet(
        stmt: PreparedStatement,
        block: (ResultSet) -> T,
    ): T =
        stmt.executeQuery().use { rs ->
            block(rs)
        }

    /**
     * Execute an update (INSERT, UPDATE, DELETE) and return affected rows.
     */
    protected fun executeUpdate(
        conn: Connection,
        sql: String,
        params: List<Any?> = emptyList(),
    ): Int =
        conn.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param ->
                stmt.setObject(index + 1, param)
            }
            stmt.executeUpdate()
        }

    /**
     * Execute an insert and return generated keys.
     */
    protected fun <T> executeInsert(
        conn: Connection,
        sql: String,
        params: List<Any?> = emptyList(),
        block: (ResultSet) -> T,
    ): T =
        conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { stmt ->
            params.forEachIndexed { index, param ->
                stmt.setObject(index + 1, param)
            }
            stmt.executeUpdate()
            stmt.generatedKeys.use { keys ->
                keys.next()
                block(keys)
            }
        }

    /**
     * Execute a transaction.
     * Commits on success, rolls back on failure.
     */
    protected suspend fun <T> withTransaction(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                try {
                    conn.autoCommit = false
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
        }
}

/**
 * Time range query helper for consistent time-based queries.
 */
object TimeRangeQueryHelper {
    /**
     * Convert epoch milliseconds to PostgreSQL timestamp.
     */
    fun epochToTimestamp(): String = "to_timestamp(? / 1000.0)"

    /**
     * Build a time range SQL query.
     */
    fun buildTimeRangeSql(
        tableName: String,
        timeColumn: String,
        userIdColumn: String = "user_id",
        orderBy: String? = null,
    ): String {
        val baseSql =
            """
            SELECT * FROM $tableName
            WHERE $userIdColumn = ?
            AND $timeColumn >= ${epochToTimestamp()}
            AND $timeColumn < ${epochToTimestamp()}
            """.trimIndent()

        return if (orderBy != null) {
            "$baseSql ORDER BY $orderBy"
        } else {
            baseSql
        }
    }

    /**
     * Build a simple time-bounded query.
     */
    fun buildBoundedQuery(
        tableName: String,
        timeColumn: String,
        userIdColumn: String = "user_id",
        limit: Int? = null,
        offset: Int? = null,
    ): String {
        val baseSql =
            """
            SELECT * FROM $tableName
            WHERE $userIdColumn = ?
            AND $timeColumn >= ${epochToTimestamp()}
            """.trimIndent()

        val withLimit =
            if (limit != null) {
                "$baseSql LIMIT $limit"
            } else {
                baseSql
            }

        return if (offset != null) {
            "$withLimit OFFSET $offset"
        } else {
            withLimit
        }
    }
}
