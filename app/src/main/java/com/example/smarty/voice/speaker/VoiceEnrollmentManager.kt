package com.example.smarty.voice.speaker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Voice Enrollment Manager
 *
 * Handles the complete voice enrollment flow:
 * 1. Records multiple phrases from user
 * 2. Extracts MFCC embeddings from each phrase
 * 3. Combines embeddings into a robust voice fingerprint
 * 4. Saves the fingerprint for speaker verification
 *
 * Recording specs:
 * - 16kHz sample rate (optimal for speech)
 * - 16-bit mono PCM
 * - 3-5 seconds per phrase
 */
class VoiceEnrollmentManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VoiceEnrollment"

        // Audio recording parameters
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val RECORD_DURATION_MS = 6000L  // Increased to 6s for longer sentences
        private const val MIN_AUDIO_LENGTH = SAMPLE_RATE * 2  // At least 2 seconds of audio
    }

    private val embeddingManager = SpeakerEmbeddingManager(context)

    // Collected embeddings during enrollment
    private val collectedEmbeddings = mutableListOf<DoubleArray>()

    // Audio recording
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val recordedSamples = mutableListOf<Short>()

    // State management
    private val _enrollmentState = MutableStateFlow<EnrollmentState>(EnrollmentState.NotStarted)
    val enrollmentState: StateFlow<EnrollmentState> = _enrollmentState.asStateFlow()

    private val _currentPhraseIndex = MutableStateFlow(0)
    val currentPhraseIndex: StateFlow<Int> = _currentPhraseIndex.asStateFlow()

    private val _recordingAmplitude = MutableStateFlow(0f)
    val recordingAmplitude: StateFlow<Float> = _recordingAmplitude.asStateFlow()

    private val _recordingProgress = MutableStateFlow(0f)
    val recordingProgress: StateFlow<Float> = _recordingProgress.asStateFlow()

    /**
     * Get enrollment phrases.
     */
    val phrases = embeddingManager.enrollmentPhrases

    /**
     * Get current phrase to record.
     */
    fun getCurrentPhrase(): SpeakerEmbeddingManager.EnrollmentPhrase? {
        val index = _currentPhraseIndex.value
        return if (index < phrases.size) phrases[index] else null
    }

    /**
     * Check if user has already enrolled.
     */
    fun isEnrolled(): Boolean = embeddingManager.isEnrolled()

    /**
     * Start the enrollment process.
     */
    fun startEnrollment() {
        Log.i(TAG, "Starting voice enrollment")
        collectedEmbeddings.clear()
        _currentPhraseIndex.value = 0
        _enrollmentState.value = EnrollmentState.WaitingToRecord(phrases[0])
    }

    /**
     * Start recording the current phrase.
     */
    fun startRecording() {
        if (_enrollmentState.value !is EnrollmentState.WaitingToRecord) {
            Log.w(TAG, "Cannot start recording - wrong state: ${_enrollmentState.value}")
            return
        }

        if (!hasRecordPermission()) {
            _enrollmentState.value = EnrollmentState.Error("Microphone permission required")
            return
        }

        Log.i(TAG, "Starting recording for phrase ${_currentPhraseIndex.value + 1}")
        _enrollmentState.value = EnrollmentState.Recording(_currentPhraseIndex.value)
        recordedSamples.clear()
        _recordingProgress.value = 0f

        recordingJob = scope.launch(Dispatchers.IO) {
            var record: AudioRecord? = null
            try {
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                    .coerceAtLeast(SAMPLE_RATE) // At least 1 second buffer

                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                audioRecord = record

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) {
                        _enrollmentState.value = EnrollmentState.Error("Failed to initialize audio recorder")
                    }
                    return@launch
                }

                record.startRecording()
                val buffer = ShortArray(bufferSize / 2)
                val startTime = System.currentTimeMillis()

                while (isActive) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed >= RECORD_DURATION_MS) break

                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // Collect samples
                        for (i in 0 until read) {
                            recordedSamples.add(buffer[i])
                        }

                        // Calculate amplitude for visualization
                        val amplitude = calculateAmplitude(buffer, read)
                        withContext(Dispatchers.Main) {
                            _recordingAmplitude.value = amplitude
                            _recordingProgress.value = (elapsed.toFloat() / RECORD_DURATION_MS).coerceIn(0f, 1f)
                        }
                    }
                }

                // Stop recording
                record.stop()

                // Process the recording
                withContext(Dispatchers.Main) {
                    _recordingProgress.value = 1f
                    processRecording()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _enrollmentState.value = EnrollmentState.Error("Recording failed: ${e.message}")
                }
            } finally {
                // ALWAYS release AudioRecord to prevent native memory leak
                try {
                    record?.stop()
                } catch (_: Exception) {}
                try {
                    record?.release()
                } catch (_: Exception) {}
                audioRecord = null
            }
        }
    }

    /**
     * Stop recording early (if user wants to retry).
     */
    fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordedSamples.clear()
        _recordingAmplitude.value = 0f
        _recordingProgress.value = 0f

        val currentPhrase = getCurrentPhrase()
        if (currentPhrase != null) {
            _enrollmentState.value = EnrollmentState.WaitingToRecord(currentPhrase)
        }
    }

    /**
     * Process the recorded audio and extract embedding.
     */
    private fun processRecording() {
        if (recordedSamples.size < MIN_AUDIO_LENGTH) {
            Log.w(TAG, "Recording too short: ${recordedSamples.size} samples")
            val currentPhrase = getCurrentPhrase()
            if (currentPhrase != null) {
                _enrollmentState.value = EnrollmentState.WaitingToRecord(currentPhrase)
            }
            return
        }

        _enrollmentState.value = EnrollmentState.Processing

        scope.launch {
            try {
                val samples = recordedSamples.toShortArray()
                Log.d(TAG, "Processing ${samples.size} samples")

                // Extract embedding
                val embedding = embeddingManager.createEmbedding(samples)

                if (embedding.all { it == 0.0 }) {
                    Log.w(TAG, "Empty embedding - audio may be too quiet")
                    _enrollmentState.value = EnrollmentState.Error("Could not detect voice. Please speak louder.")
                    return@launch
                }

                collectedEmbeddings.add(embedding)
                Log.i(TAG, "Embedding ${collectedEmbeddings.size}/${phrases.size} collected")

                // Move to next phrase or complete
                if (_currentPhraseIndex.value < phrases.size - 1) {
                    _currentPhraseIndex.value++
                    _enrollmentState.value = EnrollmentState.WaitingToRecord(phrases[_currentPhraseIndex.value])
                } else {
                    // All phrases recorded - finalize enrollment
                    finalizeEnrollment()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Processing error: ${e.message}", e)
                _enrollmentState.value = EnrollmentState.Error("Processing failed: ${e.message}")
            }
        }
    }

    /**
     * Finalize enrollment by combining embeddings and saving.
     */
    private suspend fun finalizeEnrollment() {
        _enrollmentState.value = EnrollmentState.Finalizing

        try {
            if (collectedEmbeddings.size < SpeakerEmbeddingManager.MIN_ENROLLMENT_SAMPLES) {
                _enrollmentState.value = EnrollmentState.Error(
                    "Not enough samples. Got ${collectedEmbeddings.size}, need ${SpeakerEmbeddingManager.MIN_ENROLLMENT_SAMPLES}"
                )
                return
            }

            // Combine all embeddings
            val combinedEmbedding = embeddingManager.combineEmbeddings(collectedEmbeddings)

            // Calculate quality score
            val quality = embeddingManager.calculateEnrollmentQuality(collectedEmbeddings, combinedEmbedding)

            // Save the embedding
            embeddingManager.saveEmbedding(combinedEmbedding, quality)

            Log.i(TAG, "Enrollment complete! Quality: $quality")
            _enrollmentState.value = EnrollmentState.Complete(quality)

        } catch (e: Exception) {
            Log.e(TAG, "Finalization error: ${e.message}", e)
            _enrollmentState.value = EnrollmentState.Error("Failed to save voice profile: ${e.message}")
        }
    }

    /**
     * Retry the current phrase.
     */
    fun retryCurrentPhrase() {
        if (_currentPhraseIndex.value < collectedEmbeddings.size) {
            collectedEmbeddings.removeAt(_currentPhraseIndex.value)
        }
        val currentPhrase = getCurrentPhrase()
        if (currentPhrase != null) {
            _enrollmentState.value = EnrollmentState.WaitingToRecord(currentPhrase)
        }
    }

    /**
     * Reset enrollment completely.
     */
    fun resetEnrollment() {
        stopRecording()
        collectedEmbeddings.clear()
        _currentPhraseIndex.value = 0
        _enrollmentState.value = EnrollmentState.NotStarted
    }

    /**
     * Delete existing enrollment.
     */
    fun deleteEnrollment() {
        scope.launch {
            embeddingManager.deleteEnrollment()
            resetEnrollment()
        }
    }

    /**
     * Calculate RMS amplitude from audio buffer.
     */
    private fun calculateAmplitude(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble() / 32768.0
            sum += sample * sample
        }
        val rms = kotlin.math.sqrt(sum / length)
        return (rms * 5).coerceIn(0.0, 1.0).toFloat()  // Scale for visualization
    }

    /**
     * Check microphone permission.
     */
    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        stopRecording()
        collectedEmbeddings.clear()
    }

    /**
     * Enrollment state machine.
     */
    sealed class EnrollmentState {
        object NotStarted : EnrollmentState()

        data class WaitingToRecord(
            val phrase: SpeakerEmbeddingManager.EnrollmentPhrase
        ) : EnrollmentState()

        data class Recording(
            val phraseIndex: Int
        ) : EnrollmentState()

        object Processing : EnrollmentState()

        object Finalizing : EnrollmentState()

        data class Complete(
            val quality: Float
        ) : EnrollmentState()

        data class Error(
            val message: String
        ) : EnrollmentState()
    }
}
