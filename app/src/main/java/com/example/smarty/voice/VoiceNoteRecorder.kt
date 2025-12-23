package com.example.smarty.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manager for recording voice notes.
 * Records audio to a file and provides amplitude updates for waveform visualization.
 */
class VoiceNoteRecorder(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VoiceNoteRecorder"
        private const val SAMPLE_RATE = 44100
        private const val AMPLITUDE_UPDATE_INTERVAL_MS = 50L
        private const val MAX_RECORDING_DURATION_MS = 600_000L // 10 minutes max
    }

    // Recording state
    sealed class RecordingState {
        object Idle : RecordingState()
        object Recording : RecordingState()
        object Paused : RecordingState()
        data class Completed(val filePath: String, val durationMs: Long) : RecordingState()
        data class Error(val message: String) : RecordingState()
    }

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f) // 0.0 to 1.0
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0
    private var amplitudeJob: Job? = null
    private var timerJob: Job? = null

    /**
     * Check if recording permission is granted
     */
    fun hasRecordingPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start recording a voice note
     * @return true if recording started successfully
     */
    fun startRecording(): Boolean {
        if (!hasRecordingPermission()) {
            _state.value = RecordingState.Error("Microphone permission required")
            return false
        }

        if (_state.value == RecordingState.Recording) {
            Log.w(TAG, "Already recording")
            return false
        }

        try {
            // Create output file
            val voiceNotesDir = File(context.filesDir, "voice_notes")
            if (!voiceNotesDir.exists()) {
                voiceNotesDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            outputFile = File(voiceNotesDir, "voice_note_$timestamp.m4a")

            // Configure MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(128000)
                setOutputFile(outputFile?.absolutePath)
                setMaxDuration(MAX_RECORDING_DURATION_MS.toInt())

                prepare()
                start()
            }

            startTimeMs = System.currentTimeMillis()
            _state.value = RecordingState.Recording
            _durationMs.value = 0

            // Start amplitude monitoring
            startAmplitudeMonitoring()

            // Start duration timer
            startDurationTimer()

            Log.i(TAG, "Started recording to: ${outputFile?.absolutePath}")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            _state.value = RecordingState.Error("Failed to start recording: ${e.message}")
            cleanup()
            return false
        }
    }

    /**
     * Stop recording and save the file
     * @return the file path of the recording, or null if failed
     */
    fun stopRecording(): String? {
        if (_state.value != RecordingState.Recording && _state.value != RecordingState.Paused) {
            Log.w(TAG, "Not currently recording")
            return null
        }

        amplitudeJob?.cancel()
        timerJob?.cancel()

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null

            val filePath = outputFile?.absolutePath
            val duration = _durationMs.value

            if (filePath != null && File(filePath).exists()) {
                _state.value = RecordingState.Completed(filePath, duration)
                Log.i(TAG, "Recording saved: $filePath (${duration}ms)")
                return filePath
            } else {
                _state.value = RecordingState.Error("Recording file not found")
                return null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording: ${e.message}", e)
            _state.value = RecordingState.Error("Failed to save recording: ${e.message}")
            cleanup()
            return null
        }
    }

    /**
     * Cancel recording and delete the file
     */
    fun cancelRecording() {
        amplitudeJob?.cancel()
        timerJob?.cancel()

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
            // Ignore errors during cancel
        }

        mediaRecorder = null
        outputFile?.delete()
        outputFile = null
        _state.value = RecordingState.Idle
        _amplitude.value = 0f
        _durationMs.value = 0

        Log.d(TAG, "Recording cancelled")
    }

    /**
     * Reset state after completing/error
     */
    fun reset() {
        _state.value = RecordingState.Idle
        _amplitude.value = 0f
        _durationMs.value = 0
    }

    private fun startAmplitudeMonitoring() {
        amplitudeJob = scope.launch(Dispatchers.Default) {
            while (isActive && _state.value == RecordingState.Recording) {
                try {
                    val maxAmplitude = mediaRecorder?.maxAmplitude ?: 0
                    // Normalize to 0-1 range (max amplitude is ~32767)
                    val normalized = (maxAmplitude / 32767f).coerceIn(0f, 1f)
                    _amplitude.value = normalized
                } catch (_: Exception) {
                    // Ignore amplitude errors
                }
                delay(AMPLITUDE_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun startDurationTimer() {
        timerJob = scope.launch(Dispatchers.Default) {
            while (isActive && _state.value == RecordingState.Recording) {
                _durationMs.value = System.currentTimeMillis() - startTimeMs
                delay(100)
            }
        }
    }

    private fun cleanup() {
        amplitudeJob?.cancel()
        timerJob?.cancel()

        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}

        mediaRecorder = null
        outputFile?.delete()
        outputFile = null
        _amplitude.value = 0f
    }

    /**
     * Format duration for display
     */
    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
