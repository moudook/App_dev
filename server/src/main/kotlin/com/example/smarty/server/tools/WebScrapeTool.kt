package com.example.smarty.server.tools

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import org.slf4j.LoggerFactory

/**
 * Web Scrape Tool - Fetches content from web pages.
 * Returns the main text content of a page.
 */
class WebScrapeTool {
    companion object {
        private val logger = LoggerFactory.getLogger(WebScrapeTool::class.java)
    }

    /**
     * Scrape text content from a URL
     */
    suspend fun scrape(url: String): String {
        return try {
            val client = HttpClient()

            val response =
                client.get(url) {
                    headers {
                        append("User-Agent", "Mozilla/5.0 (compatible; SmartyBot/1.0)")
                    }
                }
            val html: String = response.body()

            client.close()

            // Simple HTML tag stripping
            html.replace(Regex("<[^>]*>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(10000) // Limit to 10k chars
        } catch (e: Exception) {
            logger.error("Web scrape failed for $url: ${e.message}")
            ""
        }
    }
}
