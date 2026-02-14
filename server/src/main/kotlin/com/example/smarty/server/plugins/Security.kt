package com.example.smarty.server.plugins

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory
import java.io.FileInputStream

/**
 * Principal representing an authenticated Firebase user.
 */
data class FirebaseUserPrincipal(
    val userId: String,
    val email: String?,
    val displayName: String?,
    val deviceId: String? = null
) : Principal

/**
 * Initializes Firebase Admin SDK from service account credentials.
 * Looks for credentials in:
 * 1. GOOGLE_APPLICATION_CREDENTIALS environment variable
 * 2. server/src/main/resources/firebase-service-account.json
 */
fun initializeFirebase() {
    val logger = LoggerFactory.getLogger("FirebaseInit")

    // Skip if already initialized
    if (FirebaseApp.getApps().isNotEmpty()) {
        logger.info("Firebase already initialized")
        return
    }

    val credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS")

    val options = if (!credentialsPath.isNullOrBlank()) {
        logger.info("Initializing Firebase with service account from: $credentialsPath")
        try {
            FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(FileInputStream(credentialsPath)))
                .build()
        } catch (e: Exception) {
            logger.warn("Failed to load Firebase credentials from path: ${e.message}")
            logger.info("Firebase auth will be disabled - all requests will be allowed")
            return
        }
    } else {
        logger.warn("GOOGLE_APPLICATION_CREDENTIALS not set. Firebase auth disabled.")
        logger.info("Set GOOGLE_APPLICATION_CREDENTIALS to enable Firebase JWT verification")
        return
    }

    try {
        FirebaseApp.initializeApp(options)
        logger.info("Firebase Admin SDK initialized successfully")
    } catch (e: Exception) {
        logger.error("Failed to initialize Firebase: ${e.message}")
    }
}

/**
 * Verifies a Firebase ID token and returns the user principal.
 */
fun verifyFirebaseToken(token: String, deviceId: String?): FirebaseUserPrincipal? {
    val logger = LoggerFactory.getLogger("FirebaseAuth")

    // If Firebase is not initialized, check for explicit dev mode override
    val allowDevAuth = System.getenv("ALLOW_UNSECURE_DEV_AUTH")?.toBoolean() ?: false

    if (FirebaseApp.getApps().isEmpty()) {
        if (allowDevAuth) {
            logger.warn("Firebase not initialized, using development mode (ALLOW_UNSECURE_DEV_AUTH=true)")
            return FirebaseUserPrincipal(
                userId = "dev-user",
                email = "dev@localhost",
                displayName = "Development User",
                deviceId = deviceId ?: "dev-device"
            )
        } else {
            logger.error("Firebase not initialized and ALLOW_UNSECURE_DEV_AUTH is not set. Blocking request.")
            return null
        }
    }

    return try {
        val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
        FirebaseUserPrincipal(
            userId = decodedToken.uid,
            email = decodedToken.email,
            displayName = decodedToken.name,
            deviceId = deviceId
        )
    } catch (e: FirebaseAuthException) {
        logger.warn("Firebase token verification failed: ${e.message}")
        null
    } catch (e: Exception) {
        logger.error("Unexpected error verifying Firebase token: ${e.message}")
        null
    }
}

/**
 * Custom auth provider for development mode that always authenticates
 * without requiring any Authorization header.
 */
private class DevModeAuthProvider(config: Config) : AuthenticationProvider(config) {
    class Config(name: String) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val deviceId = context.call.request.header("X-Smarty-Device-Id")
        context.principal(
            FirebaseUserPrincipal(
                userId = "dev-user",
                email = "dev@localhost",
                displayName = "Development User",
                deviceId = deviceId ?: "dev-device"
            )
        )
    }
}

/**
 * Configures Firebase JWT authentication for the Ktor application.
 * In dev mode (Firebase not initialized + ALLOW_UNSECURE_DEV_AUTH=true),
 * all requests are auto-authenticated as a dev user.
 */
fun Application.configureSecurity() {
    val logger = LoggerFactory.getLogger("Security")

    // Initialize Firebase Admin SDK
    initializeFirebase()

    val isDevMode = FirebaseApp.getApps().isEmpty() &&
        System.getenv("ALLOW_UNSECURE_DEV_AUTH")?.toBoolean() == true

    install(Authentication) {
        if (isDevMode) {
            logger.warn("=== DEV MODE: All requests auto-authenticated as dev-user ===")
            register(DevModeAuthProvider(DevModeAuthProvider.Config("firebase")))
        } else {
            bearer("firebase") {
                realm = "Smarty API"
                authenticate { credential ->
                    val deviceId = this.request.header("X-Smarty-Device-Id")
                    verifyFirebaseToken(credential.token, deviceId)
                }
            }
        }
    }

    logger.info("Security plugin configured (devMode=$isDevMode)")
}

/**
 * Extension to get the authenticated user from the application call.
 */
fun ApplicationCall.firebaseUser(): FirebaseUserPrincipal? {
    return principal<FirebaseUserPrincipal>()
}

/**
 * Extension to require authenticated user, responding with 401 if not authenticated.
 */
suspend fun ApplicationCall.requireFirebaseUser(): FirebaseUserPrincipal? {
    val user = firebaseUser()
    if (user == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
    }
    return user
}
