package com.example.smarty.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.Looper
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
 * - Automatic timeout if speech service doesn't respond
 */
class SpeechToTextState(
    private val context: Context,
    private val speechRecognizer: SpeechRecognizer?,
    private val permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    companion object {
        private const val TAG = "SpeechToText"
        private const val STARTUP_TIMEOUT_MS = 5000L // 5 seconds to wait for onReadyForSpeech
    }

    var isListening by mutableStateOf(false)
        internal set

    var rmsDb by mutableStateOf(0f)
        internal set

    // Track which mode initiated speech to prevent cross-mode text insertion
    var speechInitiatedInChatMode by mutableStateOf<Boolean?>(null)
        internal set

    // Track if we've received any callback from the speech recognizer
    internal var hasReceivedCallback by mutableStateOf(false)

    // External callbacks
    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null

    // Timeout handler
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

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

    // Callback to set pending state before permission request
    internal var onPermissionNeeded: ((String?) -> Unit)? = null

    private fun startListeningInternal(languageCode: String? = null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecognition(languageCode)
        } else {
            // Signal that we need permission and store the language code
            onPermissionNeeded?.invoke(languageCode)
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stopListening() {
        cancelTimeout()
        try {
            speechRecognizer?.cancel() // Use cancel instead of stopListening for cleaner shutdown
            isListening = false
            rmsDb = 0f
            speechInitiatedInChatMode = null // Reset mode tracking
            hasReceivedCallback = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping: ${e.message}")
        }
    }

    /**
     * Called when a callback is received from the speech recognizer.
     * Cancels the timeout since we know the service is responding.
     */
    internal fun onCallbackReceived() {
        hasReceivedCallback = true
        cancelTimeout()
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun startRecognition(languageCode: String?) {
        if (speechRecognizer == null) {
            Log.e(TAG, "Speech recognizer is null")
            onError?.invoke("Speech recognition not available")
            return
        }

        if (isListening) {
            Log.d(TAG, "Already listening, ignoring start request")
            return
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

                val locale = languageCode ?: Locale.getDefault().toLanguageTag()
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            }

            hasReceivedCallback = false
            speechRecognizer.startListening(intent)
            isListening = true
            Log.d(TAG, "Started speech recognition")

            // Set up timeout to detect if speech service doesn't respond
            cancelTimeout()
            timeoutRunnable = Runnable {
                if (isListening && !hasReceivedCallback) {
                    Log.w(TAG, "Speech recognition timeout - no callback received in ${STARTUP_TIMEOUT_MS}ms")
                    // Reset state
                    try {
                        speechRecognizer.cancel()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error canceling on timeout: ${e.message}")
                    }
                    isListening = false
                    rmsDb = 0f
                    speechInitiatedInChatMode = null
                    onError?.invoke("Voice service not responding. Tap to retry.")
                }
            }
            handler.postDelayed(timeoutRunnable!!, STARTUP_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Start error: ${e.message}")
            isListening = false
            onError?.invoke("Failed to start: ${e.message}")
        }
    }
}

/**
 * Find an external speech recognition service (NOT our own package).
 * This is critical when Smarty is set as the default assistant, because
 * the system's "default" recognizer would be Smarty itself, creating a loop.
 */
private fun findExternalSpeechService(context: Context): android.content.ComponentName? {
    val pm = context.packageManager
    val myPackage = context.packageName

    // Query all services that can handle speech recognition
    val recognizerIntent = Intent("android.speech.RecognitionService")
    val services = pm.queryIntentServices(recognizerIntent, PackageManager.GET_META_DATA)

    Log.d("SpeechToText", "Found ${services.size} speech recognition services")

    var bestExternalService: android.content.ComponentName? = null

    for (resolveInfo in services) {
        val serviceInfo = resolveInfo.serviceInfo
        val servicePackage = serviceInfo.packageName
        val serviceName = serviceInfo.name

        // CRITICAL: Skip our own package to avoid the loop
        if (servicePackage == myPackage) {
            Log.d("SpeechToText", "Skipping our own service: $servicePackage/$serviceName")
            continue
        }

        Log.d("SpeechToText", "Found external service: $servicePackage/$serviceName")

        // Prefer Google's service if available
        if (servicePackage == "com.google.android.googlequicksearchbox") {
            Log.d("SpeechToText", "Using Google's speech service")
            return android.content.ComponentName(servicePackage, serviceName)
        }

        // Store first external service as fallback
        if (bestExternalService == null) {
            bestExternalService = android.content.ComponentName(servicePackage, serviceName)
        }
    }

    return bestExternalService
}

@Composable
fun rememberSpeechToText(
    onResult: (String) -> Unit,
    onError: ((String) -> Unit)? = null,
    onPartialResult: ((String) -> Unit)? = null
): SpeechToTextState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Create SpeechRecognizer with EXTERNAL service to avoid loop
    // When Smarty is the default assistant, the "default" recognizer is Smarty itself!
    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            val externalService = findExternalSpeechService(context)
            if (externalService != null) {
                Log.d("SpeechToText", "Using external speech service: ${externalService.packageName}")
                SpeechRecognizer.createSpeechRecognizer(context, externalService)
            } else {
                Log.w("SpeechToText", "No external speech service found, using default (may cause issues)")
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } else {
            null
        }
    }

    // Permission launcher
    // We need to define this before we instantiate the state class
    var pendingStart by remember { mutableStateOf(false) }
    var pendingLanguageCode by remember { mutableStateOf<String?>(null) }

    // Use a holder to allow callback to access state after creation
    val stateHolder = remember { mutableStateOf<SpeechToTextState?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && pendingStart) {
            // Permission granted - actually start recognition now
            stateHolder.value?.let { state ->
                // Trigger recognition with pending language code
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    // Call internal start method via reflection workaround or just re-trigger
                    state.startListening(pendingLanguageCode)
                }
            }
        } else if (!isGranted) {
            onError?.invoke("Microphone permission required")
        }
        pendingStart = false
        pendingLanguageCode = null
    }

    // State holder
    val state = remember(speechRecognizer, permissionLauncher) {
        SpeechToTextState(context, speechRecognizer, permissionLauncher)
    }

    // Update state holder for permission callback and set up pending state tracking
    LaunchedEffect(state) {
        stateHolder.value = state
        // Wire up the pending state callback
        state.onPermissionNeeded = { languageCode ->
            pendingStart = true
            pendingLanguageCode = languageCode
        }
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
                Log.d("SpeechToText", "onReadyForSpeech - speech service is ready")
                state.onCallbackReceived() // Cancel timeout - service is responding
                state.isListening = true
            }

            override fun onBeginningOfSpeech() {
                Log.d("SpeechToText", "onBeginningOfSpeech - user started speaking")
                state.onCallbackReceived()
                state.isListening = true
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Smooth out the changes or just pass raw
                state.rmsDb = rmsdB
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d("SpeechToText", "onEndOfSpeech - user stopped speaking")
                // Recognition continues until final results.
                // We keep isListening = true so the UI continues to shimmer/show active state
                // until onResults or onError is called.
            }

            override fun onError(error: Int) {
                Log.e("SpeechToText", "onError - code: $error")
                state.onCallbackReceived() // Cancel timeout - we got a response (even if error)
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
                // Filter out "No match" and "No speech detected" - these are normal
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    state.onError?.invoke(message)
                }
            }

            override fun onResults(results: Bundle?) {
                Log.d("SpeechToText", "onResults received")
                state.onCallbackReceived() // Cancel timeout
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    state.onResult?.invoke(text)
                }
                state.isListening = false
                state.rmsDb = 0f
            }

            override fun onPartialResults(partialResults: Bundle?) {
                state.onCallbackReceived() // Cancel timeout - service is responding
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
            state.stopListening() // Ensure timeout is cancelled
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                // Ignore destroy errors
            }
        }
    }

    return state
}

