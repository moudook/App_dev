@file:Suppress("DEPRECATION")

package com.example.smarty.features.voice.domain

import android.content.Context
import com.example.smarty.features.settings.domain.SettingsFeatureManager
import android.media.AudioManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import com.example.smarty.service.AudioPlayerService

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Centralized manager for Voice interactions and Wake Word detection.
 * Hybridizes logic for:
 * - Offline Vosk wake word detection
 * - Audio focus management
 * - Phone call state observation
 * - Microphone privacy controls
 *
 * This manager ensures that voice triggers are handled consistently across the app.
 */
class VoiceFeatureManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsManager: SettingsFeatureManager
) {
    companion object {
        private const val TAG = "VoiceFeatureManager"
    }

    // private var voskWakeWordManager: VoskWakeWordManager? = null

    private val _isWakeWordActive = MutableStateFlow(false)
    val isWakeWordActive: StateFlow<Boolean> = _isWakeWordActive.asStateFlow()

    private val _wakeWordTriggered = MutableStateFlow(false)
    val wakeWordTriggered: StateFlow<Boolean> = _wakeWordTriggered.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    private var isPhoneCallActive = false
    private var isAudioFocusLost = false
    private var isInAppAudioPlaying = false
    private var isMicInUseByOther = false

    private var wakeWordCollectorJob: Job? = null
    private var audioPlayerCollectorJob: Job? = null
    private var musicCheckJob: Job? = null

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var audioManager: AudioManager? = null

    init {
        // PERF: Vosk disabled
        /*
        initPhoneCallListener()
        initAudioFocusListener()
        */
    }

    fun initVoskWakeWord() {
        // PERF: Vosk disabled
        Log.d(TAG, "Vosk wake word is DISABLED for cloud migration.")
        /*
        if (voskWakeWordManager != null && !voskWakeWordManager!!.isDestroyed) return

        if (voskWakeWordManager?.isDestroyed == true) {
            voskWakeWordManager = null
        }

        voskWakeWordManager = VoskWakeWordManager(
            context = context.applicationContext,
            scope = scope,
            onWakeWordDetected = {
                Log.i(TAG, "Wake word detected")
                _wakeWordTriggered.value = true
            }
        )
        voskWakeWordManager?.initialize()

        wakeWordCollectorJob?.cancel()
        wakeWordCollectorJob = scope.launch {
            voskWakeWordManager?.isListening?.collect { isListening ->
                _isWakeWordActive.value = isListening
            }
        }
        */
    }

    fun startWakeWordDetection(isPrivacyModeActive: Boolean) {
        // PERF: Vosk disabled
        /*
        startMusicCheck()

        if (isPrivacyModeActive) {
            Log.d(TAG, "Skipping wake word start - privacy mode active")
            return
        }

        if (audioManager?.isMusicActive == true) {
            Log.d(TAG, "Skipping wake word start - system music active")
            isAudioFocusLost = true
            return
        }

        isAudioFocusLost = false
        voskWakeWordManager?.startListening()
        */
    }

    fun stopWakeWordDetection() {
        // PERF: Vosk disabled
        /*
        voskWakeWordManager?.stopListening()
        stopMusicCheck()
        */
    }

    fun resetWakeWordTrigger() {
        _wakeWordTriggered.value = false
    }

    fun triggerVoiceInput() {
        _wakeWordTriggered.value = true
    }

    fun setAppInForeground(inForeground: Boolean) {
        _isAppInForeground.value = inForeground
        if (!inForeground) {
            stopWakeWordDetection()
        } else {
            maybeResumeVosk()
        }
    }

    fun setMicInUse(inUse: Boolean) {
        isMicInUseByOther = inUse
        if (inUse) {
            voskWakeWordManager?.stopListening()
        } else {
            maybeResumeVosk()
        }
    }

    private fun maybeResumeVosk() {
        // PERF: Vosk disabled
        /*
        if (!_isAppInForeground.value || isPhoneCallActive || isAudioFocusLost ||
            isInAppAudioPlaying || isMicInUseByOther || VoskWakeWordManager.isGloballyPaused ||
            audioManager?.isMusicActive == true) {
            return
        }
        voskWakeWordManager?.restartListening()
        */
    }

    private fun initPhoneCallListener() {
        try {
            telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_RINGING,
                        TelephonyManager.CALL_STATE_OFFHOOK -> {
                            isPhoneCallActive = true
                            stopWakeWordDetection()
                        }
                        TelephonyManager.CALL_STATE_IDLE -> {
                            if (isPhoneCallActive) {
                                isPhoneCallActive = false
                                maybeResumeVosk()
                            }
                        }
                    }
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init phone listener", e)
        }
    }

    private fun initAudioFocusListener() {
        try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager?.isMusicActive == true) {
                isAudioFocusLost = true
            }
            startInAppAudioObserver()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init audio focus listener", e)
        }
    }

    private fun startInAppAudioObserver() {
        // PERF: Vosk disabled
        /*
        audioPlayerCollectorJob?.cancel()
        audioPlayerCollectorJob = scope.launch {
            AudioPlayerService.playerState.collect { state ->
                val wasPlaying = isInAppAudioPlaying
                isInAppAudioPlaying = state.isPlaying

                if (isInAppAudioPlaying && !wasPlaying) {
                    voskWakeWordManager?.stopListening()
                } else if (!isInAppAudioPlaying && wasPlaying) {
                    maybeResumeVosk()
                }
            }
        }
        */
    }

    private fun startMusicCheck() {
        // PERF: Vosk disabled
        /*
        musicCheckJob?.cancel()
        musicCheckJob = scope.launch {
            while (isActive) {
                delay(2000L)
                if (!_isAppInForeground.value) continue

                val isMusicPlaying = audioManager?.isMusicActive == true
                if (isMusicPlaying && !isInAppAudioPlaying && !isAudioFocusLost) {
                    isAudioFocusLost = true
                    voskWakeWordManager?.stopListening()
                } else if (!isMusicPlaying && isAudioFocusLost) {
                    isAudioFocusLost = false
                    maybeResumeVosk()
                }
            }
        }
        */
    }

    private fun stopMusicCheck() {
        musicCheckJob?.cancel()
        musicCheckJob = null
    }

    /**
     * Clean up resources when manager is no longer needed.
     */
    fun destroy() {
        // voskWakeWordManager?.destroy()
        // voskWakeWordManager = null

        wakeWordCollectorJob?.cancel()
        wakeWordCollectorJob = null

        audioPlayerCollectorJob?.cancel()
        audioPlayerCollectorJob = null

        musicCheckJob?.cancel()
        musicCheckJob = null

        // Unregister listeners
        try {
            phoneStateListener?.let { listener ->
                @Suppress("DEPRECATION")
                telephonyManager?.listen(listener, android.telephony.PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up phone state listener", e)
        }
        phoneStateListener = null
        telephonyManager = null
        audioManager = null
    }
}


