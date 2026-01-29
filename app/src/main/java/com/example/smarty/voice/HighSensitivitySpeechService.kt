package com.example.smarty.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import java.io.IOException

/**
 * High-sensitivity speech service with audio gain amplification.
 *
 * This allows wake word detection from further away by:
 * 1. Using VOICE_RECOGNITION audio source (has AGC - Automatic Gain Control)
 * 2. Applying software gain amplification to boost quiet audio
 * 3. Using larger buffer for better capture
 * 4. Keeps rolling audio buffer for speaker verification
 *
 * Gain multiplier: 2.0x - 4.0x recommended for wake word detection
 * Higher values = more sensitive but also more noise
 */
class HighSensitivitySpeechService(
    private val Recognizer: Recognizer,
    private val sampleRate: Float,
    private val gainMultiplier: Float = 2.5f  // 2.5x amplification for better range
) {
    companion object {
        private const val TAG = "HighSensitivityAudio"

        // Audio configuration
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Use VOICE_RECOGNITION for built-in AGC and noise suppression
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION

        // Rolling buffer for speaker verification (3 seconds of audio at 16kHz)
        private const val VERIFICATION_BUFFER_SECONDS = 3
    }

    private var audioRecord: AudioRecord? = null
    private var recognitionThread: Thread? = null
    private var listener: RecognitionListener? = null

    @Volatile
    private var isRunning = false

    // Rolling buffer for speaker verification
    private val verificationBufferSize = (sampleRate * VERIFICATION_BUFFER_SECONDS).toInt()
    private val verificationBuffer = ShortArray(verificationBufferSize)
    private var bufferWriteIndex = 0
    private val bufferLock = Any()

    /**
     * Start listening with gain amplification.
     */
    fun startListening(listener: RecognitionListener) {
        if (isRunning) {
            Log.w(TAG, "Already running")
            return
        }

        this.listener = listener

        // Create recorder in local variable first to prevent leak on exception
        var record: AudioRecord? = null
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate.toInt(),
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            // Use larger buffer for better long-range audio capture (4x minimum)
            val actualBufferSize = bufferSize * 4

            record = AudioRecord(
                AUDIO_SOURCE,
                sampleRate.toInt(),
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                actualBufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                // Release before throwing to prevent leak
                try {
                    record.release()
                } catch (_: Exception) {}
                throw IOException("Failed to initialize AudioRecord")
            }

            record.startRecording()

            // Only assign to field after successful start (prevents leak)
            audioRecord = record
            isRunning = true

            recognitionThread = Thread({
                processAudio(actualBufferSize)
            }, "VoskHighSensitivity").apply {
                start()
            }

            Log.i(TAG, "Started with ${gainMultiplier}x gain amplification")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}", e)
            // Release local record if setup failed (fixes memory leak)
            if (audioRecord == null) {
                try {
                    record?.release()
                } catch (_: Exception) {}
            }
            listener.onError(e)
            cleanup()
        }
    }

    /**
     * Process audio with gain amplification.
     */
    private fun processAudio(bufferSize: Int) {
        val buffer = ShortArray(bufferSize / 2)  // 16-bit = 2 bytes per sample
        val amplifiedBuffer = ShortArray(buffer.size)
        var audioRecordInvalidated = false  // Track if AudioRecord became invalid

        try {
            while (isRunning) {
                // CRASH FIX: Validate AudioRecord state before read
                // After process death, reference may exist but native resources are gone
                val record = audioRecord
                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord invalid - stopping processing")
                    audioRecordInvalidated = true
                    break
                }
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(TAG, "AudioRecord not recording - stopping")
                    audioRecordInvalidated = true
                    break
                }

                val readCount = try {
                    record.read(buffer, 0, buffer.size)
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "AudioRecord read failed - native resources likely reclaimed: ${e.message}")
                    audioRecordInvalidated = true
                    break
                }

                if (readCount > 0) {
                    // Apply gain amplification for Vosk wake word detection
                    applyGain(buffer, amplifiedBuffer, readCount)

                    // SECURITY FIX: Store ORIGINAL (non-amplified) audio for speaker verification
                    // Using amplified audio distorts MFCC features and causes false accepts
                    synchronized(bufferLock) {
                        for (i in 0 until readCount) {
                            verificationBuffer[bufferWriteIndex] = buffer[i]  // Use original, not amplified
                            bufferWriteIndex = (bufferWriteIndex + 1) % verificationBufferSize
                        }
                    }

                    // Convert to byte array for Vosk
                    val byteBuffer = shortsToBytes(amplifiedBuffer, readCount)

                    // Feed to Recognizer
                    // CRASH FIX #3: Wrap Recognizer calls in try-catch to handle closed Recognizer
                    // VoskWakeWordManager can close the Recognizer while processAudio is still running
                    if (!isRunning) break
                    try {
                        if (Recognizer.acceptWaveForm(byteBuffer, byteBuffer.size)) {
                            val result = Recognizer.result
                            listener?.onResult(result)
                        } else {
                            val partial = Recognizer.partialResult
                            listener?.onPartialResult(partial)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Recognizer access failed - likely closed: ${e.message}")
                        break
                    }
                } else if (readCount < 0) {
                    Log.e(TAG, "Audio read error: $readCount")
                    break
                }
            }

            // Get final result
            // CRASH FIX #3: Wrap finalResult call in try-catch to handle closed Recognizer
            try {
                val finalResult = Recognizer.finalResult
                listener?.onFinalResult(finalResult)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get final result - Recognizer likely closed: ${e.message}")
            }

            // ISSUE #3 FIX: Notify listener if AudioRecord became invalid
            // This allows VoskWakeWordManager to trigger recovery/re-initialization
            if (audioRecordInvalidated) {
                listener?.onError(IllegalStateException("AudioRecord invalidated - system took microphone"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Processing error: ${e.message}", e)
            listener?.onError(e)
        }
    }

    /**
     * Apply gain amplification to audio samples.
     * Clamps to prevent clipping distortion.
     */
    private fun applyGain(input: ShortArray, output: ShortArray, count: Int) {
        for (i in 0 until count) {
            // Apply gain with soft clipping to prevent harsh distortion
            val amplified = (input[i] * gainMultiplier).toInt()

            // Clamp to valid 16-bit range
            output[i] = when {
                amplified > Short.MAX_VALUE -> Short.MAX_VALUE
                amplified < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> amplified.toShort()
            }
        }
    }

    /**
     * Convert short array to byte array (little-endian PCM16).
     */
    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Get recent audio samples for speaker verification.
     * Returns the last ~2 seconds of audio (32000 samples at 16kHz).
     */
    fun getRecentAudioSamples(): ShortArray {
        val sampleCount = (sampleRate * 2).toInt()  // 2 seconds of audio
        val samples = ShortArray(sampleCount)

        synchronized(bufferLock) {
            // Copy from rolling buffer, starting from 2 seconds before write position
            var readIndex = (bufferWriteIndex - sampleCount + verificationBufferSize) % verificationBufferSize
            for (i in 0 until sampleCount) {
                samples[i] = verificationBuffer[readIndex]
                readIndex = (readIndex + 1) % verificationBufferSize
            }
        }

        return samples
    }

    /**
     * Clear the verification buffer.
     */
    fun clearVerificationBuffer() {
        synchronized(bufferLock) {
            verificationBuffer.fill(0)
            bufferWriteIndex = 0
        }
    }

    /**
     * Stop listening.
     */
    fun stop() {
        isRunning = false

        // ISSUE #6 FIX: Increased timeout from 500ms to 1000ms for safer Recognizer access
        // This ensures the processing thread completes before the Recognizer is closed
        try {
            val thread = recognitionThread
            if (thread != null && thread.isAlive) {
                thread.join(1000)
                // Warn if thread didn't stop in time - potential race condition
                if (thread.isAlive) {
                    Log.w(TAG, "ReJarvistion thread didn't stop within 1000ms - forcing interrupt")
                    thread.interrupt()
                    thread.join(200)  // Brief wait after interrupt
                }
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for recognition thread")
            Thread.currentThread().interrupt()
        }
        recognitionThread = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }
    }

    /**
     * Shutdown and release resources.
     */
    fun shutdown() {
        stop()
        cleanup()
    }

    private fun cleanup() {
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null
        listener = null
    }
}

