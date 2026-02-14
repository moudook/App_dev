package com.example.smarty.features.chat.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.MentionSuggestion
import com.example.smarty.di.ServiceLocator
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val chatFeatureManager = ServiceLocator.provideChatFeatureManager(application, viewModelScope)
    private val sharedAppState = ServiceLocator.provideSharedAppState()

    // State
    val isChatMode = chatFeatureManager.isChatMode
    val chatMessages = chatFeatureManager.chatMessages
    val isChatProcessing = chatFeatureManager.isChatProcessing
    val currentSessionId = chatFeatureManager.currentSessionId
    val chatSessions = chatFeatureManager.chatSessions
    val mentionState = chatFeatureManager.mentionState
    val pendingChatText = chatFeatureManager.pendingChatText
    val navigationRequest = sharedAppState.navigationRequest
    val proactiveSuggestion = chatFeatureManager.proactiveSuggestion

    // Actions
    fun toggleChatMode(fromShake: Boolean = false) {
        chatFeatureManager.toggleChatMode(fromShake)
    }

    fun enterChatMode() {
        viewModelScope.launch {
            chatFeatureManager.enterChatMode()
        }
    }

    fun exitChatMode() {
        chatFeatureManager.exitChatMode()
    }

    fun createNewChatSession() {
        chatFeatureManager.createNewChatSession()
    }

    fun switchToChatSession(sessionId: String) {
        chatFeatureManager.switchToChatSession(sessionId)
    }

    fun deleteChatSession(sessionId: String) {
        chatFeatureManager.deleteChatSession(sessionId)
    }

    fun clearChatHistory() {
        chatFeatureManager.clearChatHistory()
    }

    fun enterChatWithNoteReference(noteTitle: String) {
        chatFeatureManager.enterChatWithNoteReference(noteTitle)
    }

    fun clearPendingChatText() {
        chatFeatureManager.clearPendingChatText()
    }

    fun sendChatMessage(content: String, attachments: List<Attachment> = emptyList()) {
        chatFeatureManager.sendChatMessage(content, attachments)
    }

    fun updateMentionState(text: String, cursorPosition: Int) {
        chatFeatureManager.updateMentionState(text, cursorPosition)
    }

    fun onMentionSelected(suggestion: MentionSuggestion, currentText: String): String {
        return chatFeatureManager.onMentionSelected(suggestion, currentText)
    }

    fun dismissMention() {
        chatFeatureManager.dismissMention()
    }

    fun dispatchQuery(content: String, attachments: List<Attachment> = emptyList()) {
        chatFeatureManager.dispatchQuery(content, attachments)
    }

    fun acceptSuggestion() {
        chatFeatureManager.acceptSuggestion()
    }

    fun dismissSuggestion() {
        chatFeatureManager.dismissSuggestion()
    }

    fun clearNavigationRequest() {
        chatFeatureManager.clearNavigationRequest()
    }
}

