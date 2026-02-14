package com.example.smarty.core.common.util

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.example.smarty.protocol.DeviceCapabilities
import com.example.smarty.protocol.DeviceContext
import com.example.smarty.protocol.HardwareCapabilities
import com.example.smarty.protocol.LocalProcessingCapabilities
import com.example.smarty.protocol.MediaCapabilities
import com.example.smarty.protocol.SystemCapabilities

class DeviceInfoProvider(private val context: Context) {

    fun getDeviceCapabilities(): DeviceCapabilities {
        return DeviceCapabilities(
            hardware = HardwareCapabilities(
                flashlight = hasSystemFeature("android.hardware.camera.flash"),
                camera = hasSystemFeature("android.hardware.camera"),
                microphone = hasSystemFeature("android.hardware.microphone"),
                haptics = true, // Android usually has haptics
                screenCapture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP,
                biometric = hasSystemFeature("android.hardware.fingerprint") || 
                           hasSystemFeature("android.hardware.biometrics.face")
            ),
            localProcessing = LocalProcessingCapabilities(
                stt = true, // We have fallbacks
                contentTypeDetection = true,
                fts5Search = true,
                localCommands = listOf("FLASHLIGHT", "VOLUME", "HAPTIC", "STATUS")
            ),
            media = MediaCapabilities(
                audioPlayback = true,
                localMusicLibrary = true,
                visualizer = true
            ),
            system = SystemCapabilities(
                osVersion = Build.VERSION.SDK_INT,
                deviceClass = getDeviceClass(),
                availableMemoryMb = getAvailableMemoryMb(),
                storageFreeMb = getFreeStorageMb()
            )
        )
    }

    fun getDeviceContext(): DeviceContext {
        val batteryStatus = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
        val level = batteryStatus.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryStatus.isCharging
        
        return DeviceContext(
            batteryLevel = level,
            isCharging = isCharging,
            networkType = getNetworkType(),
            isNetworkValidated = true // Simplified for now
        )
    }

    private fun hasSystemFeature(feature: String): Boolean {
        return context.packageManager.hasSystemFeature(feature)
    }

    private fun getDeviceClass(): String {
        val ram = getAvailableMemoryMb()
        return when {
            ram > 6000 -> "HIGH"
            ram > 3000 -> "MEDIUM"
            else -> "LOW"
        }
    }

    private fun getAvailableMemoryMb(): Long {
        val mi = android.app.ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        activityManager.getMemoryInfo(mi)
        return mi.totalMem / 1024 / 1024
    }

    private fun getFreeStorageMb(): Long {
        val stat = StatFs(Environment.getDataDirectory().path)
        return stat.availableBlocksLong * stat.blockSizeLong / 1024 / 1024
    }

    private fun getNetworkType(): String {
        // Simplified network check
        return "wifi" // placeholder
    }
}
