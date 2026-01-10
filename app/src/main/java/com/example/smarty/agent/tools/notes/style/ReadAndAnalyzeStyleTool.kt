package com.example.smarty.agent.tools.notes.style

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.model.Note
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.toon.ToonManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = false }

@Serializable
data class ReadAndAnalyzeStyleArgs(
    @property:LLMDescription("Number of recent notes to analyze for style (default: 10, max: 30)")
    val limit: Int = 10,
    @property:LLMDescription("Whether to include detailed analysis of writing patterns")
    val detailed: Boolean = false
)

@Serializable
data class WritingStylePattern(
    val patternType: String,  // e.g., "structure", "tone", "length", "format"
    val description: String,
    val examples: List<String> = emptyList(),
    val frequency: Float = 1.0f  // 0.0 to 1.0 scale
)

@Serializable
data class StyleAnalysisResult(
    val success: Boolean,
    val message: String,
    val totalNotesAnalyzed: Int,
    val writingPatterns: List<WritingStylePattern>,
    val summary: String
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Tool for reading user's notes and analyzing their writing style/patterns.
 * This tool examines recent notes to understand the user's writing preferences,
 * structure, tone, and other stylistic elements.
 *
 * USE THIS TOOL when user asks for:
 * - "Read my notes and learn my style"
 * - "Analyze my writing patterns"
 * - "Tell me about my note-taking habits"
 * - "What's my style of writing?"
 */
class ReadAndAnalyzeStyleTool(
    private val getActiveNotes: () -> List<Note>
) : Tool<ReadAndAnalyzeStyleArgs, StyleAnalysisResult>(
    argsSerializer = ReadAndAnalyzeStyleArgs.serializer(),
    resultSerializer = StyleAnalysisResult.serializer(),
    name = "read_and_analyze_style",
    description = """
        Reads and analyzes your recent notes to understand your writing style and patterns.
        Triggers: "Read my notes and learn my style", "Analyze my writing patterns", "What's my style of writing?".
        This tool examines note structure, length, tone, and formatting preferences.
    """.trimIndent()
) {
    override suspend fun execute(args: ReadAndAnalyzeStyleArgs): StyleAnalysisResult {
        return try {
            val allNotes = getActiveNotes()
            val visibleNotes = PrivacyGuard.getAiVisibleNotes(allNotes)

            if (visibleNotes.isEmpty()) {
                return StyleAnalysisResult(
                    success = false,
                    message = "No AI-visible notes found to analyze.",
                    totalNotesAnalyzed = 0,
                    writingPatterns = emptyList(),
                    summary = "No notes available for style analysis."
                )
            }

            val safeLimit = args.limit.coerceIn(1, 30)
            val notesToAnalyze = visibleNotes
                .sortedByDescending { it.updatedAt }
                .take(safeLimit)

            val patterns = analyzeWritingStyle(notesToAnalyze, args.detailed)

            val summary = buildSummary(notesToAnalyze, patterns)

            StyleAnalysisResult(
                success = true,
                message = "Successfully analyzed ${notesToAnalyze.size} notes for writing style patterns.",
                totalNotesAnalyzed = notesToAnalyze.size,
                writingPatterns = patterns,
                summary = summary
            )
        } catch (e: Exception) {
            StyleAnalysisResult(
                success = false,
                message = "Error during style analysis: ${e.message}",
                totalNotesAnalyzed = 0,
                writingPatterns = emptyList(),
                summary = "An error occurred during analysis."
            )
        }
    }

    private fun analyzeWritingStyle(notes: List<Note>, detailed: Boolean): List<WritingStylePattern> {
        val patterns = mutableListOf<WritingStylePattern>()

        // Analyze structure patterns
        val bulletPointRatio = calculateBulletPointRatio(notes)
        if (bulletPointRatio > 0.3) {
            patterns.add(
                WritingStylePattern(
                    patternType = "structure",
                    description = "Prefers bullet-point lists and structured format",
                    examples = if (detailed) getBulletPointExamples(notes) else emptyList(),
                    frequency = bulletPointRatio.toFloat()
                )
            )
        }

        // Analyze length patterns
        val avgLength = notes.mapNotNull { it.content?.length }.average()
        val lengthDescription = when {
            avgLength < 100 -> "writes short, concise notes"
            avgLength < 500 -> "writes moderate-length notes"
            else -> "writes detailed, comprehensive notes"
        }
        patterns.add(
            WritingStylePattern(
                patternType = "length",
                description = lengthDescription,
                frequency = (avgLength / 1000).toFloat().coerceIn(0f, 1f)
            )
        )

        // Analyze title patterns
        val titleLengthAvg = notes.map { it.title.length }.average()
        if (titleLengthAvg < 15) {
            patterns.add(
                WritingStylePattern(
                    patternType = "title",
                    description = "uses short, concise titles",
                    frequency = (titleLengthAvg / 50).toFloat().coerceIn(0f, 1f)
                )
            )
        } else {
            patterns.add(
                WritingStylePattern(
                    patternType = "title",
                    description = "uses descriptive, longer titles",
                    frequency = (titleLengthAvg / 50).toFloat().coerceIn(0f, 1f)
                )
            )
        }

        // Analyze content patterns
        val paragraphRatio = calculateParagraphRatio(notes)
        if (paragraphRatio > 0.5) {
            patterns.add(
                WritingStylePattern(
                    patternType = "structure",
                    description = "prefers paragraph-style writing",
                    frequency = paragraphRatio.toFloat()
                )
            )
        }

        // Analyze punctuation patterns
        val exclamationRatio = calculateExclamationRatio(notes)
        if (exclamationRatio > 0.1) {
            patterns.add(
                WritingStylePattern(
                    patternType = "tone",
                    description = "uses enthusiastic tone with frequent exclamation marks",
                    frequency = exclamationRatio.toFloat()
                )
            )
        }

        // Analyze question patterns
        val questionRatio = calculateQuestionRatio(notes)
        if (questionRatio > 0.1) {
            patterns.add(
                WritingStylePattern(
                    patternType = "tone",
                    description = "asks many questions in notes",
                    frequency = questionRatio.toFloat()
                )
            )
        }

        return patterns
    }

    private fun calculateBulletPointRatio(notes: List<Note>): Double {
        return notes.count { note ->
            note.content?.contains(Regex("^\\s*[-•*]\\s|^\\s*\\d+\\.\\s", RegexOption.MULTILINE)) == true
        }.toDouble() / notes.size
    }

    private fun getBulletPointExamples(notes: List<Note>): List<String> {
        return notes.filter { note ->
            note.content?.contains(Regex("^\\s*[-•*]\\s", RegexOption.MULTILINE)) == true
        }.take(2).map { it.title }.ifEmpty { emptyList() }
    }

    private fun calculateParagraphRatio(notes: List<Note>): Double {
        return notes.count { note ->
            note.content?.contains(Regex("\n\n")) == true
        }.toDouble() / notes.size
    }

    private fun calculateExclamationRatio(notes: List<Note>): Double {
        return notes.count { note ->
            note.content?.contains('!') == true
        }.toDouble() / notes.size
    }

    private fun calculateQuestionRatio(notes: List<Note>): Double {
        return notes.count { note ->
            note.content?.contains('?') == true
        }.toDouble() / notes.size
    }

    private fun buildSummary(notes: List<Note>, patterns: List<WritingStylePattern>): String {
        val sb = StringBuilder()
        sb.append("Style analysis of ${notes.size} recent notes:\n\n")

        val structurePatterns = patterns.filter { it.patternType == "structure" }
        if (structurePatterns.isNotEmpty()) {
            sb.append("Structure preferences: ")
            sb.append(structurePatterns.joinToString(", ") { it.description })
            sb.append("\n")
        }

        val lengthPatterns = patterns.filter { it.patternType == "length" }
        if (lengthPatterns.isNotEmpty()) {
            sb.append("Length preferences: ")
            sb.append(lengthPatterns.joinToString(", ") { it.description })
            sb.append("\n")
        }

        val tonePatterns = patterns.filter { it.patternType == "tone" }
        if (tonePatterns.isNotEmpty()) {
            sb.append("Tone characteristics: ")
            sb.append(tonePatterns.joinToString(", ") { it.description })
            sb.append("\n")
        }

        if (sb.lastOrNull() == '\n') sb.setLength(sb.length - 1) // Remove trailing newline

        return sb.toString()
    }
}