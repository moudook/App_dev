package com.example.smarty.features.chat.domain.state

import androidx.compose.ui.text.input.TextFieldValue
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.MentionState

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
    val isResearchMode: Boolean = false,
    val isHistoryMode: Boolean = false,
    val showScrollToBottom: Boolean = false,
    val isAtLatestMessage: Boolean = true,
    val selectedFilters: Set<AttachmentOption> = emptySet(),
    val isSearchMode: Boolean = false,
    val isVoiceListening: Boolean = false,
    val isRecording: Boolean = false,
    val isAgentWorking: Boolean = false,
    val autoSendActive: Boolean = false,
    val attachments: List<Attachment> = emptyList()
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
