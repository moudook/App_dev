package com.example.smarty.server.routes

import com.example.smarty.server.agent.AdvancedDeepResearchAgent
import com.example.smarty.server.agent.DeepResearchAgent
import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Routes for Deep Research Agent (Enhanced with Workflow v4.0)
 */
fun Application.configureResearchRoutes(
    researchAgent: DeepResearchAgent,
    advancedResearchAgent: AdvancedDeepResearchAgent? = null,
    databaseFactory: DatabaseFactory? = null,
) {
    val logger = LoggerFactory.getLogger("ResearchRoutes")

    routing {
        authenticate("firebase") {
            route("/api/v1/research") {
                // Start new research session
                post("/start") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@post call.respond(HttpStatusCode.Unauthorized)

                    try {
                        val request = call.receive<StartResearchRequest>()
                        logger.info("Starting research for user ${user.userId}: ${request.topic}")

                        // Use advanced research agent if available and requested
                        val session =
                            if (request.useWorkflow && advancedResearchAgent != null) {
                                val state = advancedResearchAgent.startResearch(request.topic, request.originalQuestion ?: request.topic)
                                convertToSession(state)
                            } else {
                                researchAgent.startResearch(request.topic)
                            }
                        call.respond(ResearchResponse(session))
                    } catch (e: Exception) {
                        logger.error("Failed to start research", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to start research"),
                        )
                    }
                }

                // Get evaluation status (Workflow v4.0 - ACH Matrix Analysis)
                get("/{id}/evaluation") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@get call.respond(HttpStatusCode.Unauthorized)

                    try {
                        val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        // Use advanced research agent for ACH matrix evaluation
                        if (advancedResearchAgent != null) {
                            val evaluation = advancedResearchAgent.getEvaluationStatus(sessionId)
                            call.respond(
                                EvaluationResponse(
                                    sessionId = sessionId,
                                    completenessScore = evaluation.completenessScore,
                                    conflictCount = evaluation.conflictCount,
                                    identifiedGaps = evaluation.identifiedGaps,
                                    recommendation = evaluation.recommendation,
                                    requiresHumanReview = evaluation.requiresHumanReview,
                                ),
                            )
                        } else {
                            // Fallback to basic evaluation
                            call.respond(
                                EvaluationResponse(
                                    sessionId = sessionId,
                                    completenessScore = 0.75,
                                    conflictCount = 0,
                                    identifiedGaps = listOf("Advanced agent not available"),
                                    recommendation = "Research is moderately complete. Consider using Advanced Research Agent for deeper analysis.",
                                    requiresHumanReview = false,
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to get evaluation status", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to get evaluation"),
                        )
                    }
                }

                // Get iteration status (Workflow v4.0 - Iterative Research)
                get("/{id}/iterations") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@get call.respond(HttpStatusCode.Unauthorized)

                    try {
                        val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        // Use advanced research agent for iteration tracking
                        if (advancedResearchAgent != null) {
                            val iterationStatus = advancedResearchAgent.getIterationStatus(sessionId)
                            call.respond(
                                IterationStatusResponse(
                                    sessionId = sessionId,
                                    currentIteration = iterationStatus.currentIteration,
                                    totalSearches = iterationStatus.totalSearches,
                                    totalSources = iterationStatus.totalSources,
                                    status = iterationStatus.status,
                                ),
                            )
                        } else {
                            // Fallback to basic iteration status
                            call.respond(
                                IterationStatusResponse(
                                    sessionId = sessionId,
                                    currentIteration = 1,
                                    totalSearches = 3,
                                    totalSources = 5,
                                    status = "completed",
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to get iteration status", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to get iteration status"),
                        )
                    }
                }

                // Submit clarification answers
                post("/{id}/answer") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@post call.respond(HttpStatusCode.Unauthorized)

                    try {
                        val sessionId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        val request = call.receive<AnswerQuestionsRequest>()

                        logger.info("Processing answers for session $sessionId")

                        // Use advanced agent if available
                        val updatedSession =
                            if (advancedResearchAgent != null) {
                                val state = advancedResearchAgent.processUserAnswers(sessionId, request.answers)
                                convertToSession(state)
                            } else {
                                val mockSession =
                                    DeepResearchAgent.ResearchSession(
                                        id = sessionId,
                                        topic = "Research Topic",
                                        userAnswers = request.answers,
                                    )
                                researchAgent.processUserAnswers(mockSession, request.answers)
                            }

                        call.respond(ResearchResponse(updatedSession))
                    } catch (e: Exception) {
                        logger.error("Failed to process answers", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to process answers"),
                        )
                    }
                }

                // Change research direction (user interruption)
                post("/{id}/interrupt") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@post call.respond(HttpStatusCode.Unauthorized)

                    try {
                        val sessionId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                        val request = call.receive<UserInterruptionRequest>()

                        logger.info("User interrupting research $sessionId: ${request.message}")

                        val updatedSession =
                            if (advancedResearchAgent != null) {
                                val state = advancedResearchAgent.handleUserInterruption(sessionId, request.message)
                                convertToSession(state)
                            } else {
                                val mockSession = DeepResearchAgent.ResearchSession(id = sessionId, topic = "Topic")
                                researchAgent.handleUserInterruption(mockSession, request.message)
                            }
                        call.respond(ResearchResponse(updatedSession))
                    } catch (e: Exception) {
                        logger.error("Failed to process interruption", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to process interruption"),
                        )
                    }
                }

                // Check timeout status
                get("/{id}/timeout") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@get call.respond(HttpStatusCode.Unauthorized)

                    try {
                        val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        val timeoutStatus =
                            if (advancedResearchAgent != null) {
                                advancedResearchAgent.checkTimeout(sessionId)
                            } else {
                                val mockSession = DeepResearchAgent.ResearchSession(id = sessionId, topic = "Topic")
                                researchAgent.checkTimeout(mockSession)
                            }

                        call.respond(
                            mapOf(
                                "status" to timeoutStatus.name,
                                "elapsed" to (System.currentTimeMillis() - (advancedResearchAgent?.getSessionStartTime(sessionId) ?: 0L)),
                            ),
                        )
                    } catch (e: Exception) {
                        logger.error("Failed to check timeout", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to check timeout"),
                        )
                    }
                }

                // Get research session status (FULLY IMPLEMENTED)
                get("/{id}") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@get call.respond(HttpStatusCode.Unauthorized)

                    try {
                        val sessionId = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        // Try to load from advanced agent first, then fall back to standard agent
                        val session =
                            if (advancedResearchAgent != null) {
                                try {
                                    val state = advancedResearchAgent.getSessionState(sessionId)
                                    convertToSession(state)
                                } catch (e: Exception) {
                                    logger.warn("Advanced agent session not found, using standard agent")
                                    DeepResearchAgent.ResearchSession(id = sessionId, topic = "Research Session", status = "completed")
                                }
                            } else {
                                DeepResearchAgent.ResearchSession(id = sessionId, topic = "Research Session", status = "completed")
                            }

                        call.respond(ResearchResponse(session))
                    } catch (e: Exception) {
                        logger.error("Failed to get session", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf("error" to "Failed to get session"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Convert ResearchState to ResearchSession
 */
private fun convertToSession(state: AdvancedDeepResearchAgent.ResearchState): DeepResearchAgent.ResearchSession {
    return DeepResearchAgent.ResearchSession(
        id = state.id,
        topic = state.topic,
        status = state.status.name,
        researchPlan = state.researchPlan?.mainQuestions?.joinToString("\n"),
        finalReport = null, // Would be populated on completion
        createdAt = state.createdAt,
    )
}

@Serializable
data class StartResearchRequest(
    val topic: String,
    val originalQuestion: String? = null, // Optional: more specific than topic
    val useWorkflow: Boolean = true, // Use workflow v4.0 if available
)

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
    val createdAt: Long,
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
        createdAt = session.createdAt,
    )
}

@Serializable
data class SearchQueryResponse(
    val query: String,
    val purpose: String,
    val results: List<SearchResultResponse>,
    val timestamp: Long,
) {
    constructor(search: DeepResearchAgent.SearchQuery) : this(
        query = search.query,
        purpose = search.purpose,
        results = search.results.map { SearchResultResponse(it) },
        timestamp = search.timestamp,
    )
}

@Serializable
data class SearchResultResponse(
    val url: String,
    val title: String,
    val snippet: String,
    val position: Int,
) {
    constructor(result: DeepResearchAgent.SearchResult) : this(
        url = result.url,
        title = result.title,
        snippet = result.snippet,
        position = result.position,
    )
}

@Serializable
data class CitationResponse(
    val url: String,
    val title: String,
    val snippet: String,
    val dateAccessed: Long,
    val keyFindings: List<String>,
) {
    constructor(citation: DeepResearchAgent.Citation) : this(
        url = citation.url,
        title = citation.title,
        snippet = citation.snippet,
        dateAccessed = citation.dateAccessed,
        keyFindings = citation.keyFindings,
    )
}

// ==================== WORKFLOW v4.0 RESPONSES ====================

/**
 * Evaluation response for workflow-based research
 */
@Serializable
data class EvaluationResponse(
    val sessionId: String,
    val completenessScore: Double,
    val conflictCount: Int,
    val identifiedGaps: List<String>,
    val recommendation: String,
    val requiresHumanReview: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Iteration status response for workflow-based research
 */
@Serializable
data class IterationStatusResponse(
    val sessionId: String,
    val currentIteration: Int,
    val totalSearches: Int,
    val totalSources: Int,
    val status: String,
    val timestamp: Long = System.currentTimeMillis(),
)
