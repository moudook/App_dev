/**
 * Web Scrape Tool - Fetches content from web pages.
 * Returns the main text content of a page.
 *
 * SECURITY: Validates URLs to prevent SSRF attacks.
 * Only allows HTTP/HTTPS URLs, blocks internal IP ranges.
 */
package com.example.smarty.server.tools

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class WebScrapeTool {
    companion object {
        private val logger = LoggerFactory.getLogger(WebScrapeTool::class.java)

        // Blocked IP ranges for SSRF protection
        private val blockedIpRanges = listOf(
            "127.0.0.0/8",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "169.254.0.0/16",
            "198.18.0.0/15",
            "224.0.0.0/4",
            "240.0.0.0/4",
        )

        private val allowedProtocols = setOf("http", "https")

        private val client =
            HttpClient(OkHttp) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
    }

    /**
     * Scrape text content from a URL
     * SECURITY: Validates URL to prevent SSRF attacks
     */
    suspend fun scrape(url: String): String {
        return try {
            // SECURITY: Validate URL
            val validatedUrl = validateUrl(url) ?: throw IllegalArgumentException("Invalid or blocked URL: $url")

            val response =
                client.get(validatedUrl) {
                    headers {
                        append("User-Agent", "Mozilla/5.0 (compatible; SmartyBot/1.0)")
                    }
                }
            val html: String = response.body<String>()

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

    /**
     * Validate URL to prevent SSRF attacks.
     * Returns the validated URL string or null if invalid/blocked.
     */
    private fun validateUrl(url: String): String? {
        return try {
            val parsedUrl = java.net.URL(url)
            val protocol = parsedUrl.protocol.lowercase()
            val host = parsedUrl.host

            // Check protocol
            if (protocol !in allowedProtocols) {
                logger.warn("Blocked URL with disallowed protocol: $protocol - $url")
                return null
            }

            // Check if host is an IP address
            val ipAddress =
                try {
                    java.net.InetAddress.getByName(host)
                } catch (e: Exception) {
                    // If not an IP, it's a domain name - allow it
                    return url
                }

            // Check if IP is in blocked ranges
            val ipBytes = ipAddress.address
            for (range in blockedIpRanges) {
                if (isIpInRange(ipBytes, range)) {
                    logger.warn("Blocked URL with internal IP ($range): $url")
                    return null
                }
            }

            url
        } catch (e: Exception) {
            logger.warn("Invalid URL format: $url - ${e.message}")
            null
        }
    }

    /**
     * Check if an IP address is in a CIDR range.
     */
    private fun isIpInRange(
        ipBytes: ByteArray,
        cidr: String,
    ): Boolean {
        val parts = cidr.split("/")
        if (parts.size != 2) return false

        val rangeIp = java.net.InetAddress.getByName(parts[0]).address
        val prefixLength = parts[1].toIntOrNull() ?: return false

        // Convert to int for comparison
        val ipInt =
            ((ipBytes[0].toInt() and 0xFF) shl 24) or
                ((ipBytes[1].toInt() and 0xFF) shl 16) or
                ((ipBytes[2].toInt() and 0xFF) shl 8) or
                (ipBytes[3].toInt() and 0xFF)

        val rangeInt =
            ((rangeIp[0].toInt() and 0xFF) shl 24) or
                ((rangeIp[1].toInt() and 0xFF) shl 16) or
                ((rangeIp[2].toInt() and 0xFF) shl 8) or
                (rangeIp[3].toInt() and 0xFF)

        val mask = (0xFFFFFFFF.toInt() shl (32 - prefixLength)) and 0xFFFFFFFF.toInt()

        return (ipInt and mask) == (rangeInt and mask)
    }
}
