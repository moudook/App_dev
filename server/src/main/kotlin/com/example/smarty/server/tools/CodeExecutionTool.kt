package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlin.random.Random

@Serializable
data class CodeExecutionResult(
    val success: Boolean,
    val output: String,
    val executionTime: Long,
    val language: String,
    val error: String? = null,
    val warnings: List<String> = emptyList()
)

@Serializable
data class ExecutionMetrics(
    val totalExecutions: Long,
    val successRate: Double,
    val avgExecutionTime: Double,
    val peakMemoryUsage: Long,
    val languageBreakdown: Map<String, Int>,
    val errorPatterns: Map<String, Int>
)

@Serializable
data class CodeCache(
    val codeHash: String,
    val result: String,
    val language: String,
    val cachedAt: Long,
    val accessCount: Int,
    val avgExecutionTime: Long
)

@Serializable
data class SandboxConfig(
    val maxMemoryMB: Long = 256,
    val maxExecutionTimeSeconds: Int = 30,
    val maxOutputSize: Long = 10000,
    val allowNetwork: Boolean = false,
    val allowedImports: List<String> = emptyList(),
    val blockedPatterns: List<String> = emptyList()
)

@Serializable
data class LanguageProfile(
    val name: String,
    val version: String,
    val available: Boolean,
    val executionCount: Long = 0,
    val avgExecutionTime: Double = 0.0,
    val successRate: Double = 0.0
)

@Serializable
data class CodeAnalysis(
    val complexity: Int,
    val estimatedExecutionTime: Long,
    val memoryRequirement: Long,
    val riskLevel: String,
    val suggestions: List<String>,
    val detectedPatterns: List<String>
)

@Serializable
data class CodeTemplate(
    val id: String,
    val name: String,
    val language: String,
    val template: String,
    val description: String,
    val category: String,
    val usageCount: Int = 0
)

class CodeExecutionTool {
    private val logger = LoggerFactory.getLogger(CodeExecutionTool::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val executionHistory = ConcurrentHashMap<String, ConcurrentHashMap<String, Any>>()
    private val codeCache = ConcurrentHashMap<String, CodeCache>()
    private val languageProfiles = ConcurrentHashMap<String, LanguageProfile>()
    private val codeTemplates = ConcurrentHashMap<String, CodeTemplate>()
    private val executionQueue = ConcurrentHashMap<String, Long>()
    
    private val totalExecutions = AtomicLong(0)
    private val successfulExecutions = AtomicLong(0)
    private val totalExecutionTime = AtomicLong(0)
    
    private var sandboxConfig = SandboxConfig()
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    companion object {
        private const val CACHE_SIZE = 100
        private const val MAX_QUEUE_SIZE = 10
    }
    
    init {
        initializeLanguageProfiles()
        initializeCodeTemplates()
    }
    
    private fun initializeLanguageProfiles() {
        listOf(
            LanguageProfile("python", "3.x", false),
            LanguageProfile("kotlin", "1.9", false),
            LanguageProfile("javascript", "ES6", false),
            LanguageProfile("java", "17", false)
        ).forEach { profile ->
            languageProfiles[profile.name] = profile
        }
        
        checkLanguageAvailability()
    }
    
    private fun checkLanguageAvailability() {
        listOf("python3", "python", "kotlinc", "node", "java").forEach { cmd ->
            try {
                val process = ProcessBuilder(cmd, "--version")
                    .redirectErrorStream(true)
                    .start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
                process.waitFor(2, TimeUnit.SECONDS)
                
                val language = when {
                    cmd.contains("python") -> "python"
                    cmd.contains("kotlin") -> "kotlin"
                    cmd.contains("node") -> "javascript"
                    cmd.contains("java") -> "java"
                    else -> null
                }
                
                if (language != null) {
                    val version = output.lines().firstOrNull()?.substringAfter(" ")?.substringBefore(" ") ?: "unknown"
                    languageProfiles[language] = languageProfiles[language]?.copy(
                        available = true,
                        version = version
                    ) ?: LanguageProfile(language, version, true)
                }
            } catch (e: Exception) {
                logger.debug("$cmd not available: ${e.message}")
            }
        }
    }
    
    private fun initializeCodeTemplates() {
        listOf(
            CodeTemplate("py_data_process", "Data Processing", "python", 
                "import json\ndata = json.loads(input())\n# Process data here\nprint(json.dumps(result))",
                "Basic data processing template", "data"),
            CodeTemplate("py_api_call", "API Call", "python",
                "import requests\nresponse = requests.get('API_URL')\nprint(response.json())",
                "Template for making API calls", "network"),
            CodeTemplate("py_file_io", "File Operations", "python",
                "with open('file.txt', 'r') as f:\n    content = f.read()\n# Process content\nwith open('output.txt', 'w') as f:\n    f.write(result)",
                "Basic file I/O template", "io"),
            CodeTemplate("py_algorithm", "Algorithm", "python",
                "def solve(input_data):\n    # Implement algorithm\n    return result\n\nprint(solve(input()))",
                "Algorithm implementation template", "algorithm"),
            CodeTemplate("kt_basic", "Basic Kotlin", "kotlin",
                "fun main() {\n    val input = readLine()\n    // Process input\n    println(result)\n}",
                "Basic Kotlin script template", "general")
        ).forEach { template ->
            codeTemplates[template.id] = template
        }
    }
    
    fun executePython(code: String, useCache: Boolean = true): CodeExecutionResult {
        val startTime = System.currentTimeMillis()
        
        if (useCache) {
            val cacheKey = generateCacheKey(code, "python")
            val cached = codeCache[cacheKey]
            if (cached != null) {
                logger.info("Returning cached result for Python code")
                return CodeExecutionResult(
                    success = true,
                    output = cached.result,
                    executionTime = cached.avgExecutionTime,
                    language = "python"
                )
            }
        }
        
        val analysis = analyzeCode(code, "python")
        if (analysis.riskLevel == "high") {
            return CodeExecutionResult(
                success = false,
                output = "",
                executionTime = 0,
                language = "python",
                error = "Code blocked: High risk patterns detected",
                warnings = analysis.detectedPatterns
            )
        }
        
        return try {
            val tempFile = File.createTempFile("smarty_exec_", ".py")
            tempFile.writeText(sanitizeCode(code, "python"))
            tempFile.deleteOnExit()
            
            val pythonCmd = if (languageProfiles["python"]?.available == true) "python3" else "python"
            
            val processBuilder = ProcessBuilder(pythonCmd, "-u", tempFile.absolutePath)
                .redirectErrorStream(true)
            
            if (!sandboxConfig.allowNetwork) {
                processBuilder.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
            }
            
            val process = processBuilder.start()
            executionQueue[process.pid().toString()] = System.currentTimeMillis()
            
            val output = StringBuilder()
            val errorOutput = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val finished = process.waitFor(sandboxConfig.maxExecutionTimeSeconds.toLong(), TimeUnit.SECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                recordExecution("python", false, sandboxConfig.maxExecutionTimeSeconds * 1000L)
                return CodeExecutionResult(
                    success = false,
                    output = "",
                    executionTime = sandboxConfig.maxExecutionTimeSeconds * 1000L,
                    language = "python",
                    error = "Execution timed out after ${sandboxConfig.maxExecutionTimeSeconds} seconds"
                )
            }
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
                if (output.length > sandboxConfig.maxOutputSize) {
                    output.append("\n[...output truncated...]")
                    break
                }
            }
            
            while (errorReader.readLine().also { line = it } != null) {
                errorOutput.appendLine(line)
            }
            
            executionQueue.remove(process.pid().toString())
            
            val exitCode = process.exitValue()
            val executionTime = System.currentTimeMillis() - startTime
            val result = output.toString().trim()
            val error = errorOutput.toString().trim()
            
            val warnings = mutableListOf<String>()
            if (analysis.riskLevel == "medium") {
                warnings.addAll(analysis.detectedPatterns)
            }
            
            val success = exitCode == 0
            
            if (success) {
                if (useCache && executionTime > 500) {
                    val cacheKey = generateCacheKey(code, "python")
                    codeCache[cacheKey] = CodeCache(
                        codeHash = cacheKey,
                        result = result,
                        language = "python",
                        cachedAt = System.currentTimeMillis(),
                        accessCount = 1,
                        executionTime = executionTime
                    )
                    pruneCache()
                }
            }
            
            recordExecution("python", success, executionTime)
            
            if (success) {
                if (result.isEmpty()) {
                    CodeExecutionResult(
                        success = true,
                        output = "Execution completed successfully (no output)",
                        executionTime = executionTime,
                        language = "python",
                        warnings = warnings
                    )
                } else {
                    CodeExecutionResult(
                        success = true,
                        output = result,
                        executionTime = executionTime,
                        language = "python",
                        warnings = warnings
                    )
                }
            } else {
                CodeExecutionResult(
                    success = false,
                    output = result,
                    executionTime = executionTime,
                    language = "python",
                    error = if (error.isNotEmpty()) error else "Error (exit code $exitCode)",
                    warnings = warnings
                )
            }
        } catch (e: Exception) {
            logger.error("Python execution failed", e)
            val executionTime = System.currentTimeMillis() - startTime
            recordExecution("python", false, executionTime)
            
            val errorMessage = when {
                e.message?.contains("python3: not found") == true || 
                e.message?.contains("python: not found") == true -> 
                    "Error: Python is not installed on the server"
                e.message?.contains("Permission denied") == true -> 
                    "Error: Permission denied for code execution"
                else -> "Error executing code: ${e.message}"
            }
            
            CodeExecutionResult(
                success = false,
                output = "",
                executionTime = executionTime,
                language = "python",
                error = errorMessage
            )
        }
    }
    
    fun executeKotlin(code: String): CodeExecutionResult {
        val startTime = System.currentTimeMillis()
        
        if (languageProfiles["kotlin"]?.available != true) {
            return CodeExecutionResult(
                success = false,
                output = "",
                executionTime = 0,
                language = "kotlin",
                error = "Error: Kotlin is not available on this server"
            )
        }
        
        return try {
            val wrappedCode = wrapKotlinCode(code)
            val tempFile = File.createTempFile("smarty_exec_", ".kt")
            tempFile.writeText(wrappedCode)
            tempFile.deleteOnExit()
            
            val process = ProcessBuilder("kotlinc", "-script", tempFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            
            val output = StringBuilder()
            val errorOutput = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val finished = process.waitFor(sandboxConfig.maxExecutionTimeSeconds.toLong(), TimeUnit.SECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                recordExecution("kotlin", false, sandboxConfig.maxExecutionTimeSeconds * 1000L)
                return CodeExecutionResult(
                    success = false,
                    output = "",
                    executionTime = sandboxConfig.maxExecutionTimeSeconds * 1000L,
                    language = "kotlin",
                    error = "Execution timed out after ${sandboxConfig.maxExecutionTimeSeconds} seconds"
                )
            }
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
                if (output.length > sandboxConfig.maxOutputSize) {
                    output.append("\n[...output truncated...]")
                    break
                }
            }
            
            while (errorReader.readLine().also { line = it } != null) {
                errorOutput.appendLine(line)
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            val result = output.toString().trim()
            val error = errorOutput.toString().trim()
            val exitCode = process.exitValue()
            
            recordExecution("kotlin", exitCode == 0, executionTime)
            
            if (exitCode == 0) {
                CodeExecutionResult(
                    success = true,
                    output = result.ifEmpty { "Execution completed (no output)" },
                    executionTime = executionTime,
                    language = "kotlin"
                )
            } else {
                CodeExecutionResult(
                    success = false,
                    output = result,
                    executionTime = executionTime,
                    language = "kotlin",
                    error = if (error.isNotEmpty()) error else "Compilation/execution error"
                )
            }
        } catch (e: Exception) {
            logger.error("Kotlin execution failed", e)
            val executionTime = System.currentTimeMillis() - startTime
            recordExecution("kotlin", false, executionTime)
            
            CodeExecutionResult(
                success = false,
                output = "",
                executionTime = executionTime,
                language = "kotlin",
                error = "Error: Kotlin execution failed - ${e.message}"
            )
        }
    }
    
    private fun wrapKotlinCode(code: String): String {
        return if (code.contains("fun main(")) {
            code
        } else {
            """
            fun main() {
                $code
            }
            """.trimIndent()
        }
    }
    
    fun execute(code: String, language: String, useCache: Boolean = true): CodeExecutionResult {
        return when (language.lowercase()) {
            "python", "py" -> executePython(code, useCache)
            "kotlin", "kt" -> executeKotlin(code)
            "javascript", "js" -> executeJavascript(code)
            else -> CodeExecutionResult(
                success = false,
                output = "",
                executionTime = 0,
                language = language,
                error = "Error: Unsupported language '$language'. Supported: python, kotlin, javascript"
            )
        }
    }
    
    private fun executeJavascript(code: String): CodeExecutionResult {
        val startTime = System.currentTimeMillis()
        
        if (languageProfiles["javascript"]?.available != true) {
            return CodeExecutionResult(
                success = false,
                output = "",
                executionTime = 0,
                language = "javascript",
                error = "Error: JavaScript/Node.js is not available on this server"
            )
        }
        
        return try {
            val tempFile = File.createTempFile("smarty_exec_", ".js")
            tempFile.writeText(code)
            tempFile.deleteOnExit()
            
            val process = ProcessBuilder("node", tempFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            val finished = process.waitFor(sandboxConfig.maxExecutionTimeSeconds.toLong(), TimeUnit.SECONDS)
            
            if (!finished) {
                process.destroyForcibly()
                recordExecution("javascript", false, sandboxConfig.maxExecutionTimeSeconds * 1000L)
                return CodeExecutionResult(
                    success = false,
                    output = "",
                    executionTime = sandboxConfig.maxExecutionTimeSeconds * 1000L,
                    language = "javascript",
                    error = "Execution timed out"
                )
            }
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            val result = output.toString().trim()
            val exitCode = process.exitValue()
            
            recordExecution("javascript", exitCode == 0, executionTime)
            
            CodeExecutionResult(
                success = exitCode == 0,
                output = result,
                executionTime = executionTime,
                language = "javascript",
                error = if (exitCode != 0) "Execution error" else null
            )
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            recordExecution("javascript", false, executionTime)
            
            CodeExecutionResult(
                success = false,
                output = "",
                executionTime = executionTime,
                language = "javascript",
                error = "Error: ${e.message}"
            )
        }
    }
    
    private fun sanitizeCode(code: String, language: String): String {
        var sanitized = code
        
        if (language == "python") {
            val dangerousPatterns = listOf(
                "import os", "import sys", "import subprocess",
                "import socket", "import requests", "import urllib",
                "__import__", "eval(", "exec(",
                "open(", "file(", "compile("
            )
            
            if (!sandboxConfig.allowNetwork) {
                dangerousPatterns.filter { it.contains("import") }.forEach { pattern ->
                    sanitized = sanitized.replace(pattern, "# $pattern - blocked")
                }
            }
        }
        
        return sanitized
    }
    
    private fun analyzeCode(code: String, language: String): CodeAnalysis {
        val detectedPatterns = mutableListOf<String>()
        var riskLevel = "low"
        var complexity = 1
        
        val dangerousPatterns = mapOf(
            "python" to listOf("eval(", "exec(", "subprocess", "os.system", "__import__"),
            "kotlin" to listOf("Runtime.getRuntime()", "ProcessBuilder", "java.lang.Runtime"),
            "javascript" to listOf("child_process", "eval(", "Function(", "require(")
        )
        
        dangerousPatterns[language]?.forEach { pattern ->
            if (code.contains(pattern, ignoreCase = true)) {
                detectedPatterns.add("Potentially dangerous: $pattern")
                riskLevel = "high"
            }
        }
        
        val loops = Regex("""(for|while)\s*\(""").findAll(code).count()
        val conditionals = Regex("""if\s*\(""").findAll(code).count()
        val functions = Regex("""(def|fun|function)\s+\w+""").findAll(code).count()
        
        complexity = (loops * 3 + conditionals * 2 + functions * 1).coerceIn(1, 10)
        
        if (complexity > 7 || detectedPatterns.isNotEmpty()) {
            riskLevel = if (riskLevel == "high") "high" else "medium"
        }
        
        val estimatedTime = (complexity * 1000L * (1 + Random.nextDouble())).toLong()
        val memoryRequirement = (complexity * 50L + 100L)
        
        val suggestions = mutableListOf<String>()
        if (complexity > 7) {
            suggestions.add("Consider breaking down into smaller functions")
        }
        if (!code.contains("try", ignoreCase = true) && complexity > 3) {
            suggestions.add("Add error handling with try-catch blocks")
        }
        
        return CodeAnalysis(
            complexity = complexity,
            estimatedExecutionTime = estimatedTime,
            memoryRequirement = memoryRequirement,
            riskLevel = riskLevel,
            suggestions = suggestions,
            detectedPatterns = detectedPatterns
        )
    }
    
    fun analyzeCode(code: String, language: String): CodeAnalysis {
        return analyzeCode(code, language)
    }
    
    private fun generateCacheKey(code: String, language: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest("$language:$code".toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun pruneCache() {
        if (codeCache.size > CACHE_SIZE) {
            val sorted = codeCache.values.sortedBy { it.accessCount }
            repeat(codeCache.size - CACHE_SIZE + 10) {
                sorted.getOrNull(it)?.let { cache ->
                    codeCache.remove(cache.codeHash)
                }
            }
        }
    }
    
    private fun recordExecution(language: String, success: Boolean, executionTime: Long) {
        totalExecutions.incrementAndGet()
        if (success) {
            successfulExecutions.incrementAndGet()
        }
        totalExecutionTime.addAndGet(executionTime)
        
        val profile = languageProfiles[language]
        if (profile != null) {
            val newCount = profile.executionCount + 1
            val newAvgTime = (profile.avgExecutionTime * profile.executionCount + executionTime) / newCount
            val newSuccessRate = (profile.successRate * profile.executionCount + if (success) 1 else 0) / newCount
            
            languageProfiles[language] = profile.copy(
                executionCount = newCount,
                avgExecutionTime = newAvgTime,
                successRate = newSuccessRate
            )
        }
    }
    
    fun getMetrics(): ExecutionMetrics {
        val total = totalExecutions.get()
        val success = successfulExecutions.get()
        val avgTime = if (total > 0) totalExecutionTime.get().toDouble() / total else 0.0
        
        val breakdown = languageProfiles.mapValues { it.value.executionCount.toInt() }
        val errors = mutableMapOf<String, Int>()
        
        return ExecutionMetrics(
            totalExecutions = total,
            successRate = if (total > 0) success.toDouble() / total else 0.0,
            avgExecutionTime = avgTime,
            peakMemoryUsage = sandboxConfig.maxMemoryMB * 1024 * 1024,
            languageBreakdown = breakdown,
            errorPatterns = errors
        )
    }
    
    fun getAvailableLanguages(): List<LanguageProfile> {
        return languageProfiles.values.toList().sortedByDescending { it.executionCount }
    }
    
    fun getTemplate(language: String, category: String? = null): CodeTemplate? {
        return codeTemplates.values
            .filter { it.language == language }
            .let { templates ->
                if (category != null) templates.filter { it.category == category }
                else templates
            }
            .maxByOrNull { it.usageCount }
    }
    
    fun useTemplate(templateId: String): String? {
        return codeTemplates[templateId]?.let { template ->
            codeTemplates[templateId] = template.copy(usageCount = template.usageCount + 1)
            template.template
        }
    }
    
    fun updateSandboxConfig(config: SandboxConfig) {
        sandboxConfig = config
        logger.info("Sandbox config updated: maxMemory=${config.maxMemoryMB}MB, timeout=${config.maxExecutionTimeSeconds}s")
    }
    
    fun getCacheStats(): Map<String, Any> {
        val totalAccesses = codeCache.values.sumOf { it.accessCount }
        val avgCacheTime = if (codeCache.isNotEmpty()) {
            codeCache.values.map { System.currentTimeMillis() - it.cachedAt }.average()
        } else 0.0
        
        return mapOf(
            "cached_entries" to codeCache.size,
            "total_accesses" to totalAccesses,
            "avg_cache_age_seconds" to avgCacheTime / 1000.0,
            "hit_potential" to if (totalAccesses > 10) "high" else "low"
        )
    }
    
    fun clearCache() {
        codeCache.clear()
        logger.info("Code cache cleared")
    }
    
    fun formatMetrics(): String {
        val metrics = getMetrics()
        
        return buildString {
            appendLine("[Code Execution Metrics]")
            appendLine("=".repeat(40))
            appendLine("Total Executions: ${metrics.totalExecutions}")
            appendLine("Success%.1f".format(metrics.successRate * 100)}%")
            appendLine("Average Rate: ${" Execution Time: ${"%.0f".format(metrics.avgExecutionTime)}ms")
            appendLine("Peak Memory: ${metrics.peakMemoryUsage / 1024 / 1024}MB")
            
            appendLine("\n[Language Breakdown]")
            metrics.languageBreakdown.forEach { (lang, count) ->
                val profile = languageProfiles[lang]
                val success = profile?.successRate ?: 0.0
                appendLine("  $lang: $count runs, ${"%.1f".format(success * 100)}% success")
            }
            
            appendLine("\n[Cache Statistics]")
            val cacheStats = getCacheStats()
            appendLine("  Cached entries: ${cacheStats["cached_entries"]}")
            appendLine("  Total accesses: ${cacheStats["total_accesses"]}")
            appendLine("  Hit potential: ${cacheStats["hit_potential"]}")
        }
    }
}
