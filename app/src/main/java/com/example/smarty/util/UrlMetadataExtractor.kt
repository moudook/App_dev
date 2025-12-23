package com.example.smarty.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Extracts metadata (title, description, image) from URLs.
 * Used for web clipper functionality when sharing URLs to the app.
 */
object UrlMetadataExtractor {
    private const val TAG = "UrlMetadataExtractor"
    private const val TIMEOUT_MS = 5000L

    // Lazy-initialized OkHttpClient with short timeouts
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // Regex patterns for extracting metadata
    private val titlePattern = Pattern.compile(
        "<title[^>]*>([^<]+)</title>",
        Pattern.CASE_INSENSITIVE
    )
    private val ogTitlePattern = Pattern.compile(
        "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    )
    private val ogDescriptionPattern = Pattern.compile(
        "<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    )
    private val metaDescriptionPattern = Pattern.compile(
        "<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    )
    private val ogImagePattern = Pattern.compile(
        "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    )

    // URL pattern for detection
    private val urlPattern = Pattern.compile(
        "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Extract URL from text if present
     */
    fun extractUrl(text: String): String? {
        val matcher = urlPattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    /**
     * Check if text contains a URL
     */
    fun containsUrl(text: String): Boolean {
        return urlPattern.matcher(text).find()
    }

    /**
     * Fetch metadata from a URL
     */
    suspend fun fetchMetadata(url: String): UrlMetadata? = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(TIMEOUT_MS) {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (compatible; CogniApp/1.0)")
                    .header("Accept", "text/html")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to fetch URL: $url, code: ${response.code}")
                    return@withTimeoutOrNull null
                }

                // Read only first 50KB to avoid memory issues
                val body = response.body?.source()?.let { source ->
                    val buffer = okio.Buffer()
                    source.read(buffer, 50 * 1024)
                    buffer.readUtf8()
                }

                response.close()

                if (body.isNullOrBlank()) {
                    Log.w(TAG, "Empty response body for URL: $url")
                    return@withTimeoutOrNull null
                }

                // Extract metadata
                val title = extractTitle(body)
                val description = extractDescription(body)
                val imageUrl = extractImage(body)
                val domain = extractDomain(url)

                if (title == null && description == null) {
                    Log.w(TAG, "No metadata found for URL: $url")
                    return@withTimeoutOrNull null
                }

                UrlMetadata(
                    url = url,
                    title = cleanHtmlEntities(title) ?: domain ?: url,
                    description = cleanHtmlEntities(description),
                    imageUrl = imageUrl,
                    domain = domain
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching metadata for $url: ${e.message}")
            null
        }
    }

    private fun extractTitle(html: String): String? {
        // Try OG title first
        val ogMatcher = ogTitlePattern.matcher(html)
        if (ogMatcher.find()) {
            return ogMatcher.group(1)?.trim()
        }

        // Fall back to regular title
        val titleMatcher = titlePattern.matcher(html)
        if (titleMatcher.find()) {
            return titleMatcher.group(1)?.trim()
        }

        return null
    }

    private fun extractDescription(html: String): String? {
        // Try OG description first
        val ogMatcher = ogDescriptionPattern.matcher(html)
        if (ogMatcher.find()) {
            return ogMatcher.group(1)?.trim()?.take(300)
        }

        // Fall back to meta description
        val metaMatcher = metaDescriptionPattern.matcher(html)
        if (metaMatcher.find()) {
            return metaMatcher.group(1)?.trim()?.take(300)
        }

        return null
    }

    private fun extractImage(html: String): String? {
        val matcher = ogImagePattern.matcher(html)
        return if (matcher.find()) matcher.group(1)?.trim() else null
    }

    private fun extractDomain(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            uri.host?.removePrefix("www.")
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanHtmlEntities(text: String?): String? {
        if (text == null) return null
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }

    /**
     * Data class for URL metadata
     */
    data class UrlMetadata(
        val url: String,
        val title: String,
        val description: String?,
        val imageUrl: String?,
        val domain: String?
    )
}
