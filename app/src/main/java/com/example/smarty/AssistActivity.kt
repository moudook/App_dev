package com.example.smarty

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.data.repository.ChatRepository
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.service.AlarmScheduler
import com.example.smarty.ui.screens.AssistOverlayScreen
import com.example.smarty.ui.theme.SmartyTheme
import com.example.smarty.viewmodel.AssistViewModel
import com.example.smarty.viewmodel.AssistViewModelFactory
import com.example.smarty.voice.VoskWakeWordManager
import java.util.concurrent.TimeUnit

/**
 * Assistant Overlay Activity - "Soft Tech" Floating Pill
 *
 * Triggered by Android's assistant gesture.
 * Features:
 * - Fully transparent background
 * - Modern floating UI
 * - Voice and text input
 */
class AssistActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AssistActivity"
    }

    private val securePreferences: SecurePreferences by lazy { SecurePreferences.getInstance(application) }
    private val database: SmartyDatabase by lazy { SmartyDatabase.getDatabase(application) }
    private val repository: SmartyRepository by lazy {
        SmartyRepository(database.noteDao(), database.categoryDao(), database.calendarDao(), database.noteVersionDao())
    }
    private val chatRepository by lazy { ChatRepository(database.chatDao()) }
    private val alarmScheduler: AlarmScheduler by lazy { AlarmScheduler.getInstance(application) }

    // Use ViewModel factory for dependency injection
    private val viewModel: AssistViewModel by viewModels {
        AssistViewModelFactory(
            application,
            repository,
            chatRepository,
            alarmScheduler,
            database.aiMemoryDao()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pause wake word detection while overlay is active to avoid mic conflict
        VoskWakeWordManager.isGloballyPaused = true

        // CRITICAL: Setup transparent window BEFORE setContentView
        setupTransparentWindow()

        setContent {
            // Apply Smarty Theme with isTransparent=true to ensure proper window setup
            val isDarkTheme by securePreferences.isDarkTheme.collectAsState(initial = true)

            SmartyTheme(darkTheme = isDarkTheme, isTransparent = true) {
                AssistOverlayScreen(
                    viewModel = viewModel,
                    onDismiss = { finishWithAnimation() }
                )
            }
        }

        // Handle incoming assist context if needed
        handleAssistContext()
    }

    private fun handleAssistContext() {
        // Extract selected text from Intent
        // Note: EXTRA_ASSIST_BUNDLE is "android.intent.extra.ASSIST_BUNDLE"
        try {
            val assistBundle = intent.getBundleExtra("android.intent.extra.ASSIST_BUNDLE")
            val selectedText = assistBundle?.getString(android.content.Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT)?.toString()

            val referringPackage = referrer?.host ?: ""

            if (!selectedText.isNullOrBlank()) {
                viewModel.setAssistContext(selectedText, referringPackage)
            }
        } catch (e: Exception) {
            // Safely ignore if bundle extraction fails
        }
    }

    /**
     * Setup window for true transparency - background app remains visible
     */
    private fun setupTransparentWindow() {
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            @Suppress("DEPRECATION")
            statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            navigationBarColor = Color.TRANSPARENT
            setDimAmount(0f)
        }
    }

    /**
     * Finish activity with smooth transition (no animation)
     */
    private fun finishWithAnimation() {
        // Resume wake word detection
        VoskWakeWordManager.isGloballyPaused = false
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure wake word detection is resumed even if destroyed by system
        VoskWakeWordManager.isGloballyPaused = false
    }

    /**
     * Fallback for back button press
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWithAnimation()
    }
}
