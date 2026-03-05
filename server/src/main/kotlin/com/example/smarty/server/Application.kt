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
import com.example.smarty.server.routes.configureHandshakeRoutes
import com.example.smarty.server.routes.configureDataRoutes
import com.example.smarty.server.services.DigestService
import com.example.smarty.server.services.DigestScheduler
import com.example.smarty.server.services.FcmNotificationService
import com.example.smarty.server.data.ChatRepository
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.llm.LlmProviderFactory
import com.example.smarty.server.routes.configureDigestRoutes

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

    // Configure CORS
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
    }

    // Configure Call ID
    install(CallId) {
        header("X-Request-ID")
        generate { UUID.randomUUID().toString() }
        verify { it.isNotEmpty() }
    }

    // Configure Call Logging
    /*
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
        callIdMdc("trace_id")
    }
    */

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
        digestService = DigestService(
            dataSource = ds,
            chatRepository = ChatRepository(ds),
            vectorStore = PostgresVectorStore(),
            llmProvider = LlmProviderFactory.create(io.ktor.client.HttpClient())
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
    configureSyncRoutes()
    if (digestService != null && digestScheduler != null && ds != null) {
        configureDigestRoutes(digestService, digestScheduler, ds)
    }

    // Configure Monitoring
    configureMonitoring()

    // Log startup
    log.info("Friday Server started on port $serverPort")
    if (digestScheduler != null) {
        log.info("Digest Scheduler started - daily digests will be generated at configured times")
    } else {
        log.warn("Database not configured - Digest Scheduler disabled")
    }
}
