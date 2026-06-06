package com.example.smarty.features.chat.domain.state

import androidx.compose.ui.text.input.TextFieldValue
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.MentionState

/**
 * Fallback model list — used only before the server responds with the real list.
 * The server connects to the Zen API to fetch free models.
 *
 * IMPORTANT: ONLY include models that the server actually returns.
 * Current models: deepseek-v4-flash-free, nemotron-3-super-free, qwen3.6-plus-free.
 */
val DEFAULT_FREE_MODELS = listOf(
    "default" to "Default Model",
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
    val selectedModel: String = "default",
    val availableModels: List<Pair<String, String>> = DEFAULT_FREE_MODELS,
    val modelVariantMap: Map<String, List<String>> = emptyMap(),
    val selectedVariant: String? = null,
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

    companion object {
        fun fromAttachmentType(type: com.example.smarty.core.domain.model.AttachmentType): AttachmentOption =
            when (type.name) {
                "IMAGE" -> Image
                "VIDEO" -> Video
                "DOCUMENT" -> Document
                "AUDIO" -> Audio
                "LINK" -> Link
                else -> Document // Default fallback for any other type
            }
    }
}
