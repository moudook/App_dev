package com.example.smarty.util

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Simple Speech-to-Text using Google's built-in speech recognition dialog.
 *
 * This launches the standard Google Speech Recognition popup that:
 * - Shows a microphone UI
 * - Records user's speech
 * - Converts speech to text
 * - Returns the result
 *
 * This is the standard approach used by most Android apps.
 * No custom UI needed - Google handles everything.
 */
class SpeechToTextState(
    private val launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
    private val isAvailable: Boolean
) {
    companion object {
        private const val TAG = "SpeechToText"
    }

    /**
     * Launch the Google Speech Recognition dialog.
     * @param prompt Optional text to show on the speech dialog
     * @param languageCode Optional BCP-47 language code (e.g., "en-US", "hi-IN").
     *                     If null, auto-detects from device settings.
     */
    fun launch(prompt: String = "Speak now...", languageCode: String? = null, onError: ((String) -> Unit)? = null) {
        if (!isAvailable) {
            Log.w(TAG, "Speech recognition not available on this device")
            onError?.invoke("Speech recognition is not available on this device.")
            return
        }

        // Auto-detect language from device settings if not specified
        val language = languageCode ?: Locale.getDefault().toLanguageTag()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Use free form for natural speech
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

            // Set language from device settings or specified language
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)

            // Get multiple results for better accuracy
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

            // Show partial results as user speaks
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

            // Custom prompt text
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }

        Log.d(TAG, "Launching speech recognition with language: $language")
        launcher.launch(intent)
    }

    /**
     * Check if speech recognition is available on this device.
     */
    fun isAvailable(): Boolean = isAvailable
}

/**
 * Remember a SpeechToTextState that can launch Google's speech recognition dialog.
 *
 * Usage:
 * ```
 * val speechToText = rememberSpeechToText { result ->
 *     // Handle the recognized text
 *     textFieldValue = result
 * }
 *
 * Button(onClick = { speechToText.launch() }) {
 *     Icon(Icons.Default.Mic, "Voice input")
 * }
 * ```
 *
 * @param onResult Callback invoked with the recognized text when speech recognition succeeds
 * @param onError Optional callback invoked when an error occurs
 */
@Composable
fun rememberSpeechToText(
    onError: ((String) -> Unit)? = null,
    onResult: (String) -> Unit
): SpeechToTextState {
    val context = LocalContext.current

    // Check if speech recognition is available
    val isAvailable = remember {
        SpeechRecognizer.isRecognitionAvailable(context)
    }

    // Create activity result launcher
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("SpeechToText", "Result code: ${result.resultCode}")

        when (result.resultCode) {
            Activity.RESULT_OK -> {
                val data = result.data
                val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                Log.d("SpeechToText", "Results: $results")

                val recognizedText = results?.firstOrNull() ?: ""

                if (recognizedText.isNotBlank()) {
                    Log.d("SpeechToText", "Recognized: $recognizedText")
                    onResult(recognizedText)
                } else {
                    Log.w("SpeechToText", "Empty result received")
                    onError?.invoke("No speech detected")
                }
            }
            Activity.RESULT_CANCELED -> {
                Log.d("SpeechToText", "User cancelled")
                onError?.invoke("Cancelled")
            }
            else -> {
                Log.e("SpeechToText", "Error result: ${result.resultCode}")
                onError?.invoke("Speech recognition failed")
            }
        }
    }

    return remember(launcher, isAvailable) {
        SpeechToTextState(launcher, isAvailable)
    }
}
