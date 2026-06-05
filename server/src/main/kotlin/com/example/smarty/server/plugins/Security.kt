package com.example.smarty.server.plugins

import com.example.smarty.server.data.DatabaseFactory
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

// =========================================================================
// AUTH DISABLED — HF Space is public for live testing of OpenCode streaming.
// All Firebase init + token verification is a no-op. Re-add ONLY with explicit
// user instruction. See AGENTS.md "Auth State".
// =========================================================================

/**
 * Principal representing an authenticated Firebase user.
 * Kept as a data class so route code that reads `call.firebaseUser()` still
 * compiles and returns a non-null stub.
 */
data class FirebaseUserPrincipal(
    val userId: String,
    val email: String?,
    val displayName: String?,
    val deviceId: String? = null,
) {
    override fun toString(): String = "FirebaseUserPrincipal(userId=$userId, email=$email)"
}

/**
 * Global flag to track if Firebase is properly initialized.
 * Always returns true now — kept for API compatibility.
 */
object FirebaseStatus {
    @Volatile
    var isInitialized: Boolean = true
        private set

    fun markInitialized() {
        isInitialized = true
    }

    fun markNotInitialized() {
        isInitialized = false
    }
}

/**
 * Determines if the application is running in production environment.
 */
fun isProductionEnvironment(): Boolean {
    return System.getenv("ENVIRONMENT")?.lowercase() == "production" ||
        System.getenv("K_SERVICE") != null ||
        System.getenv("HUGGINGFACE_SPACES") == "1"
}

/**
 * AUTH DISABLED — no-op stub. Original logic commented out below for restore.
 * All calls return immediately. FirebaseStatus is marked initialized so other
 * checks that gate on it (e.g. production safety checks) pass through.
 */
fun initializeFirebase() {
    val logger = LoggerFactory.getLogger("FirebaseInit")
    logger.warn("[AUTH_DISABLED] initializeFirebase() is a no-op — server is running without Firebase auth.")
    FirebaseStatus.markInitialized()
    return

    // ---- ORIGINAL CODE (COMMENTED OUT, UNREACHABLE) ----
    /*
    val isProduction = isProductionEnvironment()
    if (FirebaseApp.getApps().isNotEmpty()) { ... }
    // ... full original implementation, see git history ...
    */
}

/**
 * Admin-only whitelist: only this email can access the server.
 * Kept as a constant so old route code that imports it still compiles.
 */
const val ADMIN_EMAIL = "forpblcusz@gmail.com"

fun isAdminEmail(email: String?): Boolean = email == ADMIN_EMAIL

/**
 * Configures Firebase JWT authentication for the Ktor application.
 *
 * AUTH DISABLED — bearer("firebase") is a no-op stub. Any bearer token
 * (or even no token) returns a stub principal. See AGENTS.md "Auth State".
 */
fun Application.configureSecurity() {
    val logger = LoggerFactory.getLogger("Security")

    initializeFirebase()

    install(Authentication) {
        bearer("firebase") {
            realm = "Smarty API"
            authenticate { credential ->
                logger.debug("[AUTH_DISABLED] bearer('firebase') no-op — token=${credential.token.take(8)}...")
                FirebaseUserPrincipal(
                    userId = "anonymous",
                    email = ADMIN_EMAIL,
                    displayName = "Auth Disabled",
                )
            }
        }
    }

    logger.warn("=".repeat(80))
    logger.warn("[AUTH_DISABLED] Security plugin configured as a no-op stub.")
    logger.warn("All requests with ANY bearer token are accepted as ADMIN_EMAIL.")
    logger.warn("=".repeat(80))
}

/**
 * Extension to get the authenticated user from the application call.
 */
fun ApplicationCall.firebaseUser(): FirebaseUserPrincipal? = principal<FirebaseUserPrincipal>()

/**
 * Extension to require authenticated user, responding with 401 if not authenticated.
 * Now always returns the stub principal because the no-op auth provider always succeeds.
 */
suspend fun ApplicationCall.requireFirebaseUser(): FirebaseUserPrincipal? = firebaseUser()
