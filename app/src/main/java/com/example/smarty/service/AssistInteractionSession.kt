package com.example.smarty.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.example.smarty.features.chat.ui.AssistActivity

/**
 * Session that handles the Smarty interaction.
 * When the user triggers Smarty (e.g., long press home),
 * this session is created and launches the AssistActivity.
 */
class AssistInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // Disable default session UI since we launch our own Activity
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        // Launch our transparent AssistActivity
        val intent = Intent(context, AssistActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Pass any context/args if needed (e.g. screen context)
        if (args != null) {
            intent.putExtras(args)
        }

        startAssistantActivity(intent)

        // We don't need to keep this session UI showing since we launched an Activity
        // But we keep it alive briefly to ensure the transition is smooth
        hide()
    }
}
