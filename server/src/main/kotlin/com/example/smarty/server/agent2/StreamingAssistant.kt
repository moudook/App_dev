package com.example.smarty.server.agent2

import dev.langchain4j.service.MemoryId
import dev.langchain4j.service.TokenStream
import dev.langchain4j.service.UserMessage

interface StreamingAssistant {
    fun chat(
        @MemoryId memoryId: Any,
        @UserMessage userMessage: String,
    ): TokenStream
}
