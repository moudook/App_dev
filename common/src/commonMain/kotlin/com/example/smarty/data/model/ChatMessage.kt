package com.example.smarty.core.domain.model

// import java.util.UUID

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Role in a chat conversation
 */
@Serializable
enum class ChatRole {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    SMARTY,

    @SerialName("system")
    SYSTEM,
}

/**
 * Result of an agent action execution
 */
data class AgentActionResult(
    val action: String,
    val success: Boolean,
    val resultSummary: String,
    val affectedNoteIds: List<String> = emptyList(),
)

/**
 * A single agentic step in the agent's execution timeline.
 * Represents one discrete phase: thinking, tool call, checkpoint, etc.
 *
 * @param stepType   "thinking" | "tool_call" | "opencode_tool" | "checkpoint"
 * @param stepTitle  Human-readable title (e.g., "Thinking…", "Searching the web")
 * @param stepContent Accumulated content (reasoning text, tool output summary)
 * @param stepStatus "started" | "streaming" | "completed" | "failed"
 * @param stepIndex  Monotonically increasing per conversation turn
 * @param toolName   Machine tool name (for tool_call/opencode_tool types)
 * @param durationMs How long this step took (set on completed/failed)
 */
@Serializable
data class AgentStepEntry(
    val stepType: String,
    val stepTitle: String,
    val stepContent: String = "",
    val stepStatus: String = "started",
    val stepIndex: Int = 0,
    val toolName: String? = null,
    val durationMs: Long? = null,
)

/**
 * A single search query + its result, used inside an [AgentToolCallEntry].
 * Allows the UI to show expandable result cards for each parallel web search.
 */
data class SearchQueryEntry(
    val query: String,
    val result: String? = null,
)

/**
 * An action block recorded during agent reasoning.
 * Stored alongside the message so previous chat sessions restore the full trace.
 *
 * @param toolName   machine name ("search_web", "memory_save", …)
 * @param status     "started" | "completed" | "failed"
 * @param displayName friendly header for the action card
 * @param inputSummary  what the agent sent to the tool (e.g. the search query)
 * @param outputSummary abbreviated tool output (~800 chars)
 * @param searchQueries for web search tools: each discrete query + its result
 * @param timestamp  epoch ms when the tool call finished
 */
data class AgentToolCallEntry(
    val toolName: String,
    val status: String,
    val displayName: String,
    val inputSummary: String? = null,
    val outputSummary: String? = null,
    val searchQueries: List<SearchQueryEntry> = emptyList(),
    val timestamp: Long = 0L,
    val toolCallId: String = "",
)

/**
 * Citation/source from web research
 */
@Serializable
data class Citation(
    val title: String,
    val url: String,
    val snippet: String,
)

/**
 * Image to display inline in chat message.
 * Used by ViewImageTool to show images from notes in AI responses.
 */
data class InlineChatImage(
    val uri: String,
    val fileName: String,
    val noteTitle: String,
)

/**
 * A single message in the chat conversation
 */
data class ChatMessage(
    val id: String, // = UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String,
    val thinking: String? = null, // AI thinking/reasoning process (collapsible)
    val toolCalls: List<AgentToolCallEntry> = emptyList(), // Structured action blocks
    val agentSteps: List<AgentStepEntry> = emptyList(), // Agentic step timeline
    val attachments: List<Attachment> = emptyList(),
    val timestamp: Long = 0L, // System.currentTimeMillis(),
    val executedActions: List<AgentActionResult> = emptyList(),
    val referencedNoteIds: List<String> = emptyList(),
    val isAudioRelated: Boolean = false, // True when user asked about audio/music playback
    val suggestions: List<String> = emptyList(), // AI-provided suggestions (max 2)
    val isError: Boolean = false, // True when this message represents an API error
    val isStreaming: Boolean = false, // True when AI response is still being received
    val citations: List<Citation> = emptyList(), // Sources from web research
    val inlineImages: List<InlineChatImage> = emptyList(), // Images from ViewImageTool to display inline
    val clarificationRequest: ClarificationRequest? = null, // Interactive clarification request
    val noteReferences: List<NoteReference> = emptyList(), // Clickable note cards in AI response
    val eventReferences: List<EventReference> = emptyList(), // Clickable event cards in AI response
    val confidence: String? = null, // Server-computed confidence: verified, moderate, model_knowledge
    val sourceType: String? = null, // Source type: web_search, user_data, model_knowledge
    // Transient — raw canonical AgentEvent stream for this turn (NOT persisted in Room).
    // The AgentTimelineItem uses a TimelineNodeAggregator to derive UI nodes from this list.
    @Transient
    val agentEvents: List<com.example.smarty.protocol.AgentEvent> = emptyList(),
) {
    /**
     * Check if this message has a thinking/reasoning section
     */
    val hasThinking: Boolean get() = !thinking.isNullOrBlank()

    /**
     * Check if this message has agent action blocks to display
     */
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()

    /**
     * Show the full Action Panel if there's thinking, tool call blocks, or agent steps
     */
    val hasActionPanel: Boolean get() = hasToolCalls || hasAgentSteps

    val hasAgentSteps: Boolean get() = agentSteps.isNotEmpty()

    /**
     * Check if this is a user message
     */
    val isUser: Boolean get() = role == ChatRole.USER

    /**
     * Check if this is a Smarty response
     */
    val isSmarty: Boolean get() = role == ChatRole.SMARTY

    /**
     * Check if this is a system message
     */
    val isSystem: Boolean get() = role == ChatRole.SYSTEM

    /**
     * Check if any actions were executed
     */
    val hasActions: Boolean get() = executedActions.isNotEmpty()

    /**
     * Check if all actions succeeded
     */
    val allActionsSucceeded: Boolean get() = executedActions.all { it.success }

    /**
     * Check if suggestions should be shown (not an error and has suggestions)
     */
    val hasSuggestions: Boolean get() = !isError && suggestions.isNotEmpty()

    /**
     * Check if citations/sources are available
     */
    val hasCitations: Boolean get() = citations.isNotEmpty()

    /**
     * Check if inline images are available to display
     */
    val hasInlineImages: Boolean get() = inlineImages.isNotEmpty()
}
