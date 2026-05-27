package com.example.smarty.server.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant

@Serializable
data class ProgressStep(
    val id: Int,
    val description: String,
    val status: String,
    val toolUsed: String? = null,
    val result: String? = null,
    val timestamp: String? = null,
)

@Serializable
data class ProgressData(
    val sessionId: String,
    val goal: String,
    val status: String,
    val steps: List<ProgressStep> = emptyList(),
    val currentStep: Int = 0,
    val toolCallsCount: Int = 0,
    val errors: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class KeyInsight(
    val content: String,
    val timestamp: String = Instant.now().toString(),
)

@Serializable
data class ImportantResult(
    val stepId: Int,
    val data: String,
    val timestamp: String = Instant.now().toString(),
)

@Serializable
data class MemoryData(
    val sessionId: String,
    val keyInsights: List<KeyInsight> = emptyList(),
    val gatheredData: Map<String, String> = emptyMap(),
    val patternsNoted: List<String> = emptyList(),
    val importantResults: List<ImportantResult> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
sealed class ToolExecutionResult {
    @Serializable
    data class Success(
        val toolName: String,
        val args: String,
        val result: String,
        val stepDescription: String,
    ) : ToolExecutionResult()

    @Serializable
    data class Error(
        val toolName: String,
        val args: String,
        val error: String,
        val stepDescription: String,
    ) : ToolExecutionResult()
}

class GoalMemoryManager(
    private val sessionId: String,
    private val goal: String,
) {
    private val logger = LoggerFactory.getLogger(GoalMemoryManager::class.java)
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    private var progressData =
        ProgressData(
            sessionId = sessionId,
            goal = goal,
            status = "in_progress",
            steps =
                listOf(
                    ProgressStep(
                        id = 1,
                        description = "Understanding the goal",
                        status = "in_progress",
                        toolUsed = null,
                        result = null,
                        timestamp = Instant.now().toString(),
                    ),
                ),
            currentStep = 1,
            toolCallsCount = 0,
            errors = emptyList(),
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString(),
        )

    private var memoryData =
        MemoryData(
            sessionId = sessionId,
            keyInsights = emptyList(),
            gatheredData = emptyMap(),
            patternsNoted = emptyList(),
            importantResults = emptyList(),
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString(),
        )

    fun initializeWithGoal() {
        logger.info("Initializing GoalMemoryManager for session $sessionId with goal: $goal")
    }

    fun markStepCompleted(
        description: String,
        toolUsed: String?,
        result: String?,
    ) {
        val now = Instant.now().toString()
        val completedSteps =
            progressData.steps
                .map { step ->
                    if (step.status == "in_progress") {
                        step.copy(
                            status = "completed",
                            toolUsed = toolUsed,
                            result = result?.take(500),
                            timestamp = now,
                        )
                    } else {
                        step
                    }
                }.toMutableList()

        progressData =
            progressData.copy(
                steps = completedSteps,
                currentStep = progressData.currentStep + 1,
                toolCallsCount = progressData.toolCallsCount + 1,
                updatedAt = now,
            )

        logger.info("Step completed: $description, tool: $toolUsed")
    }

    fun addStep(
        description: String,
        toolUsed: String? = null,
        result: String? = null,
        status: String = "in_progress",
    ) {
        val now = Instant.now().toString()
        val newStep =
            ProgressStep(
                id = progressData.currentStep,
                description = description,
                status = status,
                toolUsed = toolUsed,
                result = result?.take(500),
                timestamp = if (status == "completed") now else null,
            )

        progressData =
            progressData.copy(
                steps = progressData.steps + newStep,
                currentStep = progressData.currentStep + 1,
                toolCallsCount = if (status == "completed") progressData.toolCallsCount + 1 else progressData.toolCallsCount,
                updatedAt = now,
            )
    }

    fun addError(error: String) {
        val now = Instant.now().toString()
        progressData =
            progressData.copy(
                errors = progressData.errors + error,
                updatedAt = now,
            )
        logger.warn("Error added to progress: $error")
    }

    fun markCompleted() {
        val now = Instant.now().toString()
        progressData =
            progressData.copy(
                status = "completed",
                updatedAt = now,
            )
    }

    fun markFailed(reason: String) {
        val now = Instant.now().toString()
        progressData =
            progressData.copy(
                status = "failed",
                errors = progressData.errors + reason,
                updatedAt = now,
            )
    }

    fun markWaiting(reason: String) {
        val now = Instant.now().toString()
        progressData =
            progressData.copy(
                status = "waiting",
                updatedAt = now,
            )
    }

    fun addKeyInsight(insight: String) {
        val now = Instant.now().toString()
        memoryData =
            memoryData.copy(
                keyInsights = memoryData.keyInsights + KeyInsight(insight, now),
                updatedAt = now,
            )
    }

    fun addGatheredData(
        key: String,
        value: String,
    ) {
        val now = Instant.now().toString()
        memoryData =
            memoryData.copy(
                gatheredData = memoryData.gatheredData + (key to value),
                updatedAt = now,
            )
    }

    fun addPattern(pattern: String) {
        val now = Instant.now().toString()
        memoryData =
            memoryData.copy(
                patternsNoted = memoryData.patternsNoted + pattern,
                updatedAt = now,
            )
    }

    fun addImportantResult(
        stepId: Int,
        data: String,
    ) {
        val now = Instant.now().toString()
        memoryData =
            memoryData.copy(
                importantResults = memoryData.importantResults + ImportantResult(stepId, data, now),
                updatedAt = now,
            )
    }

    fun getProgressContext(): String {
        val completedSteps = progressData.steps.filter { it.status == "completed" }
        val pendingSteps = progressData.steps.filter { it.status == "in_progress" || it.status == "pending" }

        val completedStr =
            if (completedSteps.isNotEmpty()) {
                completedSteps.joinToString(" ") { "[✓] ${it.description}" }
            } else {
                "None yet"
            }

        val pendingStr =
            if (pendingSteps.isNotEmpty()) {
                pendingSteps.joinToString(" ") { "[ ] ${it.description}" }
            } else {
                "None"
            }

        val errorsStr =
            if (progressData.errors.isNotEmpty()) {
                progressData.errors.joinToString("; ")
            } else {
                "None"
            }

        val insightsStr =
            if (memoryData.keyInsights.isNotEmpty()) {
                memoryData.keyInsights.take(3).joinToString("; ") { it.content }
            } else {
                "None"
            }

        return """
            CURRENT PROGRESS:
            - Goal: ${progressData.goal}
            - Status: ${progressData.status}
            - Completed: $completedStr
            - Pending: $pendingStr
            - Tool calls: ${progressData.toolCallsCount}
            - Errors: $errorsStr
            - Key insights: $insightsStr

            Use this progress to understand what's done and what's next.
            """.trimIndent()
    }

    fun getProgressData(): ProgressData = progressData

    fun getMemoryData(): MemoryData = memoryData

    fun getProgressJson(): String = json.encodeToString(progressData)

    fun getMemoryJson(): String = json.encodeToString(memoryData)

    fun toCheckpoint(): GoalMemoryCheckpoint =
        GoalMemoryCheckpoint(
            progress = progressData,
            memory = memoryData,
        )

    fun loadFromCheckpoint(checkpoint: GoalMemoryCheckpoint) {
        progressData = checkpoint.progress
        memoryData = checkpoint.memory
    }
}

@Serializable
data class GoalMemoryCheckpoint(
    val progress: ProgressData,
    val memory: MemoryData,
)
