package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.viewmodel.managers.StyleFeatureManager
import com.example.smarty.viewmodel.managers.StyleAnalysisReport
import com.example.smarty.data.model.Note
import kotlinx.serialization.Serializable

@Serializable
data class ReadAndAnalyzeStyleArgs(
    @property:LLMDescription("Number of recent notes to analyze for style (default: 10, max: 30)")
    val limit: Int = 10,
    @property:LLMDescription("Whether to include detailed analysis of writing patterns")
    val detailed: Boolean = false
)

@Serializable
data class StyleAnalysisResult(
    val success: Boolean,
    val message: String,
    val data: StyleAnalysisReport? = null
) {
    override fun toString(): String {
        return "{success:$success|message:$message|data:$data}"
    }
}

/**
 * Hybridized Style Analysis Tool.
 * 100% logic-free. Delegates to StyleFeatureManager via callback.
 */
class ReadAndAnalyzeStyleTool(
    private val onAnalyzeStyle: (Int) -> StyleAnalysisReport,
    private val onStatusUpdate: (String) -> Unit
) : Tool<ReadAndAnalyzeStyleArgs, StyleAnalysisResult>(
    argsSerializer = ReadAndAnalyzeStyleArgs.serializer(),
    resultSerializer = StyleAnalysisResult.serializer(),
    name = "read_and_analyze_style",
    description = """
        Reads and analyzes your recent notes to understand your writing style and patterns.
        Examines note structure, length, tone, and formatting preferences.
    """.trimIndent()
) {
    override suspend fun execute(args: ReadAndAnalyzeStyleArgs): StyleAnalysisResult {
        return try {
            onStatusUpdate("status_analyzing_style")
            val report = onAnalyzeStyle(args.limit.coerceIn(1, 30))
            StyleAnalysisResult(
                success = true,
                message = "style_analysis_success",
                data = report
            )
        } catch (e: Exception) {
            StyleAnalysisResult(false, "batch_error_failed|${e.message}")
        }
    }
}
