package com.example.smarty.core.common.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.regex.Pattern

/**
 * Extracts metadata (title, description, image) from URLs.
 * Used for web clipper functionality when sharing URLs to the app.
 */
object UrlMetadataExtractor {
    private const val TAG = "UrlMetadataExtractor"
    private const val TIMEOUT_MS = 5000L

    // Use shared HttpClient to prevent connection pool exhaustion
    private val client: OkHttpClient
        get() = HttpClientProvider.quick

    // Regex patterns for extracting metadata
    private val titlePattern =
        Pattern.compile(
            "<title[^>]*>([^<]+)</title>",
            Pattern.CASE_INSENSITIVE,
        )
    private val ogTitlePattern =
        Pattern.compile(
            "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE,
        )
    private val ogDescriptionPattern =
        Pattern.compile(
            "<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE,
        )
    private val metaDescriptionPattern =
        Pattern.compile(
            "<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE,
        )
    private val ogImagePattern =
        Pattern.compile(
            "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE,
        )

    // URL pattern for detection
    private val urlPattern =
        Pattern.compile(
            "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
            Pattern.CASE_INSENSITIVE,
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
    suspend fun fetchMetadata(url: String): UrlMetadata? =
        withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    val request =
                        Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (compatible; SmartyApp/1.0)")
                            .header("Accept", "text/html")
                            .build()

                    // Use .use{} to ensure response is always closed (fixes memory leak)
                    val body =
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                Log.w(TAG, "Failed to fetch URL: $url, code: ${response.code}")
                                return@withTimeoutOrNull null
                            }

                            // Read up to 200KB for article extraction (increased from 50KB)
                            response.body.source().let { source ->
                                val buffer = okio.Buffer()
                                source.read(buffer, 200 * 1024)
                                buffer.readUtf8()
                            }
                        }

                    if (body.isNullOrBlank()) {
                        Log.w(TAG, "Empty response body for URL: $url")
                        return@withTimeoutOrNull null
                    }

                    // Extract metadata
                    val title = extractTitle(body)
                    val description = extractDescription(body)
                    val imageUrl = extractImage(body)
                    val domain = extractDomain(url)

                    // READER MODE: Extract full article text for AI searchability
                    val articleContent = extractArticleText(body)

                    if (title == null && description == null && articleContent.isNullOrBlank()) {
                        Log.w(TAG, "No metadata found for URL: $url")
                        return@withTimeoutOrNull null
                    }

                    Log.d(TAG, "Reader mode: Extracted ${articleContent?.length ?: 0} chars of article text")

                    UrlMetadata(
                        url = url,
                        title = cleanHtmlEntities(title) ?: domain ?: url,
                        description = cleanHtmlEntities(description),
                        imageUrl = imageUrl,
                        domain = domain,
                        articleContent = articleContent, // NEW: Full article text for AI
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

    /**
     * READER MODE: Extract clean article text from HTML.
     * Removes scripts, styles, navigation, footer, ads, and other non-content elements.
     * Returns plain text suitable for AI search and analysis.
     */
    private fun extractArticleText(html: String): String? {
        try {
            var content = html

            // Step 1: Remove non-content elements (scripts, styles, nav, footer, ads)
            val removePatterns =
                listOf(
                    Pattern.compile("<script[^>]*>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("<style[^>]*>[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("<nav[^>]*>[\\s\\S]*?</nav>", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("<footer[^>]*>[\\s\\S]*?</footer>", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("<header[^>]*>[\\s\\S]*?</header>", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("<aside[^>]*>[\\s\\S]*?</aside>", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("<form[^>]*>[\\s\\S]*?</form>", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("<iframe[^>]*>[\\s\\S]*?</iframe>", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("<noscript[^>]*>[\\s\\S]*?</noscript>", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("<!--[\\s\\S]*?-->", Pattern.CASE_INSENSITIVE),
                )

            for (pattern in removePatterns) {
                content = pattern.matcher(content).replaceAll(" ")
            }

            // Step 2: Try to find main article content
            val articlePatterns =
                listOf(
                    Pattern.compile("<article[^>]*>([\\s\\S]*?)</article>", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("<main[^>]*>([\\s\\S]*?)</main>", Pattern.CASE_INSENSITIVE),
                )

            var articleHtml: String? = null
            for (pattern in articlePatterns) {
                val matcher = pattern.matcher(content)
                if (matcher.find()) {
                    articleHtml = matcher.group(1)
                    break
                }
            }

            // If no article container found, use cleaned body
            val bodyPattern = Pattern.compile("<body[^>]*>([\\s\\S]*?)</body>", Pattern.CASE_INSENSITIVE)
            val bodyMatcher = bodyPattern.matcher(content)
            if (articleHtml == null && bodyMatcher.find()) {
                articleHtml = bodyMatcher.group(1)
            }

            if (articleHtml == null) {
                articleHtml = content
            }

            // Step 3: Extract text from paragraphs and headings
            val textBuilder = StringBuilder()

            // Extract headings
            val headingPattern = Pattern.compile("<h[1-6][^>]*>([^<]+)</h[1-6]>", Pattern.CASE_INSENSITIVE)
            val headingMatcher = headingPattern.matcher(articleHtml)
            while (headingMatcher.find()) {
                val heading = headingMatcher.group(1)?.trim()
                if (!heading.isNullOrBlank() && heading.length > 2) {
                    textBuilder.append("\n## ").append(heading).append("\n")
                }
            }

            // Extract paragraphs
            val paragraphPattern = Pattern.compile("<p[^>]*>([\\s\\S]*?)</p>", Pattern.CASE_INSENSITIVE)
            val paragraphMatcher = paragraphPattern.matcher(articleHtml)
            while (paragraphMatcher.find()) {
                var paragraph = paragraphMatcher.group(1) ?: continue
                // Strip inline tags but keep text
                paragraph = paragraph.replace(Regex("<[^>]+>"), " ")
                paragraph = cleanHtmlEntities(paragraph) ?: continue
                paragraph = paragraph.replace(Regex("\\s+"), " ").trim()

                if (paragraph.length > 20) { // Skip tiny fragments
                    textBuilder.append(paragraph).append("\n\n")
                }
            }

            // Step 4: Clean up and limit size
            var result =
                textBuilder.toString()
                    .replace(Regex("\n{3,}"), "\n\n") // Max 2 newlines
                    .replace(Regex("[ \t]+"), " ") // Normalize spaces
                    .trim()

            // Limit to ~10KB of text (plenty for AI context)
            if (result.length > 10000) {
                result = result.take(10000) + "\n\n[Article truncated...]"
            }

            // Return null if we got very little content
            return if (result.length > 100) result else null
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting article text: ${e.message}")
            return null
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
     * Data class for URL metadata with Reader Mode article content
     */
    data class UrlMetadata(
        val url: String,
        val title: String,
        val description: String?,
        val imageUrl: String?,
        val domain: String?,
        /** READER MODE: Full extracted article text for AI search/analysis */
        val articleContent: String? = null,
    )
}
