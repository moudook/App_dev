package com.example.smarty.features.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val TAG = "SpeechToText"

@Composable
fun rememberSpeechToText(
    onResult: (String) -> Unit = {},
    onError: (String) -> Unit = {}
): SpeechToTextState {
    val context = LocalContext.current
    val state = remember { SpeechToTextState(context, onResult, onError) }
    
    DisposableEffect(state) {
        onDispose {
            state.destroy()
        }
    }
    
    return state
}

class SpeechToTextState(
    private val context: Context,
    private val onResult: (String) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    var isListening by mutableStateOf(false)
        private set
    
    var transcript by mutableStateOf("")
        private set
    
    var partialTranscript by mutableStateOf("")
        private set
    
    var speechInitiatedInChatMode by mutableStateOf<Boolean?>(null)
    
    var onPartialResult: ((String) -> Unit)? = null
    
    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
        }
        
        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
        }
        
        override fun onRmsChanged(rmsdB: Float) {
            // Can be used for visual feedback
        }
        
        override fun onBufferReceived(buffer: ByteArray?) {
            // Not used
        }
        
        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
            isListening = false
        }
        
        override fun onError(error: Int) {
            val errorMessage = getErrorMessage(error)
            Log.e(TAG, "Recognition error: $errorMessage ($error)")
            isListening = false
            
            // Only report significant errors, not just "no match"
            if (error != SpeechRecognizer.ERROR_NO_MATCH && 
                error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                onError(errorMessage)
            }
        }
        
        override fun onResults(bundle: Bundle?) {
            val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val result = matches?.firstOrNull() ?: ""
            
            Log.d(TAG, "onResults: $result")
            
            if (result.isNotBlank()) {
                transcript = result
                onResult(result)
            }
            
            isListening = false
        }
        
        override fun onPartialResults(bundle: Bundle?) {
            val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: ""
            
            Log.d(TAG, "onPartialResults: $partial")
            
            if (partial.isNotBlank()) {
                partialTranscript = partial
                onPartialResult?.invoke(partial)
            }
        }
        
        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "onEvent: $eventType")
        }
    }
    
    fun startListening(isChatMode: Boolean = false) {
        if (isListening) {
            Log.w(TAG, "Already listening, ignoring startListening call")
            return
        }
        
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            onError("Speech recognition not available on this device")
            return
        }
        
        speechInitiatedInChatMode = isChatMode
        
        try {
            // Destroy existing recognizer if any
            speechRecognizer?.destroy()
            
            // Create new recognizer
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(recognitionListener)
            
            // Configure intent
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Shorter silence timeout for better responsiveness
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            }
            
            speechRecognizer?.startListening(intent)
            isListening = true
            partialTranscript = ""
            transcript = ""
            
            Log.d(TAG, "Started listening in ${if (isChatMode) "CHAT" else "MAIN"} mode")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}", e)
            isListening = false
            onError("Failed to start speech recognition: ${e.message}")
        }
    }
    
    fun stopListening() {
        if (!isListening) {
            return
        }
        
        Log.d(TAG, "Stopping listening")
        
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognizer: ${e.message}")
        }
        
        isListening = false
    }
    
    fun cancelRecording() {
        Log.d(TAG, "Canceling recording")
        
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling recognizer: ${e.message}")
        }
        
        isListening = false
        transcript = ""
        partialTranscript = ""
        speechInitiatedInChatMode = null
    }
    
    fun reset() {
        transcript = ""
        partialTranscript = ""
        speechInitiatedInChatMode = null
    }
    
    fun destroy() {
        Log.d(TAG, "Destroying speech recognizer")
        
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying recognizer: ${e.message}")
        }
        
        speechRecognizer = null
        isListening = false
    }
    
    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
            else -> "Unknown error ($errorCode)"
        }
    }
}
