package com.example.smarty.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Service that manages the voice interaction session.
 * This is the entry point for the Android Assistant API.
 */
class AssistInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistInteractionSession(this)
}
