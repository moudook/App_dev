package com.example.smarty.viewmodel.managers

import android.util.Log
import com.example.smarty.data.model.Note
import com.example.smarty.util.PrivacyGuard
import kotlinx.serialization.Serializable

@Serializable
data class WritingStylePattern(
    val patternType: String,  // e.g., "structure", "tone", "length", "format"
    val description: String,
    val frequency: Float = 1.0f  // 0.0 to 1.0 scale
)

@Serializable
data class StyleAnalysisReport(
    val totalNotesAnalyzed: Int,
    val writingPatterns: List<WritingStylePattern>,
    val summary: String
)

/**
 * Manages stylistic analysis of user content.
 * Hybridizes logic for:
 * - Writing pattern recognition
 * - Tone analysis
 * - Structural preference detection
 *
 * This allows the AI and the system to adapt to the user's preferred "voice".
 */
class StyleFeatureManager {
    companion object {
        private const val TAG = "StyleFeatureManager"
    }

    /**
     * Analyze a set of notes to determine writing patterns.
     */
    fun analyzeStyle(notes: List<Note>, limit: Int = 10): StyleAnalysisReport {
        val visibleNotes = PrivacyGuard.getAiVisibleNotes(notes)
        if (visibleNotes.isEmpty()) {
            return StyleAnalysisReport(0, emptyList(), "No notes available for analysis.")
        }

        val notesToAnalyze = visibleNotes
            .sortedByDescending { it.updatedAt }
            .take(limit)

        val patterns = mutableListOf<WritingStylePattern>()

        // 1. Analyze Structure
        val bulletPointRatio = calculateRatio(notesToAnalyze) {
            it.content?.contains(Regex("^\\s*[-•*]\\s|^\\s*\\d+\\.\\s", RegexOption.MULTILINE)) == true
        }
        if (bulletPointRatio > 0.3) {
            patterns.add(WritingStylePattern("structure", "Prefers bullet-point lists", bulletPointRatio.toFloat()))
        }

        val paragraphRatio = calculateRatio(notesToAnalyze) { it.content?.contains(Regex("\n\n")) == true }
        if (paragraphRatio > 0.5) {
            patterns.add(WritingStylePattern("structure", "Prefers paragraph-style writing", paragraphRatio.toFloat()))
        }

        // 2. Analyze Length
        val avgLength = notesToAnalyze.mapNotNull { it.content?.length }.average()
        val lengthDesc = when {
            avgLength < 150 -> "Writes short, concise notes"
            avgLength < 600 -> "Writes moderate-length notes"
            else -> "Writes detailed, comprehensive notes"
        }
        patterns.add(WritingStylePattern("length", lengthDesc, (avgLength / 1200).toFloat().coerceIn(0f, 1f)))

        // 3. Analyze Tone
        val exclamationRatio = calculateRatio(notesToAnalyze) { it.content?.contains('!') == true }
        if (exclamationRatio > 0.1) {
            patterns.add(WritingStylePattern("tone", "Enthusiastic tone with frequent exclamations", exclamationRatio.toFloat()))
        }

        val questionRatio = calculateRatio(notesToAnalyze) { it.content?.contains('?') == true }
        if (questionRatio > 0.1) {
            patterns.add(WritingStylePattern("tone", "Inquisitive; asks many questions", questionRatio.toFloat()))
        }

        val summary = buildSummary(notesToAnalyze.size, patterns)

        return StyleAnalysisReport(
            totalNotesAnalyzed = notesToAnalyze.size,
            writingPatterns = patterns,
            summary = summary
        )
    }

    private fun calculateRatio(notes: List<Note>, predicate: (Note) -> Boolean): Double {
        if (notes.isEmpty()) return 0.0
        return notes.count(predicate).toDouble() / notes.size
    }

    private fun buildSummary(count: Int, patterns: List<WritingStylePattern>): String {
        val sb = StringBuilder("Analyzed $count recent notes. ")
        if (patterns.isEmpty()) return sb.append("No distinct patterns found.").toString()

        sb.append("Key patterns: ")
        sb.append(patterns.joinToString("; ") { it.description.lowercase() })
        return sb.toString()
    }
}
