package com.example.smarty.server.routes

import com.example.smarty.server.data.SearchHistory
import com.example.smarty.server.data.SearchHistoryRepository
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Search History Routes
 * API endpoints for managing user search history
 */
fun Application.configureSearchHistoryRoutes(searchHistoryRepository: SearchHistoryRepository) {
    routing {
        authenticate("firebase") {
            route("/api/search/history") {
                /**
                 * Get user's search history
                 * GET /api/search/history?limit=20
                 */
                get {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@get call.respond(HttpStatusCode.Unauthorized, "User not authenticated")

                    try {
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                        val history = searchHistoryRepository.getSearchHistory(user.userId, limit)

                        call.respond(
                            SearchHistoryResponse(
                                success = true,
                                history = history,
                            ),
                        )
                    } catch (e: Exception) {
                        call.application.log.error("Failed to get search history", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to get search history")
                    }
                }

                /**
                 * Add a search to history
                 * POST /api/search/history
                 * Body: { "query": "best restaurants", "searchScope": "all", "resultCount": 10 }
                 */
                post {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@post call.respond(HttpStatusCode.Unauthorized, "User not authenticated")

                    try {
                        val request = call.receive<AddSearchRequest>()
                        val search =
                            SearchHistory(
                                id = UUID.randomUUID().toString(),
                                userId = user.userId,
                                query = request.query,
                                searchScope = request.searchScope ?: "all",
                                resultCount = request.resultCount ?: 0,
                            )

                        val id = searchHistoryRepository.addSearch(search)

                        call.respond(
                            AddSearchResponse(
                                success = true,
                                id = id,
                            ),
                        )
                    } catch (e: Exception) {
                        call.application.log.error("Failed to add search", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to add search")
                    }
                }

                /**
                 * Delete a specific search from history
                 * DELETE /api/search/history/{searchId}
                 */
                delete("/{searchId}") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@delete call.respond(HttpStatusCode.Unauthorized, "User not authenticated")

                    val searchId =
                        call.parameters["searchId"]
                            ?: return@delete call.respond(HttpStatusCode.BadRequest, "Search ID required")

                    try {
                        val deleted = searchHistoryRepository.deleteSearch(searchId)

                        if (deleted) {
                            call.respond(
                                DeleteSearchResponse(
                                    success = true,
                                    message = "Search deleted",
                                ),
                            )
                        } else {
                            call.respond(HttpStatusCode.NotFound, "Search not found")
                        }
                    } catch (e: Exception) {
                        call.application.log.error("Failed to delete search", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to delete search")
                    }
                }

                /**
                 * Clear all search history for user
                 * DELETE /api/search/history/clear
                 */
                delete("/clear") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@delete call.respond(HttpStatusCode.Unauthorized, "User not authenticated")

                    try {
                        val count = searchHistoryRepository.clearUserSearchHistory(user.userId)

                        call.respond(
                            ClearSearchResponse(
                                success = true,
                                deletedCount = count,
                                message = "Cleared $count searches",
                            ),
                        )
                    } catch (e: Exception) {
                        call.application.log.error("Failed to clear search history", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to clear search history")
                    }
                }

                /**
                 * Search chat history across all sessions
                 * GET /api/search/history/chat?q=query&limit=20
                 */
                get("/chat") {
                    val user =
                        call.principal<FirebaseUserPrincipal>()
                            ?: return@get call.respond(HttpStatusCode.Unauthorized, "User not authenticated")

                    try {
                        val query =
                            call.request.queryParameters["q"]
                                ?: return@get call.respond(HttpStatusCode.BadRequest, "Search query required")
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20

                        val dataSource =
                            com.example.smarty.server.data.DatabaseFactory.getDataSource()
                                ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, "Database not available")

                        val chatRepo =
                            com.example.smarty.server.data.ChatRepository(
                                dataSource,
                                com.example.smarty.server.data.ChatMessageNotesRepository(dataSource),
                            )

                        val results = chatRepo.searchHistory(user.userId, query, limit)

                        call.respond(
                            ChatSearchResponse(
                                success = true,
                                results = results,
                            ),
                        )
                    } catch (e: Exception) {
                        call.application.log.error("Failed to search chat history", e)
                        call.respond(HttpStatusCode.InternalServerError, "Failed to search chat history")
                    }
                }
            }
        }
    }
}

/**
 * Response for chat history search
 */
@Serializable
data class ChatSearchResponse(
    val success: Boolean,
    val results: List<com.example.smarty.server.data.SearchResult> = emptyList(),
)

// ==================== REQUEST/RESPONSE DATA CLASSES ====================

@Serializable
data class AddSearchRequest(
    val query: String,
    val searchScope: String? = null, // all, notes, chat, research, tasks
    val resultCount: Int? = null,
)

@Serializable
data class AddSearchResponse(
    val success: Boolean,
    val id: String,
)

@Serializable
data class SearchHistoryResponse(
    val success: Boolean,
    val history: List<SearchHistory> = emptyList(),
)

@Serializable
data class DeleteSearchResponse(
    val success: Boolean,
    val message: String,
)

@Serializable
data class ClearSearchResponse(
    val success: Boolean,
    val deletedCount: Int,
    val message: String,
)
