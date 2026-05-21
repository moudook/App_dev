package com.example.smarty.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.sse.*
import io.ktor.server.response.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import com.example.smarty.server.routes.configureHealthRoutes
import com.example.smarty.server.routes.configureChatRoutes
import com.example.smarty.server.routes.configureOptimizedSyncRoutes
import com.example.smarty.server.routes.configureDataRoutes
import com.example.smarty.server.routes.configureResearchRoutes
import com.example.smarty.server.routes.configureSyncRoutes
import com.example.smarty.server.data.DatabaseFactory
import io.ktor.server.plugins.cors.routing.*
import io.ktor.http.*
import com.example.smarty.server.plugins.configureSecurity
import com.example.smarty.server.plugins.configureFirewall
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import com.example.smarty.server.routes.configureProcessingRoutes
import com.example.smarty.server.plugins.configureMonitoring
import com.example.smarty.server.plugins.installStructuredLogging
import com.example.smarty.server.plugins.configureEnhancedHealthCheck
import com.example.smarty.server.routes.configureHandshakeRoutes
import com.example.smarty.server.services.DigestService
import com.example.smarty.server.services.DigestScheduler
import com.example.smarty.server.services.FcmNotificationService
import com.example.smarty.server.data.ChatRepository
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.ChatMessageNotesRepository
import com.example.smarty.server.data.CalendarEventNotesRepository
import com.example.smarty.server.llm.LlmProviderFactory
import com.example.smarty.server.routes.configureDigestRoutes
import com.example.smarty.server.tools.WebScrapeTool
import com.example.smarty.server.routes.configureNewFeaturesRoutes
import com.example.smarty.server.routes.configureReasoningRoutes
import com.example.smarty.server.data.TaskRepository
import com.example.smarty.server.data.TagRepository
import com.example.smarty.server.data.NotificationRepository
import com.example.smarty.server.data.ChatFolderRepository
import com.example.smarty.server.data.ReasoningTraceRepository
import com.example.smarty.server.services.ReasoningService
import com.example.smarty.server.services.OrchestratorService
import com.example.smarty.server.services.VisionService
import com.example.smarty.server.routes.configureOrchestratorRoutes
import com.example.smarty.server.services.UtilityService
import com.example.smarty.server.routes.configureUtilityRoutes
import com.example.smarty.server.data.SearchHistoryRepository
import com.example.smarty.server.data.GeneratedImageRepository
import com.example.smarty.server.data.UserDeviceRepository
import com.example.smarty.server.routes.configureSearchHistoryRoutes
import com.example.smarty.server.routes.configureUserDeviceRoutes
import com.example.smarty.server.routes.configureModelRoutes

/**
 * Friday Server - Cloud-hosted agent runtime.
 *
 * This is the entry point for the Ktor server that will eventually
 * host the KOOG agent and serve commands to the Android client.
 *
 * Current capabilities:
 * - /health endpoint for status checks
 * - /chat/stream SSE endpoint for agent event streaming
 *
 * Future capabilities:
 * - KOOG agent runtime
 * - Command dispatch to Android clients
 */
// import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import org.slf4j.event.*
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * Server port. Can be overridden via SERVER_PORT environment variable.
 */
private val serverPort = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 7860
val serverStartTime = System.currentTimeMillis()

fun main() {
    embeddedServer(Netty, port = serverPort, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // Discover OpenCode free models at startup (blocking, runs `opencode models`)
    com.example.smarty.server.llm.OpencodeModelRegistry.discoverAtStartup()

    // Initialize Database
    DatabaseFactory.init()

    // Configure Security (Firebase JWT verification)
    configureSecurity()

    // Configure Firewall (IP restrictions, request limits)
    configureFirewall()

    // Configure CORS (Allow Android client + HF Spaces + common development origins)
    install(CORS) {
        allowHost("*")  // allowHeaders and allowMethods below restrict it
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Smarty-Device-Id")
        allowHeader("X-Smarty-Version")
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Head)
        // WebSocket upgrade header
        allowHeader(HttpHeaders.Upgrade)
        allowHeader(HttpHeaders.Connection)
        // Allow pre-flight requests from any origin (auth is enforced via Firebase JWT)
        anyHost()
        allowCredentials = true
    }
    install(CallId) {
        header("X-Request-ID")
        generate { UUID.randomUUID().toString() }
        verify { it.isNotEmpty() }
    }

    // Configure Call Logging
    // Legacy CallLogging disabled in favor of StructuredLogging
    /*
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
        callIdMdc("trace_id")
    }
     */

    // Install Structured Logging (JSON format, correlation IDs, performance metrics)
    installStructuredLogging()

    // Configure Security Monitoring
    configureSecurityMonitoring()

    // Configure Rate Limiting - Simplified for low-memory environment
    install(RateLimit) {
        register(RateLimitName("chat")) {
            rateLimiter(limit = 120, refillPeriod = 1.minutes)
            requestKey { call ->
                call.principal<FirebaseUserPrincipal>()?.userId
                    ?: call.request.local.remoteHost
            }
        }
        global {
            rateLimiter(limit = 100, refillPeriod = 1.minutes)
            requestKey { call ->
                call.principal<FirebaseUserPrincipal>()?.userId
                    ?: call.request.local.remoteHost
            }
        }
    }

    // Configure JSON serialization
    install(ContentNegotiation) {
        json(
            Json {
                isLenient = true
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }

    // Configure SSE plugin for streaming
    install(SSE)

    // Initialize core services — single pass, no duplicates
    val ds = DatabaseFactory.getDataSource()
    var digestService: DigestService? = null
    var digestScheduler: DigestScheduler? = null

    if (ds != null) {
        val chatMessageNotesRepo = ChatMessageNotesRepository(ds)
        val calendarEventNotesRepo = CalendarEventNotesRepository(ds)
        val chatRepo = ChatRepository(ds, chatMessageNotesRepo)

        digestService = DigestService(
            dataSource = ds,
            chatRepository = chatRepo,
            vectorStore = PostgresVectorStore(),
            llmProvider = LlmProviderFactory.getOrCreateProvider(HttpClientSingleton.client),
        )

        val fcmService = FcmNotificationService.fromEnvironment(ds)
        digestScheduler = DigestScheduler(
            application = this,
            dataSource = ds,
            digestService = digestService,
            fcmService = fcmService,
        )
        digestScheduler.start()

        val noteRepo = NoteRepository(ds, chatMessageNotesRepo, calendarEventNotesRepo)
        val noteService = com.example.smarty.server.services.NoteService(
            noteRepo,
            com.example.smarty.server.services.ContentAnalysisService(
                HttpClientSingleton.client,
                VisionService(HttpClientSingleton.client)
            ),
            PostgresVectorStore(),
            com.example.smarty.server.services.AdaptiveSearchService()
        )

        // Configure routes
        configureHealthRoutes()
        configureChatRoutes(noteService)
        configureProcessingRoutes()
        configureHandshakeRoutes()
        configureDataRoutes(noteService)

        val taskRepo = TaskRepository(ds)
        val tagRepo = TagRepository(ds)
        val notificationRepo = NotificationRepository(ds)
        val chatFolderRepo = ChatFolderRepository(ds)
        configureNewFeaturesRoutes(taskRepo, tagRepo, notificationRepo, chatFolderRepo)

        val deepResearchAgent = com.example.smarty.server.agent.DeepResearchAgent(
            llmProvider = LlmProviderFactory.getOrCreateProvider(HttpClientSingleton.client),
            webScrapeTool = WebScrapeTool(),
            progressFileManager = com.example.smarty.server.agent.ProgressFileManager(),
        )

        val advancedDeepResearchAgent = com.example.smarty.server.agent.AdvancedDeepResearchAgent(
            llmProvider = LlmProviderFactory.getOrCreateProvider(HttpClientSingleton.client),
            webScrapeTool = WebScrapeTool(),
            progressTracker = com.example.smarty.server.agent.ResearchProgressTracker(),
        )

        configureResearchRoutes(deepResearchAgent, advancedDeepResearchAgent)
        configureOptimizedSyncRoutes()
        configureSyncRoutes()

        val reasoningRepo = ReasoningTraceRepository(ds)
        val reasoningService = ReasoningService(reasoningRepo)
        routing { configureReasoningRoutes(reasoningService) }
        log.info("ReasoningRoutes configured with ReasoningService")

        val utilityService = UtilityService(LlmProviderFactory.getOrCreateProvider(HttpClientSingleton.client))
        configureUtilityRoutes(utilityService)
        log.info("UtilityRoutes configured")

        val orchestratorService = OrchestratorService(
            visionService = VisionService(HttpClientSingleton.client),
            kreaImageTool = com.example.smarty.server.tools.KreaImageTool(),
        )
        configureOrchestratorRoutes(orchestratorService)
        log.info("OrchestratorRoutes configured")

        val searchHistoryRepo = SearchHistoryRepository(ds)
        configureSearchHistoryRoutes(searchHistoryRepo)
        log.info("SearchHistoryRoutes configured")

        val userDeviceRepo = UserDeviceRepository(ds)
        configureUserDeviceRoutes(userDeviceRepo)
        log.info("UserDeviceRoutes configured")

        configureModelRoutes()
        log.info("ModelRoutes configured")

        val mcpServer = com.example.smarty.server.mcp.McpServer(
            vectorStore = PostgresVectorStore(),
            noteRepository = noteRepo,
            timerRepository = TimerRepository(ds),
            calendarRepository = CalendarRepository(ds, calendarEventNotesRepo),
            noteService = noteService
        )
        routing {
            mcpServer.configureRouting(this)
        }
        log.info("McpServer configured")

        configureDigestRoutes(digestService, digestScheduler, ds)
    } else {
        configureHealthRoutes()
        configureProcessingRoutes()
        configureHandshakeRoutes()
        configureOptimizedSyncRoutes()
        configureSyncRoutes()
        configureModelRoutes()
    }

    // Image serving endpoint
    routing {
        get("/generated-images/{id}") {
            val providedApiKey = call.parameters["apiKey"]
            val expectedApiKey = System.getenv("SMARTY_API_KEY") ?: "dev-key"

            if (providedApiKey != expectedApiKey && providedApiKey != null) {
                call.respondText("{\"error\": \"Invalid API key\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }

            val imageId = call.parameters["id"]
            if (imageId.isNullOrBlank()) {
                call.respondText("{\"error\": \"Image ID required\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }

            try {
                java.util.UUID.fromString(imageId)
            } catch (e: IllegalArgumentException) {
                call.respondText("{\"error\": \"Invalid image ID format\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }

            val dataSource = DatabaseFactory.getDataSource()
            if (dataSource == null) {
                call.respondText("{\"error\": \"Database not available\"}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                return@get
            }

            try {
                val imageRepo = GeneratedImageRepository(dataSource)
                val imageData = imageRepo.getImageBytes(imageId)

                if (imageData == null) {
                    call.respondText("{\"error\": \"Image not found\"}", ContentType.Application.Json, HttpStatusCode.NotFound)
                    return@get
                }

                if (providedApiKey == null) {
                    val storedImage = imageRepo.getById(imageId)
                    if (storedImage == null) {
                        call.respondText("{\"error\": \"Image not found\"}", ContentType.Application.Json, HttpStatusCode.NotFound)
                        return@get
                    }
                }

                val (bytes, contentType) = imageData
                val mimeType = when {
                    contentType.contains("png") -> ContentType.Image.PNG
                    contentType.contains("jpg") || contentType.contains("jpeg") -> ContentType.Image.JPEG
                    contentType.contains("gif") -> ContentType.Image.GIF
                    contentType.contains("webp") -> ContentType("image", "webp")
                    else -> ContentType.Image.Any
                }
                call.respondBytes(bytes, mimeType)
            } catch (e: Exception) {
                call.application.log.error("Failed to serve image", e)
                call.respondText("{\"error\": \"Failed to serve image\"}", ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }
    }

    // Configure Monitoring
    configureMonitoring()

    // Configure Enhanced Health Check
    configureEnhancedHealthCheck()

    // Log startup
    log.info("Friday Server started on port $serverPort")
    if (digestScheduler != null) {
        log.info("Digest Scheduler started - daily digests will be generated at configured times")
    } else {
        log.warn("Database not configured - Digest Scheduler disabled")
    }
}

/**
 * Configure security monitoring.
 */
fun Application.configureSecurityMonitoring() {
    val logger = org.slf4j.LoggerFactory.getLogger("SecurityMonitoring")
    logger.info("Security monitoring initialized")
    logger.info("Security utilities available:")
    logger.info("  - InputValidation: Input validation and sanitization")
    logger.info("  - SecurityHeaders: Security header management")
    logger.info("  - SecurityMonitor: Security event tracking and monitoring")
}


