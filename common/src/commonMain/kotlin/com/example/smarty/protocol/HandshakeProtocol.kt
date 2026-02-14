package com.example.smarty.protocol

import kotlinx.serialization.Serializable

@Serializable
data class HandshakeRequest(
    val protocolVersion: String,
    val deviceId: String,
    val capabilities: DeviceCapabilities,
    val context: DeviceContext
)

@Serializable
data class DeviceCapabilities(
    val hardware: HardwareCapabilities,
    val localProcessing: LocalProcessingCapabilities,
    val media: MediaCapabilities,
    val system: SystemCapabilities
)

@Serializable
data class HardwareCapabilities(
    val flashlight: Boolean,
    val camera: Boolean,
    val microphone: Boolean,
    val haptics: Boolean,
    val screenCapture: Boolean,
    val biometric: Boolean
)

@Serializable
data class LocalProcessingCapabilities(
    val stt: Boolean,
    val contentTypeDetection: Boolean,
    val fts5Search: Boolean,
    val localCommands: List<String>
)

@Serializable
data class MediaCapabilities(
    val audioPlayback: Boolean,
    val localMusicLibrary: Boolean,
    val visualizer: Boolean
)

@Serializable
data class SystemCapabilities(
    val osVersion: Int,
    val deviceClass: String, // "HIGH", "MEDIUM", "LOW"
    val availableMemoryMb: Long,
    val storageFreeMb: Long
)

@Serializable
data class DeviceContext(
    val batteryLevel: Int,
    val isCharging: Boolean,
    val networkType: String, // "wifi", "cellular", "none"
    val isNetworkValidated: Boolean
)

@Serializable
data class HandshakeResponse(
    val sessionId: String,
    val executionPolicy: ExecutionPolicy,
    val syncState: RemoteSyncState
)

@Serializable
data class ExecutionPolicy(
    val serverSide: List<String>,
    val deviceSide: List<String>,
    val hybrid: List<HybridActionPolicy>
)

@Serializable
data class HybridActionPolicy(
    val action: String,
    val prefer: String, // "server", "device"
    val fallback: String,
    val condition: String? = null
)

@Serializable
data class RemoteSyncState(
    val lastServerTimestamp: String,
    val pendingSyncCount: Int
)
