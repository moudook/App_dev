package com.example.smarty.server.agent2

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Intelligent Compactor")
class CompactorTest {

    @Test
    @DisplayName("Small message list should not trigger compaction")
    fun smallListNoCompaction() {
        val compactor = IntelligentCompactor()
        val messages = listOf(
            SystemMessage("You are Friday."),
            UserMessage("Hello"),
            AiMessage("Hi there!"),
        )
        assertFalse(compactor.shouldCompact(messages))
    }

    @Test
    @DisplayName("Large message list should trigger compaction")
    fun largeListTriggersCompaction() {
        val compactor = IntelligentCompactor()
        val messages = (1..35).map { UserMessage("Message $it") }
        assertTrue(compactor.shouldCompact(messages))
    }

    @Test
    @DisplayName("System messages are classified as verbatim")
    fun systemMessagesAreVerbatim() {
        val compactor = IntelligentCompactor()
        val messages = listOf(
            SystemMessage("You are Friday."),
            UserMessage("Hello"),
            AiMessage("Hi!"),
        )
        val plan = compactor.plan(messages)
        assertTrue(plan.preservedMessages.any { it is SystemMessage })
    }

    @Test
    @DisplayName("Entity IDs are extracted from tool results")
    fun entityIdsExtracted() {
        val compactor = IntelligentCompactor()
        val messages = listOf(
            UserMessage("Save flight info"),
            AiMessage.from(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .id("call-1").name("saveNote")
                    .arguments("""{"title":"Tokyo flight","content":"JAL 123"}""").build()
            ),
            ToolExecutionResultMessage("call-1", "saveNote", "Saved as note_abc123"),
            AiMessage("Done! note_abc123 is ready."),
        )
        val plan = compactor.plan(messages)
        assertTrue(plan.conversationState.contains("note_abc123"))
    }

    @Test
    @DisplayName("Plan includes conversation state section")
    fun planIncludesState() {
        val compactor = IntelligentCompactor()
        val messages = listOf(
            UserMessage("What's the weather?"),
            AiMessage("It's sunny!"),
        )
        val plan = compactor.plan(messages)
        assertTrue(plan.conversationState.isNotBlank())
    }

    @Test
    @DisplayName("Execute combines state and preserved messages")
    fun executeProducesMessages() {
        val compactor = IntelligentCompactor()
        val messages = listOf(
            SystemMessage("You are Friday."),
            UserMessage("Hello"),
            UserMessage("How are you?"),
            UserMessage("Still there?"),
            UserMessage("Hello?"),
            UserMessage("This is a long conversation"),
            UserMessage("Testing compaction"),
            UserMessage("One more message"),
            UserMessage("And another"),
            UserMessage("And the last one"),
        )
        val plan = compactor.plan(messages, "CONCISE")
        val compacted = compactor.execute(plan)
        assertTrue(compacted.isNotEmpty())
        // CONCISE mode: 1 state message + system + max 6 non-system = 8 max
        assertTrue(compacted.size <= 8)
        assertTrue(compacted.size >= 2, "Should have at least state + some preserved")
    }

    @Test
    @DisplayName("Dropped messages are counted")
    fun droppedCounted() {
        val compactor = IntelligentCompactor()
        val messages = listOf(
            SystemMessage("Identity"),
            UserMessage("Create a note"),
            AiMessage("I've asked you a question"), // should be dropped
            ToolExecutionResultMessage("call-1", "saveNote", """{"success":false,"error":"fail"}"""),
        )
        val plan = compactor.plan(messages)
        assertTrue(plan.droppedCount > 0)
    }

    @Test
    @DisplayName("CONCISE personality keeps fewer exchanges")
    fun conciseKeepsFewer() {
        val compactor = IntelligentCompactor()
        val messages = listOf(
            SystemMessage("Identity"),
            UserMessage("1"), AiMessage("A"),
            UserMessage("2"), AiMessage("B"),
            UserMessage("3"), AiMessage("C"),
            UserMessage("4"), AiMessage("D"),
            UserMessage("5"), AiMessage("E"),
            UserMessage("6"), AiMessage("F"),
        )
        val concisePlan = compactor.plan(messages, "CONCISE")
        val detailedPlan = compactor.plan(messages, "DETAILED")
        assertTrue(concisePlan.preservedMessages.size <= detailedPlan.preservedMessages.size)
    }
}
