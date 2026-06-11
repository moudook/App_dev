package com.example.smarty.server.agent

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Stateful JSON bracket-counting buffer for streaming Tool arguments.
 * Correctly ignores brackets inside string literals.
 * Caps at 64KB and enforces a 30s stall timeout.
 */
class ToolCallAccumulator {
    private val logger = LoggerFactory.getLogger(ToolCallAccumulator::class.java)

    private val buffer = StringBuilder()
    private var braceCount = 0
    private var inString = false
    private var escapeNext = false

    private val maxSizeBytes = 64 * 1024 // 64 KB
    private val lastUpdateTime = AtomicLong(System.currentTimeMillis())

    fun append(chunk: String) {
        val now = System.currentTimeMillis()
        if (now - lastUpdateTime.get() > 30_000) {
            logger.warn("ToolCallAccumulator stall timeout exceeded (30s). Resetting.")
            reset()
        }
        lastUpdateTime.set(now)

        if (buffer.length + chunk.length > maxSizeBytes) {
            logger.warn("ToolCallAccumulator max size (64KB) exceeded. Ignoring further chunks.")
            return
        }

        for (char in chunk) {
            buffer.append(char)
            if (escapeNext) {
                escapeNext = false
                continue
            }
            when (char) {
                '\\' -> if (inString) escapeNext = true
                '"' -> inString = !inString
                '{' -> if (!inString) braceCount++
                '}' -> if (!inString) braceCount--
            }
        }
    }

    fun isComplete(): Boolean = buffer.isNotEmpty() && braceCount == 0 && !inString

    fun getAccumulatedContent(): String = buffer.toString()

    fun reset() {
        buffer.clear()
        braceCount = 0
        inString = false
        escapeNext = false
        lastUpdateTime.set(System.currentTimeMillis())
    }
}
