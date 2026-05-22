package com.example.smarty.server.utils

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/**
 * SQL query builder for consistent query construction.
 *
 * Eliminates duplication of common database query patterns across repositories.
 *
 * Usage:
 * ```
 * val sql = QueryBuilder.select("notes")
 *     .whereUser(userId)
 *     .whereNotNull("deleted_at")
 *     .orderBy("updated_at DESC")
 *     .limit(50)
 *     .build()
 * ```
 */
class QueryBuilder private constructor(
    private val tableName: String,
    private val columns: List<String> = listOf("*"),
    private val whereClauses: MutableList<String> = mutableListOf(),
    private val whereParams: MutableList<Any?> = mutableListOf(),
    private val orderBy: String? = null,
    private val limit: Int? = null,
    private val offset: Int? = null,
) {
    companion object {
        /**
         * Start building a SELECT query.
         */
        fun select(
            tableName: String,
            columns: List<String> = listOf("*"),
        ): QueryBuilder = QueryBuilder(tableName, columns)

        /**
         * Start building an UPDATE query.
         */
        fun update(tableName: String): UpdateBuilder = UpdateBuilder(tableName)

        /**
         * Start building an INSERT query.
         */
        fun insert(tableName: String): InsertBuilder = InsertBuilder(tableName)

        /**
         * Start building a DELETE query.
         */
        fun delete(tableName: String): DeleteBuilder = DeleteBuilder(tableName)
    }

    /**
     * Add WHERE clause for user_id filter.
     */
    fun whereUser(userId: String): QueryBuilder =
        apply {
            whereClauses.add("user_id = ?")
            whereParams.add(UUID.fromString(userId))
        }

    /**
     * Add WHERE clause for column equals value.
     */
    fun whereEquals(
        column: String,
        value: Any?,
    ): QueryBuilder =
        apply {
            whereClauses.add("$column = ?")
            whereParams.add(value)
        }

    /**
     * Add WHERE clause for column IS NULL.
     */
    fun whereIsNull(column: String): QueryBuilder =
        apply {
            whereClauses.add("$column IS NULL")
        }

    /**
     * Add WHERE clause for column IS NOT NULL.
     */
    fun whereIsNotNull(column: String): QueryBuilder =
        apply {
            whereClauses.add("$column IS NOT NULL")
        }

    /**
     * Add WHERE clause for timestamp greater than.
     */
    fun whereTimestampAfter(
        column: String,
        timestamp: Long,
    ): QueryBuilder =
        apply {
            whereClauses.add("$column > to_timestamp(? / 1000.0)")
            whereParams.add(timestamp)
        }

    /**
     * Add WHERE clause with custom SQL.
     */
    fun whereCustom(
        sql: String,
        vararg params: Any?,
    ): QueryBuilder =
        apply {
            whereClauses.add(sql)
            params.forEach { whereParams.add(it) }
        }

    /**
     * Set ORDER BY clause.
     */
    fun orderBy(orderBy: String): QueryBuilder = copy(orderBy = orderBy)

    /**
     * Set LIMIT clause.
     */
    fun limit(limit: Int): QueryBuilder = copy(limit = limit)

    /**
     * Set OFFSET clause.
     */
    fun offset(offset: Int): QueryBuilder = copy(offset = offset)

    /**
     * Build the final SQL query.
     */
    fun build(): Pair<String, List<Any?>> {
        val selectClause = columns.joinToString(", ")
        val baseSql = "SELECT $selectClause FROM $tableName"

        val whereSql =
            if (whereClauses.isNotEmpty()) {
                "WHERE " + whereClauses.joinToString(" AND ")
            } else {
                ""
            }

        val orderSql = orderBy?.let { "ORDER BY $it" } ?: ""
        val limitSql = limit?.let { "LIMIT $it" } ?: ""
        val offsetSql = offset?.let { "OFFSET $it" } ?: ""

        val fullSql =
            listOf(baseSql, whereSql, orderSql, limitSql, offsetSql)
                .filter { it.isNotEmpty() }
                .joinToString(" ")

        return fullSql to whereParams
    }

    /**
     * Execute the query and process results.
     */
    fun <T> execute(
        conn: Connection,
        mapper: (ResultSet) -> T,
    ): List<T> {
        val (sql, params) = build()
        return conn.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param ->
                stmt.setObject(index + 1, param)
            }
            stmt.executeQuery().use { rs ->
                val results = mutableListOf<T>()
                while (rs.next()) {
                    results.add(mapper(rs))
                }
                results
            }
        }
    }

    private fun copy(
        tableName: String = this.tableName,
        columns: List<String> = this.columns,
        whereClauses: MutableList<String> = this.whereClauses,
        whereParams: MutableList<Any?> = this.whereParams,
        orderBy: String? = this.orderBy,
        limit: Int? = this.limit,
        offset: Int? = this.offset,
    ): QueryBuilder =
        QueryBuilder(
            tableName,
            columns,
            whereClauses,
            whereParams,
            orderBy,
            limit,
            offset,
        )
}

/**
 * UPDATE query builder.
 */
class UpdateBuilder(private val tableName: String) {
    private val setClauses = mutableListOf<String>()
    private val setParams = mutableListOf<Any?>()
    private val whereClauses = mutableListOf<String>()
    private val whereParams = mutableListOf<Any?>()

    /**
     * Add SET clause for column.
     */
    fun set(
        column: String,
        value: Any?,
    ): UpdateBuilder =
        apply {
            setClauses.add("$column = ?")
            setParams.add(value)
        }

    /**
     * Add SET clause for updated_at timestamp.
     */
    fun setUpdatedAtNow(): UpdateBuilder =
        apply {
            setClauses.add("updated_at = now()")
        }

    /**
     * Add WHERE clause.
     */
    fun where(
        column: String,
        value: Any?,
    ): UpdateBuilder =
        apply {
            whereClauses.add("$column = ?")
            whereParams.add(value)
        }

    /**
     * Add WHERE clause for user_id.
     */
    fun whereUser(userId: String): UpdateBuilder =
        apply {
            whereClauses.add("user_id = ?")
            whereParams.add(UUID.fromString(userId))
        }

    /**
     * Build the UPDATE query.
     */
    fun build(): Pair<String, List<Any?>> {
        val setSql = setClauses.joinToString(", ")
        val whereSql =
            if (whereClauses.isNotEmpty()) {
                "WHERE " + whereClauses.joinToString(" AND ")
            } else {
                ""
            }

        val sql = "UPDATE $tableName SET $setSql $whereSql"
        val allParams = setParams + whereParams

        return sql to allParams
    }

    /**
     * Execute the UPDATE query.
     */
    fun execute(conn: Connection): Int {
        val (sql, params) = build()
        return conn.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param ->
                stmt.setObject(index + 1, param)
            }
            stmt.executeUpdate()
        }
    }
}

/**
 * INSERT query builder.
 */
class InsertBuilder(private val tableName: String) {
    private val columns = mutableListOf<String>()
    private val values = mutableListOf<Any?>()

    /**
     * Add column and value.
     */
    fun column(
        column: String,
        value: Any?,
    ): InsertBuilder =
        apply {
            columns.add(column)
            values.add(value)
        }

    /**
     * Add UUID column.
     */
    fun uuidColumn(
        column: String,
        uuidString: String,
    ): InsertBuilder =
        apply {
            columns.add(column)
            values.add(UUID.fromString(uuidString))
        }

    /**
     * Add timestamp column with current time.
     */
    fun nowColumn(column: String): InsertBuilder =
        apply {
            columns.add(column)
            values.add(Timestamp(System.currentTimeMillis()))
        }

    /**
     * Build the INSERT query.
     */
    fun build(): Pair<String, List<Any?>> {
        val columnsSql = columns.joinToString(", ")
        val placeholders = List(values.size) { "?" }.joinToString(", ")

        val sql = "INSERT INTO $tableName ($columnsSql) VALUES ($placeholders)"
        return sql to values
    }

    /**
     * Execute the INSERT query and return generated key.
     */
    fun executeWithGeneratedKey(
        conn: Connection,
        keyMapper: (ResultSet) -> String,
    ): String {
        val (sql, params) = build()
        return conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { stmt ->
            params.forEachIndexed { index, param ->
                stmt.setObject(index + 1, param)
            }
            stmt.executeUpdate()
            stmt.generatedKeys.use { rs ->
                if (rs.next()) keyMapper(rs) else throw IllegalStateException("No generated key")
            }
        }
    }
}

/**
 * DELETE query builder.
 */
class DeleteBuilder(private val tableName: String) {
    private val whereClauses = mutableListOf<String>()
    private val whereParams = mutableListOf<Any?>()

    /**
     * Add WHERE clause.
     */
    fun where(
        column: String,
        value: Any?,
    ): DeleteBuilder =
        apply {
            whereClauses.add("$column = ?")
            whereParams.add(value)
        }

    /**
     * Add WHERE clause for user_id.
     */
    fun whereUser(userId: String): DeleteBuilder =
        apply {
            whereClauses.add("user_id = ?")
            whereParams.add(UUID.fromString(userId))
        }

    /**
     * Build the DELETE query.
     */
    fun build(): Pair<String, List<Any?>> {
        val whereSql =
            if (whereClauses.isNotEmpty()) {
                "WHERE " + whereClauses.joinToString(" AND ")
            } else {
                ""
            }

        val sql = "DELETE FROM $tableName $whereSql"
        return sql to whereParams
    }

    /**
     * Execute the DELETE query.
     */
    fun execute(conn: Connection): Int {
        val (sql, params) = build()
        return conn.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param ->
                stmt.setObject(index + 1, param)
            }
            stmt.executeUpdate()
        }
    }
}

/**
 * Common SQL query templates for consistent patterns.
 */
object QueryTemplates {
    /**
     * Standard SELECT query for user-scoped soft-delete tables.
     */
    fun standardSelect(
        tableName: String,
        userId: String,
        columns: String = "*",
        orderBy: String = "updated_at DESC",
        limit: Int = 50,
    ): Pair<String, List<Any?>> {
        val sql =
            """
            SELECT $columns FROM $tableName
            WHERE user_id = ? AND deleted_at IS NULL
            ORDER BY $orderBy
            LIMIT ?
            """.trimIndent()

        return sql to listOf(UUID.fromString(userId), limit)
    }

    /**
     * Standard delta sync query for updated_after.
     */
    fun deltaSyncSelect(
        tableName: String,
        userId: String,
        timestamp: Long,
        columns: String = "*",
        limit: Int = 50,
    ): Pair<String, List<Any?>> {
        val sql =
            """
            SELECT $columns FROM $tableName
            WHERE user_id = ? AND updated_at > to_timestamp(? / 1000.0) AND deleted_at IS NULL
            ORDER BY updated_at DESC
            LIMIT ?
            """.trimIndent()

        return sql to listOf(UUID.fromString(userId), timestamp, limit)
    }

    /**
     * Standard soft delete query.
     */
    fun standardSoftDelete(
        tableName: String,
        userId: String,
        id: String,
    ): Pair<String, List<Any?>> {
        val sql =
            """
            UPDATE $tableName
            SET deleted_at = now(), updated_at = now()
            WHERE id = ? AND user_id = ? AND deleted_at IS NULL
            """.trimIndent()

        return sql to listOf(UUID.fromString(id), UUID.fromString(userId))
    }

    /**
     * Standard existence check query.
     */
    fun existsCheck(
        tableName: String,
        userId: String,
        id: String,
    ): Pair<String, List<Any?>> {
        val sql =
            """
            SELECT 1 FROM $tableName
            WHERE id = ? AND user_id = ? AND deleted_at IS NULL
            """.trimIndent()

        return sql to listOf(UUID.fromString(id), UUID.fromString(userId))
    }
}
