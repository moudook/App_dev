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
 *
 * Gain multiplier: 2.0x - 4.0x recommended for wake word detection
 * Higher values = more sensitive but also more noise
 */
class HighSensitivitySpeechService(
    private val recognizer: Recognizer,
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
    }

    private var audioRecord: AudioRecord? = null
    private var recognitionThread: Thread? = null
    private var listener: RecognitionListener? = null

    @Volatile
    private var isRunning = false

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

        try {
            while (isRunning && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1

                if (readCount > 0) {
                    // Apply gain amplification
                    applyGain(buffer, amplifiedBuffer, readCount)

                    // Convert to byte array for Vosk
                    val byteBuffer = shortsToBytes(amplifiedBuffer, readCount)

                    // Feed to recognizer
                    if (recognizer.acceptWaveForm(byteBuffer, byteBuffer.size)) {
                        val result = recognizer.result
                        listener?.onResult(result)
                    } else {
                        val partial = recognizer.partialResult
                        listener?.onPartialResult(partial)
                    }
                } else if (readCount < 0) {
                    Log.e(TAG, "Audio read error: $readCount")
                    break
                }
            }

            // Get final result
            val finalResult = recognizer.finalResult
            listener?.onFinalResult(finalResult)

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
     * Stop listening.
     */
    fun stop() {
        isRunning = false

        try {
            recognitionThread?.join(500)
        } catch (_: InterruptedException) {
            // Ignore
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
