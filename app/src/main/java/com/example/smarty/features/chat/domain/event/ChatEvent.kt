package com.example.smarty.features.chat.domain.event

import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.MentionSuggestion
import com.example.smarty.features.chat.domain.state.AttachmentOption

/**
 * Sealed class representing all UI events in the chat feature.
 * 
 * Principles:
 * - One-way event flow (UI -> ViewModel)
 * - Intent-based (describes what happened, not how to handle it)
 * - Type-safe through sealed class hierarchy
 */
sealed class ChatEvent {
    
    // User input events
    data class MessageSent(val content: String, val attachments: List<Attachment> = emptyList()) : ChatEvent()
    data class InputTextChanged(val newText: TextFieldValue) : ChatEvent()
    data object InputSubmitted : ChatEvent()
    data object VoiceInputToggled : ChatEvent()
    data object VoiceInputStarted : ChatEvent()
    data object VoiceInputStopped : ChatEvent()
    data object RecordingStarted : ChatEvent()
    data object RecordingStopped : ChatEvent()
    
    // Message interaction events
    data class MessageCopied(val messageId: String, val content: String) : ChatEvent()
    data class MessageDeleted(val messageId: String) : ChatEvent()
    data class MessageRegenerated(val messageId: String) : ChatEvent()
    data class SuggestionClicked(val suggestion: String) : ChatEvent()
    data class ClarificationSubmitted(val messageId: String, val response: String) : ChatEvent()
    data class NoteClicked(val noteId: String) : ChatEvent()
    data class CitationClicked(val citation: com.example.smarty.core.domain.model.Citation) : ChatEvent()
    data class ImageExpanded(val messageId: String, val imageIndex: Int) : ChatEvent()
    
    // UI state events
    data class InputFocusChanged(val isFocused: Boolean) : ChatEvent()
    data class AttachmentPanelToggled(val isVisible: Boolean) : ChatEvent()
    data class HistorySheetToggled(val isVisible: Boolean) : ChatEvent()
    data class ThinkingExpanded(val messageId: String) : ChatEvent()
    data class ThinkingCollapsed(val messageId: String) : ChatEvent()
    data class ContextMenuOpened(val messageId: String) : ChatEvent()
    data object ContextMenuClosed : ChatEvent()
    data class ScrollPositionChanged(val position: Int, val isAtLatest: Boolean) : ChatEvent()
    data object ScrollToBottomRequested : ChatEvent()
    data object ScrollToTopRequested : ChatEvent()
    
    // Attachment events
    data class AttachmentAdded(val attachment: Attachment) : ChatEvent()
    data class AttachmentRemoved(val attachmentId: String) : ChatEvent()
    data class AttachmentPickRequested(val type: AttachmentType) : ChatEvent()
    data object CameraRequested : ChatEvent()
    
    // Chat session events
    data object NewChatRequested : ChatEvent()
    data object ChatHistoryRequested : ChatEvent()
    data class ChatSessionLoaded(val sessionId: String) : ChatEvent()
    data object GenerationStopped : ChatEvent()
    
    // Search and filter events
    data object SearchModeToggled : ChatEvent()
    data object ResearchModeToggled : ChatEvent()
    data class FilterToggled(val filter: AttachmentOption) : ChatEvent()
    data object FiltersCleared : ChatEvent()
    
    // Mention events
    data class MentionSelected(val mention: MentionSuggestion) : ChatEvent()
    data class MentionQueryChanged(val query: String) : ChatEvent()
    
    // Error handling
    data class ErrorOccurred(val message: String, val error: Throwable? = null) : ChatEvent()
    data object ErrorDismissed : ChatEvent()
}

// Type alias for convenience
typealias TextFieldValue = androidx.compose.ui.text.input.TextFieldValue

/**
 * Attachment type for events - mirrors domain AttachmentType
 * but kept in UI layer to avoid circular dependencies.
 */
sealed class AttachmentType {
    data object Image : AttachmentType()
    data object Video : AttachmentType()
    data object Document : AttachmentType()
    data object Audio : AttachmentType()
    data object Link : AttachmentType()
    data object Research : AttachmentType()
    
    companion object {
        fun fromDomain(type: com.example.smarty.core.domain.model.AttachmentType): AttachmentType {
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
