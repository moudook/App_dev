package com.example.smarty.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * VoiceInteractionSessionService for creating assistant sessions.
 *
 * This service creates new VoiceInteractionSession instances when the
 * assistant is triggered.
 */
class AssistInteractionSessionService : VoiceInteractionSessionService() {

    companion object {
        private const val TAG = "AssistSessionSvc"
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.d(TAG, "Creating new assistant session")
        return AssistInteractionSession(this)
    }
}
