package com.example.smarty.agent.tools.external

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class OpenAppArgs(
    @property:LLMDescription("Name of the app to open (e.g., 'YouTube', 'Instagram', 'WhatsApp', 'Camera')")
    val appName: String,
    @property:LLMDescription("Optional: specific action to perform after opening (not always supported)")
    val action: String? = null
)

@Serializable
data class OpenAppResult(
    val success: Boolean,
    val appName: String,
    val packageName: String? = null,
    val message: String,
    val error: String? = null
)

/**
 * Tool for opening installed apps by name.
 * Uses intelligent fuzzy matching to find apps even with partial names or aliases.
 */
class OpenAppTool(
    private val context: Context,
    private val onLaunchApp: (String) -> Unit
) : Tool<OpenAppArgs, OpenAppResult>(
    argsSerializer = OpenAppArgs.serializer(),
    resultSerializer = OpenAppResult.serializer(),
    name = "open_app",
    description = """
        Opens an installed Android app by name.
        Use this when user wants to launch/open/start an application.

        Examples:
        - "open YouTube" -> appName="YouTube"
        - "launch Instagram" -> appName="Instagram"
        - "open the camera" -> appName="Camera"
        - "start WhatsApp" -> appName="WhatsApp"
        - "open settings" -> appName="Settings"

        Supports common aliases like:
        - "yt" for YouTube
        - "ig" or "insta" for Instagram
        - "fb" for Facebook
        - "wa" for WhatsApp
    """.trimIndent()
) {
    companion object {
        private const val TAG = "OpenAppTool"
    }

    override suspend fun execute(args: OpenAppArgs): OpenAppResult {
        Log.d(TAG, "Opening app: '${args.appName}'")

        if (args.appName.isBlank()) {
            return OpenAppResult(
                success = false,
                appName = args.appName,
                message = "App name cannot be empty",
                error = "Empty app name"
            )
        }

        return try {
            val installedApps = getInstalledApps()
            if (installedApps.isEmpty()) {
                return OpenAppResult(
                    success = false,
                    appName = args.appName,
                    message = "Could not find any installed apps",
                    error = "No apps found"
                )
            }

            val match = findBestAppMatch(args.appName, installedApps)

            if (match != null) {
                val (packageName, appLabel, confidence) = match
                Log.d(TAG, "Found match: $appLabel ($packageName) confidence=$confidence")

                onLaunchApp(packageName)

                OpenAppResult(
                    success = true,
                    appName = appLabel,
                    packageName = packageName,
                    message = if (confidence >= 0.8f) "Opening $appLabel" else "Opening $appLabel (best match)"
                )
            } else {
                OpenAppResult(
                    success = false,
                    appName = args.appName,
                    message = "Could not find an app matching '${args.appName}'",
                    error = "App not found"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app: ${e.message}", e)
            OpenAppResult(
                success = false,
                appName = args.appName,
                message = "Failed to open app: ${e.message}",
                error = e.message
            )
        }
    }

    private fun getInstalledApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        return pm.queryIntentActivities(mainIntent, 0).mapNotNull { resolveInfo ->
            try {
                val packageName = resolveInfo.activityInfo.packageName
                val appName = resolveInfo.loadLabel(pm).toString()
                packageName to appName
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.first }
    }

    private fun findBestAppMatch(
        query: String,
        apps: List<Pair<String, String>>
    ): Triple<String, String, Float>? {
        if (apps.isEmpty()) return null

        val normalizedQuery = query.lowercase(Locale.getDefault()).trim()
        val cleanQuery = normalizedQuery
            .removeSuffix(" app")
            .removeSuffix(" application")
            .trim()

        // 1. Try known package prefixes (most reliable)
        val packageMatch = findByPackagePrefix(cleanQuery, apps)
        if (packageMatch != null) {
            return Triple(packageMatch.first, packageMatch.second, 1.0f)
        }

        // 2. Exact name match
        apps.find { it.second.lowercase(Locale.getDefault()) == cleanQuery }?.let {
            return Triple(it.first, it.second, 1.0f)
        }

        // 3. Name starts with query
        apps.find { it.second.lowercase(Locale.getDefault()).startsWith(cleanQuery) }?.let {
            return Triple(it.first, it.second, 0.95f)
        }

        // 4. Name contains query (min 3 chars)
        if (cleanQuery.length >= 3) {
            apps.find { it.second.lowercase(Locale.getDefault()).contains(cleanQuery) }?.let {
                return Triple(it.first, it.second, 0.9f)
            }
        }

        // 5. Package name contains query
        if (cleanQuery.length >= 3) {
            apps.find { it.first.lowercase(Locale.getDefault()).contains(cleanQuery) }?.let {
                return Triple(it.first, it.second, 0.85f)
            }
        }

        // 6. Word-based matching
        val queryWords = cleanQuery.split(" ", "-", "_").filter { it.length >= 2 }
        if (queryWords.isNotEmpty()) {
            val wordMatch = apps.maxByOrNull { (pkg, name) ->
                val lowerName = name.lowercase(Locale.getDefault())
                val lowerPkg = pkg.lowercase(Locale.getDefault())
                queryWords.count { lowerName.contains(it) || lowerPkg.contains(it) }
            }
            wordMatch?.let { (pkg, name) ->
                val lowerName = name.lowercase(Locale.getDefault())
                val lowerPkg = pkg.lowercase(Locale.getDefault())
                val matchCount = queryWords.count { lowerName.contains(it) || lowerPkg.contains(it) }
                if (matchCount > 0) {
                    return Triple(pkg, name, (matchCount.toFloat() / queryWords.size) * 0.8f)
                }
            }
        }

        // 7. Fuzzy similarity matching
        val scored = apps.map { (pkg, name) ->
            Triple(pkg, name, calculateSimilarity(cleanQuery, name.lowercase(Locale.getDefault())))
        }.filter { it.third > 0.4f }
            .sortedByDescending { it.third }

        return scored.firstOrNull()?.let { Triple(it.first, it.second, it.third * 0.7f) }
    }

    private fun findByPackagePrefix(query: String, apps: List<Pair<String, String>>): Pair<String, String>? {
        val prefixes = getPackagePrefixes(query)
        for (prefix in prefixes) {
            apps.find { (pkg, _) ->
                pkg.lowercase(Locale.getDefault()).startsWith(prefix) ||
                pkg.lowercase(Locale.getDefault()).contains(".$prefix.")
            }?.let { return it }
        }
        return null
    }

    private fun getPackagePrefixes(query: String): List<String> {
        return when (query) {
            "youtube", "yt", "you tube" -> listOf("com.google.android.youtube")
            "instagram", "insta", "ig" -> listOf("com.instagram")
            "facebook", "fb" -> listOf("com.facebook.katana", "com.facebook")
            "whatsapp", "whats app", "wa" -> listOf("com.whatsapp")
            "twitter", "x", "tw" -> listOf("com.twitter", "com.x")
            "telegram", "tg" -> listOf("org.telegram")
            "snapchat", "snap" -> listOf("com.snapchat")
            "tiktok", "tik tok", "tt" -> listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
            "chrome" -> listOf("com.android.chrome")
            "gmail", "email", "mail" -> listOf("com.google.android.gm")
            "maps", "google maps" -> listOf("com.google.android.apps.maps")
            "photos", "google photos", "gallery" -> listOf("com.google.android.apps.photos", "com.sec.android.gallery")
            "camera", "cam" -> listOf("camera", "com.android.camera", "com.google.android.GoogleCamera")
            "settings" -> listOf("com.android.settings")
            "clock", "alarm" -> listOf("com.google.android.deskclock")
            "calculator", "calc" -> listOf("com.google.android.calculator")
            "calendar" -> listOf("com.google.android.calendar")
            "messages", "sms", "text" -> listOf("com.google.android.apps.messaging")
            "phone", "dialer", "call" -> listOf("com.google.android.dialer")
            "play store", "store" -> listOf("com.android.vending")
            "spotify", "music" -> listOf("com.spotify.music")
            "netflix" -> listOf("com.netflix")
            "discord" -> listOf("com.discord")
            "reddit" -> listOf("com.reddit")
            else -> emptyList()
        }
    }

    private fun calculateSimilarity(s1: String, s2: String): Float {
        if (s1.isEmpty() || s2.isEmpty()) return 0f
        if (s1 == s2) return 1f

        var matches = 0
        var s2Index = 0
        for (char in s1) {
            val foundIndex = s2.indexOf(char, s2Index)
            if (foundIndex >= 0) {
                matches++
                s2Index = foundIndex + 1
            }
        }
        return (2f * matches) / (s1.length + s2.length)
    }
}
