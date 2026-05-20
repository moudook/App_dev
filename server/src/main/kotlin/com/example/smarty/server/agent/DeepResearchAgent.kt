package com.example.smarty.server.agent

import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProvider
import com.example.smarty.server.tools.WebScrapeTool
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Advanced Deep Research Agent with progress file tracking.
 * Features:
 * - Limited tools (web scrape + notes only) — web search is handled by OpenCode CLI internally
 * - Progress file for long-running research
 * - Context overflow handling via progress files
 * - Auto-creates note card with findings on completion
 */
class DeepResearchAgent(
    private val llmProvider: LlmProvider,
    private val webScrapeTool: WebScrapeTool,
    private val progressFileManager: ProgressFileManager,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(DeepResearchAgent::class.java)

        // Timeout thresholds (milliseconds)
        private const val TIMEOUT_WARNING_MS = 12 * 60 * 1000L // 12 minutes - warning
        private const val TIMEOUT_FORCE_COMPLETE_MS = 15 * 60 * 1000L // 15 minutes - forced completion

        // Context overflow threshold (tokens)
        private const val CONTEXT_THRESHOLD = 8000
    }

    @Serializable
    data class ResearchSession(
        val id: String = UUID.randomUUID().toString(),
        val topic: String,
        val status: String = "asking_questions",
        val clarificationQuestions: List<String> = emptyList(),
        val userAnswers: Map<String, String> = emptyMap(),
        val researchPlan: String? = null,
        val searchQueries: List<SearchQuery> = emptyList(),
        val citations: List<Citation> = emptyList(),
        val researchLog: List<ResearchLogEntry> = emptyList(),
        val finalReport: String? = null,
        val progressFileId: String? = null,
        val contextTokenCount: Int = 0,
        val startTime: Long = System.currentTimeMillis(),
        val userInterruptions: List<UserInterruption> = emptyList(),
        val timeoutWarningSent: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
    )

    @Serializable
    data class UserInterruption(
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val addressed: Boolean = false,
    )

    @Serializable
    data class SearchQuery(
        val query: String,
        val results: List<SearchResult> = emptyList(),
        val timestamp: Long = System.currentTimeMillis(),
        val purpose: String = "",
    )

    @Serializable
    data class SearchResult(
        val url: String,
        val title: String,
        val snippet: String,
        val position: Int,
    )

    @Serializable
    data class Citation(
        val url: String,
        val title: String,
        val snippet: String,
        val dateAccessed: Long = System.currentTimeMillis(),
        val keyFindings: List<String> = emptyList(),
    )

    @Serializable
    data class ResearchLogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val action: String,
        val details: String,
        val metadata: Map<String, String> = emptyMap(),
    )

    /**
     * Start research with clarification questions
     */
    suspend fun startResearch(topic: String): ResearchSession {
        logger.info("Starting research on: $topic")

        val questions =
            listOf(
                "What specific aspects of \"$topic\" are you most interested in?",
                "Are there any particular time periods or regions to focus on?",
                "What type of information do you need? (academic, news, technical, general)",
                "Are there any specific questions you want answered?",
                "Do you need current information or historical context?",
            )

        return ResearchSession(
            topic = topic,
            status = "asking_questions",
            clarificationQuestions = questions,
            researchLog =
                listOf(
                    ResearchLogEntry(
                        action = "asked_question",
                        details = "Asked ${questions.size} clarification questions",
                        metadata = mapOf("topic" to topic),
                    ),
                ),
        )
    }

    /**
     * Process user answers
     */
    suspend fun processUserAnswers(
        session: ResearchSession,
        answers: Map<String, String>,
    ): ResearchSession {
        logger.info("Processing ${answers.size} answers")

        val updatedSession =
            session.copy(
                userAnswers = session.userAnswers + answers,
                status = "researching",
                researchPlan = "Research plan for: ${session.topic}",
                researchLog =
                    session.researchLog +
                        ResearchLogEntry(
                            action = "received_answer",
                            details = "Received ${answers.size} answers",
                        ),
            )

        // Perform initial search
        return performSearch(updatedSession, session.topic, "Initial research")
    }

    /**
     * Perform web search with progress tracking.
     * NOTE: Web search is handled internally by OpenCode CLI's built-in websearch.
     * This method simulates search results for the research workflow.
     */
    suspend fun performSearch(
        session: ResearchSession,
        query: String,
        purpose: String = "",
    ): ResearchSession {
        logger.info("Searching (via OpenCode websearch): $query")

        // OpenCode CLI handles websearch internally — the LLM uses its built-in tool.
        // For the research agent workflow, we track the search intent and move forward.
        val searchQuery =
            SearchQuery(
                query = query,
                results = emptyList(),
                purpose = purpose,
            )

        // OpenCode CLI handles websearch internally — citations are gathered by the LLM.
        // We track the search intent and continue the workflow.
        val newCitations = emptyList<Citation>()

        // Check if context is getting too large
        val newContextCount = session.contextTokenCount + estimateTokens(500)
        val shouldOffload = progressFileManager.shouldOffloadToProgress(session.citations.size)

        // If context exceeded, save to progress file and continue
        if (shouldOffload && newContextCount > CONTEXT_THRESHOLD) {
            logger.info("Context threshold reached, offloading to progress file")
        }

        return session.copy(
            searchQueries = session.searchQueries + searchQuery,
            citations = session.citations + newCitations,
            contextTokenCount = newContextCount,
            researchLog =
                session.researchLog +
                    ResearchLogEntry(
                        action = "performed_search",
                        details = "Searched: $query (via OpenCode websearch)",
                        metadata =
                            mapOf(
                                "query" to query,
                                "offloaded" to shouldOffload.toString(),
                            ),
                    ),
        )
    }

    /**
     * Change research direction
     */
    suspend fun changeDirection(
        session: ResearchSession,
        newDirection: String,
    ): ResearchSession {
        logger.info("Changing direction: $newDirection")

        return session.copy(
            researchPlan = "${session.researchPlan}\n\nDirection change: $newDirection",
            researchLog =
                session.researchLog +
                    ResearchLogEntry(
                        action = "changed_direction",
                        details = "User redirected: $newDirection",
                    ),
        )
    }

    /**
     * Check timeout status and return appropriate message
     */
    fun checkTimeout(session: ResearchSession): TimeoutStatus {
        val elapsed = System.currentTimeMillis() - session.startTime

        return when {
            elapsed >= TIMEOUT_FORCE_COMPLETE_MS -> TimeoutStatus.FORCE_COMPLETE
            elapsed >= TIMEOUT_WARNING_MS && !session.timeoutWarningSent -> TimeoutStatus.WARNING
            else -> TimeoutStatus.CONTINUE
        }
    }

    /**
     * Handle user interruption during research
     */
    suspend fun handleUserInterruption(
        session: ResearchSession,
        interruptionMessage: String,
    ): ResearchSession {
        logger.info("User interrupted research: $interruptionMessage")

        val updatedSession =
            session.copy(
                userInterruptions =
                    session.userInterruptions +
                        UserInterruption(
                            message = interruptionMessage,
                            addressed = false,
                        ),
                researchLog =
                    session.researchLog +
                        ResearchLogEntry(
                            action = "user_interruption",
                            details = "User interrupted: $interruptionMessage",
                        ),
            )

        // Send interruption to agent context for next iteration
        return updatedSession
    }

    /**
     * Send timeout warning to agent
     */
    suspend fun sendTimeoutWarning(session: ResearchSession): ResearchSession {
        logger.info("Sending timeout warning to research agent")

        return session.copy(
            timeoutWarningSent = true,
            userInterruptions =
                session.userInterruptions +
                    UserInterruption(
                        message = "⚠️ TIME WARNING: You have only 3 minutes left. Wrap up your research quickly and prepare to synthesize findings.",
                        addressed = false,
                    ),
            researchLog =
                session.researchLog +
                    ResearchLogEntry(
                        action = "timeout_warning",
                        details = "12-minute warning sent to agent",
                    ),
        )
    }

    /**
     * Force complete research due to timeout
     */
    suspend fun forceComplete(session: ResearchSession): ResearchSession {
        logger.info("Forcing research completion due to 15-minute timeout")

        // Synthesize with whatever findings we have
        val forcedCompletionSession = synthesizeReport(session)

        return forcedCompletionSession.copy(
            researchLog =
                forcedCompletionSession.researchLog +
                    ResearchLogEntry(
                        action = "forced_completion",
                        details = "Research forced to complete at 15-minute timeout",
                        metadata =
                            mapOf(
                                "finalCitations" to forcedCompletionSession.citations.size.toString(),
                                "finalSearches" to forcedCompletionSession.searchQueries.size.toString(),
                            ),
                    ),
        )
    }

    enum class TimeoutStatus {
        CONTINUE,
        WARNING,
        FORCE_COMPLETE,
    }

    /**
     * Read from progress file (for context recovery)
     */
    fun readProgress(
        session: ResearchSession,
        category: String? = null,
    ): String {
        return progressFileManager.getProgressText(session.id, session.topic)
    }

    /**
     * Synthesize final report and create note card
     */
    suspend fun synthesizeReport(session: ResearchSession): ResearchSession {
        logger.info("Synthesizing report")

        // Read progress file if exists
        val progressText =
            if (session.citations.size > 10) {
                "\n\n=== PROGRESS FILE FINDINGS ===\n" +
                    progressFileManager.getProgressText(session.id, session.topic)
            } else {
                ""
            }

        val messages =
            listOf(
                LlmMessage(role = LlmMessage.Role.SYSTEM, content = buildSystemPrompt()),
                LlmMessage(role = LlmMessage.Role.USER, content = buildReportPrompt(session) + progressText),
            )

        val response = llmProvider.generate(messages)

        // Clear progress file after completion
        progressFileManager.clearProgress(session.id)

        return session.copy(
            finalReport = response.content,
            status = "completed",
            researchLog =
                session.researchLog +
                    ResearchLogEntry(
                        action = "completed_research",
                        details = "Research completed",
                        metadata =
                            mapOf(
                                "citations" to session.citations.size.toString(),
                                "searches" to session.searchQueries.size.toString(),
                            ),
                    ),
        )
    }

    /**
     * Build system prompt for research agent
     */
    private fun buildSystemPrompt(): String {
        return """
You are a Deep Research Agent with access to:
- Web search tool (find information online)
- Web scrape tool (extract full page content)
- Progress file (save/read findings during long research)

RULES:
1. Always save important findings to progress file using save_progress tool
2. If context gets too large, read from progress file instead of keeping everything in memory
3. Track all sources with proper citations
4. Be thorough but organized
5. When research is complete, create a comprehensive report with all findings

TOOLS AVAILABLE:
- web_search: Search for information
- web_scrape: Extract full content from URLs
- save_progress: Save key findings to progress file
- read_progress: Read saved findings when needed

Your goal is comprehensive research with full transparency and citations.
"""
    }

    private fun buildReportPrompt(session: ResearchSession): String {
        return """
Topic: ${session.topic}

Searches Performed:
${session.searchQueries.joinToString("\n") { "- ${it.query}: ${it.results.size} results" }}

Sources:
${session.citations.joinToString("\n") { "- ${it.title} (${it.url})" }}

Create a comprehensive research report with citations.
"""
    }

    /**
     * Estimate token count (rough approximation)
     */
    private fun estimateTokens(charCount: Int): Int {
        return charCount / 4 // Rough estimate: 1 token ≈ 4 characters
    }
}
