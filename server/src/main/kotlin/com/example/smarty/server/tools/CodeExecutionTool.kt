package com.example.smarty.server.tools

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class CodeExecutionTool {
    private val logger = LoggerFactory.getLogger(CodeExecutionTool::class.java)
    
    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val MAX_OUTPUT_LENGTH = 10000
    }
    
    fun executePython(code: String): String {
        return try {
            val tempFile = File.createTempFile("smarty_exec_", ".py")
            tempFile.writeText(code)
            tempFile.deleteOnExit()
            
            val process = ProcessBuilder("python3", "-u", tempFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                return "Error: Execution timed out after $TIMEOUT_SECONDS seconds"
            }
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
                if (output.length > MAX_OUTPUT_LENGTH) {
                    output.append("\n[...output truncated...]")
                    break
                }
            }
            
            val exitCode = process.exitValue()
            val result = output.toString().trim()
            
            if (exitCode == 0) {
                if (result.isEmpty()) "Execution completed successfully (no output)" else result
            } else {
                "Error (exit code $exitCode): $result"
            }
        } catch (e: Exception) {
            logger.error("Python execution failed", e)
            when {
                e.message?.contains("python3: not found") == true -> 
                    "Error: Python3 is not installed on the server"
                e.message?.contains("Permission denied") == true -> 
                    "Error: Permission denied for code execution"
                else -> "Error executing code: ${e.message}"
            }
        }
    }
    
    fun executeKotlin(code: String): String {
        return try {
            val tempFile = File.createTempFile("smarty_exec_", ".kt")
            tempFile.writeText("""
                fun main() {
                    $code
                }
            """.trimIndent())
            tempFile.deleteOnExit()
            
            val process = ProcessBuilder("kotlinc", "-script", tempFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                return "Error: Execution timed out after $TIMEOUT_SECONDS seconds"
            }
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
                if (output.length > MAX_OUTPUT_LENGTH) {
                    output.append("\n[...output truncated...]")
                    break
                }
            }
            
            output.toString().trim().ifEmpty { "Execution completed (no output)" }
        } catch (e: Exception) {
            "Error: Kotlin execution not available - ${e.message}"
        }
    }
    
    fun execute(code: String, language: String): String {
        return when (language.lowercase()) {
            "python", "py" -> executePython(code)
            "kotlin", "kt" -> executeKotlin(code)
            else -> "Error: Unsupported language '$language'. Supported: python"
        }
    }
}
