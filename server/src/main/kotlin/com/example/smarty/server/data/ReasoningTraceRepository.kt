package com.example.smarty.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID

/**
 * Reasoning Trace Repository
 * Database operations for reasoning traces and thinking logs
 */
class ReasoningTraceRepository(private val dataSource: javax.sql.DataSource) {
    private val logger = LoggerFactory.getLogger(ReasoningTraceRepository::class.java)

    /**
     * Save a reasoning trace
     */
    suspend fun saveTrace(trace: ReasoningTrace): String = withContext(Dispatchers.IO) {
        val traceId = trace.traceId ?: UUID.randomUUID()
        
        dataSource.connection.use { conn ->
            // Schema uses 'id' as primary key, not 'trace_id'
            val sql = """
                INSERT INTO reasoning_traces (
                    id, session_id, message_id, user_id,
                    step_index, step_type, title, content,
                    confidence_score, importance_score, is_final, was_revised,
                    revised_by_trace_id, token_count, duration_ms, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (id) DO UPDATE SET
                    content = EXCLUDED.content,
                    confidence_score = EXCLUDED.confidence_score,
                    importance_score = EXCLUDED.importance_score,
                    duration_ms = EXCLUDED.duration_ms
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, traceId)
                stmt.setObject(2, UUID.fromString(trace.sessionId))
                trace.messageId?.let { stmt.setObject(3, UUID.fromString(it)) } ?: stmt.setNull(3, java.sql.Types.OTHER)
                stmt.setObject(4, UUID.fromString(trace.userId))  // UUID cast — v6 schema
                stmt.setInt(5, trace.stepIndex)
                stmt.setString(6, trace.stepType.name)
                stmt.setString(7, trace.title)
                stmt.setString(8, trace.content)
                stmt.setDouble(9, trace.confidenceScore)
                stmt.setDouble(10, trace.importanceScore)  // fixed: was param 11 before, skipping index
                stmt.setBoolean(11, trace.isFinal)         // fixed: corrected param indices
                stmt.setBoolean(12, trace.wasRevised)
                trace.revisedByTraceId?.let { stmt.setObject(13, UUID.fromString(it)) } ?: stmt.setNull(13, java.sql.Types.OTHER)
                stmt.setInt(14, trace.tokenCount)
                stmt.setLong(15, trace.durationMs)
                stmt.executeUpdate()
            }
        }
        
        traceId.toString()
    }

    /**
     * Save multiple reasoning traces (batch)
     */
    suspend fun saveTraces(traces: List<ReasoningTrace>): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val sql = """
                    INSERT INTO reasoning_traces (
                        id, session_id, message_id, user_id,
                        step_index, step_type, title, content,
                        confidence_score, importance_score, is_final, was_revised,
                        revised_by_trace_id, token_count, duration_ms, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """.trimIndent()
                
                conn.prepareStatement(sql).use { stmt ->
                    traces.forEach { trace ->
                        val traceId = trace.traceId ?: UUID.randomUUID()
                        stmt.setObject(1, traceId)
                        stmt.setObject(2, UUID.fromString(trace.sessionId))
                        trace.messageId?.let { stmt.setObject(3, UUID.fromString(it)) } ?: stmt.setNull(3, java.sql.Types.OTHER)
                        stmt.setObject(4, UUID.fromString(trace.userId))  // UUID cast — v6 schema
                        stmt.setInt(5, trace.stepIndex)
                        stmt.setString(6, trace.stepType.name)
                        stmt.setString(7, trace.title)
                        stmt.setString(8, trace.content)
                        stmt.setDouble(9, trace.confidenceScore)
                        stmt.setDouble(10, trace.importanceScore)
                        stmt.setBoolean(11, trace.isFinal)
                        stmt.setBoolean(12, trace.wasRevised)
                        trace.revisedByTraceId?.let { stmt.setObject(13, UUID.fromString(it)) } ?: stmt.setNull(13, java.sql.Types.OTHER)
                        stmt.setInt(14, trace.tokenCount)
                        stmt.setLong(15, trace.durationMs)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
                traces.size
            } catch (e: Exception) {
                conn.rollback()
                logger.error("Error saving reasoning traces batch", e)
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /**
     * Get reasoning traces for a session
     */
    suspend fun getTracesForSession(sessionId: String, messageId: String? = null): List<ReasoningTrace> = withContext(Dispatchers.IO) {
        val traces = mutableListOf<ReasoningTrace>()
        
        dataSource.connection.use { conn ->
            val sql = """
                SELECT 
                    id, session_id, message_id, user_id,
                    step_index, step_type, title, content,
                    confidence_score, importance_score, is_final, was_revised,
                    revised_by_trace_id, token_count, duration_ms, created_at
                FROM reasoning_traces
                WHERE session_id = ?
                ${if (messageId != null) "AND (message_id = ? OR message_id IS NULL)" else ""}
                ORDER BY step_index ASC
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                if (messageId != null) {
                    stmt.setObject(2, UUID.fromString(messageId))
                }
                
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        traces.add(
                            ReasoningTrace(
                            traceId = rs.getObject("id") as UUID,  // v6 schema: 'id' not 'trace_id'
                                sessionId = (rs.getObject("session_id") as UUID).toString(),
                                messageId = (rs.getObject("message_id") as UUID?)?.toString(),
                                userId = rs.getString("user_id"),
                                stepIndex = rs.getInt("step_index"),
                                stepType = ReasoningStepType.valueOf(rs.getString("step_type")),
                                title = rs.getString("title"),
                                content = rs.getString("content"),
                                confidenceScore = rs.getDouble("confidence_score"),
                                importanceScore = rs.getDouble("importance_score"),
                                isFinal = rs.getBoolean("is_final"),
                                wasRevised = rs.getBoolean("was_revised"),
                                revisedByTraceId = (rs.getObject("revised_by_trace_id") as UUID?)?.toString(),
                                tokenCount = rs.getInt("token_count"),
                                durationMs = rs.getLong("duration_ms")
                            )
                        )
                    }
                }
            }
        }
        
        traces
    }

    /**
     * Get reasoning timeline for UI
     */
    suspend fun getReasoningTimeline(sessionId: String): List<ReasoningTraceWithSummary> = withContext(Dispatchers.IO) {
        val traces = mutableListOf<ReasoningTraceWithSummary>()
        
        dataSource.connection.use { conn ->
            val sql = """
                SELECT 
                    rt.id, rt.session_id, rt.message_id, rt.user_id,
                    rt.step_index, rt.step_type, rt.title, rt.content,
                    rt.confidence_score, rt.importance_score,
                    rt.is_final, rt.was_revised, rt.duration_ms, rt.created_at,
                    rs.one_liner, rs.brief_summary
                FROM reasoning_traces rt
                LEFT JOIN reasoning_summaries rs ON rt.session_id = rs.session_id
                WHERE rt.session_id = ?
                ORDER BY rt.step_index ASC
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        traces.add(
                            ReasoningTraceWithSummary(
                                traceId = rs.getObject("id") as UUID,  // v6 schema: 'id' not 'trace_id'
                                sessionId = (rs.getObject("session_id") as UUID).toString(),
                                stepIndex = rs.getInt("step_index"),
                                stepType = ReasoningStepType.valueOf(rs.getString("step_type")),
                                title = rs.getString("title"),
                                content = rs.getString("content"),
                                confidenceScore = rs.getDouble("confidence_score"),
                                importanceScore = rs.getDouble("importance_score"),
                                isFinal = rs.getBoolean("is_final"),
                                wasRevised = rs.getBoolean("was_revised"),
                                durationMs = rs.getLong("duration_ms"),
                                oneLiner = rs.getString("one_liner"),
                                briefSummary = rs.getString("brief_summary")
                            )
                        )
                    }
                }
            }
        }
        
        traces
    }

    /**
     * Save reasoning summary
     */
    suspend fun saveSummary(summary: ReasoningSummary): String = withContext(Dispatchers.IO) {
        val summaryId = summary.summaryId ?: UUID.randomUUID()
        
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO reasoning_summaries (
                    summary_id, session_id, message_id, user_id,
                    one_liner, brief_summary, detailed_summary,
                    total_steps, total_duration_ms, total_tokens,
                    confidence_score, complexity_score, reasoning_type, tags
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (summary_id) DO UPDATE SET
                    one_liner = EXCLUDED.one_liner,
                    brief_summary = EXCLUDED.brief_summary,
                    detailed_summary = EXCLUDED.detailed_summary,
                    total_steps = EXCLUDED.total_steps,
                    total_duration_ms = EXCLUDED.total_duration_ms,
                    updated_at = NOW()
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, summaryId)
                stmt.setObject(2, UUID.fromString(summary.sessionId))
                summary.messageId?.let { stmt.setObject(3, UUID.fromString(it)) } ?: stmt.setNull(3, java.sql.Types.OTHER)
                stmt.setObject(4, UUID.fromString(summary.userId))  // UUID cast — v6 schema
                stmt.setString(5, summary.oneLiner)
                stmt.setString(6, summary.briefSummary)
                stmt.setString(7, summary.detailedSummary)
                stmt.setInt(8, summary.totalSteps)
                stmt.setLong(9, summary.totalDurationMs)
                stmt.setInt(10, summary.totalTokens)
                stmt.setDouble(11, summary.confidenceScore)
                stmt.setDouble(12, summary.complexityScore)
                stmt.setString(13, summary.reasoningType)
                stmt.setArray(14, conn.createArrayOf("text", summary.tags.toTypedArray()))
                stmt.executeUpdate()
            }
        }
        
        summaryId.toString()
    }

    /**
     * Get reasoning summary
     */
    suspend fun getSummary(sessionId: String, messageId: String? = null): ReasoningSummary? = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT 
                    summary_id, session_id, message_id, user_id,
                    one_liner, brief_summary, detailed_summary,
                    total_steps, total_duration_ms, total_tokens,
                    confidence_score, complexity_score, reasoning_type, tags
                FROM reasoning_summaries
                WHERE session_id = ?
                ${if (messageId != null) "AND (message_id = ? OR message_id IS NULL)" else ""}
                ORDER BY created_at DESC
                LIMIT 1
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(sessionId))
                if (messageId != null) {
                    stmt.setObject(2, UUID.fromString(messageId))
                }
                
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        ReasoningSummary(
                            summaryId = rs.getObject("summary_id") as UUID,
                            sessionId = (rs.getObject("session_id") as UUID).toString(),
                            messageId = (rs.getObject("message_id") as UUID?)?.toString(),
                            userId = rs.getString("user_id"),
                            oneLiner = rs.getString("one_liner"),
                            briefSummary = rs.getString("brief_summary"),
                            detailedSummary = rs.getString("detailed_summary"),
                            totalSteps = rs.getInt("total_steps"),
                            totalDurationMs = rs.getLong("total_duration_ms"),
                            totalTokens = rs.getInt("total_tokens"),
                            confidenceScore = rs.getDouble("confidence_score"),
                            complexityScore = rs.getDouble("complexity_score"),
                            reasoningType = rs.getString("reasoning_type"),
                            tags = (rs.getArray("tags")?.array as? Array<*>)?.filterIsInstance<String>() ?: emptyList()
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    /**
     * Mark trace as revised
     */
    suspend fun markTraceAsRevised(traceId: String, revisedByTraceId: String): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE reasoning_traces
                SET was_revised = TRUE,
                    revised_by_trace_id = ?
                WHERE trace_id = ?
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, UUID.fromString(revisedByTraceId))
                stmt.setObject(2, UUID.fromString(traceId))
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Delete old reasoning traces (cleanup)
     */
    suspend fun deleteOldTraces(daysOld: Int = 30): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            val sql = """
                DELETE FROM reasoning_traces
                WHERE created_at < NOW() - INTERVAL '$daysOld days'
                  AND session_id NOT IN (
                      SELECT id FROM chat_sessions WHERE is_active = true
                  )
            """.trimIndent()
            
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeUpdate()
            }
        }
    }

    // Helper function to generate content hash
    private fun generateContentHash(content: String): String {
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Reasoning Trace data class
 */
data class ReasoningTrace(
    val traceId: UUID? = null,
    val sessionId: String,
    val messageId: String? = null,
    val userId: String,
    val stepIndex: Int,
    val stepType: ReasoningStepType,
    val title: String,
    val content: String,
    val confidenceScore: Double = 0.5,
    val importanceScore: Double = 0.5,
    val isFinal: Boolean = false,
    val wasRevised: Boolean = false,
    val revisedByTraceId: String? = null,
    val tokenCount: Int = 0,
    val durationMs: Long = 0
)

/**
 * Reasoning Step Type enum
 */
enum class ReasoningStepType {
    ANALYSIS,
    PLANNING,
    HYPOTHESIS,
    RESEARCH,
    VERIFICATION,
    SYNTHESIS,
    REFLECTION,
    CORRECTION
}

/**
 * Reasoning Summary data class
 */
data class ReasoningSummary(
    val summaryId: UUID? = null,
    val sessionId: String,
    val messageId: String? = null,
    val userId: String,
    val oneLiner: String = "",
    val briefSummary: String = "",
    val detailedSummary: String = "",
    val totalSteps: Int = 0,
    val totalDurationMs: Long = 0,
    val totalTokens: Int = 0,
    val confidenceScore: Double = 0.5,
    val complexityScore: Double = 0.5,
    val reasoningType: String = "",
    val tags: List<String> = emptyList()
)

/**
 * Reasoning Trace with Summary (for UI timeline)
 */
data class ReasoningTraceWithSummary(
    val traceId: UUID,
    val sessionId: String,
    val messageId: String? = null,
    val stepIndex: Int,
    val stepType: ReasoningStepType,
    val title: String,
    val content: String,
    val confidenceScore: Double,
    val importanceScore: Double,
    val isFinal: Boolean,
    val wasRevised: Boolean,
    val durationMs: Long,
    val oneLiner: String? = null,
    val briefSummary: String? = null
)
