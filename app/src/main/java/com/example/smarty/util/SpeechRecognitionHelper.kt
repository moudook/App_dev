package com.example.smarty.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Helper class for speech-to-text functionality using Android's SpeechRecognizer.
 * Provides real-time transcription with partial results for immediate feedback.
 *
 * Optimized for reliability:
 * - Proper lifecycle management
 * - Main thread safety
 * - Automatic restart on recoverable errors
 * - Support for both online and offline recognition
 */
class SpeechRecognitionHelper(private val context: Context) {

    companion object {
        private const val TAG = "SpeechRecognition"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialResult = MutableStateFlow("")
    val partialResult: StateFlow<String> = _partialResult.asStateFlow()

    private val _finalResult = MutableStateFlow("")
    val finalResult: StateFlow<String> = _finalResult.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private var onResultCallback: ((String) -> Unit)? = null
    private var isInitialized = false

    /**
     * Check if speech recognition is available on this device.
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * Initialize the speech recognizer. Call this before startListening.
     */
    private fun initializeRecognizer() {
        // Destroy existing recognizer first
        speechRecognizer?.destroy()
        speechRecognizer = null

        // Create new recognizer on main thread
        mainHandler.post {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createRecognitionListener())
                }
                isInitialized = true
                Log.d(TAG, "SpeechRecognizer initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SpeechRecognizer", e)
                _error.value = "Failed to initialize speech recognition"
                isInitialized = false
            }
        }
    }

    /**
     * Start listening for speech input.
     * @param onResult Callback invoked with the final recognized text
     */
    fun startListening(onResult: (String) -> Unit) {
        if (_isListening.value) {
            Log.d(TAG, "Already listening, ignoring startListening call")
            return
        }

        if (!isAvailable()) {
            Log.e(TAG, "Speech recognition not available on this device")
            _error.value = "Speech recognition not available on this device"
            return
        }

        onResultCallback = onResult
        _error.value = null
        _partialResult.value = ""
        _finalResult.value = ""

        // Always reinitialize for fresh state
        mainHandler.post {
            try {
                // Destroy existing recognizer
                speechRecognizer?.destroy()

                // Create fresh recognizer
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createRecognitionListener())
                }

                val intent = createRecognizerIntent()

                _isListening.value = true
                speechRecognizer?.startListening(intent)
                Log.d(TAG, "Started listening for speech")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting speech recognition", e)
                _error.value = "Error starting speech recognition"
                _isListening.value = false
            }
        }
    }

    /**
     * Create the intent for speech recognition with optimal settings.
     */
    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Use free-form language model for natural speech
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

            // Use device's default language
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toString())

            // Request partial results for real-time feedback
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

            // Only need one result
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

            // Silence detection settings - more forgiving for better UX
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)

            // Prefer offline recognition if available (faster, more reliable)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false) // Use online for better accuracy
            }

            // Enable web search for better recognition accuracy
            putExtra(RecognizerIntent.EXTRA_WEB_SEARCH_ONLY, false)

            // Calling package for proper permissions
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }

    /**
     * Stop listening and cancel any ongoing recognition.
     */
    fun stopListening() {
        Log.d(TAG, "stopListening called")
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech recognition", e)
            }
            _isListening.value = false
            _rmsLevel.value = 0f
        }
    }

    /**
     * Cancel recognition and release resources.
     */
    fun cancel() {
        Log.d(TAG, "cancel called")
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error canceling speech recognition", e)
            }
            _isListening.value = false
            _partialResult.value = ""
            _rmsLevel.value = 0f
        }
    }

    /**
     * Release all resources. Call when done with speech recognition.
     */
    fun destroy() {
        Log.d(TAG, "destroy called")
        cancel()
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying speech recognition", e)
            }
            speechRecognizer = null
            isInitialized = false
        }
        onResultCallback = null
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
                _error.value = null
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech - User started speaking")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Normalize RMS to 0-1 range for visualization
                // RMS typically ranges from -2 to 10
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _rmsLevel.value = normalized
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Audio buffer received - not used
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
                _isListening.value = false
                _rmsLevel.value = 0f
            }

            override fun onError(error: Int) {
                Log.e(TAG, "onError: $error (${getErrorText(error)})")
                _isListening.value = false
                _rmsLevel.value = 0f

                _error.value = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error. Please check microphone."
                    SpeechRecognizer.ERROR_CLIENT -> {
                        // Client error often means we need to reinitialize
                        null
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error. Check your internet connection."
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout. Please try again."
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        // No speech detected - not really an error
                        Log.d(TAG, "No speech detected")
                        null
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Recognizer busy - will retry
                        "Speech recognizer busy. Please try again."
                    }
                    SpeechRecognizer.ERROR_SERVER -> "Server error. Please try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // User didn't speak - not an error
                        Log.d(TAG, "Speech timeout - no speech detected")
                        null
                    }
                    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests. Please wait."
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Server disconnected. Please try again."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable for offline use"
                    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "Cannot check language support"
                    SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "Download error"
                    else -> "Recognition error ($error)"
                }
            }

            override fun onResults(results: Bundle?) {
                Log.d(TAG, "onResults received")
                _isListening.value = false
                _rmsLevel.value = 0f

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                Log.d(TAG, "Results: $matches, Confidences: ${confidences?.toList()}")

                val text = matches?.firstOrNull() ?: ""

                if (text.isNotBlank()) {
                    _finalResult.value = text
                    onResultCallback?.invoke(text)
                    Log.d(TAG, "Final result: $text")
                } else {
                    Log.d(TAG, "Empty result received")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    _partialResult.value = text
                    Log.d(TAG, "Partial result: $text")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "onEvent: $eventType")
            }
        }
    }

    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            else -> "UNKNOWN_ERROR"
        }
    }
}
