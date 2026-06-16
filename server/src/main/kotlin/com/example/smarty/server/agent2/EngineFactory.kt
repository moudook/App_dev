package com.example.smarty.server.agent2

import com.example.smarty.server.agent.ApplicationAttributes
import io.ktor.server.application.Application

object EngineFactory {
    fun createEngine(application: Application): AgentEngine {
        val chatModelFactory = application.attributes
            .getOrNull(ApplicationAttributes.CHAT_MODEL_FACTORY)
        val contextWindowManager = application.attributes
            .getOrNull(ApplicationAttributes.CONTEXT_WINDOW_MANAGER)

        require(chatModelFactory != null) { "CHAT_MODEL_FACTORY not initialized. Ensure Application.kt initializes LangChain4j attributes before configureChatRoutes." }
        require(contextWindowManager != null) { "CONTEXT_WINDOW_MANAGER not initialized. Ensure Application.kt initializes LangChain4j attributes before configureChatRoutes." }

        val usageListener = LoggingChatUsageListener()
        val listenerAdapter = ChatModelListenerAdapter(usageListener)

        val factoryWithListeners = OpenRouterChatModelFactory(
            listeners = listOf(listenerAdapter),
        )

        val chatMemoryStore = application.attributes
            .getOrNull(ApplicationAttributes.CHAT_MEMORY_STORE)

        return AiServicesEngine(
            chatModelFactory = factoryWithListeners,
            contextWindowManager = contextWindowManager,
            chatMemoryStore = chatMemoryStore,
        )
    }
}
