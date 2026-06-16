package com.example.smarty.server.agent2

import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.memory.ChatMemory

class SimpleChatMemory(
    private val memoryId: Any,
    private val maxTokens: Int = 128_000,
) : ChatMemory {
    private val messages = mutableListOf<ChatMessage>()

    override fun id(): Any = memoryId

    override fun add(message: ChatMessage) {
        messages.add(message)
    }

    override fun messages(): List<ChatMessage> = messages.toList()

    override fun clear() = messages.clear()
}
