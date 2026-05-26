package com.example.smarty.server.plugins

import com.example.smarty.server.data.DatabaseFactory
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
    val deviceId: String? = null,
) {
    override fun toString(): String = "FirebaseUserPrincipal(userId=$userId, email=$email)"
}

/**
 * Global flag to track if Firebase is properly initialized.
 * Used for security checks throughout the application.
 */
object FirebaseStatus {
    @Volatile
    var isInitialized: Boolean = false
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
        System.getenv("K_SERVICE") != null || // Cloud Run
        System.getenv("CF_PAGES") == "1" || // Cloudflare Pages
        System.getenv("HUGGINGFACE_SPACES") == "1" // Hugging Face Spaces
}

/**
 * Initializes Firebase Admin SDK from service account credentials.
 * Looks for credentials in:
 * 1. FIREBASE_CREDENTIALS environment variable (Raw JSON string)
 * 2. GOOGLE_APPLICATION_CREDENTIALS environment variable (File path)
 * 3. server/src/main/resources/firebase-service-account.json
 *
 * SECURITY: In production, this will FAIL if credentials are not provided.
 */
fun initializeFirebase() {
    val logger = LoggerFactory.getLogger("FirebaseInit")
    val isProduction = isProductionEnvironment()

    // Skip if already initialized
    if (FirebaseApp.getApps().isNotEmpty()) {
        logger.info("Firebase already initialized")
        FirebaseStatus.markInitialized()
        return
    }

    val credentialsJson = System.getenv("FIREBASE_CREDENTIALS")
    val credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS")

    // SECURITY CHECK: In production, require Firebase credentials
    if (isProduction && credentialsJson.isNullOrBlank() && credentialsPath.isNullOrBlank()) {
        logger.error("=".repeat(80))
        logger.error("CRITICAL SECURITY ERROR: Firebase credentials required in production!")
        logger.error("=".repeat(80))
        logger.error("Firebase is not initialized and we are running in PRODUCTION environment.")
        logger.error("This is a critical security requirement - authentication cannot be bypassed.")
        logger.error("")
        logger.error("To fix this, set ONE of the following environment variables:")
        logger.error("  1. FIREBASE_CREDENTIALS=<service_account_json>")
        logger.error("  2. GOOGLE_APPLICATION_CREDENTIALS=/path/to/service_account.json")
        logger.error("")
        logger.error("Shutting down to prevent insecure operation.")
        logger.error("=".repeat(80))
        FirebaseStatus.markNotInitialized()
        throw IllegalStateException("Firebase credentials required in production - shutting down for security")
    }

    val options =
        if (!credentialsJson.isNullOrBlank()) {
            logger.info("Initializing Firebase with raw JSON from FIREBASE_CREDENTIALS")
            try {
                FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(credentialsJson.byteInputStream()))
                    .build()
            } catch (e: Exception) {
                logger.error("Failed to load Firebase credentials from JSON string: ${e.message}")
                FirebaseStatus.markNotInitialized()
                if (isProduction) {
                    throw IllegalStateException("Invalid Firebase credentials in production", e)
                }
                return
            }
        } else if (!credentialsPath.isNullOrBlank()) {
            logger.info("Initializing Firebase with service account from: $credentialsPath")
            try {
                FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(FileInputStream(credentialsPath)))
                    .build()
            } catch (e: Exception) {
                logger.error("Failed to load Firebase credentials from path: ${e.message}")
                FirebaseStatus.markNotInitialized()
                if (isProduction) {
                    throw IllegalStateException("Invalid Firebase credentials in production", e)
                }
                return
            }
        } else {
            // Only allow Firebase to be disabled in NON-production environments
            if (!isProduction) {
                logger.warn("=".repeat(60))
                logger.warn("FIREBASE DISABLED - Running without authentication")
                logger.warn("=".repeat(60))
                logger.warn("This is ONLY allowed in non-production environments.")
                logger.warn("All API requests will return 401 Unauthorized.")
                logger.warn("Set FIREBASE_CREDENTIALS to enable authentication.")
                logger.warn("=".repeat(60))
            } else {
                // This branch should never be reached due to the check above
                logger.error("Firebase credentials missing in production - shutting down")
                FirebaseStatus.markNotInitialized()
                throw IllegalStateException("Firebase credentials required in production")
            }

            FirebaseStatus.markNotInitialized()
            return
        }

    try {
        FirebaseApp.initializeApp(options)
        logger.info("Firebase Admin SDK initialized successfully")
        FirebaseStatus.markInitialized()
    } catch (e: Exception) {
        logger.error("Failed to initialize Firebase: ${e.message}")
        FirebaseStatus.markNotInitialized()
        if (isProduction) {
            throw IllegalStateException("Failed to initialize Firebase in production", e)
        }
    }
}

/**
 * Verifies a Firebase ID token and returns the user principal.
 * Also ensures the user exists in the database.
 *
 * SECURITY: This function NEVER allows bypassing authentication.
 * - In production: ALWAYS requires valid Firebase token
 * - In development: Returns null if Firebase not initialized (caller must handle 401)
 */
fun verifyFirebaseToken(
    token: String,
    deviceId: String?,
): FirebaseUserPrincipal? {
    val logger = LoggerFactory.getLogger("FirebaseAuth")

    // SECURITY: NEVER allow dev mode bypass - removed ALLOW_UNSECURE_DEV_AUTH check
    if (FirebaseApp.getApps().isEmpty()) {
        logger.error("Firebase not initialized - cannot verify token. All requests will be rejected.")
        return null
    }

    return try {
        val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
        val firebaseUid = decodedToken.uid
        val email = decodedToken.email
        val displayName = decodedToken.name

        // Ensure user exists in database and get their UUID user_id
        val userId = ensureUserExistsInDatabase(firebaseUid, email, displayName)

        // Create FirebaseUserPrincipal with UUID user_id
        val principal =
            FirebaseUserPrincipal(
                userId = userId,
                email = email,
                displayName = displayName,
                deviceId = deviceId,
            )

        return principal
    } catch (e: FirebaseAuthException) {
        logger.warn("Firebase token verification failed: ${e.message}")
        throw e // Re-throw to allow specific handling in auth routes
    } catch (e: IllegalStateException) {
        logger.warn("Single user restriction: ${e.message}")
        throw e // Re-throw for specific handling
    } catch (e: Exception) {
        logger.error("Unexpected error verifying Firebase token: ${e.message}")
        throw e
    }
}

/**
 * Ensures a user exists in the database. Creates the user record if it doesn't exist.
 * Returns the user's UUID user_id.
 */
private fun ensureUserExistsInDatabase(
    firebaseUid: String,
    email: String?,
    displayName: String?,
): String {
    val ds = DatabaseFactory.getDataSource()
    if (ds == null) {
        LoggerFactory.getLogger("FirebaseAuth").warn("DataSource not available, skipping user creation")
        throw IllegalStateException("DataSource not available")
    }

    val logger = LoggerFactory.getLogger("FirebaseAuth")

    ds.connection.use { conn ->
        // Always SELECT first to get the id if it exists
        val checkSql = "SELECT id FROM users WHERE firebase_uid = ?"
        conn.prepareStatement(checkSql).use { stmt ->
            stmt.setString(1, firebaseUid)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val userId = rs.getString("id")
                    logger.info("Resolved user for Firebase UID: {}, User ID: {}", firebaseUid, userId)
                    return userId
                }
            }
        }

        // Single-user check: if the user DOES NOT exist, check if ANY user exists
        val countSql = "SELECT COUNT(*) as user_count FROM users"
        conn.prepareStatement(countSql).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val count = rs.getInt("user_count")
                    if (count > 0) {
                        logger.error("Single-user restriction: Blocked secondary user creation for {}", email)
                        throw IllegalStateException("There is already an existing user. Please use that Google account.")
                    }
                }
            }
        }

        // Atomic upsert: INSERT ... ON CONFLICT DO NOTHING eliminates the TOCTOU race condition
        val insertSql =
            """
            INSERT INTO users (firebase_uid, email, display_name, created_at, updated_at)
            VALUES (?, ?, ?, NOW(), NOW())
            ON CONFLICT (firebase_uid) DO NOTHING
            """.trimIndent()
        conn.prepareStatement(insertSql).use { stmt ->
            stmt.setString(1, firebaseUid)
            stmt.setString(2, email)
            stmt.setString(3, displayName)
            val rowsAffected = stmt.executeUpdate()
            if (rowsAffected > 0) {
                logger.info("Created user record for Firebase UID: {}", firebaseUid)
            }
        }

        // Always SELECT to get the id — works whether the row was just inserted or already existed
        val selectSql = "SELECT id FROM users WHERE firebase_uid = ?"
        conn.prepareStatement(selectSql).use { stmt ->
            stmt.setString(1, firebaseUid)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    val userId = rs.getString("id")
                    logger.info("Resolved user for Firebase UID: {}, User ID: {}", firebaseUid, userId)
                    return userId
                }
            }
        }

        throw IllegalStateException("Failed to find or create user record for Firebase UID: $firebaseUid")
    }
}

/**
 * Configures Firebase JWT authentication for the Ktor application.
 *
 * SECURITY CHANGES:
 * - REMOVED: DevModeAuthProvider (allowed complete auth bypass)
 * - REMOVED: ALLOW_UNSECURE_DEV_AUTH environment variable support
 * - ADDED: Production safety checks
 * - ADDED: Firebase initialization requirement
 */
fun Application.configureSecurity() {
    val logger = LoggerFactory.getLogger("Security")
    val isProduction = isProductionEnvironment()

    // Initialize Firebase Admin SDK (will throw in production if credentials missing)
    initializeFirebase()

    // SECURITY CHECK: Verify Firebase is initialized in production
    if (isProduction && !FirebaseStatus.isInitialized) {
        logger.error("=".repeat(80))
        logger.error("CRITICAL: Firebase not initialized in production!")
        logger.error("Shutting down to prevent security breach.")
        logger.error("=".repeat(80))
        throw IllegalStateException("Firebase must be initialized in production")
    }

    install(Authentication) {
        // SECURITY: REMOVED DevModeAuthProvider - NO MORE AUTH BYPASS
        // All environments now require proper Firebase authentication

        bearer("firebase") {
            realm = "Smarty API"
            authenticate { credential ->
                try {
                    val deviceId = this.request.header("X-Smarty-Device-Id")
                    verifyFirebaseToken(credential.token, deviceId)
                } catch (e: Exception) {
                    null // Let Ktor return 401 for standard authenticated routes
                }
            }
        }
    }

    if (FirebaseStatus.isInitialized) {
        logger.info("Security plugin configured - Firebase authentication ENABLED")
        if (isProduction) {
            logger.info("Running in PRODUCTION mode - all requests require valid authentication")
        }
    } else {
        logger.warn("=".repeat(60))
        logger.warn("SECURITY WARNING: Firebase authentication DISABLED")
        logger.warn("All API requests will return 401 Unauthorized")
        logger.warn("This should ONLY happen in local development")
        logger.warn("=".repeat(60))
    }
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
        respondText(
            text = """{"error":"Authentication required. Valid Firebase token must be provided."}""",
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.Unauthorized,
        )
    }
    return user
}
