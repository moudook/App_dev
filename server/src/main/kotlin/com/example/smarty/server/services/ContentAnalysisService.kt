package com.example.smarty.server.services

import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProviderFactory
import com.example.smarty.server.models.*
import io.ktor.client.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Service for content and document analysis.
 * Ported from app's ContentAnalyzer with server-side processing.
 */
class ContentAnalysisService(
    private val httpClient: HttpClient,
    private val visionService: VisionService
) {
    private val logger = LoggerFactory.getLogger(ContentAnalysisService::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        /**
         * System prompt for note content analysis.
         */
        val SYSTEM_PROMPT = """
            <system_instructions>
            <identity>
                You are Smarty's Architect. Your goal is to transform raw notes into precise, searchable metadata with a calm, professional tone.
            </identity>

            <objective>
                Extract high-signal metadata. If content is low-value (gibberish, trivial), return "low_value" in the summary.
            </objective>

            <directives>
                1. **Searchable Title**: 4-7 keywords the user would search for.
                2. **Selective Summary**: 1-3 lines of unique value. No fluff.
                3. **Precise Category**: Single one-word category (e.g., react_native, finance, recipe). Use snake_case.
                4. **Action Items**: Extract tasks only if explicit; otherwise return empty array.
            </directives>

            <tone_and_style>
                - Prefer lowercase for summaries and category names.
                - NO markdown headers or bolding.
                - NO social commentary.
                - STRICT JSON format.
            </tone_and_style>

            <output_format>
                Return a single JSON object with these keys:
                {
                    "title": "[Keywords]",
                    "category": "[topic_name]",
                    "summary": "[insight in lowercase]",
                    "whySaved": "[purpose]",
                    "todos": ["task1", "task2"]
                }
            </output_format>
            </system_instructions>
        """.trimIndent()

        /**
         * Document analysis prompt.
         */
        val DOCUMENT_ANALYSIS_PROMPT = """
            <system_instructions>
            <identity>
                You are Smarty's Deep Analyst. You specialize in synthesizing complex documents into high-density insights with a calm, professional tone.
            </identity>

            <objective>
                Analyze the document and produce a structured JSON report that highlights technical depth and actionable takeaways.
            </objective>

            <directives>
                1. **Formula Precision**: Explicitly extract ALL mathematical or chemical formulas.
                2. **Technical Glossary**: Identify 3-7 core technical terms and provide concise definitions.
                3. **Style**: Use lowercase for summaries and recurring topics. Avoid large headers.
            </directives>

            <constraints>
                - Output MUST be a single JSON object.
                - NO markdown code blocks.
            </constraints>

            <output_template>
                {
                  "title": "Searchable Title",
                  "summary": "concise lowercase overview",
                  "keyPoints": ["takeaway one", "takeaway two"],
                  "category": "topic_name",
                  "actionItems": ["next step"],
                  "userRelevance": "strategic value",
                  "references": {
                    "formulas": ["..."],
                    "keyTerms": [{"term": "...", "definition": "..."}],
                    "recurringTopics": ["topic_a", "topic_b"]
                  }
                }
            </output_template>
            </system_instructions>
        """.trimIndent()
    }

    /**
     * Analyze content and extract metadata.
     */
    suspend fun analyzeContent(
        content: String,
        attachmentInfo: List<AttachmentInfo>? = null
    ): ContentAnalysisResult {
        logger.info("Starting content analysis (${content.length} chars)")

        val sanitizedContent = sanitizeContent(content)

        val contentWithMetadata = if (attachmentInfo.isNullOrEmpty()) {
            sanitizedContent
        } else {
            val attachmentsSection = attachmentInfo.mapIndexed { index, meta ->
                "${index + 1}. ${meta.fileName} (${meta.fileType})"
            }.joinToString("\n")
            "$sanitizedContent\n\n---\nAttached Files:\n$attachmentsSection"
        }

        val provider = LlmProviderFactory.create(httpClient)

        val messages = listOf(
            LlmMessage(role = LlmMessage.Role.SYSTEM, content = SYSTEM_PROMPT),
            LlmMessage(role = LlmMessage.Role.USER, content = contentWithMetadata)
        )

        return try {
            val response = StringBuilder()
            provider.stream(messages, emptyList(), null).collect { chunk ->
                chunk.content?.let { response.append(it) }
            }
            parseContentAnalysisResponse(response.toString())
        } catch (e: Exception) {
            logger.error("Content analysis failed: ${e.message}", e)
            ContentAnalysisResult(
                title = "Untitled Note",
                category = "note",
                summary = "Analysis failed",
                whySaved = "Error",
                todos = emptyList(),
                success = false,
                error = e.message
            )
        }
    }

    /**
     * Analyze a document (PDF, long-form text).
     */
    suspend fun analyzeDocument(
        documentText: String,
        fileName: String? = null,
        userContext: String? = null
    ): DocumentAnalysisResult {
        logger.info("Starting document analysis (${documentText.length} chars, file: $fileName)")

        val sanitizedText = sanitizeContent(documentText)

        val contextPrefix = buildString {
            if (fileName != null) {
                val safeFileName = fileName.replace(Regex("""[<>{}|\\^`\[\]]"""), "_")
                append("Document filename: $safeFileName\n")
            }
            if (userContext != null) {
                append("User's context/intent: $userContext\n")
            }
            append("\n")
        }

        val fullContent = contextPrefix + sanitizedText
        val provider = LlmProviderFactory.create(httpClient)

        val messages = listOf(
            LlmMessage(role = LlmMessage.Role.SYSTEM, content = DOCUMENT_ANALYSIS_PROMPT),
            LlmMessage(role = LlmMessage.Role.USER, content = fullContent)
        )

        return try {
            val response = StringBuilder()
            provider.stream(messages, emptyList(), null).collect { chunk ->
                chunk.content?.let { response.append(it) }
            }
            parseDocumentAnalysisResponse(response.toString(), fileName)
        } catch (e: Exception) {
            logger.error("Document analysis failed: ${e.message}", e)
            DocumentAnalysisResult(
                title = fileName ?: "Document",
                summary = "Analysis failed",
                keyPoints = emptyList(),
                category = "document",
                actionItems = emptyList(),
                userRelevance = "Error during analysis",
                references = null,
                success = false,
                error = e.message
            )
        }
    }

    private fun sanitizeContent(content: String): String {
        return content
            .replace(Regex("""<system>.*?</system>""", RegexOption.DOT_MATCHES_ALL), "[filtered]")
            .replace(Regex("""<\|.*?\|>"""), "[filtered]")
            .take(50000)
    }

    private fun parseContentAnalysisResponse(response: String): ContentAnalysisResult {
        val cleaned = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        return try {
            val jsonElement = json.parseToJsonElement(cleaned).jsonObject
            ContentAnalysisResult(
                title = jsonElement["title"]?.jsonPrimitive?.content ?: "Untitled",
                category = (jsonElement["category"]?.jsonPrimitive?.content ?: "note").lowercase().replace(" ", "_"),
                summary = jsonElement["summary"]?.jsonPrimitive?.content ?: "",
                whySaved = jsonElement["whySaved"]?.jsonPrimitive?.content ?: "",
                todos = jsonElement["todos"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                success = true
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse analysis response", e)
            ContentAnalysisResult(
                title = "Untitled",
                category = "note",
                summary = response.take(200),
                whySaved = "Auto-saved",
                todos = emptyList(),
                success = true
            )
        }
    }

    private fun parseDocumentAnalysisResponse(response: String, fileName: String?): DocumentAnalysisResult {
        val cleaned = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        return try {
            val jsonElement = json.parseToJsonElement(cleaned).jsonObject

            // Extract references manually since it's nested
            val referencesObj = jsonElement["references"]?.jsonObject
            val references = if (referencesObj != null) {
                DocumentReferences(
                    formulas = referencesObj["formulas"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    keyTerms = referencesObj["keyTerms"]?.jsonArray?.map {
                        KeyTerm(
                            term = it.jsonObject["term"]?.jsonPrimitive?.content ?: "",
                            definition = it.jsonObject["definition"]?.jsonPrimitive?.content ?: ""
                        )
                    } ?: emptyList(),
                    recurringTopics = referencesObj["recurringTopics"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                )
            } else null

            DocumentAnalysisResult(
                title = jsonElement["title"]?.jsonPrimitive?.content ?: fileName ?: "Document",
                summary = jsonElement["summary"]?.jsonPrimitive?.content ?: "",
                keyPoints = jsonElement["keyPoints"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                category = jsonElement["category"]?.jsonPrimitive?.content ?: "document",
                actionItems = jsonElement["actionItems"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                userRelevance = jsonElement["userRelevance"]?.jsonPrimitive?.content ?: "",
                references = references,
                success = true
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse document analysis response", e)
            DocumentAnalysisResult(
                title = fileName ?: "Document",
                summary = response.take(500),
                keyPoints = emptyList(),
                category = "document",
                actionItems = emptyList(),
                userRelevance = "",
                references = null,
                success = true
            )
        }
    }
}
