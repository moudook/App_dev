package com.example.smarty.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import com.example.smarty.AssistActivity

/**
 * VoiceInteractionSession that launches the AssistActivity overlay.
 *
 * This session uses startAssistantActivity() to launch a proper Activity
 * with full theme and animation support for Gemini-style overlay behavior.
 *
 * The actual UI and logic is in AssistActivity - this session just:
 * 1. Creates the session when assistant is triggered
 * 2. Launches AssistActivity via startAssistantActivity()
 * 3. Receives show/hide callbacks
 */
class AssistInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "AssistSession"
        const val EXTRA_FROM_VOICE_INTERACTION = "from_voice_interaction"
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        
        // Check if we should show with assist context (preserves background app visibility)
        val isShowWithAssist = (showFlags and SHOW_WITH_ASSIST) != 0
        Log.d(TAG, "Session shown - showFlags=$showFlags, isShowWithAssist=$isShowWithAssist")

        // Launch the AssistActivity as the floating overlay
        val intent = Intent(context, AssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Don't use FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS to keep it minimal
            putExtra(EXTRA_FROM_VOICE_INTERACTION, true)
            putExtra("show_with_assist", isShowWithAssist)
        }

        startAssistantActivity(intent)
    }

    override fun onHide() {
        super.onHide()
        Log.d(TAG, "Session hidden")
    }

    override fun onHandleAssist(
        state: AssistState
    ) {
        super.onHandleAssist(state)
        Log.d(TAG, "Handle assist called")
    }

    override fun onCreateContentView(): View? {
        // We don't use content view - we launch an Activity instead
        // Return null to indicate no embedded UI
        return null
    }
}
