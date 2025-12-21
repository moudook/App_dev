package com.example.smarty.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Advanced Speech-to-Text using Android's native SpeechRecognizer.
 * 
 * Features:
 * - Silent background recognition (no Google pop-up)
 * - Real-time RMS (loudness) feedback for UI animations
 * - Direct control over listening state
 * - Handles runtime permissions
 */
class SpeechToTextState(
    private val context: Context,
    private val speechRecognizer: SpeechRecognizer?,
    private val permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    var isListening by mutableStateOf(false)
        internal set

    var rmsDb by mutableStateOf(0f)
        internal set

    // Track which mode initiated speech to prevent cross-mode text insertion
    var speechInitiatedInChatMode by mutableStateOf<Boolean?>(null)
        internal set

    // External callbacks
    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null

    /**
     * Start listening with mode context.
     * @param isChatMode true if initiated from chat mode, false for normal mode
     */
    fun startListening(isChatMode: Boolean, languageCode: String? = null) {
        speechInitiatedInChatMode = isChatMode
        startListeningInternal(languageCode)
    }

    fun startListening(languageCode: String? = null) {
        startListeningInternal(languageCode)
    }

    private fun startListeningInternal(languageCode: String? = null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecognition(languageCode)
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            isListening = false
            rmsDb = 0f
            speechInitiatedInChatMode = null // Reset mode tracking
        } catch (e: Exception) {
            Log.e("SpeechToText", "Error stopping: ${e.message}")
        }
    }

    private fun startRecognition(languageCode: String?) {
        if (speechRecognizer == null) {
            onError?.invoke("Speech recognition not available")
            return
        }

        if (isListening) return

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                
                val locale = languageCode ?: Locale.getDefault().toLanguageTag()
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            }
            
            speechRecognizer.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            Log.e("SpeechToText", "Start error: ${e.message}")
            isListening = false
            onError?.invoke("Failed to start: ${e.message}")
        }
    }
}

@Composable
fun rememberSpeechToText(
    onResult: (String) -> Unit,
    onError: ((String) -> Unit)? = null,
    onPartialResult: ((String) -> Unit)? = null
): SpeechToTextState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Create SpeechRecognizer
    // We use a key to recreate if context changes, but wrap in remember to keep instance
    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    // Permission launcher
    // We need to define this before we instantiate the state class
    var pendingStart by remember { mutableStateOf(false) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && pendingStart) {
             // We can't easily callback into the class here without a circular ref potential
             // But we can signal the state if we had it, or just let the user tap again.
             // For better UX, we could try to auto-start, but simple is robust.
             // The user will tap again. 
        } else if (!isGranted) {
            onError?.invoke("Microphone permission required")
        }
        pendingStart = false
    }

    // State holder
    val state = remember(speechRecognizer, permissionLauncher) {
        SpeechToTextState(context, speechRecognizer, permissionLauncher)
    }
    
    // Update callbacks
    LaunchedEffect(onResult, onError, onPartialResult) {
        state.onResult = onResult
        state.onError = onError
        state.onPartialResult = onPartialResult
    }

    // Set up the listener
    DisposableEffect(speechRecognizer) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                state.isListening = true // Redundant but safe
            }

            override fun onBeginningOfSpeech() {
                state.isListening = true
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Smooth out the changes or just pass raw
                state.rmsDb = rmsdB
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                 // Recognition continues until final results. 
                 // We keep isListening = true so the UI continues to shimmer/show active state
                 // until onResults or onError is called.
            }

            override fun onError(error: Int) {
                state.isListening = false
                state.rmsDb = 0f
                val message = when(error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
                    else -> "Error $error"
                }
                // Filter out "No match" if it happens too often during silence, but standard is to report
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    state.onError?.invoke(message)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    state.onResult?.invoke(text)
                }
                state.isListening = false
                state.rmsDb = 0f
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = matches?.firstOrNull()
                if (!partialText.isNullOrBlank()) {
                    state.onPartialResult?.invoke(partialText)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        speechRecognizer?.setRecognitionListener(listener)

        onDispose {
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                // Ignore destroy errors
            }
        }
    }

    return state
}

