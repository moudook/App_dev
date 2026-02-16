package com.example.smarty.features.voice

sealed class RecordingState {
    object Idle : RecordingState()
    object Recording : RecordingState()
    object Paused : RecordingState()
    object Processing : RecordingState()
    data class Completed(val filePath: String, val durationMs: Long = 0) : RecordingState()
    data class Error(val message: String) : RecordingState()
}