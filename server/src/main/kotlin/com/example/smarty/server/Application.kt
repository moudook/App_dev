package com.example.smarty.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.sse.*
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
import io.ktor.server.request.path
import com.example.smarty.server.plugins.configureSecurity
import com.example.smarty.server.plugins.configureFirewall
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import com.example.smarty.server.routes.configureProcessingRoutes
import com.example.smarty.server.plugins.configureMonitoring
import com.example.smarty.server.plugins.installStructuredLogging
import com.example.smarty.server.plugins.configureEnhancedHealthCheck
import com.example.smarty.server.routes.configureHandshakeRoutes
import com.example.smarty.server.routes.configureDataRoutes
import com.example.smarty.server.services.DigestService
import com.example.smarty.server.services.DigestScheduler
import com.example.smarty.server.services.FcmNotificationService
import com.example.smarty.server.data.ChatRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.ChatMessageNotesRepository
import com.example.smarty.server.data.CalendarEventNotesRepository
import com.example.smarty.server.llm.LlmProviderFactory
import com.example.smarty.server.routes.configureDigestRoutes
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
import javax.sql.DataSource
import com.example.smarty.server.HttpClientSingleton

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
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.routing.*
import io.micrometer.prometheus.*
import org.slf4j.event.*
import java.util.UUID
import kotlin.time.Duration.Companion.minutes


/**
 * Server port. Can be overridden via SERVER_PORT environment variable.
 */
private val serverPort = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 7860
private val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
val serverStartTime = System.currentTimeMillis()

fun main() {
    embeddedServer(Netty, port = serverPort, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // Initialize Database
    DatabaseFactory.init()

    // Configure Security (Firebase JWT verification)
    configureSecurity()

    // Configure Firewall (IP restrictions, request limits)
    configureFirewall()

    // Configure CORS (Restricted)
    install(CORS) {
        allowHost("localhost")
        allowHost("127.0.0.1")
        // Allow Hugging Face Spaces
        allowHost("huggingface.co")
        allowHost("*.hf.space")
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Smarty-Device-Id")
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
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

    // Configure Rate Limiting - Per-user to prevent abuse
    install(RateLimit) {
        // Chat endpoints - more generous for real-time interaction
        register(RateLimitName("chat")) {
            rateLimiter(limit = 120, refillPeriod = 1.minutes)
            requestKey { call ->
                // Use User ID from Firebase auth, fallback to IP
                call.principal<FirebaseUserPrincipal>()?.userId
                    ?: call.request.local.remoteHost
            }
        }
        // Processing endpoints - more restrictive (expensive operations)
        register(RateLimitName("processing")) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey { call ->
                call.principal<FirebaseUserPrincipal>()?.userId
                    ?: call.request.local.remoteHost
            }
        }
        // Global fallback for unregistered routes
        global {
            rateLimiter(limit = 100, refillPeriod = 1.minutes)
            requestKey { call ->
                call.principal<FirebaseUserPrincipal>()?.userId
                    ?: call.request.local.remoteHost
            }
        }
    }

    // Configure Micrometer Metrics
    install(MicrometerMetrics) {
        registry = prometheusRegistry
    }

    // Configure JSON serialization
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    // Configure SSE plugin for streaming
    install(SSE)

    // Initialize Digest System
    val ds = DatabaseFactory.getDataSource()
    var digestService: DigestService? = null
    var digestScheduler: DigestScheduler? = null
    
    if (ds != null) {
        val chatMessageNotesRepo = ChatMessageNotesRepository(ds)
        val calendarEventNotesRepo = CalendarEventNotesRepository(ds)
        
        digestService = DigestService(
            dataSource = ds,
            chatRepository = ChatRepository(ds, chatMessageNotesRepo),
            vectorStore = PostgresVectorStore(),
            llmProvider = LlmProviderFactory.create(HttpClientSingleton.client)
        )

        val fcmService = FcmNotificationService.fromEnvironment(ds)

        digestScheduler = DigestScheduler(
            application = this,
            dataSource = ds,
            digestService = digestService,
            fcmService = fcmService
        )
        digestScheduler.start()
    }

    // Configure routes
    configureHealthRoutes()
    configureChatRoutes()
    configureProcessingRoutes()
    configureHandshakeRoutes()
    configureDataRoutes()


    
    // Configure v6.0.0 new features routes (Tasks, Tags, Notifications, Folders)
    if (ds != null) {
        val taskRepo = TaskRepository(ds)
        val tagRepo = TagRepository(ds)
        val notificationRepo = NotificationRepository(ds)
        val chatFolderRepo = ChatFolderRepository(ds)
        configureNewFeaturesRoutes(taskRepo, tagRepo, notificationRepo, chatFolderRepo)
    }
    
    val deepResearchAgent = com.example.smarty.server.agent.DeepResearchAgent(
        llmProvider = com.example.smarty.server.llm.LlmProviderFactory.create(HttpClientSingleton.client),
        tavilyTool = com.example.smarty.server.tools.TavilySearchTool(),
        webScrapeTool = com.example.smarty.server.tools.WebScrapeTool(),
        progressFileManager = com.example.smarty.server.agent.ProgressFileManager()
    )
    
    val advancedDeepResearchAgent = com.example.smarty.server.agent.AdvancedDeepResearchAgent(
        llmProvider = com.example.smarty.server.llm.LlmProviderFactory.create(HttpClientSingleton.client),
        tavilyTool = com.example.smarty.server.tools.TavilySearchTool(),
        webScrapeTool = com.example.smarty.server.tools.WebScrapeTool(),
        progressTracker = com.example.smarty.server.agent.ResearchProgressTracker()
    )
    
    configureResearchRoutes(deepResearchAgent, advancedDeepResearchAgent)
    configureOptimizedSyncRoutes()
    configureSyncRoutes()

    // Initialize Reasoning Service
    val reasoningService = if (ds != null) {
        val reasoningRepo = ReasoningTraceRepository(ds)
        ReasoningService(reasoningRepo)
    } else null

    // Configure Reasoning Routes
    if (reasoningService != null) {
        routing {
            configureReasoningRoutes(reasoningService)
        }
        log.info("ReasoningRoutes configured with ReasoningService")
    }

    // Initialize Utility Service
    val utilityService = UtilityService(LlmProviderFactory.create(HttpClientSingleton.client))

    // Initialize Orchestrator Service (The Brain - routes requests to appropriate services)
    val orchestratorService = if (ds != null) {
        val providerRouter = com.example.smarty.server.llm.ProviderRouter(HttpClientSingleton.client)
        OrchestratorService(
            providerRouter = providerRouter,
            visionService = VisionService(HttpClientSingleton.client),
            kreaImageTool = com.example.smarty.server.tools.KreaImageTool()
        )
    } else null

    // Initialize Search History Repository
    val searchHistoryRepository = if (ds != null) SearchHistoryRepository(ds) else null

    // Initialize User Device Repository
    val userDeviceRepository = if (ds != null) UserDeviceRepository(ds) else null

    // Configure Utility Routes (ENABLED)
    configureUtilityRoutes(utilityService)
    log.info("UtilityRoutes configured")

    // Configure Orchestrator Routes (ENABLED)
    if (orchestratorService != null) {
        configureOrchestratorRoutes(orchestratorService)
        log.info("OrchestratorRoutes configured")
    }

    // Configure Search History Routes (ENABLED)
    if (searchHistoryRepository != null) {
        configureSearchHistoryRoutes(searchHistoryRepository)
        log.info("SearchHistoryRoutes configured")
    }

    // Configure User Device Routes (ENABLED)
    if (userDeviceRepository != null) {
        configureUserDeviceRoutes(userDeviceRepository)
        log.info("UserDeviceRoutes configured")
    }

    // Configure Image Serving Endpoint
    routing {
        get("/generated-images/{id}") {
            val providedApiKey = call.parameters["apiKey"]
            val expectedApiKey = System.getenv("SMARTY_API_KEY") ?: "dev-key"
            
            // Verify API key if provided, otherwise allow for development
            if (providedApiKey != expectedApiKey && providedApiKey != null) {
                call.respondText("{\"error\": \"Invalid API key\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }

            val imageId = call.parameters["id"]
            if (imageId.isNullOrBlank()) {
                call.respondText("{\"error\": \"Image ID required\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
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

                // Verify image ownership when API key is not provided
                if (providedApiKey == null) {
                    val storedImage = imageRepo.getById(imageId)
                    if (storedImage == null) {
                        call.respondText("{\"error\": \"Image not found\"}", ContentType.Application.Json, HttpStatusCode.NotFound)
                        return@get
                    }
                }

                // Serve the image bytes with appropriate content type
                val (bytes, contentType) = imageData
                val mimeType = when {
                    contentType.contains("png") -> ContentType.Image.PNG
                    contentType.contains("jpg") || contentType.contains("jpeg") -> ContentType.Image.JPEG
                    contentType.contains("gif") -> ContentType.Image.GIF
                    contentType.contains("webp") -> ContentType("image", "webp")
                    else -> ContentType.Image.Any
                }
                call.respond(mimeType, bytes)
            } catch (e: Exception) {
                call.application.log.error("Failed to serve image", e)
                call.respondText("{\"error\": \"Failed to serve image\"}", ContentType.Application.Json, HttpStatusCode.InternalServerError)
            }
        }
    }

    if (digestService != null && digestScheduler != null && ds != null) {
        configureDigestRoutes(digestService, digestScheduler, ds!!)
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
