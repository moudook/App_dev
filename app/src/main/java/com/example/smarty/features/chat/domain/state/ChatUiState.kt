package com.example.smarty.features.chat.domain.state

import androidx.compose.ui.text.input.TextFieldValue
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.MentionState

/**
 * Fallback model list — used only before the server responds with the real list.
 * The server discovers free models at runtime via `opencode models`, so this
 * list is a best-effort default that may be stale.
 *
 * IMPORTANT: ONLY include models that the server actually discovers.
 * Current server-discovered models: deepseek-v4-flash-free, nemotron-3-super-free, qwen3.6-plus-free.
 */
val DEFAULT_FREE_MODELS =
    listOf(
        "opencode/deepseek-v4-flash-free" to "DeepSeek V4 Flash Free",
        "opencode/nemotron-3-super-free" to "Nemotron 3 Super Free",
        "opencode/qwen3.6-plus-free" to "Qwen 3.6 Plus Free",
    )

/**
 * UI-specific state for chat screen.
 * Separated from domain state to avoid leaking UI concerns.
 *
 * Principles:
 * - UI-only state (input text, focus, expanded states)
 * - Derived from domain state where possible
 * - Minimizes recomposition scope
 */
data class ChatUiState(
    val inputText: TextFieldValue = TextFieldValue(""),
    val isInputFocused: Boolean = false,
    val showAttachmentPanel: Boolean = false,
    val showHistorySheet: Boolean = false,
    val expandedMessageIds: Set<String> = emptySet(),
    val showContextMenuForMessageId: String? = null,
    val mentionState: MentionState = MentionState(),
    val isHistoryMode: Boolean = false,
    val showScrollToBottom: Boolean = false,
    val isAtLatestMessage: Boolean = true,
    val selectedFilters: Set<AttachmentOption> = emptySet(),
    val isSearchMode: Boolean = false,
    val isVoiceListening: Boolean = false,
    val isRecording: Boolean = false,
    val isAgentWorking: Boolean = false,
    val autoSendActive: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val selectedModel: String = "opencode/deepseek-v4-flash-free",
    val availableModels: List<Pair<String, String>> = DEFAULT_FREE_MODELS,
) {
    val canSend: Boolean = inputText.text.isNotEmpty() || attachments.isNotEmpty()
    val hasActiveFilters: Boolean = selectedFilters.isNotEmpty()
    val isThinkingExpanded: Boolean = expandedMessageIds.isNotEmpty()

    companion object {
        fun initial() = ChatUiState()
    }
}

/**
 * Attachment filter options for search.
 * Extracted to avoid coupling with UI components.
 */
sealed class AttachmentOption {
    data object Image : AttachmentOption()

    data object Video : AttachmentOption()

    data object Document : AttachmentOption()

    data object Audio : AttachmentOption()

    data object Link : AttachmentOption()

    data object Research : AttachmentOption()

    companion object {
        fun fromAttachmentType(type: com.example.smarty.core.domain.model.AttachmentType): AttachmentOption {
            return when (type.name) {
                "IMAGE" -> Image
                "VIDEO" -> Video
                "DOCUMENT" -> Document
                "AUDIO" -> Audio
                "LINK" -> Link
                "RESEARCH" -> Research
                else -> Document // Default fallback for any other type
            }
        }
    }
}
