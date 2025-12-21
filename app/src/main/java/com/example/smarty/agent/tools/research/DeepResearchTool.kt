package com.example.smarty.agent.tools.research

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus
import com.example.smarty.data.remote.providers.TavilySearchProvider
import com.example.smarty.data.repository.CogniRepository
import com.example.smarty.util.toon.ToonManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

private val json = Json { encodeDefaults = false }

@Serializable
data class DeepResearchArgs(
    @property:LLMDescription("The main topic to research")
    val topic: String,
    @property:LLMDescription("Specific aspects or questions to focus on (optional)")
    val focusAreas: List<String> = emptyList(),
    @property:LLMDescription("Number of search queries to run: 1-5 (default 3)")
    val searchDepth: Int = 3,
    @property:LLMDescription("Whether to automatically save findings as a note (default true)")
    val saveAsNote: Boolean = true,
    @property:LLMDescription("Category for the research note (default 'Research')")
    val category: String = "Research"
)

@Serializable
data class ResearchFinding(
    val query: String,
    val summary: String,
    val sources: List<String>
)

@Serializable
data class DeepResearchResult(
    val success: Boolean,
    val topic: String,
    val findings: List<ResearchFinding>,
    val synthesis: String,
    val noteId: String? = null,
    val noteTitle: String? = null,
    val queriesExecuted: Int,
    val sourcesFound: Int,
    val message: String,
    val error: String? = null
) {
    override fun toString(): String {
        val jsonStr = json.encodeToString(serializer(), this)
        return ToonManager.jsonToToon(jsonStr)
    }
}

/**
 * Advanced research tool that performs multi-step web research.
 *
 * Features:
 * - Generates multiple search queries from topic and focus areas
 * - Gathers information from multiple angles
 * - Synthesizes findings into structured summary
 * - Optionally saves research as a note with sources
 */
class DeepResearchTool(
    private val tavilySearchProvider: TavilySearchProvider,
    private val repository: CogniRepository,
    private val getApiKey: () -> String?
) : Tool<DeepResearchArgs, DeepResearchResult>() {

    override val argsSerializer: KSerializer<DeepResearchArgs> = DeepResearchArgs.serializer()
    override val resultSerializer: KSerializer<DeepResearchResult> = DeepResearchResult.serializer()

    override val name = "deep_research"

    override val description = """
        Performs comprehensive multi-step research on a topic.
        Automatically generates multiple search queries, gathers information from various angles,
        synthesizes findings, and optionally saves the research as a structured note.
        Use for: "research X", "find out about Y", "investigate Z", "learn everything about W".
        Returns findings with sources and can save directly to notes.
    """.trimIndent()

    override suspend fun execute(args: DeepResearchArgs): DeepResearchResult {
        val apiKey = getApiKey() ?: return DeepResearchResult(
            success = false,
            topic = args.topic,
            findings = emptyList(),
            synthesis = "",
            queriesExecuted = 0,
            sourcesFound = 0,
            message = "Web search is not configured. Please add a Tavily API key in settings.",
            error = "No Tavily API key configured"
        )

        try {
            val findings = mutableListOf<ResearchFinding>()
            val allSources = mutableSetOf<String>()

            // Generate search queries based on topic and focus areas
            val searchDepth = args.searchDepth.coerceIn(1, 5)
            val queries = generateQueries(args.topic, args.focusAreas, searchDepth)

            // Execute each search
            for (query in queries) {
                try {
                    val result = tavilySearchProvider.search(
                        apiKey = apiKey,
                        query = query,
                        maxResults = 3,
                        topic = "research"
                    )

                    if (result.success && result.results.isNotEmpty()) {
                        val sources = result.results.map { it.url }
                        allSources.addAll(sources)

                        findings.add(ResearchFinding(
                            query = query,
                            summary = result.answer
                                ?: result.results.firstOrNull()?.snippet
                                ?: "No summary available",
                            sources = sources
                        ))
                    }
                } catch (e: Exception) {
                    // Continue with other queries if one fails
                }
            }

            if (findings.isEmpty()) {
                return DeepResearchResult(
                    success = false,
                    topic = args.topic,
                    findings = emptyList(),
                    synthesis = "",
                    queriesExecuted = queries.size,
                    sourcesFound = 0,
                    message = "Could not find relevant information about '${args.topic}'",
                    error = "No search results returned"
                )
            }

            // Synthesize findings
            val synthesis = synthesizeFindings(args.topic, findings)

            // Save as note if requested
            var noteId: String? = null
            var noteTitle: String? = null
            if (args.saveAsNote) {
                val (id, title) = saveResearchNote(args.topic, findings, synthesis, args.category, allSources.toList())
                noteId = id
                noteTitle = title
            }

            return DeepResearchResult(
                success = true,
                topic = args.topic,
                findings = findings,
                synthesis = synthesis,
                noteId = noteId,
                noteTitle = noteTitle,
                queriesExecuted = queries.size,
                sourcesFound = allSources.size,
                message = buildString {
                    append("Research completed! Found ${allSources.size} sources across ${queries.size} searches.")
                    if (noteId != null) {
                        append(" Saved to note: \"$noteTitle\"")
                    }
                }
            )
        } catch (e: Exception) {
            return DeepResearchResult(
                success = false,
                topic = args.topic,
                findings = emptyList(),
                synthesis = "",
                queriesExecuted = 0,
                sourcesFound = 0,
                message = "Research failed: ${e.message}",
                error = e.message
            )
        }
    }

    /**
     * Generate varied search queries from topic and focus areas.
     */
    private fun generateQueries(topic: String, focusAreas: List<String>, depth: Int): List<String> {
        val queries = mutableListOf<String>()

        // Primary topic query
        queries.add(topic)

        // Add focus area queries
        focusAreas.take(depth - 1).forEach { area ->
            queries.add("$topic $area")
        }

        // Add variations if still under depth
        if (queries.size < depth) {
            queries.add("$topic latest developments 2024 2025")
        }
        if (queries.size < depth) {
            queries.add("$topic comprehensive guide overview")
        }
        if (queries.size < depth) {
            queries.add("$topic best practices recommendations")
        }

        return queries.take(depth)
    }

    /**
     * Create a structured synthesis of all findings.
     */
    private fun synthesizeFindings(topic: String, findings: List<ResearchFinding>): String {
        if (findings.isEmpty()) return "No findings to synthesize."

        return buildString {
            appendLine("## Research Summary: $topic")
            appendLine()

            findings.forEachIndexed { index, finding ->
                appendLine("### ${index + 1}. ${finding.query}")
                appendLine(finding.summary)
                appendLine()
            }
        }
    }

    /**
     * Save research findings as a structured note.
     */
    private suspend fun saveResearchNote(
        topic: String,
        findings: List<ResearchFinding>,
        synthesis: String,
        categoryName: String,
        sources: List<String>
    ): Pair<String, String> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val timestamp = dateFormat.format(Date())

        val noteTitle = "Research: $topic"
        val content = buildString {
            appendLine("# Research: $topic")
            appendLine()
            appendLine("*Researched on $timestamp*")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine(synthesis)
            appendLine("---")
            appendLine()
            appendLine("## Sources")
            appendLine()
            sources.distinct().take(15).forEach { source ->
                appendLine("- $source")
            }
            appendLine()
            appendLine("---")
            appendLine("*Generated by Cogni Deep Research*")
        }

        val note = Note(
            title = noteTitle,
            content = content,
            summary = "Research findings about $topic with ${sources.size} sources",
            type = NoteType.DOCUMENT,  // Use DOCUMENT for research notes
            isAiCreated = true,
            processingStatus = ProcessingStatus.PROCESSING
        )

        repository.insertNote(note)

        // Set category
        try {
            val category = repository.getOrCreateCategory(categoryName)
            repository.updateNote(note.copy(
                categoryId = category.id,
                categoryName = category.name,
                processingStatus = ProcessingStatus.COMPLETED
            ))
        } catch (e: Exception) {
            // Update without category if it fails
            repository.updateNote(note.copy(
                processingStatus = ProcessingStatus.COMPLETED
            ))
        }

        return Pair(note.id, noteTitle)
    }

    override fun toString(): String {
        return "DeepResearchTool - Multi-step web research with auto-save"
    }
}
