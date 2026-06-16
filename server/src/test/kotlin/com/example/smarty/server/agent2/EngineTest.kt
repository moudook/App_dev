package com.example.smarty.server.agent2

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.output.FinishReason
import dev.langchain4j.model.output.TokenUsage
import com.example.smarty.server.agent2.ModelContextWindowProvider
import com.example.smarty.server.agent2.ModelInfo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A stub [StreamingChatModel] that returns a configured text response.
 */
class StubChatModel(private val responseText: String) : StreamingChatModel {
    override fun chat(request: ChatRequest, handler: StreamingChatResponseHandler) {
        for (chunk in responseText.chunked(3)) {
            handler.onPartialResponse(chunk)
        }
        handler.onCompleteResponse(
            ChatResponse.builder()
                .aiMessage(AiMessage(responseText))
                .finishReason(FinishReason.STOP)
                .tokenUsage(TokenUsage(10, responseText.length, 10 + responseText.length))
                .build()
        )
    }

    override fun chat(messages: List<ChatMessage>, handler: StreamingChatResponseHandler) {
        chat(
            ChatRequest.builder().messages(messages).build(),
            handler,
        )
    }
}

/**
 * A stub [StreamingChatModel] that returns a tool call response.
 */
class StubToolCallChatModel(
    private val toolName: String,
    private val arguments: String = "{}",
) : StreamingChatModel {
    override fun chat(request: ChatRequest, handler: StreamingChatResponseHandler) {
        val req = ToolExecutionRequest.builder()
            .id("test-call-1")
            .name(toolName)
            .arguments(arguments)
            .build()
        handler.onCompleteResponse(
            ChatResponse.builder()
                .aiMessage(AiMessage.from(req))
                .finishReason(FinishReason.STOP)
                .tokenUsage(TokenUsage(10, 5, 15))
                .build()
        )
    }

    override fun chat(messages: List<ChatMessage>, handler: StreamingChatResponseHandler) {
        chat(
            ChatRequest.builder().messages(messages).build(),
            handler,
        )
    }
}

@DisplayName("Agent2 Engine Tests")
class EngineTest {

    @Test
    @DisplayName("SimpleChatMemory stores messages")
    fun simpleChatMemory() {
        val memory = SimpleChatMemory("test")
        memory.add(dev.langchain4j.data.message.UserMessage("hi"))
        assertEquals(1, memory.messages().size)
    }

    @Test
    @DisplayName("ContextWindowManager returns default for unknown model")
    fun contextWindowManager() {
        val provider = ContextWindowManager(object : ModelContextWindowProvider {
            override suspend fun getContextWindow(modelId: String): Int = 128_000
            override suspend fun getAllModels(): List<ModelInfo> = emptyList()
        })
        val limit = provider.getChatMemoryLimit(128_000)
        assertTrue(limit > 0, "Should return budget-adjusted limit")
        assertTrue(limit < 128_000, "Should subtract overhead from context window")
    }

    @Test
    @DisplayName("OpenRouterConfig defaults gracefully")
    fun openRouterConfigDefaults() {
        val config = OpenRouterConfig()
        assertFalse(config.isConfigured, "Should not be configured without API key")

        val configWithKey = OpenRouterConfig(apiKey = "test-key")
        assertTrue(configWithKey.isConfigured)
        assertEquals("https://openrouter.ai/api/v1", configWithKey.baseUrl)
        assertTrue(configWithKey.enableCache)
    }

    @Test
    @DisplayName("StubChatModel produces tokens")
    fun stubChatModelProducesTokens() {
        val model = StubChatModel("Hello, world!")
        val tokens = mutableListOf<String>()
        model.chat(
            ChatRequest.builder()
                .messages(listOf(dev.langchain4j.data.message.UserMessage("test")))
                .build(),
            object : StreamingChatResponseHandler {
                override fun onPartialResponse(text: String) { tokens.add(text) }
                override fun onCompleteResponse(response: ChatResponse) {}
                override fun onError(error: Throwable) {}
            },
        )
        assertTrue(tokens.isNotEmpty())
        val combined = tokens.joinToString("")
        assertEquals("Hello, world!", combined)
    }

    @Test
    @DisplayName("StubToolCallChatModel returns tool call")
    fun stubToolCallModelReturnsToolCall() {
        val model = StubToolCallChatModel("webSearch", """{"queries":["test"]}""")
        var response: ChatResponse? = null
        model.chat(
            ChatRequest.builder()
                .messages(listOf(dev.langchain4j.data.message.UserMessage("search")))
                .build(),
            object : StreamingChatResponseHandler {
                override fun onPartialResponse(text: String) {}
                override fun onCompleteResponse(resp: ChatResponse) { response = resp }
                override fun onError(error: Throwable) {}
            },
        )
        assertNotNull(response)
        val aiMessage = response!!.aiMessage()
        assertTrue(aiMessage.hasToolExecutionRequests())
        assertEquals("webSearch", aiMessage.toolExecutionRequests()[0].name())
    }
}
