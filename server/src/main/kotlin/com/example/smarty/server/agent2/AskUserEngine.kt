package com.example.smarty.server.agent2

import com.example.smarty.protocol.AgentEvent
import com.example.smarty.protocol.AskUserQuestion
import com.example.smarty.server.agent2.tools.ToolDependencies
import com.example.smarty.server.agent2.tools.ToolRegistry
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.agent.tool.ToolSpecifications
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.service.tool.DefaultToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID
import com.fasterxml.jackson.databind.ObjectMapper

private sealed class StreamEvent {
    data class Token(val text: String) : StreamEvent()
    data class Complete(val response: ChatResponse) : StreamEvent()
}

class AskUserEngine(
    private val chatModelFactory: OpenRouterChatModelFactory,
    private val contextWindowManager: ContextWindowManager,
    private val systemPromptBuilder: SystemPromptBuilder = SystemPromptBuilder(),
    private val chatMemoryStore: PostgresChatMemoryStore? = null,
    private val usageListener: ChatUsageListener = LoggingChatUsageListener(),
    private val mapper: ObjectMapper = ObjectMapper(),
) : AgentEngine {
    private val logger = LoggerFactory.getLogger(AskUserEngine::class.java)

    override val name: String = "langchain4j-askuser"

    override suspend fun stream(
        request: AgentRequest,
        eventEmitter: suspend (AgentEvent) -> Unit,
    ): Flow<String> = callbackFlow {
        val modelId = request.modelOverride ?: "meta-llama/llama-3.1-8b-instruct"
        val sessionId = request.sessionId
        val model = chatModelFactory.buildStreamingModel(modelId = modelId, sessionId = sessionId)

        val messages = mutableListOf<ChatMessage>()
        val systemText = buildSystemPrompt(request)
        messages.add(SystemMessage(systemText))

        if (request.historyJson != null) {
            val historyMessages = deserializeHistory(request.historyJson)
            messages.addAll(historyMessages)
        }

        if (request.resumeToolResultJson != null) {
            messages.add(ToolExecutionResultMessage("ask_user_resume", "askUser", request.resumeToolResultJson))
        }

        messages.add(UserMessage(request.query))

        val toolDeps = ToolDependencies(userId = request.userId, section = request.section)
        val toolObjects = ToolRegistry(toolDeps).getAllTools()
        val toolSpecs = toolObjects.flatMap { ToolSpecifications.toolSpecificationsFrom(it) }

        val toolExecutors = buildToolExecutors(toolObjects)

        @Suppress("UNCHECKED_CAST")
        val executorMap = toolExecutors as Map<String, DefaultToolExecutor>

        launch(Dispatchers.IO) {
            usageListener.onRequest(modelId)
            val startTime = System.currentTimeMillis()
            try {
                runToolLoop(
                    model = model,
                    messages = messages,
                    toolSpecs = toolSpecs,
                    executorMap = executorMap,
                    toolObjects = toolObjects,
                    eventEmitter = eventEmitter,
                    sessionId = sessionId,
                    sendChannel = this@callbackFlow,
                    modelId = modelId,
                    startTime = startTime,
                )
            } catch (e: Exception) {
                logger.error("[AskUserEngine] Fatal error: ${e.message}", e)
                usageListener.onError(modelId, e)
            }
        }

        awaitClose {
            logger.debug("[AskUserEngine] Flow closed for session $sessionId")
        }
    }

    private suspend fun runToolLoop(
        model: dev.langchain4j.model.chat.StreamingChatModel,
        messages: MutableList<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        executorMap: Map<String, DefaultToolExecutor>,
        toolObjects: List<Any>,
        eventEmitter: suspend (AgentEvent) -> Unit,
        sessionId: String,
        sendChannel: kotlinx.coroutines.channels.SendChannel<String>,
        modelId: String,
        startTime: Long,
    ) {
        var round = 0
        while (round < 10) {
            round++
            val roundChannel = Channel<StreamEvent>(Channel.BUFFERED)

            val params = DefaultChatRequestParameters.builder()
                .toolSpecifications(toolSpecs)
                .build()

            val chatRequest = ChatRequest.builder()
                .messages(messages.toList())
                .parameters(params)
                .build()

            model.chat(chatRequest, object : StreamingChatResponseHandler {
                override fun onPartialResponse(text: String) {
                    roundChannel.trySend(StreamEvent.Token(text))
                }

                override fun onCompleteResponse(response: ChatResponse) {
                    roundChannel.trySend(StreamEvent.Complete(response))
                    roundChannel.close()
                }

                override fun onError(error: Throwable) {
                    roundChannel.close(error)
                }
            })

            val textBuilder = StringBuilder()

            for (event in roundChannel) {
                when (event) {
                    is StreamEvent.Token -> {
                        textBuilder.append(event.text)
                        sendChannel.trySend(event.text)
                    }
                    is StreamEvent.Complete -> {
                        val response = event.response
                        val tokenUsage = response.tokenUsage()
                        if (tokenUsage != null) {
                            val latency = System.currentTimeMillis() - startTime
                            usageListener.onResponse(ChatUsage(
                                modelId = modelId,
                                promptTokens = tokenUsage.inputTokenCount() ?: 0,
                                completionTokens = tokenUsage.outputTokenCount() ?: 0,
                                totalTokens = tokenUsage.totalTokenCount() ?: 0,
                                latencyMs = latency,
                            ))
                        }

                        val aiMessage = response.aiMessage()

                        if (aiMessage.hasToolExecutionRequests()) {
                            val requests = aiMessage.toolExecutionRequests()

                            val askUserRequest = requests.find { it.name() == "askUser" }
                            if (askUserRequest != null) {
                                logger.info("[AskUserEngine] askUser detected, stopping tool loop")
                                eventEmitter(buildAskUserEvent(askUserRequest, requests, sessionId))
                                return@runToolLoop
                            }

            // Execute all non-askUser tools
            for (execReq in requests) {
                val result = executeTool(execReq, executorMap, toolObjects)
                messages.add(ToolExecutionResultMessage.from(execReq, result))
            }
                        } else {
                            // Text-only response — done
                            sendChannel.close()
                            return@runToolLoop
                        }
                    }
                }
            }
        }

        if (round >= 10) {
            logger.warn("[AskUserEngine] Tool loop exceeded max rounds (10)")
        }
        sendChannel.close()
    }

    private suspend fun executeTool(
        request: ToolExecutionRequest,
        executorMap: Map<String, DefaultToolExecutor>,
        toolObjects: List<Any>,
    ): String {
        val executor = executorMap[request.name()]
        if (executor != null) {
            return try {
                executor.execute(request, "memoryId-not-used")
            } catch (e: Exception) {
                """{"success":false,"error":"${e.message}","suggestion":"Check the tool parameters and try again.","code":"TOOL_EXECUTION_ERROR"}"""
            }
        }

        // Fallback: try to find the method via reflection
        for (obj in toolObjects) {
            for (method in obj::class.java.methods) {
                val toolAnnotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool::class.java)
                if (toolAnnotation != null) {
                    val toolName = resolveToolName(toolAnnotation, method)
                    if (toolName == request.name() || method.name == request.name()) {
                        return try {
                            val args = deserializeArgs(method, request.arguments())
                            method.invoke(obj, *args).toString()
                        } catch (e: Exception) {
                            """{"success":false,"error":"${e.message}","suggestion":"Check the tool parameters and try again.","code":"TOOL_EXECUTION_ERROR"}"""
                        }
                    }
                }
            }
        }

        return """{"success":false,"error":"Tool '${request.name()}' not found","suggestion":"Available tools: ${executorMap.keys}","code":"TOOL_NOT_FOUND"}"""
    }

    private fun resolveToolName(
        annotation: dev.langchain4j.agent.tool.Tool,
        method: java.lang.reflect.Method,
    ): String {
        // Java annotation methods accessed via reflection to avoid Kotlin interop issues
        val nameMethod = annotation::class.java.getMethod("name")
        val annName = nameMethod.invoke(annotation) as String
        if (annName.isNotEmpty()) return annName

        val valueMethod = annotation::class.java.getMethod("value")
        val annValue = valueMethod.invoke(annotation) as Array<String>
        if (annValue.isNotEmpty()) return annValue[0]

        return method.name
    }

    private fun buildToolExecutors(toolObjects: List<Any>): Map<String, DefaultToolExecutor> {
        val map = mutableMapOf<String, DefaultToolExecutor>()
        for (obj in toolObjects) {
            for (method in obj::class.java.methods) {
                val toolAnnotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool::class.java)
                if (toolAnnotation != null) {
                    val toolName = resolveToolName(toolAnnotation, method)
                    map[toolName] = DefaultToolExecutor(obj, method)
                }
            }
        }
        return map
    }

    private fun buildAskUserEvent(
        request: ToolExecutionRequest,
        allRequests: List<ToolExecutionRequest>,
        sessionId: String,
    ): AgentEvent.AskUserRequest {
        val args = mapper.readTree(request.arguments())
        val questionsNode = args.get("questions") ?: args.get("arguments")

        val questions = if (questionsNode != null && questionsNode.isArray) {
            questionsNode.map { q ->
                val questionText = q.get("question")?.asText() ?: "Unknown"
                val options = q.get("options")?.map { it.asText() }?.filterNotNull() ?: emptyList()
                val allowCustom = q.get("allowCustom")?.asBoolean() ?: true
                AskUserQuestion(
                    question = questionText,
                    options = options,
                    allowCustom = allowCustom,
                    inputMode = if (options.isEmpty()) "text" else "choice",
                )
            }
        } else {
            listOf(
                AskUserQuestion(
                    question = args.get("question")?.asText() ?: "I need your input",
                    options = emptyList(),
                    allowCustom = true,
                    inputMode = "text",
                )
            )
        }

        return AgentEvent.AskUserRequest(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            toolId = "askUser",
            sessionId = sessionId,
            questions = questions,
            toolCallId = request.id(),
        )
    }

    private fun deserializeArgs(method: java.lang.reflect.Method, jsonArgs: String): Array<Any?> {
        val params = method.parameters
        if (params.isEmpty()) return emptyArray()
        val tree = mapper.readTree(jsonArgs)
        return params.map { param ->
            val name = param.name ?: return@map null
            val node = tree.get(name)
            when {
                node == null || node.isNull -> null
                param.type == String::class.java -> node.asText()
                param.type == Int::class.java || param.type == Int::class.javaPrimitiveType -> node.asInt()
                param.type == Long::class.java || param.type == Long::class.javaPrimitiveType -> node.asLong()
                param.type == Boolean::class.java || param.type == Boolean::class.javaPrimitiveType -> node.asBoolean()
                param.type == Double::class.java || param.type == Double::class.javaPrimitiveType -> node.asDouble()
                param.type == List::class.java -> mapper.treeToValue(node, List::class.java)
                param.type == Map::class.java -> mapper.treeToValue(node, Map::class.java)
                else -> mapper.treeToValue(node, param.type)
            }
        }.toTypedArray()
    }

    private fun buildSystemPrompt(request: AgentRequest): String {
        val systemRequest = SystemPromptRequest(
            personality = request.personality,
            clientTimezone = request.clientTimezone,
            clientTimeMillis = request.clientTimeMillis,
            section = request.section,
            userId = request.userId,
        )
        return systemPromptBuilder.build(systemRequest)
    }

    private fun deserializeHistory(json: String): List<ChatMessage> {
        return try {
            mapper.readValue(json, object : com.fasterxml.jackson.core.type.TypeReference<List<ChatMessage>>() {})
        } catch (e: Exception) {
            logger.warn("[AskUserEngine] Failed to deserialize history: ${e.message}")
            emptyList()
        }
    }
}
