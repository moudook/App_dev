package com.example.smarty.server.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile

/**
 * Real-time monitor for OpenCode daemon logs.
 * 
 * Tails /tmp/opencode-daemon.log and emits structured events for:
 * - Tool calls (web search, fetch, etc.)
 * - Reasoning/thinking steps
 * - Sub-agent delegation
 * - Internal processing steps
 * 
 * This allows the server to stream daemon internals to clients in real-time.
 */
class DaemonLogMonitor {
    private val logger = LoggerFactory.getLogger(DaemonLogMonitor::class.java)
    private val logFile = File("/tmp/opencode-daemon.log")
    private val _events = MutableSharedFlow<DaemonEvent>(replay = 0, extraBufferCapacity = 1000)
    val events: SharedFlow<DaemonEvent> = _events.asSharedFlow()
    
    private var monitorJob: Job? = null
    
    fun start(scope: CoroutineScope) {
        if (!logFile.exists()) {
            logger.warn("Daemon log file not found: ${logFile.absolutePath}")
            return
        }
        
        monitorJob = scope.launch(Dispatchers.IO) {
            logger.info("Starting daemon log monitor: ${logFile.absolutePath}")
            
            // Start from current end of file
            var filePosition = logFile.length()
            
            while (isActive) {
                try {
                    if (logFile.exists()) {
                        val currentLength = logFile.length()
                        
                        // Check if file was rotated (smaller than last position)
                        if (currentLength < filePosition) {
                            logger.info("Log file rotated, starting from beginning")
                            filePosition = 0
                        }
                        
                        if (currentLength > filePosition) {
                            // Read new content
                            RandomAccessFile(logFile, "r").use { raf ->
                                raf.seek(filePosition)
                                val buffer = ByteArray((currentLength - filePosition).toInt())
                                val bytesRead = raf.read(buffer)
                                if (bytesRead > 0) {
                                    val newContent = String(buffer, 0, bytesRead)
                                    parseAndEmitEvents(newContent)
                                    filePosition = raf.filePointer
                                }
                            }
                        }
                    }
                    
                    delay(100) // Check every 100ms
                } catch (e: Exception) {
                    logger.error("Error monitoring daemon log", e)
                    delay(1000) // Wait longer on error
                }
            }
            
            logger.info("Daemon log monitor stopped")
        }
    }
    
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }
    
    private suspend fun parseAndEmitEvents(content: String) {
        content.lines().forEach { line ->
            if (line.isBlank()) return@forEach
            
            // Try to parse as JSON first (structured logs)
            if (line.trim().startsWith("{")) {
                try {
                    val json = kotlinx.serialization.json.Json.parseToJsonElement(line)
                    val jsonObj = json as? kotlinx.serialization.json.JsonObject
                    
                    if (jsonObj != null) {
                        val level = jsonObj["level"]?.let { 
                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content 
                        }
                        val message = jsonObj["msg"]?.let {
                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                        }
                        
                        if (level != null && message != null) {
                            val event = parseLogLine(level, message, jsonObj)
                            if (event != null) {
                                _events.emit(event)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Not valid JSON, try plain text parsing
                    parsePlainTextLog(line)
                }
            } else {
                // Plain text log
                parsePlainTextLog(line)
            }
        }
    }
    
    private suspend fun parseLogLine(
        level: String,
        message: String,
        jsonObj: kotlinx.serialization.json.JsonObject
    ): DaemonEvent? {
        // Parse different types of daemon events
        
        // Tool calls
        if (message.contains("tool", ignoreCase = true) || 
            message.contains("calling", ignoreCase = true) ||
            jsonObj.containsKey("tool") ||
            jsonObj.containsKey("toolName")) {
            
            val toolName = jsonObj["tool"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            } ?: jsonObj["toolName"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            } ?: extractToolName(message)
            
            val status = when {
                message.contains("starting", ignoreCase = true) || 
                message.contains("calling", ignoreCase = true) -> "running"
                message.contains("completed", ignoreCase = true) ||
                message.contains("finished", ignoreCase = true) -> "completed"
                message.contains("error", ignoreCase = true) ||
                message.contains("failed", ignoreCase = true) -> "error"
                else -> "unknown"
            }
            
            return DaemonEvent.ToolCall(
                toolName = toolName,
                status = status,
                arguments = jsonObj["args"]?.toString(),
                result = jsonObj["result"]?.toString()
            )
        }
        
        // Reasoning/thinking
        if (message.contains("think", ignoreCase = true) ||
            message.contains("reason", ignoreCase = true) ||
            message.contains("analyzing", ignoreCase = true) ||
            level.equals("debug", ignoreCase = true) && message.length > 50) {
            
            return DaemonEvent.Reasoning(
                content = message,
                step = jsonObj["step"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                }
            )
        }
        
        // Web search
        if (message.contains("search", ignoreCase = true) ||
            message.contains("web", ignoreCase = true) ||
            message.contains("fetch", ignoreCase = true)) {
            
            return DaemonEvent.WebSearch(
                query = jsonObj["query"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                } ?: extractSearchQuery(message),
                status = when {
                    message.contains("starting", ignoreCase = true) -> "searching"
                    message.contains("completed", ignoreCase = true) ||
                    message.contains("found", ignoreCase = true) -> "completed"
                    else -> "unknown"
                },
                results = jsonObj["results"]?.toString()
            )
        }
        
        // Sub-agent delegation
        if (message.contains("subagent", ignoreCase = true) ||
            message.contains("delegate", ignoreCase = true) ||
            message.contains("spawn", ignoreCase = true)) {
            
            return DaemonEvent.SubAgent(
                action = when {
                    message.contains("spawn", ignoreCase = true) ||
                    message.contains("create", ignoreCase = true) -> "spawned"
                    message.contains("message", ignoreCase = true) -> "messaged"
                    message.contains("complete", ignoreCase = true) -> "completed"
                    else -> "unknown"
                },
                agentId = jsonObj["agentId"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                },
                task = jsonObj["task"]?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                }
            )
        }
        
        // Generic debug event for other debug-level logs
        if (level.equals("debug", ignoreCase = true)) {
            return DaemonEvent.Debug(
                message = message,
                metadata = jsonObj.toString()
            )
        }
        
        return null
    }
    
    private suspend fun parsePlainTextLog(line: String) {
        // Parse plain text logs (fallback)
        // Look for patterns like:
        // "Calling tool: websearch with args: {...}"
        // "Tool completed: websearch"
        // "Reasoning: ..."
        // "Web search: query..."
        
        val toolCallPattern = Regex("(?i)(?:calling|invoking|using)\\s+tool[:\\s]+(\\w+)")
        val toolCompletePattern = Regex("(?i)tool[:\\s]+(\\w+)\\s+(?:completed|finished|done)")
        val reasoningPattern = Regex("(?i)(?:reasoning|thinking|analyzing)[:\\s]+(.+)")
        val searchPattern = Regex("(?i)(?:search|web search|fetching)[:\\s]+(.+)")
        
        when {
            toolCallPattern.containsMatchIn(line) -> {
                val match = toolCallPattern.find(line)
                val toolName = match?.groupValues?.get(1) ?: "unknown"
                _events.emit(DaemonEvent.ToolCall(toolName = toolName, status = "running"))
            }
            toolCompletePattern.containsMatchIn(line) -> {
                val match = toolCompletePattern.find(line)
                val toolName = match?.groupValues?.get(1) ?: "unknown"
                _events.emit(DaemonEvent.ToolCall(toolName = toolName, status = "completed"))
            }
            reasoningPattern.containsMatchIn(line) -> {
                val match = reasoningPattern.find(line)
                val content = match?.groupValues?.get(1) ?: line
                _events.emit(DaemonEvent.Reasoning(content = content))
            }
            searchPattern.containsMatchIn(line) -> {
                val match = searchPattern.find(line)
                val query = match?.groupValues?.get(1) ?: ""
                _events.emit(DaemonEvent.WebSearch(query = query, status = "searching"))
            }
        }
    }
    
    private fun extractToolName(message: String): String {
        val patterns = listOf(
            Regex("(?i)tool[:\\s]+(\\w+)"),
            Regex("(?i)calling[:\\s]+(\\w+)"),
            Regex("(?i)invoking[:\\s]+(\\w+)")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(message)
            if (match != null) {
                return match.groupValues.getOrNull(1) ?: "unknown"
            }
        }
        
        return "unknown"
    }
    
    private fun extractSearchQuery(message: String): String {
        val patterns = listOf(
            Regex("(?i)search(?:ing)?[:\\s]+['\"]?([^'\"]+)['\"]?"),
            Regex("(?i)query[:\\s]+['\"]?([^'\"]+)['\"]?"),
            Regex("(?i)fetching[:\\s]+['\"]?([^'\"]+)['\"]?")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(message)
            if (match != null) {
                return match.groupValues.getOrNull(1)?.trim() ?: ""
            }
        }
        
        return message
    }
}

/**
 * Structured daemon events parsed from logs
 */
sealed class DaemonEvent {
    data class ToolCall(
        val toolName: String,
        val status: String, // "running", "completed", "error"
        val arguments: String? = null,
        val result: String? = null
    ) : DaemonEvent()
    
    data class Reasoning(
        val content: String,
        val step: String? = null
    ) : DaemonEvent()
    
    data class WebSearch(
        val query: String,
        val status: String, // "searching", "completed"
        val results: String? = null
    ) : DaemonEvent()
    
    data class SubAgent(
        val action: String, // "spawned", "messaged", "completed"
        val agentId: String? = null,
        val task: String? = null
    ) : DaemonEvent()
    
    data class Debug(
        val message: String,
        val metadata: String? = null
    ) : DaemonEvent()
}
