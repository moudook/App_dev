package com.example.smarty.server.tools

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class WebFetchTool(private val client: HttpClient) {
    private val logger = LoggerFactory.getLogger(WebFetchTool::class.java)
    
    companion object {
        private const val MAX_CONTENT_LENGTH = 100_000
        private const val TIMEOUT_MS = 30_000L
    }
    
    suspend fun fetch(
        url: String,
        format: String = "readable"
    ): String {
        return try {
            val response: HttpResponse = client.get(url) {
                timeout {
                    requestTimeoutMillis = TIMEOUT_MS
                    connectTimeoutMillis = 10_000
                }
                headers {
                    append("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    append("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    append("Accept-Language", "en-US,en;q=0.5")
                }
            }
            
            if (!response.status.isSuccess()) {
                return "Error: HTTP ${response.status.value} - ${response.status.description}"
            }
            
            val contentType = response.headers["Content-Type"] ?: ""
            val body = response.bodyAsText()
            
            when (format) {
                "raw" -> body
                "readable" -> extractReadableContent(body, contentType, url)
                "markdown" -> htmlToMarkdown(extractReadableContent(body, contentType, url), url)
                else -> extractReadableContent(body, contentType, url)
            }
        } catch (e: Exception) {
            logger.error("Fetch failed for $url", e)
            "Error fetching URL: ${e.message}"
        }
    }
    
    private fun extractReadableContent(html: String, contentType: String, url: String): String {
        if (!contentType.contains("text/html", ignoreCase = true)) {
            return html.take(MAX_CONTENT_LENGTH)
        }
        
        var content = html
        
        content = removeScriptAndStyle(content)
        content = extractMainContent(content)
        content = cleanHtml(content)
        content = normalizeWhitespace(content)
        
        return buildString {
            appendLine("--- Source: $url ---")
            appendLine(content.take(MAX_CONTENT_LENGTH))
            if (content.length > MAX_CONTENT_LENGTH) {
                appendLine("\n[...content truncated...]")
            }
        }
    }
    
    private fun removeScriptAndStyle(html: String): String {
        var result = html
        result = Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("""<style[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL).replace(result, "")
        result = Regex("""<nav[^>]*>.*?</nav>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("""<footer[^>]*>.*?</footer>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("""<header[^>]*>.*?</header>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).replace(result, "")
        result = Regex("""<aside[^>]*>.*?</aside>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).replace(result, "")
        return result
    }
    
    private fun extractMainContent(html: String): String {
        val articleMatch = Regex("""<article[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).find(html)
        if (articleMatch != null) {
            return articleMatch.groupValues[1]
        }
        
        val mainMatch = Regex("""<main[^>]*>(.*?)</main>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).find(html)
        if (mainMatch != null) {
            return mainMatch.groupValues[1]
        }
        
        val contentDiv = Regex("""<div[^>]*(?:class|id)=["'](?:content|article|post|entry|body)["'][^>]*>(.*?)</div>""", 
            RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).find(html)
        if (contentDiv != null) {
            return contentDiv.groupValues[1]
        }
        
        val bodyMatch = Regex("""<body[^>]*>(.*?)</body>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).find(html)
        return bodyMatch?.groupValues?.get(1) ?: html
    }
    
    private fun cleanHtml(html: String): String {
        var text = html
        
        text = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(text, "\n")
        text = Regex("""</p>""", RegexOption.IGNORE_CASE).replace(text, "\n\n")
        text = Regex("""</div>""", RegexOption.IGNORE_CASE).replace(text, "\n")
        text = Regex("""</li>""", RegexOption.IGNORE_CASE).replace(text, "\n")
        text = Regex("""<li[^>]*>""", RegexOption.IGNORE_CASE).replace(text, "- ")
        text = Regex("""</h[1-6]>""", RegexOption.IGNORE_CASE).replace(text, "\n\n")
        text = Regex("""<h[1-6][^>]*>""", RegexOption.IGNORE_CASE).replace(text, "\n## ")
        text = Regex("""<a[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE)
            .replace(text) { matchResult ->
                val href = matchResult.groupValues[1]
                val linkText = matchResult.groupValues[2].replace(Regex("<[^>]+>"), "")
                if (linkText.isNotBlank() && href.isNotBlank()) "[$linkText]($href)" else linkText
            }
        
        text = Regex("""<[^>]+>""").replace(text, "")
        
        text = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        
        return text
    }
    
    private fun normalizeWhitespace(text: String): String {
        return text
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .lines()
            .map { it.trim() }
            .joinToString("\n")
            .trim()
    }
    
    private fun htmlToMarkdown(text: String, url: String): String {
        return "## Content from: $url\n\n$text"
    }
    
    suspend fun extractLinks(url: String): String {
        return try {
            val response = client.get(url) {
                timeout {
                    requestTimeoutMillis = TIMEOUT_MS
                }
                headers {
                    append("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                }
            }
            
            if (!response.status.isSuccess()) {
                return "Error: HTTP ${response.status.value}"
            }
            
            val body = response.bodyAsText()
            val links = mutableMapOf<String, String>()
            
            Regex("""<a[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE)
                .findAll(body)
                .forEach { match ->
                    val href = match.groupValues[1]
                    val text = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                    if (href.startsWith("http") && text.isNotBlank()) {
                        links[href] = text.take(100)
                    }
                }
            
            if (links.isEmpty()) "No links found."
            else {
                "Found ${links.size} links:\n" + links.entries.take(30).joinToString("\n") { "- [${it.value}](${it.key})" }
            }
        } catch (e: Exception) {
            "Error extracting links: ${e.message}"
        }
    }
}
