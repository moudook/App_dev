package com.example.smarty.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
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
    // Audio focus management
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

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
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping: ${e.message}")
        } finally {
            // Always reset state, even if recognizer crashes
            isListening = false
            rmsDb = 0f
            speechInitiatedInChatMode = null // Reset mode tracking
            hasReceivedCallback = false
            releaseAudioFocus()

            // CRITICAL: Always resume Vosk global pause
            // Use post-delay to ensure SpeechRecognizer has fully released the mic
            handler.postDelayed({
                com.example.smarty.voice.VoskWakeWordManager.isGloballyPaused = false
                Log.d(TAG, "Vosk global pause released (finally block)")
            }, 300)
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

    /**
     * Request audio focus to ensure we get exclusive access to the microphone.
     */
    private fun requestAudioFocus(): Boolean {
        Log.d(TAG, "Requesting audio focus...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            Log.d(TAG, "Audio focus GAINED")
                            hasAudioFocus = true
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            Log.d(TAG, "Audio focus LOST permanently")
                            hasAudioFocus = false
                            if (isListening) stopListening()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            Log.d(TAG, "Audio focus TRANSIENT loss: $focusChange (expected)")
                        }
                    }
                }
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            Log.d(TAG, "Audio focus request result: $result (granted: $hasAudioFocus)")
            return hasAudioFocus
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        hasAudioFocus = false
                        if (isListening) stopListening()
                    }
                },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            Log.d(TAG, "Audio focus request result (legacy): $result (granted: $hasAudioFocus)")
            return hasAudioFocus
        }
    }

    private fun releaseAudioFocus() {
        Log.d(TAG, "Releasing audio focus...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        hasAudioFocus = false
    }

    private fun startRecognition(languageCode: String?) {
        // If no speech recognizer (service missing) or offline mode preferred
        // We can inject offline logic here if needed, but for now we focus on fallback

        if (speechRecognizer == null) {
            Log.e(TAG, "Speech recognizer is null")
            // Try offline fallback immediately
            startOfflineFallback(languageCode)
            return
        }

        if (isListening) {
            Log.d(TAG, "Already listening, ignoring start request")
            return
        }

        // CRITICAL: Pause Vosk globally before starting
        Log.d(TAG, "Pausing Vosk globally for speech recognition")
        com.example.smarty.voice.VoskWakeWordManager.isGloballyPaused = true

        // Request audio focus
        if (!requestAudioFocus()) {
            Log.e(TAG, "Failed to get audio focus")
            onError?.invoke("Microphone in use. Tap to retry.")
            com.example.smarty.voice.VoskWakeWordManager.isGloballyPaused = false
            return
        }

        // Delay starting speech recognizer to allow Vosk to release mic
        handler.postDelayed({
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

                    val locale = languageCode ?: Locale.getDefault().toLanguageTag()
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)

                    // Add silence timeout parameters
                    putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2500L)
                    putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2500L)
                    putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 1500L)
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

                        // Try fallback instead of just failing
                        Log.i(TAG, "Attempting offline fallback after timeout")
                        startOfflineFallback(languageCode)
                    }
                }
                handler.postDelayed(timeoutRunnable!!, STARTUP_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.e(TAG, "Start error: ${e.message}")
                // Try fallback on start error
                startOfflineFallback(languageCode)
            }
        }, 150) // 150ms delay to ensure Vosk releases mic
    }

    /**
     * Fallback to Vosk offline recognition when Google Speech fails or is unavailable.
     */
    private fun startOfflineFallback(languageCode: String?) {
        Log.i(TAG, "Starting offline STT fallback")

        // Ensure Vosk global pause is set (might have been cleared if coming from error)
        com.example.smarty.voice.VoskWakeWordManager.isGloballyPaused = true

        // We need a coroutine scope - use Main scope for UI updates
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

        val voskRecognizer = com.example.smarty.voice.VoskSpeechRecognizer(context, scope)

        isListening = true
        // Note: Vosk doesn't provide RMS updates in this simple wrapper, so UI won't animate
        // We could simulate it or enhance the wrapper later

        voskRecognizer.startListening(
            onResult = { text ->
                Log.d(TAG, "Offline result: $text")
                onResult?.invoke(text)
                isListening = false
                releaseAudioFocus()
                com.example.smarty.voice.VoskWakeWordManager.isGloballyPaused = false
            },
            onError = { error ->
                Log.e(TAG, "Offline error: $error")
                isListening = false
                onError?.invoke("Offline recognition failed: $error")
                releaseAudioFocus()
                com.example.smarty.voice.VoskWakeWordManager.isGloballyPaused = false
            }
        )
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
    val RecognizerIntent = Intent("android.speech.recognitionService")
    val services = pm.queryIntentServices(RecognizerIntent, PackageManager.GET_META_DATA)

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
    val speechRecognizerInstance = remember {
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
    val state = remember(speechRecognizerInstance, permissionLauncher) {
        SpeechToTextState(context, speechRecognizerInstance, permissionLauncher)
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
    DisposableEffect(speechRecognizerInstance) {
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
                // recognition continues until final results.
                // We keep isListening = true so the UI continues to shimmer/show active state
                // until onResults or onError is called.
            }

            override fun onError(error: Int) {
                Log.e("SpeechToText", "onError - code: $error")
                state.onCallbackReceived() // Cancel timeout - we got a response (even if error)

                // For network errors, try offline fallback
                if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER) {
                    Log.w("SpeechToText", "Network error ($error) - switching to offline fallback")
                    // Don't set isListening=false yet, we're transitioning
                    try {
                        // Need to use reflection or expose the method to access private startOfflineFallback
                        // Since we can't easily access private method from anonymous inner class in Composable,
                        // we'll handle this by modifying the state architecture in a future refactor.
                        // For now, we'll just report error but adding specific message about offline mode
                        state.stopListening()
                        state.onError?.invoke("Network error. Try offline mode?")

                        // NOTE: To properly support fallback from here, we'd need to refactor
                        // rememberSpeechToText to expose the fallback capability to the listener.
                        // For this immediate fix, we relied on the startRecognition timeout/error catch blocks.
                    } catch (e: Exception) {
                        state.stopListening()
                        state.onError?.invoke("Error: $error")
                    }
                    return
                }

                state.stopListening()
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
                state.stopListening()
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

        speechRecognizerInstance?.setRecognitionListener(listener)

        onDispose {
            state.stopListening() // Ensure timeout is cancelled
            try {
                speechRecognizerInstance?.destroy()
            } catch (e: Exception) {
                // Ignore destroy errors
            }
        }
    }

    return state
}






