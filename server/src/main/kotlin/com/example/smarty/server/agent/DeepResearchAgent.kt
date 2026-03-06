package com.example.smarty.server.agent

import com.example.smarty.server.llm.LlmProvider
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.tools.WebSearchTool
import com.example.smarty.server.tools.WebScrapeTool
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Deep Research Agent - Simplified working version.
 * Performs web research with citations and transparency.
 */
class DeepResearchAgent(
    private val llmProvider: LlmProvider,
    private val webSearchTool: WebSearchTool,
    private val webScrapeTool: WebScrapeTool
) {
    companion object {
        private val logger = LoggerFactory.getLogger(DeepResearchAgent::class.java)
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
        val createdAt: Long = System.currentTimeMillis()
    )
    
    @Serializable
    data class SearchQuery(
        val query: String,
        val results: List<SearchResult> = emptyList(),
        val timestamp: Long = System.currentTimeMillis(),
        val purpose: String = ""
    )
    
    @Serializable
    data class SearchResult(
        val url: String,
        val title: String,
        val snippet: String,
        val position: Int
    )
    
    @Serializable
    data class Citation(
        val url: String,
        val title: String,
        val snippet: String,
        val dateAccessed: Long = System.currentTimeMillis(),
        val keyFindings: List<String> = emptyList()
    )
    
    @Serializable
    data class ResearchLogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val action: String,
        val details: String,
        val metadata: Map<String, String> = emptyMap()
    )
    
    /**
     * Start research with clarification questions
     */
    suspend fun startResearch(topic: String): ResearchSession {
        logger.info("Starting research on: $topic")
        
        val questions = listOf(
            "What specific aspects of \"$topic\" are you most interested in?",
            "Are there any particular time periods or regions to focus on?",
            "What type of information do you need? (academic, news, technical, general)",
            "Are there any specific questions you want answered?",
            "Do you need current information or historical context?"
        )
        
        return ResearchSession(
            topic = topic,
            status = "asking_questions",
            clarificationQuestions = questions,
            researchLog = listOf(
                ResearchLogEntry(
                    action = "asked_question",
                    details = "Asked ${questions.size} clarification questions",
                    metadata = mapOf("topic" to topic)
                )
            )
        )
    }
    
    /**
     * Process user answers
     */
    suspend fun processUserAnswers(
        session: ResearchSession,
        answers: Map<String, String>
    ): ResearchSession {
        logger.info("Processing ${answers.size} answers")
        
        val updatedSession = session.copy(
            userAnswers = session.userAnswers + answers,
            status = "researching",
            researchPlan = "Research plan for: ${session.topic}",
            researchLog = session.researchLog + ResearchLogEntry(
                action = "received_answer",
                details = "Received ${answers.size} answers"
            )
        )
        
        // Perform initial search
        return performSearch(updatedSession, session.topic, "Initial research")
    }
    
    /**
     * Perform web search
     */
    suspend fun performSearch(
        session: ResearchSession,
        query: String,
        purpose: String = ""
    ): ResearchSession {
        logger.info("Searching: $query")
        
        val searchResults = webSearchTool.search(query, 10)
        
        val searchQuery = SearchQuery(
            query = query,
            results = searchResults.map { result ->
                SearchResult(
                    url = result.link,
                    title = result.title,
                    snippet = result.snippet,
                    position = result.position
                )
            },
            purpose = purpose
        )
        
        // Add citations from top results
        val newCitations = searchResults.take(5).map { result ->
            Citation(
                url = result.link,
                title = result.title,
                snippet = result.snippet,
                keyFindings = listOf(result.snippet.take(200))
            )
        }
        
        return session.copy(
            searchQueries = session.searchQueries + searchQuery,
            citations = session.citations + newCitations,
            researchLog = session.researchLog + ResearchLogEntry(
                action = "performed_search",
                details = "Searched: $query",
                metadata = mapOf(
                    "query" to query,
                    "results" to searchResults.size.toString()
                )
            )
        )
    }
    
    /**
     * Change research direction
     */
    suspend fun changeDirection(
        session: ResearchSession,
        newDirection: String
    ): ResearchSession {
        logger.info("Changing direction: $newDirection")
        
        return session.copy(
            researchPlan = "${session.researchPlan}\n\nDirection change: $newDirection",
            researchLog = session.researchLog + ResearchLogEntry(
                action = "changed_direction",
                details = "User redirected: $newDirection",
                metadata = mapOf("newDirection" to newDirection)
            )
        )
    }
    
    /**
     * Synthesize final report
     */
    suspend fun synthesizeReport(session: ResearchSession): ResearchSession {
        logger.info("Synthesizing report")

        val messages = listOf(
            LlmMessage(role = LlmMessage.Role.SYSTEM, content = "You are a research assistant. Create a comprehensive report with citations."),
            LlmMessage(role = LlmMessage.Role.USER, content = buildReportPrompt(session))
        )

        val response = llmProvider.generate(messages)

        return session.copy(
            finalReport = response.content,
            status = "completed",
            researchLog = session.researchLog + ResearchLogEntry(
                action = "completed_research",
                details = "Research completed",
                metadata = mapOf(
                    "citations" to session.citations.size.toString(),
                    "searches" to session.searchQueries.size.toString()
                )
            )
        )
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
}
