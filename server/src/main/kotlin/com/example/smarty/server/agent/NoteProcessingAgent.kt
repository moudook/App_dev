package com.example.smarty.server.agent

import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.LlmProviderFactory
import com.example.smarty.server.models.ContentAnalysisResult
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class NoteProcessingAgent(
    private val httpClient: HttpClient
) {
    private val logger = LoggerFactory.getLogger(NoteProcessingAgent::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        val SYSTEM_PROMPT = """
            <identity>
            You are Friday's Note Architect. Transform raw notes into searchable metadata.
            </identity>
            <task>
            Extract high-signal metadata from the note. If low-value, set summary to "low_value".
            DO NOT USE ANY TOOLS. Output ONLY valid JSON.
            </task>
            <output_format>
            Return ONLY valid JSON:
            {
              "title": "4-7 searchable keywords",
              "category": "single_word_snake_case",
              "summary": "1-3 lines of unique value, lowercase",
              "whySaved": "purpose or intent",
              "todos": ["explicit tasks only"],
              "memories": ["user facts, preferences, or personal details to remember"],
              "tags": ["relevant", "keywords"],
              "stackId": "topic_cluster_id or null"
            }
            </output_format>
            <rules>
            - Lowercase for summaries and categories
            - No markdown, no explanation
            - Extract todos only if explicitly stated
            </rules>
        """.trimIndent()
    }

    suspend fun processNote(content: String): ContentAnalysisResult {
        logger.info("NoteProcessingAgent processing content length: ${content.length}")
        val sanitizedContent = content.take(50000)
        val provider = LlmProviderFactory.getOrCreateProvider(httpClient)
        val messages = listOf(
            LlmMessage(role = LlmMessage.Role.SYSTEM, content = SYSTEM_PROMPT),
            LlmMessage(role = LlmMessage.Role.USER, content = sanitizedContent)
        )

        return try {
            val response = StringBuilder()
            provider.stream(messages, emptyList(), null).collect { chunk ->
                chunk.content?.let { response.append(it) }
            }
            
            val cleaned = response.toString().trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val jsonElement = json.parseToJsonElement(cleaned).jsonObject

            ContentAnalysisResult(
                title = jsonElement["title"]?.jsonPrimitive?.content ?: "Untitled",
                category = (jsonElement["category"]?.jsonPrimitive?.content ?: "note").lowercase().replace(" ", "_"),
                summary = jsonElement["summary"]?.jsonPrimitive?.content ?: "",
                whySaved = jsonElement["whySaved"]?.jsonPrimitive?.content ?: "",
                todos = jsonElement["todos"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                memories = jsonElement["memories"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                tags = jsonElement["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                stackId = jsonElement["stackId"]?.jsonPrimitive?.content?.takeIf { it != "null" && it.isNotBlank() },
                success = true
            )
        } catch (e: Exception) {
            logger.error("NoteProcessingAgent failed", e)
            ContentAnalysisResult(
                title = "Untitled Note",
                category = "note",
                summary = "Analysis failed",
                whySaved = "Error",
                todos = emptyList(),
                memories = emptyList(),
                tags = emptyList(),
                stackId = null,
                success = false,
                error = e.message
            )
        }
    }
}
