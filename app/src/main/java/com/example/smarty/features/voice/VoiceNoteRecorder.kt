package com.example.smarty.features.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Composable
fun rememberVoiceNoteRecorder(
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onResult: (String) -> Unit = {},
    onError: (String) -> Unit = {}
): VoiceNoteRecorder {
    return remember { VoiceNoteRecorder(context, coroutineScope, onResult, onError) }
}

class VoiceNoteRecorder(
    private val context: android.content.Context,
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
    private val onResult: (String) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    var speechInitiatedInChatMode by mutableStateOf(false)
    
    fun startRecording(isChatMode: Boolean = false) {
        speechInitiatedInChatMode = isChatMode
        _state.value = RecordingState.Recording
        _durationMs.value = 0L
        _amplitude.value = 0f
        // In a real implementation, this would start actual audio recording
    }
    
    fun stopRecording(): String? {
        val currentPath = "placeholder_path_${System.currentTimeMillis()}.mp4"
        val duration = _durationMs.value
        _state.value = RecordingState.Completed(currentPath, duration)
        // In a real implementation, this would stop actual audio recording and return the path
        return currentPath
    }
    
    fun cancelRecording() {
        _state.value = RecordingState.Idle
        _durationMs.value = 0L
        _amplitude.value = 0f
    }
    
    fun reset() {
        _state.value = RecordingState.Idle
        _durationMs.value = 0L
        _amplitude.value = 0f
        speechInitiatedInChatMode = false
    }
    
    fun hasRecordingPermission(): Boolean {
        return true
    }

    fun formatDuration(durationMs: Long): String {
        return com.example.smarty.features.voice.formatDuration(durationMs)
    }
}

fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%02d:%02d", minutes, remainingSeconds)
}