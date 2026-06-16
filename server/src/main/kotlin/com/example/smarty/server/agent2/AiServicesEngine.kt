package com.example.smarty.server.agent2

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.server.agent2.tools.ToolDependencies
import com.example.smarty.server.agent2.tools.ToolRegistry
import dev.langchain4j.memory.chat.TokenWindowChatMemory
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator
import dev.langchain4j.service.AiServices
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import org.slf4j.LoggerFactory

class AiServicesEngine(
    private val chatModelFactory: OpenRouterChatModelFactory,
    private val contextWindowManager: ContextWindowManager,
    private val systemPromptBuilder: SystemPromptBuilder = SystemPromptBuilder(),
    private val chatMemoryStore: PostgresChatMemoryStore? = null,
) : AgentEngine {
    private val logger = LoggerFactory.getLogger(AiServicesEngine::class.java)
    private val tokenizer = OpenAiTokenCountEstimator("gpt-4o")

    override val name: String = "langchain4j-aiservices"

    override suspend fun stream(
        request: AgentRequest,
        eventEmitter: suspend (AgentEvent) -> Unit,
    ): Flow<String> {
        val modelId = request.modelOverride ?: "meta-llama/llama-3.1-8b-instruct"
        val sessionId = request.sessionId

        val model = chatModelFactory.buildStreamingModel(
            modelId = modelId,
            sessionId = sessionId,
        )

        val chatMemoryLimit = contextWindowManager.getChatMemoryLimitForModel(modelId)
        val compactTrigger = contextWindowManager.getCompactTriggerForModel(modelId)

        val memoryBuilder = TokenWindowChatMemory.builder()
            .id(sessionId)
            .maxTokens(chatMemoryLimit, tokenizer)

        if (chatMemoryStore != null) {
            val compactor = IntelligentCompactor(
                tokenizer = tokenizer,
                compactTrigger = compactTrigger,
            )
            val compactingStore = CompactingChatMemoryStore(
                delegate = chatMemoryStore,
                compactor = compactor,
                personality = request.personality,
            )
            memoryBuilder.chatMemoryStore(compactingStore)
        }

        val chatMemory = memoryBuilder.build()

        val systemRequest = SystemPromptRequest(
            personality = request.personality,
            clientTimezone = request.clientTimezone,
            clientTimeMillis = request.clientTimeMillis,
            section = request.section,
            userId = request.userId,
        )

        val toolDeps = ToolDependencies(
            userId = request.userId,
            section = request.section,
        )
        val tools = ToolRegistry(toolDeps).getAllTools()

        val assistant = AiServices.builder(StreamingAssistant::class.java)
            .streamingChatModel(model)
            .chatMemory(chatMemory)
            .systemMessageProvider { systemPromptBuilder.build(systemRequest) }
            .tools(*tools.toTypedArray())
            .build()

        val channel = Channel<String>(Channel.BUFFERED)

        assistant.chat(sessionId, request.query)
            .onPartialResponse { token ->
                channel.trySend(token)
            }
            .onCompleteResponse {
                channel.close()
            }
            .onError { error ->
                logger.error("[AiServicesEngine] Stream error: ${error.message}")
                channel.close(error)
            }
            .start()

        return channel.consumeAsFlow()
    }
}
