package com.example.smarty.server.agent2

import dev.langchain4j.model.chat.listener.ChatModelListener
import dev.langchain4j.model.chat.listener.ChatModelRequestContext
import dev.langchain4j.model.chat.listener.ChatModelResponseContext
import dev.langchain4j.model.chat.listener.ChatModelErrorContext
import dev.langchain4j.model.output.TokenUsage
import org.slf4j.LoggerFactory

class ChatModelListenerAdapter(
    private val delegate: ChatUsageListener,
) : ChatModelListener {
    private val logger = LoggerFactory.getLogger(ChatModelListenerAdapter::class.java)

    override fun onRequest(context: ChatModelRequestContext) {
        val model = context.chatRequest().modelName() ?: "unknown"
        delegate.onRequest(model)
    }

    override fun onResponse(context: ChatModelResponseContext) {
        val model = context.chatRequest().modelName() ?: "unknown"
        val response = context.chatResponse()
        val tokenUsage = response.tokenUsage()
        val usage = ChatUsage(
            modelId = model,
            promptTokens = tokenUsage?.inputTokenCount() ?: 0,
            completionTokens = tokenUsage?.outputTokenCount() ?: 0,
            totalTokens = tokenUsage?.totalTokenCount() ?: 0,
        )
        delegate.onResponse(usage)
    }

    override fun onError(context: ChatModelErrorContext) {
        val model = context.chatRequest().modelName() ?: "unknown"
        delegate.onError(model, context.error())
    }
}
