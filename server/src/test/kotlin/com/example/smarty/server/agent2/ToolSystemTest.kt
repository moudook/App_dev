package com.example.smarty.server.agent2

import dev.langchain4j.agent.tool.ToolSpecifications
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Agent2 Tool System")
class ToolSystemTest {

    @Test
    @DisplayName("ToolRegistry creates all expected tools")
    fun toolRegistryHasAllTools() {
        val registry = com.example.smarty.server.agent2.tools.ToolRegistry()
        val tools = registry.getAllTools()
        assertEquals(11, tools.size, "Should have 11 tool objects")
    }

    @Test
    @DisplayName("ToolSpecifications extracted from all tool objects")
    fun toolSpecificationsFromRegistry() {
        val registry = com.example.smarty.server.agent2.tools.ToolRegistry()
        val tools = registry.getAllTools()
        val specs = tools.flatMap { ToolSpecifications.toolSpecificationsFrom(it) }
        assertTrue(specs.isNotEmpty(), "Should extract tool specifications")
        val names = specs.map { it.name() }.toSet()
        assertTrue(names.contains("saveNote"), "Should contain saveNote tool")
        assertTrue(names.contains("webSearch"), "Should contain webSearch tool")
        assertTrue(names.contains("askUser"), "Should contain askUser tool")
    }

    @Test
    @DisplayName("SimpleChatMemory stores and retrieves messages")
    fun simpleChatMemoryWorks() {
        val memory = SimpleChatMemory("test-session")
        assertTrue(memory.id() == "test-session")
        assertTrue(memory.messages().isEmpty())

        val msg = dev.langchain4j.data.message.UserMessage("Hello")
        memory.add(msg)
        assertEquals(1, memory.messages().size)
        assertEquals("Hello", (memory.messages()[0] as dev.langchain4j.data.message.UserMessage).singleText())

        memory.clear()
        assertTrue(memory.messages().isEmpty())
    }

    @Test
    @DisplayName("SystemPromptBuilder generates valid prompts")
    fun systemPromptBuilderWorks() {
        val builder = SystemPromptBuilder()
        val request = SystemPromptRequest(
            personality = "DETAILED",
            clientTimezone = "America/New_York",
            clientTimeMillis = 1700000000000,
            section = "chat",
            userId = "test-user",
        )
        val prompt = builder.build(request)
        assertNotNull(prompt)
        assertTrue(prompt.contains("thorough"), "Should include DETAILED personality traits")
        assertTrue(prompt.contains("Friday"), "Should include identity")
    }

    @Test
    @DisplayName("ChatUsageListener tracks usage")
    fun chatUsageListenerWorks() {
        val listener = LoggingChatUsageListener()
        listener.onRequest("test-model")
        listener.onResponse(ChatUsage("test-model", 10, 20, 30, 100))
        listener.onError("test-model", RuntimeException("test error"))
        // No exception means success
    }
}
