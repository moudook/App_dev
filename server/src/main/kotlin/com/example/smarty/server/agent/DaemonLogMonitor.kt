package com.example.smarty.server.agent

import com.example.smarty.protocol.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Real-time monitor for OpenCode daemon logs.
 *
 * Tails /tmp/opencode-daemon.log and emits AgentEvent objects for:
 * - Tool calls (web search, fetch, etc.)
 * - Reasoning/thinking steps
 * - Agent loop steps (step=0, step=1, etc.)
 * - LLM streaming events
 * - Sub-agent delegation
 * - Permission evaluations
 * - Tool registry events
 *
 * The daemon log format is PLAIN TEXT with key=value pairs:
 *   INFO  2026-05-28T16:29:15 +6ms service=session.tools status=started resolveTools
 *   INFO  2026-05-28T16:29:15 +0ms service=tool.registry status=started websearch
 *   INFO  2026-05-28T16:29:19 +1ms service=bus type=message.part.delta publishing
 */
object DaemonLogMonitor {
    private val logger = LoggerFactory.getLogger(DaemonLogMonitor::class.java)
    private val logFile = File("/tmp/opencode-daemon.log")
    private val _events = MutableSharedFlow<AgentEvent>(replay = 0, extraBufferCapacity = 1000)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private var monitorJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (!logFile.exists()) {
            logger.warn("\u26A0\uFE0F [DAEMON_LOG] Log file not found: ${logFile.absolutePath}")
            return
        }

        monitorJob = scope.launch(Dispatchers.IO) {
            logger.info("\uD83D\uDE80 [DAEMON_LOG] Starting daemon log monitor: ${logFile.absolutePath}")

            // Start from current end of file
            var filePosition = logFile.length()

            while (isActive) {
                try {
                    if (logFile.exists()) {
                        val currentLength = logFile.length()

                        // Handle log rotation
                        if (currentLength < filePosition) {
                            logger.info("\uD83D\uDD04 [DAEMON_LOG] Log file rotated, starting from beginning")
                            filePosition = 0
                        }

                        if (currentLength > filePosition) {
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

                    delay(100)
                } catch (e: Exception) {
                    logger.error("\u274C [DAEMON_LOG] Error monitoring daemon log: ${e.message}")
                    delay(1000)
                }
            }

            logger.info("\uD83D\uDED1 [DAEMON_LOG] Daemon log monitor stopped")
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private suspend fun parseAndEmitEvents(content: String) {
        content.lines().forEach { line ->
            if (line.isBlank()) return@forEach

            // Skip lines that don't have service= (these are Ktor server logs, not daemon logs)
            if (!line.contains("service=")) return@forEach

            try {
                val event = parseDaemonLogLine(line)
                if (event != null) {
                    _events.emit(event)
                }
            } catch (e: Exception) {
                // Ignore parse errors for individual lines
            }
        }
    }

    /**
     * Parse a daemon log line into an AgentEvent.
     *
     * Format: LEVEL  TIMESTAMP +DURMS key=value key=value ...
     * Example: INFO  2026-05-28T16:29:15 +6ms service=session.tools status=started resolveTools
     */
    private fun parseDaemonLogLine(line: String): AgentEvent? {
        // Extract key=value pairs from the line
        val kvPairs = extractKeyValuePairs(line)
        val service = kvPairs["service"] ?: return null
        val status = kvPairs["status"]
        val now = System.currentTimeMillis()

        return when (service) {
            // Agent loop steps: service=session.prompt session.id=... step=N loop
            "session.prompt" -> {
                val step = kvPairs["step"]
                val sessionId = kvPairs["session.id"]
                if (step != null) {
                    val action = line.substringAfterLast(" ").trim() // "loop" or "exiting loop"
                    AgentEvent.AgentStep(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = now,
                        stepIndex = step.toIntOrNull() ?: 0,
                        stepType = "agent_loop",
                        stepTitle = "Agent Step $step",
                        stepContent = if (action.contains("exiting")) "Agent loop completed at step $step" else "Executing agent step $step",
                        stepStatus = if (action.contains("exiting")) "completed" else "running",
                    )
                } else null
            }

            // Tool resolution: service=session.tools status=started resolveTools
            "session.tools" -> {
                val action = line.substringAfterLast(" ").trim()
                AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = now,
                    content = "Resolving available tools...",
                    thinking = "Tool resolution: $action",
                )
            }

            // Tool registry events: service=tool.registry status=started websearch
            "tool.registry" -> {
                val toolName = line.substringAfterLast(" ").trim()
                if (toolName.isNotEmpty() && toolName != "invalid") {
                    val emoji = getToolEmoji(toolName)
                    AgentEvent.Processing(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = now,
                        content = "$emoji Tool registered: $toolName",
                        thinking = "Tool registry: $toolName ($status)",
                    )
                } else null
            }

            // LLM calls: service=llm providerID=opencode modelID=... stream
            "llm" -> {
                val modelId = kvPairs["modelID"] ?: "unknown"
                val sessionId = kvPairs["session.id"]
                AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = now,
                    content = "\uD83E\uDDE0 Calling LLM: $modelId",
                    thinking = "LLM inference started with model $modelId for session $sessionId",
                )
            }

            // Provider events: service=provider status=started state
            "provider" -> {
                val providerId = kvPairs["providerID"]
                if (providerId != null) {
                    AgentEvent.Processing(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = now,
                        content = "\uD83D\uDD0C Provider: $providerId ($status)",
                        thinking = "Provider $providerId status: $status",
                    )
                } else null
            }

            // MCP tool calls: service=mcp key=smarty toolCount=12 create() successfully created client
            "mcp" -> {
                val toolCount = kvPairs["toolCount"]
                val key = kvPairs["key"]
                if (toolCount != null) {
                    AgentEvent.Processing(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = now,
                        content = "\uD83D\uDD17 MCP connected: $toolCount tools available",
                        thinking = "MCP server ($key) connected with $toolCount tools",
                    )
                } else null
            }

            // Permission evaluations: service=permission permission=smarty_ask_user pattern=* action=...
            "permission" -> {
                val permission = kvPairs["permission"] ?: "unknown"
                val actionObj = kvPairs["action"]
                AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = now,
                    content = "\uD83D\uDD11 Permission check: $permission",
                    thinking = "Permission evaluated: $permission = $actionObj",
                )
            }

            // Session status changes: service=session.status publishing
            "session.status" -> {
                AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = now,
                    content = "\uD83D\uDCCA Session status updated",
                    thinking = "Session status changed",
                )
            }

            // Bus events (streaming deltas): service=bus type=message.part.delta publishing
            "bus" -> {
                val busType = kvPairs["type"]
                when (busType) {
                    "message.part.delta" -> {
                        // Streaming text delta - these are the intermediate streaming events
                        // We don't emit these individually as they're too granular
                        // The SSE stream handles these
                        null
                    }
                    "message.part.updated" -> null // Part completed - handled by SSE
                    "message.updated" -> null // Message completed - handled by SSE
                    "session.updated" -> null // Session state change - not actionable
                    "session.diff" -> null // Session diff - not actionable
                    "session.idle" -> null // Session idle - not actionable
                    "session.created" -> null // Session created - not actionable
                    "session.next.agent.switched" -> {
                        AgentEvent.Processing(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = now,
                            content = "\uD83E\uDD16 Agent switched",
                            thinking = "Sub-agent or agent type changed",
                        )
                    }
                    "session.next.model.switched" -> {
                        AgentEvent.Processing(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = now,
                            content = "\uD83E\uDDE0 Model switched",
                            thinking = "Model changed for next inference",
                        )
                    }
                    "session.status" -> null // Duplicate of service=session.status
                    "command.executed" -> {
                        AgentEvent.Processing(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = now,
                            content = "\u26A1 Command executed",
                            thinking = "A command was executed in the session",
                        )
                    }
                    else -> null
                }
            }

            // Session processor: service=session.processor session.id=... messageID=... process
            "session.processor" -> {
                val messageId = kvPairs["messageID"]
                AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = now,
                    content = "\uD83D\uDCDD Processing message...",
                    thinking = "Session processor started for message $messageId",
                )
            }

            // Storage/migration: service=storage index=N running migration
            "storage" -> null // Internal, not useful for user

            // Shell tool: service=shell-tool shell=/bin/bash shell tool using shell
            "shell-tool" -> {
                AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = now,
                    content = "\uD83D\uDCBB Shell tool initialized",
                    thinking = "Shell tool ready for execution",
                )
            }

            // Config loading: service=config path=... loading
            "config" -> null // Internal, not useful for user

            // Plugin loading: service=plugin name=... loading internal plugin
            "plugin" -> null // Internal, not useful for user

            // File watcher: service=file.watcher directory=... init
            "file" -> null // Internal, not useful for user

            // LSP: service=lsp all LSPs are disabled
            "lsp" -> null // Internal

            // Format: service=format all formatters are disabled
            "format" -> null // Internal

            // Default: skip unknown services
            else -> null
        }
    }

    /**
     * Extract key=value pairs from a daemon log line.
     * Handles quoted values and values with spaces.
     */
    private fun extractKeyValuePairs(line: String): Map<String, String> {
        val pairs = mutableMapOf<String, String>()

        // Split by whitespace, but respect quoted strings
        val tokens = line.split("\\s+".toRegex())
        for (token in tokens) {
            val eqIndex = token.indexOf('=')
            if (eqIndex > 0) {
                val key = token.substring(0, eqIndex)
                val value = token.substring(eqIndex + 1).removeSurrounding("\"")
                pairs[key] = value
            }
        }

        return pairs
    }

    private fun getToolEmoji(toolName: String): String {
        return when {
            toolName.contains("websearch", ignoreCase = true) -> "\uD83D\uDD0D"
            toolName.contains("webfetch", ignoreCase = true) -> "\uD83C\uDF10"
            toolName.contains("bash", ignoreCase = true) -> "\uD83D\uDCBB"
            toolName.contains("read", ignoreCase = true) -> "\uD83D\uDCD6"
            toolName.contains("write", ignoreCase = true) -> "\u270F\uFE0F"
            toolName.contains("edit", ignoreCase = true) -> "\uD83D\uDD27"
            toolName.contains("glob", ignoreCase = true) -> "\uD83D\uDCC1"
            toolName.contains("grep", ignoreCase = true) -> "\uD83D\uDD0E"
            toolName.contains("task", ignoreCase = true) -> "\uD83D\uDCCB"
            toolName.contains("skill", ignoreCase = true) -> "\uD83C\uDFAF"
            toolName.contains("question", ignoreCase = true) -> "\u2753"
            toolName.contains("todowrite", ignoreCase = true) -> "\uD83D\uDCDD"
            else -> "\uD83D\uDEE0\uFE0F"
        }
    }
}
