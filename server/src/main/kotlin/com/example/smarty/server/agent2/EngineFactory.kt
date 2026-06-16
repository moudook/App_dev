package com.example.smarty.server.agent2

import com.example.smarty.server.agent.ApplicationAttributes
import com.example.smarty.server.config.AppConfig
import io.ktor.server.application.Application

object EngineFactory {
    fun createEngine(application: Application): AgentEngine? {
        if (!AppConfig.enableLangChain4j) return null

        val chatModelFactory = application.attributes
            .getOrNull(ApplicationAttributes.CHAT_MODEL_FACTORY)
        val contextWindowManager = application.attributes
            .getOrNull(ApplicationAttributes.CONTEXT_WINDOW_MANAGER)

        if (chatModelFactory == null || contextWindowManager == null) {
            return null
        }

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
