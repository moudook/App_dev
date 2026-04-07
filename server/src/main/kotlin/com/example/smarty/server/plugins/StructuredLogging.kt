package com.example.smarty.server.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.principal
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Structured logging for production-ready server monitoring.
 */
class StructuredLogging {
    companion object {
        /**
         * Key for correlation ID in call attributes
         */
        val CorrelationIdKey = AttributeKey<String>("CorrelationId")

        /**
         * Key for start time in call attributes
         */
        val StartTimeKey = AttributeKey<Long>("StartTime")

        /**
         * Key for request ID in call attributes
         */
        val RequestIdKey = AttributeKey<String>("RequestId")
    }
}

/**
 * Install structured logging plugin
 */
fun Application.installStructuredLogging() {
    val logger = LoggerFactory.getLogger("RequestLogging")

    intercept(ApplicationCallPipeline.Monitoring) {
        val startTime = System.currentTimeMillis()
        val correlationId = UUID.randomUUID().toString()
        val requestId = "${call.request.httpMethod.value}-${System.nanoTime()}"

        call.attributes.put(StructuredLogging.CorrelationIdKey, correlationId)
        call.attributes.put(StructuredLogging.StartTimeKey, startTime)
        call.attributes.put(StructuredLogging.RequestIdKey, requestId)

        call.response.headers.append("X-Correlation-ID", correlationId)
        call.response.headers.append("X-Request-ID", requestId)

        logRequestStart(logger, call, correlationId, requestId)
    }

    intercept(ApplicationCallPipeline.Fallback) {
        val startTime = call.attributes[StructuredLogging.StartTimeKey]
        val correlationId = call.attributes[StructuredLogging.CorrelationIdKey]
        val requestId = call.attributes[StructuredLogging.RequestIdKey]
        val durationMs = System.currentTimeMillis() - startTime

        logRequestComplete(logger, call, correlationId, requestId, durationMs)
    }
}

private fun logRequestStart(
    logger: Logger,
    call: ApplicationCall,
    correlationId: String,
    requestId: String,
) {
    val logEntry =
        buildLogEntry(
            level = "DEBUG",
            correlationId = correlationId,
            requestId = requestId,
            method = call.request.httpMethod.value,
            path = call.request.path(),
            userId = call.getUserId(),
            deviceId = call.getDeviceId(),
            message = "Request started",
            additionalFields =
                mapOf(
                    "userAgent" to (call.request.userAgent() ?: "unknown"),
                    "referer" to (call.request.headers["Referer"] ?: "none"),
                ),
        )
    logger.debug(logEntry)
}

private fun logRequestComplete(
    logger: Logger,
    call: ApplicationCall,
    correlationId: String,
    requestId: String,
    durationMs: Long,
) {
    val statusCode = call.response.status()?.value ?: 0
    val requestSize = call.request.contentLength() ?: 0
    val responseSize = call.response.headers["Content-Length"]?.toLongOrNull() ?: 0

    val level =
        when {
            statusCode >= 500 -> "ERROR"
            statusCode >= 400 -> "WARN"
            else -> "INFO"
        }

    val message =
        when {
            statusCode >= 500 -> "Server error"
            statusCode >= 400 -> "Client error"
            else -> "Request completed successfully"
        }

    val logEntry =
        buildLogEntry(
            level = level,
            correlationId = correlationId,
            requestId = requestId,
            method = call.request.httpMethod.value,
            path = call.request.path(),
            userId = call.getUserId(),
            deviceId = call.getDeviceId(),
            statusCode = statusCode,
            responseTimeMs = durationMs,
            requestSize = requestSize,
            responseSize = responseSize,
            message = message,
        )

    when (level) {
        "ERROR" -> logger.error(logEntry)
        "WARN" -> logger.warn(logEntry)
        "INFO" -> logger.info(logEntry)
    }
}

private fun buildLogEntry(
    level: String,
    correlationId: String,
    requestId: String,
    method: String,
    path: String,
    userId: String? = null,
    deviceId: String? = null,
    statusCode: Int? = null,
    responseTimeMs: Long? = null,
    requestSize: Long? = null,
    responseSize: Long? = null,
    message: String,
    additionalFields: Map<String, String> = emptyMap(),
): String {
    val timestamp = java.time.Instant.now().toString()

    return buildString {
        append("{")
        append("\"timestamp\":\"$timestamp\"")
        append(",\"level\":\"$level\"")
        append(",\"correlationId\":\"$correlationId\"")
        append(",\"requestId\":\"$requestId\"")
        append(",\"method\":\"$method\"")
        append(",\"path\":\"$path\"")

        if (userId != null) append(",\"userId\":\"$userId\"")
        if (deviceId != null) append(",\"deviceId\":\"$deviceId\"")
        if (statusCode != null) append(",\"statusCode\":$statusCode")
        if (responseTimeMs != null) append(",\"responseTimeMs\":$responseTimeMs")
        if (requestSize != null) append(",\"requestSize\":$requestSize")
        if (responseSize != null) append(",\"responseSize\":$responseSize")

        additionalFields.forEach { (key, value) ->
            append(",\"$key\":\"${value.replace("\"", "\\\"")}\"")
        }

        append(",\"message\":\"${message.replace("\"", "\\\"")}\"")
        append("}")
    }
}

private fun ApplicationCall.getUserId(): String? {
    return try {
        principal<FirebaseUserPrincipal>()?.userId
    } catch (e: Exception) {
        null
    }
}

private fun ApplicationCall.getDeviceId(): String? {
    return request.headers["X-Smarty-Device-Id"]
}

suspend fun PipelineContext<*, ApplicationCall>.logEvent(
    level: String = "INFO",
    event: String,
    message: String,
    additionalFields: Map<String, String> = emptyMap(),
) {
    val correlationId = call.attributes.getOrNull(StructuredLogging.CorrelationIdKey) ?: "unknown"
    val requestId = call.attributes.getOrNull(StructuredLogging.RequestIdKey) ?: "unknown"
    val logger = LoggerFactory.getLogger("CustomEvent")

    val logEntry =
        buildLogEntry(
            level = level,
            correlationId = correlationId,
            requestId = requestId,
            method = call.request.httpMethod.value,
            path = call.request.path(),
            userId = call.getUserId(),
            deviceId = call.getDeviceId(),
            message = message,
            additionalFields = additionalFields + mapOf("event" to event),
        )

    withContext(Dispatchers.IO) {
        when (level) {
            "ERROR" -> logger.error(logEntry)
            "WARN" -> logger.warn(logEntry)
            "INFO" -> logger.info(logEntry)
            "DEBUG" -> logger.debug(logEntry)
        }
    }
}

suspend fun PipelineContext<*, ApplicationCall>.logError(
    error: Throwable,
    message: String = "Error occurred",
    additionalFields: Map<String, String> = emptyMap(),
) {
    val correlationId = call.attributes.getOrNull(StructuredLogging.CorrelationIdKey) ?: "unknown"
    val requestId = call.attributes.getOrNull(StructuredLogging.RequestIdKey) ?: "unknown"
    val logger = LoggerFactory.getLogger("ErrorLogging")
    val timestamp = java.time.Instant.now().toString()

    val logEntry =
        buildString {
            append("{")
            append("\"timestamp\":\"$timestamp\"")
            append(",\"level\":\"ERROR\"")
            append(",\"correlationId\":\"$correlationId\"")
            append(",\"requestId\":\"$requestId\"")
            append(",\"method\":\"${call.request.httpMethod.value}\"")
            append(",\"path\":\"${call.request.path()}\"")

            call.getUserId()?.let { append(",\"userId\":\"$it\"") }
            call.getDeviceId()?.let { append(",\"deviceId\":\"$it\"") }

            append(",\"message\":\"${message.replace("\"", "\\\"")}\"")
            append(",\"errorType\":\"${error.javaClass.name}\"")
            append(",\"errorMessage\":\"${error.message?.replace("\"", "\\\"") ?: "null"}\"")
            append(",\"stackTrace\":\"${error.stackTraceToString().replace("\"", "\\\"").replace("\n", "\\n")}\"")

            additionalFields.forEach { (key, value) ->
                append(",\"$key\":\"${value.replace("\"", "\\\"")}\"")
            }

            append("}")
        }

    withContext(Dispatchers.IO) {
        logger.error(logEntry)
    }
}

suspend fun PipelineContext<*, ApplicationCall>.logPerformance(
    operation: String,
    durationMs: Long,
    thresholdMs: Long = 1000L,
    additionalFields: Map<String, String> = emptyMap(),
) {
    val level = if (durationMs >= thresholdMs) "WARN" else "DEBUG"
    val message =
        if (durationMs >= thresholdMs) {
            "Slow operation: $operation took ${durationMs}ms (threshold: ${thresholdMs}ms)"
        } else {
            "Operation completed: $operation took ${durationMs}ms"
        }

    logEvent(
        level = level,
        event = "performance",
        message = message,
        additionalFields =
            additionalFields +
                mapOf(
                    "operation" to operation,
                    "durationMs" to durationMs.toString(),
                    "thresholdMs" to thresholdMs.toString(),
                ),
    )
}
