package com.example.smarty.server

import com.example.smarty.server.data.CalendarEventNotesRepository
import com.example.smarty.server.data.ChatFolderRepository
import com.example.smarty.server.data.ChatMessageNotesRepository
import com.example.smarty.server.data.ChatRepository
import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.data.GeneratedImageRepository
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.NotificationRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.ReasoningTraceRepository
import com.example.smarty.server.data.SearchHistoryRepository
import com.example.smarty.server.data.TagRepository
import com.example.smarty.server.data.TaskRepository
import com.example.smarty.server.data.UserDeviceRepository
import com.example.smarty.server.agent.ApplicationAttributes
import com.example.smarty.server.agent2.ContextWindowManager
import com.example.smarty.server.agent2.OpenRouterChatModelFactory
import com.example.smarty.server.agent2.OpenRouterConfig
import com.example.smarty.server.agent2.OpenRouterModelProvider
import com.example.smarty.server.agent2.PostgresChatMemoryStore
import com.example.smarty.server.config.AppConfig
import com.example.smarty.server.llm.LlmProviderFactory
import com.example.smarty.server.plugins.FirebaseUserPrincipal
import com.example.smarty.server.plugins.configureEnhancedHealthCheck
import com.example.smarty.server.plugins.configureFirewall
import com.example.smarty.server.plugins.configureMonitoring
import com.example.smarty.server.plugins.configureSecurity
import com.example.smarty.server.plugins.installStructuredLogging
import com.example.smarty.server.routes.configureAuthRoutes
import com.example.smarty.server.routes.configureChatRoutes
import com.example.smarty.server.routes.configureDataRoutes
import com.example.smarty.server.routes.configureDigestRoutes
import com.example.smarty.server.routes.configureFileRoutes
import com.example.smarty.server.routes.configureHandshakeRoutes
import com.example.smarty.server.routes.configureHealthRoutes
import com.example.smarty.server.routes.configureModelRoutes
import com.example.smarty.server.routes.configureNewFeaturesRoutes
import com.example.smarty.server.routes.configureOptimizedSyncRoutes
import com.example.smarty.server.routes.configureOrchestratorRoutes
import com.example.smarty.server.routes.configurePermissionRoutes
import com.example.smarty.server.routes.configureProcessingRoutes
import com.example.smarty.server.routes.configureReasoningRoutes
import com.example.smarty.server.routes.configureSearchHistoryRoutes
import com.example.smarty.server.routes.configureSyncRoutes
import com.example.smarty.server.routes.configureUserDeviceRoutes
import com.example.smarty.server.routes.configureUtilityRoutes
import com.example.smarty.server.services.DigestScheduler
import com.example.smarty.server.services.DigestService
import com.example.smarty.server.services.FcmNotificationService
import com.example.smarty.server.services.FileProcessingService
import com.example.smarty.server.services.GroqWhisperService
import com.example.smarty.server.services.OrchestratorService
import com.example.smarty.server.services.ReasoningService
import com.example.smarty.server.services.UtilityService
import com.example.smarty.server.services.VisionService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

/*
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

/** Server port. Can be overridden via SERVER_PORT environment variable. */
private val serverPort = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 7860
val serverStartTime = System.currentTimeMillis()

fun main() {
    val server = embeddedServer(Netty, port = serverPort, host = "0.0.0.0", module = Application::module)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop(gracePeriodMillis = 5000, timeoutMillis = 10000)
        },
    )

    server.start(wait = true)
}

fun Application.module() {
    // Discover OpenCode free models in background (daemon thread, non-blocking).
    // The registry falls back to "opencode/auto" (daemon decides) until discovery completes.
    val modelDiscoveryThread =
        Thread {
            com.example.smarty.server.llm.OpencodeModelRegistry
                .discoverAtStartup()
        }
    modelDiscoveryThread.isDaemon = true
    modelDiscoveryThread.name = "model-discovery"
    modelDiscoveryThread.start()

    // Phase 1: LangChain4j Agent Foundation (feature-flagged)
    if (AppConfig.enableLangChain4j) {
        log.info("=== LangChain4j agent foundation enabled ===")
        val modelProvider = OpenRouterModelProvider()
        val ctxWindowManager = ContextWindowManager(modelProvider)
        val chatModelFactory = OpenRouterChatModelFactory(OpenRouterConfig())
        val chatMemoryStore = DatabaseFactory.getDataSource()?.let { PostgresChatMemoryStore { DatabaseFactory.getDataSource()?.connection } }

        // Discover models in background for context window cache
        val langchain4jDiscoveryThread = Thread {
            kotlinx.coroutines.runBlocking {
                modelProvider.getAllModels()
                log.info("[LangChain4j] Discovered ${modelProvider.getAllModels().size} models for context window management")
            }
        }
        langchain4jDiscoveryThread.isDaemon = true
        langchain4jDiscoveryThread.name = "langchain4j-model-discovery"
        langchain4jDiscoveryThread.start()

        // Stash on Application attributes for downstream wiring
        attributes.put(ApplicationAttributes.CONTEXT_WINDOW_MANAGER, ctxWindowManager)
        attributes.put(ApplicationAttributes.CHAT_MODEL_FACTORY, chatModelFactory)
        if (chatMemoryStore != null) {
            attributes.put(ApplicationAttributes.CHAT_MEMORY_STORE, chatMemoryStore)
        }
    }

    // Initialize Database
    DatabaseFactory.init()

    // Configure Security (Firebase JWT verification)
    configureSecurity()

    // Configure Firewall (IP restrictions, request limits)
    configureFirewall()

    // Configure CORS (Allow Android client + HF Spaces + common development origins)
    install(CORS) {
        val allowedOrigins = (
            System
                .getenv("ALLOWED_ORIGINS")
                ?.split(",")
                ?.map { it.trim() }
                ?.toSet()
                ?: setOf(
                    "http://localhost:7860",
                    "http://127.0.0.1:7860",
                    "https://huggingface.co",
                    "https://*.huggingface.co",
                )
        )
        for (origin in allowedOrigins) {
            val parts = origin.split("://", limit = 2)
            if (parts.size == 2) {
                allowHost(parts[1], schemes = listOf(parts[0]))
            } else {
                allowHost(origin)
            }
        }
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
        allowCredentials = true
    }
    install(CallId) {
        header("X-Request-ID")
        generate { UUID.randomUUID().toString() }
        verify { it.isNotEmpty() }
    }

    /*
    // Configure Call Logging
    // Legacy CallLogging disabled in favor of StructuredLogging
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

    // Enable response compression
    install(Compression) {
        gzip { }
        deflate { }
    }

    // Global exception handler — turns unhandled exceptions into clean JSON
    // 500s so HF Spaces' gateway never substitutes its HTML error page.
    install(StatusPages) {
        val statusLog = org.slf4j.LoggerFactory.getLogger("StatusPages")
        exception<Throwable> { call, cause ->
            statusLog.error("Unhandled exception in ${call.request.path()}: ${cause.message}", cause)
            call.respondText(
                text = """{"error":"Internal server error","message":"${cause.message?.replace("\"", "'") ?: "Unknown"}"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.InternalServerError,
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondText(
                text = """{"error":"Not found","path":"${call.request.path()}"}""",
                contentType = ContentType.Application.Json,
                status = status,
            )
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respondText(
                text = """{"error":"Method not allowed","method":"${call.request.httpMethod.value}","path":"${call.request.path()}"}""",
                contentType = ContentType.Application.Json,
                status = status,
            )
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.respondText(
                text = """{"error":"Authentication required"}""",
                contentType = ContentType.Application.Json,
                status = status,
            )
        }
    }

    // Configure SSE plugin for streaming
    install(SSE)

    // Configure WebSockets plugin for real-time bidirectional communication
    install(WebSockets) {
        pingPeriodMillis = 15000
        timeoutMillis = 15000
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    // Initialize core services — single pass, no duplicates
    val ds = DatabaseFactory.getDataSource()
    var digestService: DigestService? = null
    var digestScheduler: DigestScheduler? = null

    // PermissionRepository: backs the per-user `tool_permissions`
    // overrides and the `permission_audit_log`. The ToolPermissionEnforcer
    // consults it on every `permission.asked` event from the plugin
    // to decide whether to auto-respond (ALLOW/DENY) or forward to
    // the Android app (DEFAULT).
    val permissionRepository =
        com.example.smarty.server.data
            .PermissionRepository(ds)
    val toolPermissionEnforcer =
        com.example.smarty.server.agent.ToolPermissionEnforcer(
            policy = com.example.smarty.agent.permissions.ToolPermissionPolicy.SMARTY_DEFAULT,
            repository = permissionRepository,
        )
    // Stash on Application attributes so any route / tool can pull it
    // without needing constructor injection.
    attributes.put(
        com.example.smarty.server.agent.ApplicationAttributes.TOOL_PERMISSION_ENFORCER,
        toolPermissionEnforcer,
    )
    attributes.put(
        com.example.smarty.server.agent.ApplicationAttributes.PERMISSION_REPOSITORY,
        permissionRepository,
    )
    // Wire the audit-log writer into the ApprovalRegistry so that
    // every user-driven approval/denial decision (via the
    // /api/v1/chat/events/approval endpoint → resolveApproval) is
    // recorded in permission_audit_log with actor='user'.
    com.example.smarty.server.agent.ApprovalRegistry
        .setRepository(permissionRepository)

    if (ds != null) {
        val chatMessageNotesRepo = ChatMessageNotesRepository(ds)
        val calendarEventNotesRepo = CalendarEventNotesRepository(ds)
        val chatRepo = ChatRepository(ds, chatMessageNotesRepo)

        digestService =
            DigestService(
                dataSource = ds,
                chatRepository = chatRepo,
                vectorStore = PostgresVectorStore(),
                llmProvider = LlmProviderFactory.getOrCreateProvider(HttpClientSingleton.client),
            )

        val fcmService = FcmNotificationService.fromEnvironment(ds)
        digestScheduler =
            DigestScheduler(
                application = this,
                dataSource = ds,
                digestService = digestService,
                fcmService = fcmService,
            )
        digestScheduler.start()

        val noteRepo = NoteRepository(ds, chatMessageNotesRepo, calendarEventNotesRepo)
        val visionService = VisionService(HttpClientSingleton.client)

        val noteService =
            com.example.smarty.server.services.NoteService(
                noteRepo,
                com.example.smarty.server.agent
                    .NoteProcessingAgent(HttpClientSingleton.client),
                PostgresVectorStore(),
                com.example.smarty.server.services
                    .AdaptiveSearchService(),
            )

        // Services for files
        val fileProcessingService =
            FileProcessingService(
                visionService = visionService,
                httpClient = HttpClientSingleton.client,
            )

        val groqWhisperService =
            GroqWhisperService(
                httpClient = HttpClientSingleton.client,
                apiKey = System.getenv("GROQ_API_KEY") ?: "",
            )

        // Configure routes
        configureAuthRoutes()
        configureHealthRoutes()
        configureChatRoutes(noteService, fcmService)
        configureProcessingRoutes()
        configureHandshakeRoutes()
        configureDataRoutes(noteService)
        configureFileRoutes(groqWhisperService)

        val taskRepo = TaskRepository(ds)
        val tagRepo = TagRepository(ds)
        val notificationRepo = NotificationRepository(ds)
        val chatFolderRepo = ChatFolderRepository(ds)
        configureNewFeaturesRoutes(taskRepo, tagRepo, notificationRepo, chatFolderRepo)

        configureSyncRoutes()
        configureOptimizedSyncRoutes(noteService)

        val reasoningRepo = ReasoningTraceRepository(ds)
        val reasoningService = ReasoningService(reasoningRepo)
        routing { configureReasoningRoutes(reasoningService) }
        log.info("ReasoningRoutes configured with ReasoningService")

        val utilityService = UtilityService(LlmProviderFactory.getOrCreateProvider(HttpClientSingleton.client))
        configureUtilityRoutes(utilityService)
        log.info("UtilityRoutes configured")

        val orchestratorService =
            OrchestratorService(
                visionService = visionService,
                kreaImageTool =
                    com.example.smarty.server.tools
                        .KreaImageTool(),
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

        configurePermissionRoutes()
        log.info("PermissionRoutes configured")

        // Image serving endpoint (Firebase-authenticated)
        routing {
            authenticate("firebase") {
                get("/generated-images/{id}") {
                    val user = call.principal<FirebaseUserPrincipal>()
                    if (user == null) {
                        call.respondText(
                            "{\"error\":\"Authentication required\"}",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized,
                        )
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
                        call.respondText(
                            "{\"error\": \"Invalid image ID format\"}",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                        return@get
                    }

                    val dataSource = DatabaseFactory.getDataSource()
                    if (dataSource == null) {
                        call.respondText(
                            "{\"error\": \"Database not available\"}",
                            ContentType.Application.Json,
                            HttpStatusCode.ServiceUnavailable,
                        )
                        return@get
                    }

                    try {
                        val imageRepo = GeneratedImageRepository(dataSource)
                        val imageData = imageRepo.getImageBytes(imageId)

                        if (imageData == null) {
                            call.respondText("{\"error\": \"Image not found\"}", ContentType.Application.Json, HttpStatusCode.NotFound)
                            return@get
                        }

                        val (bytes, contentType) = imageData
                        val mimeType =
                            when {
                                contentType.contains("png") -> ContentType.Image.PNG
                                contentType.contains("jpg") || contentType.contains("jpeg") -> ContentType.Image.JPEG
                                contentType.contains("gif") -> ContentType.Image.GIF
                                contentType.contains("webp") -> ContentType("image", "webp")
                                else -> ContentType.Image.Any
                            }
                        call.respondBytes(bytes, mimeType)
                    } catch (e: Exception) {
                        call.application.log.error("Failed to serve image", e)
                        call.respondText(
                            "{\"error\": \"Failed to serve image\"}",
                            ContentType.Application.Json,
                            HttpStatusCode.InternalServerError,
                        )
                    }
                }
            }
        }

        configureDigestRoutes(digestService, digestScheduler, ds)
    } else {
        configureAuthRoutes()
        configureHealthRoutes()
        configureProcessingRoutes()
        configureHandshakeRoutes()
        configureSyncRoutes()
        configureOptimizedSyncRoutes()
        configureModelRoutes()
        configureChatRoutes(null)
    }

    // Configure Monitoring
    configureMonitoring()

    // Start background sweeper for stale sessions
    com.example.smarty.server.agent.ActiveSessionManager
        .startSweeper(this)

    // Registry Reaper: evict orphaned ApprovalRegistry + DeviceResponseRegistry entries
    // Per §7.1: ApprovalRegistry TTL=30min, DeviceResponseRegistry TTL=60s
    // Runs every 5 minutes as a non-blocking background coroutine.
    GlobalScope.launch(Dispatchers.IO) {
        val reaperLog = org.slf4j.LoggerFactory.getLogger("RegistryReaper")
        val toolSessionRepo =
            DatabaseFactory.getDataSource()?.let {
                com.example.smarty.server.data
                    .ToolSessionRepository(it)
            }
        while (true) {
            delay(5 * 60_000L) // 5 minutes
            try {
                val approvalEvicted =
                    com.example.smarty.server.agent.ApprovalRegistry
                        .evictExpired()
                val deviceEvicted =
                    com.example.smarty.server.agent.DeviceResponseRegistry
                        .evictExpired()
                val toolSessionsSwept = toolSessionRepo?.sweepExpired() ?: 0
                if (approvalEvicted > 0 || deviceEvicted > 0 || toolSessionsSwept > 0) {
                    reaperLog.info(
                        "[RegistryReaper] Evicted $approvalEvicted approval(s), $deviceEvicted device request(s), $toolSessionsSwept tool_session(s). Remaining: ${com.example.smarty.server.agent.ApprovalRegistry.size()} approvals, ${com.example.smarty.server.agent.DeviceResponseRegistry.size()} device requests.",
                    )
                }
            } catch (e: Exception) {
                reaperLog.error("[RegistryReaper] Error during eviction pass: ${e.message}", e)
            }
        }
    }

    // Configure Enhanced Health Check
    configureEnhancedHealthCheck()

    // Log startup
    log.info("Friday Server started on port $serverPort")
    if (digestScheduler != null) {
        log.info("Digest Scheduler started - daily digests will be generated at configured times")
    } else {
        log.warn("Database not configured - Digest Scheduler disabled")
    }

    // Graceful shutdown: stop background jobs and close database pool
    monitor.subscribe(ApplicationStopping) {
        log.info("Server stopping — cleaning up resources")
        digestScheduler?.stop()
        com.example.smarty.server.agent.AgentRunManager
            .shutdown()
        DatabaseFactory.close()
        log.info("Server shutdown complete")
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
