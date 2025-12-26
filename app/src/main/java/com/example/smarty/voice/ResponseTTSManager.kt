package com.example.smarty.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages Text-to-Speech for AI responses.
 *
 * Features:
 * - Fast initialization with lazy engine setup
 * - Immediate speech trigger when AI responds
 * - Configurable speech rate (default: slightly faster for natural feel)
 * - Clean lifecycle management
 */
class ResponseTTSManager(
    private val context: Context
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "ResponseTTSManager"
        private const val DEFAULT_SPEECH_RATE = 1.1f  // Slightly faster than normal
        private const val FAST_SPEECH_RATE = 1.3f
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val utteranceId = AtomicInteger(0)

    // Observable speaking state
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // TTS enabled state (can be toggled by user)
    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    // Pending text to speak (if TTS not yet initialized)
    private var pendingText: String? = null

    // Callbacks for speech lifecycle (for pausing wake word detection)
    private var onSpeechStart: (() -> Unit)? = null
    private var onSpeechEnd: (() -> Unit)? = null

    init {
        initializeTTS()
    }

    private fun initializeTTS() {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to device default
                tts?.setLanguage(Locale.getDefault())
                Log.w(TAG, "US English not available, using device default")
            }

            // Set faster speech rate for snappy responses
            tts?.setSpeechRate(DEFAULT_SPEECH_RATE)

            // Set up progress listener
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                    onSpeechStart?.invoke()  // Pause wake word detection
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    onSpeechEnd?.invoke()  // Resume wake word detection
                }

                @Deprecated("Deprecated in API 21", replaceWith = ReplaceWith("onError(utteranceId, errorCode)"))
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    onSpeechEnd?.invoke()  // Resume on error too
                    Log.e(TAG, "TTS error for utterance: $utteranceId")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _isSpeaking.value = false
                    onSpeechEnd?.invoke()  // Resume on error too
                    Log.e(TAG, "TTS error code $errorCode for utterance: $utteranceId")
                }
            })

            isInitialized = true
            Log.d(TAG, "TTS initialized successfully")

            // Speak any pending text
            pendingText?.let { text ->
                speakInternal(text)
                pendingText = null
            }
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    /**
     * Speak AI response immediately.
     * If TTS not yet initialized, queues the text for when ready.
     */
    fun speak(text: String) {
        if (!_isEnabled.value) {
            Log.d(TAG, "TTS disabled, skipping speech")
            return
        }

        if (text.isBlank()) return

        if (isInitialized) {
            speakInternal(text)
        } else {
            // Queue for when TTS is ready
            pendingText = text
            Log.d(TAG, "TTS not ready, queued text for later")
        }
    }

    private fun speakInternal(text: String) {
        // Clean text for TTS (remove markdown, code blocks, etc.)
        val cleanedText = cleanTextForSpeech(text)

        if (cleanedText.isBlank()) return

        val id = "tts_${utteranceId.incrementAndGet()}"
        tts?.speak(cleanedText, TextToSpeech.QUEUE_FLUSH, null, id)
        Log.d(TAG, "Speaking: ${cleanedText.take(50)}...")
    }

    /**
     * Clean text for natural speech output.
     * Removes markdown formatting, code blocks, URLs, etc.
     */
    private fun cleanTextForSpeech(text: String): String {
        return text
            // Remove code blocks
            .replace(Regex("```[\\s\\S]*?```"), " code block ")
            // Remove inline code
            .replace(Regex("`[^`]+`"), "")
            // Remove markdown headers
            .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
            // Remove markdown bold/italic
            .replace(Regex("[*_]{1,3}"), "")
            // Remove markdown links, keep text
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            // Remove URLs
            .replace(Regex("https?://\\S+"), "")
            // Remove bullet points
            .replace(Regex("^[\\-*•]\\s*", RegexOption.MULTILINE), "")
            // Clean up multiple spaces
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Stop current speech immediately.
     * Also resumes wake word detection if TTS was speaking.
     */
    fun stop() {
        val wasSpeaking = _isSpeaking.value
        tts?.stop()
        _isSpeaking.value = false

        // Resume wake word detection if we were speaking
        if (wasSpeaking) {
            try {
                onSpeechEnd?.invoke()
                Log.d(TAG, "TTS stopped manually - resuming wake word detection")
            } catch (e: Exception) {
                Log.e(TAG, "Error invoking onSpeechEnd callback: ${e.message}")
            }
        }
    }

    /**
     * Enable or disable TTS.
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (!enabled) {
            stop()
        }
        Log.d(TAG, "TTS enabled: $enabled")
    }

    /**
     * Set callbacks for speech lifecycle.
     * Use this to pause/resume wake word detection during TTS.
     *
     * @param onStart Called when TTS starts speaking (pause wake word)
     * @param onEnd Called when TTS finishes or errors (resume wake word)
     */
    fun setSpeechLifecycleCallbacks(onStart: () -> Unit, onEnd: () -> Unit) {
        this.onSpeechStart = onStart
        this.onSpeechEnd = onEnd
    }

    /**
     * Set speech rate.
     * @param rate Speech rate multiplier (0.5 = half speed, 2.0 = double speed)
     */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /**
     * Use fast speech mode for quick responses.
     */
    fun useFastMode() {
        tts?.setSpeechRate(FAST_SPEECH_RATE)
    }

    /**
     * Use normal speech mode.
     */
    fun useNormalMode() {
        tts?.setSpeechRate(DEFAULT_SPEECH_RATE)
    }

    /**
     * Clean up TTS resources.
     * Call when done using TTS (e.g., in ViewModel onCleared).
     */
    fun shutdown() {
        stop()
        // Clear callbacks to prevent memory leaks and stale references
        onSpeechStart = null
        onSpeechEnd = null
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS shutdown complete")
    }
}
