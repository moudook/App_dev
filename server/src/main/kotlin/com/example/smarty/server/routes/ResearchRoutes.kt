package com.example.smarty.server.routes

import com.example.smarty.server.agent.DeepResearchAgent
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Routes for Deep Research Agent
 */
fun Application.configureResearchRoutes(
    researchAgent: DeepResearchAgent
) {
    val logger = LoggerFactory.getLogger("ResearchRoutes")
    
    routing {
        authenticate("firebase") {
            route("/api/v1/research") {
                
                // Start new research session
                post("/start") {
                    val user = call.principal<FirebaseUserPrincipal>() 
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    
                    try {
                        val request = call.receive<StartResearchRequest>()
                        logger.info("Starting research for user ${user.userId}: ${request.topic}")
                        
                        val session = researchAgent.startResearch(request.topic)
                        call.respond(ResearchResponse(session))
                        
                    } catch (e: Exception) {
                        logger.error("Failed to start research", e)
                        call.respond(HttpStatusCode.InternalServerError, 
                            mapOf("error" to "Failed to start research: ${e.message}"))
                    }
                }
                
                // Submit clarification answers
                post("/{id}/answer") {
                    val user = call.principal<FirebaseUserPrincipal>()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    
                    try {
                        val sessionId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        val request = call.receive<AnswerQuestionsRequest>()
                        
                        logger.info("Processing answers for session $sessionId")
                        
                        // Mock session for now (database integration pending)
                        val mockSession = DeepResearchAgent.ResearchSession(
                            id = sessionId,
                            topic = "Research Topic",
                            userAnswers = request.answers
                        )
                        
                        val updatedSession = researchAgent.processUserAnswers(mockSession, request.answers)
                        call.respond(ResearchResponse(updatedSession))
                        
                    } catch (e: Exception) {
                        logger.error("Failed to process answers", e)
                        call.respond(HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to process answers: ${e.message}"))
                    }
                }
                
                // Change research direction (user interruption)
                post("/{id}/interrupt") {
                    val user = call.principal<FirebaseUserPrincipal>()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    
                    try {
                        val sessionId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        val request = call.receive<UserInterruptionRequest>()
                        
                        logger.info("User interrupting research $sessionId: ${request.message}")
                        
                        val mockSession = DeepResearchAgent.ResearchSession(id = sessionId, topic = "Topic")
                        val updatedSession = researchAgent.handleUserInterruption(mockSession, request.message)
                        call.respond(ResearchResponse(updatedSession))
                        
                    } catch (e: Exception) {
                        logger.error("Failed to process interruption", e)
                        call.respond(HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to process interruption: ${e.message}"))
                    }
                }
                
                // Check timeout status
                get("/{id}/timeout") {
                    val user = call.principal<FirebaseUserPrincipal>()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    
                    try {
                        val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        
                        val mockSession = DeepResearchAgent.ResearchSession(id = sessionId, topic = "Topic")
                        val timeoutStatus = researchAgent.checkTimeout(mockSession)
                        
                        call.respond(mapOf(
                            "status" to timeoutStatus.name,
                            "elapsed" to (System.currentTimeMillis() - mockSession.startTime)
                        ))
                        
                    } catch (e: Exception) {
                        logger.error("Failed to check timeout", e)
                        call.respond(HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to check timeout: ${e.message}"))
                    }
                }
                
                // Get research session status
                get("/{id}") {
                    val user = call.principal<FirebaseUserPrincipal>()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    
                    try {
                        val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        // TODO: Load from database
                        call.respond(HttpStatusCode.OK, mapOf("status" to "not_implemented_yet"))
                        
                    } catch (e: Exception) {
                        logger.error("Failed to get session", e)
                        call.respond(HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to get session: ${e.message}"))
                    }
                }
            }
        }
    }
}

@Serializable
data class StartResearchRequest(val topic: String)

@Serializable
data class AnswerQuestionsRequest(val answers: Map<String, String>)

@Serializable
data class RedirectResearchRequest(val newDirection: String)

@Serializable
data class UserInterruptionRequest(val message: String)

@Serializable
data class ResearchResponse(
    val id: String,
    val topic: String,
    val status: String,
    val clarificationQuestions: List<String>,
    val userAnswers: Map<String, String>,
    val researchPlan: String?,
    val searchQueries: List<SearchQueryResponse>,
    val citations: List<CitationResponse>,
    val finalReport: String?,
    val createdAt: Long
) {
    constructor(session: DeepResearchAgent.ResearchSession) : this(
        id = session.id,
        topic = session.topic,
        status = session.status,
        clarificationQuestions = session.clarificationQuestions,
        userAnswers = session.userAnswers,
        researchPlan = session.researchPlan,
        searchQueries = session.searchQueries.map { SearchQueryResponse(it) },
        citations = session.citations.map { CitationResponse(it) },
        finalReport = session.finalReport,
        createdAt = session.createdAt
    )
}

@Serializable
data class SearchQueryResponse(
    val query: String,
    val purpose: String,
    val results: List<SearchResultResponse>,
    val timestamp: Long
) {
    constructor(search: DeepResearchAgent.SearchQuery) : this(
        query = search.query,
        purpose = search.purpose,
        results = search.results.map { SearchResultResponse(it) },
        timestamp = search.timestamp
    )
}

@Serializable
data class SearchResultResponse(
    val url: String,
    val title: String,
    val snippet: String,
    val position: Int
) {
    constructor(result: DeepResearchAgent.SearchResult) : this(
        url = result.url,
        title = result.title,
        snippet = result.snippet,
        position = result.position
    )
}

@Serializable
data class CitationResponse(
    val url: String,
    val title: String,
    val snippet: String,
    val dateAccessed: Long,
    val keyFindings: List<String>
) {
    constructor(citation: DeepResearchAgent.Citation) : this(
        url = citation.url,
        title = citation.title,
        snippet = citation.snippet,
        dateAccessed = citation.dateAccessed,
        keyFindings = citation.keyFindings
    )
}
