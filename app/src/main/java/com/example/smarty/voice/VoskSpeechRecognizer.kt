package com.example.smarty.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.StorageService

/**
 * One-shot offline speech recognizer using Vosk.
 * Provides a simple interface similar to Android's SpeechRecognizer but fully offline.
 * Reuses the same model as the wake word manager.
 */
class VoskSpeechRecognizer(
    private val context: Context,
    private val scope: CoroutineScope
) : RecognitionListener {

    companion object {
        private const val TAG = "VoskSpeechRecognizer"
        private const val MODEL_PATH = "vosk-model-small-en-us-0.15"
        private const val SAMPLE_RATE = 16000.0f

        // STT uses 1.0x gain (normal) as users are typically closer to the device
        // when intentionally speaking, unlike wake word detection which needs higher gain.
        private const val AUDIO_GAIN = 1.0f
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: HighSensitivitySpeechService? = null

    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    @Volatile
    private var isInitializing = false

    /**
     * Start listening for speech. If the model is not loaded, it will be loaded first.
     * This is a one-shot recognizer: it stops automatically after a result is returned.
     */
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        this.onResultCallback = onResult
        this.onErrorCallback = onError

        if (model == null) {
            if (isInitializing) {
                Log.d(TAG, "Initialization already in progress, queuing start")
                return
            }
            initAndStart()
        } else {
            startRecognition()
        }
    }

    /**
     * Stop listening immediately and release resources.
     */
    fun stopListening() {
        Log.d(TAG, "Stopping offline STT")
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech service: ${e.message}")
        }
        speechService = null

        try {
            recognizer?.close()
        } catch (_: Exception) {}
        recognizer = null
    }

    private fun initAndStart() {
        isInitializing = true
        Log.i(TAG, "Initializing Vosk model for offline STT: $MODEL_PATH")

        StorageService.unpack(context, MODEL_PATH, "model-en",
            { loadedModel ->
                model = loadedModel
                isInitializing = false
                Log.i(TAG, "Model loaded successfully, starting recognition")
                startRecognition()
            },
            { exception ->
                isInitializing = false
                Log.e(TAG, "Failed to unpack model for offline STT", exception)
                onErrorCallback?.invoke("Failed to load offline recognition model: ${exception.message}")
            }
        )
    }

    private fun startRecognition() {
        val currentModel = model
        if (currentModel == null) {
            onErrorCallback?.invoke("Model not initialized")
            return
        }

        try {
            // Clean up any existing session
            stopListening()

            // Create a fresh recognizer for this session
            recognizer = Recognizer(currentModel, SAMPLE_RATE)

            // Use HighSensitivitySpeechService with 1.0x gain for high-quality STT
            speechService = HighSensitivitySpeechService(recognizer!!, SAMPLE_RATE, AUDIO_GAIN)
            speechService?.startListening(this)

            Log.i(TAG, "Offline STT started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start offline recognition", e)
            onErrorCallback?.invoke("Recognition error: ${e.message}")
        }
    }

    // ==================== RecognitionListener Implementation ====================

    override fun onPartialResult(hypothesis: String?) {
        // Partial results could be used for real-time UI updates if needed
        Log.v(TAG, "Partial: $hypothesis")
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis == null) return

        try {
            val json = JSONObject(hypothesis)
            val text = json.optString("text", "")
            if (text.isNotBlank()) {
                Log.i(TAG, "Final Result (from onResult): $text")
                onResultCallback?.invoke(text)
                // One-shot behavior: stop after we get a solid result
                stopListening()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing result JSON: ${e.message}")
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        if (hypothesis == null) return

        try {
            val json = JSONObject(hypothesis)
            val text = json.optString("text", "")
            if (text.isNotBlank()) {
                Log.i(TAG, "Final Result: $text")
                onResultCallback?.invoke(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing final result JSON: ${e.message}")
        } finally {
            // Always stop after final result
            stopListening()
        }
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk STT error", exception)
        onErrorCallback?.invoke(exception?.message ?: "Unknown offline recognition error")
        stopListening()
    }

    override fun onTimeout() {
        Log.d(TAG, "Vosk STT timeout")
        stopListening()
    }
}
