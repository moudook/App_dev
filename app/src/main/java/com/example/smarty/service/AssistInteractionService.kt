package com.example.smarty.service

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * VoiceInteractionService for Smarty/Smarty Assistant
 *
 * This service is required for Android to recognize the app as a digital assistant.
 * When set as the default assistant, this service handles the assistant trigger
 * (edge swipe, home button hold, etc.).
 */
class AssistInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "AssistInteractionSvc"
    }

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "Assistant service ready")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.d(TAG, "Assistant service shutdown")
    }
}
