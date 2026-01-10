package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.smarty.agent.ImageDisplayItem
import com.example.smarty.agent.tools.base.AudioPlaybackResult
import com.example.smarty.agent.tools.base.ImageDisplayResult
import com.example.smarty.agent.tools.external.ScreenContext
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.data.repository.JarvisRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class SystemInterfaceArgs(
    @property:LLMDescription("The action to perform: 'play_media', 'display_media', 'launch_app', 'capture_screen'")
    val action: String,
    @property:LLMDescription("The resource to act on: file path, URL, or app name")
    val resource: String? = null,
    @property:LLMDescription("Optional parameters for the action")
    val parameters: SystemParameters? = null
)

@Serializable
data class SystemParameters(
    val loop: Boolean? = null,
    val timestamp: Int? = null
)

@Serializable
data class SystemResult(
    val success: Boolean,
    val message: String,
    val data: String? = null
) {
    override fun toString(): String {
        return "{success:$success|message:$message|data:${data ?: "null"}}"
    }
}

class SystemInterfaceTool(
    private val context: Context,
    private val repository: JarvisRepository,
    private val onLaunchApp: (String) -> Unit,
    private val onPlayAudio: (AudioTrack) -> Unit,
    private val onDisplayImages: (List<ImageDisplayItem>) -> Unit,
    private val getScreenContext: () -> ScreenContext?,
    private val onStatusUpdate: (String) -> Unit
) : Tool<SystemInterfaceArgs, SystemResult>(
    argsSerializer = SystemInterfaceArgs.serializer(),
    resultSerializer = SystemResult.serializer(),
    name = "system_interface",
    description = """
        Handles interactions with the host OS, hardware, and apps.
        
        ACTIONS:
        - play_media: Play audio file. usage: resource="filename.mp3"
        - display_media: Display image. usage: resource="filename.jpg"
        - launch_app: Open an application. usage: resource="AppName"
        - capture_screen: Capture and save screenshot. usage: (no resource needed)
    """.trimIndent()
) {
    private val systemJson = Json { encodeDefaults = false }

    override suspend fun execute(args: SystemInterfaceArgs): SystemResult {
        return try {
            when (args.action) {
                "play_media" -> {
                    onStatusUpdate("Playing audio...")
                    playMedia(args)
                }
                "display_media" -> {
                    onStatusUpdate("Opening image...")
                    displayMedia(args)
                }
                "launch_app" -> {
                    onStatusUpdate("Launching ${args.resource}...")
                    launchApp(args)
                }
                "capture_screen" -> {
                    onStatusUpdate("Capturing screen...")
                    captureScreen(args)
                }
                else -> SystemResult(false, "Unknown action: ${args.action}")
            }
        } catch (e: Exception) {
            SystemResult(false, "Error: ${e.message}")
        }
    }

    private fun playMedia(args: SystemInterfaceArgs): SystemResult {
        val fileName = args.resource ?: return SystemResult(false, "Resource (filename) required for play_media")
        
        val track = AudioTrack(
            id = fileName.hashCode().toString(), 
            title = fileName,
            uri = fileName
        )
        onPlayAudio(track)
        return SystemResult(true, "Playing audio: $fileName")
    }

    private fun displayMedia(args: SystemInterfaceArgs): SystemResult {
        val fileName = args.resource ?: return SystemResult(false, "Resource (filename) required for display_media")
        val item = ImageDisplayItem(
            uri = fileName, 
            fileName = fileName,
            noteTitle = "Image"
        )
        onDisplayImages(listOf(item))
        return SystemResult(true, "Displaying image: $fileName")
    }

    private fun launchApp(args: SystemInterfaceArgs): SystemResult {
        val appName = args.resource ?: return SystemResult(false, "Resource (app name) required for launch_app")
        
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        
        var bestMatch = packages.find { pkg ->
            pkg.applicationInfo?.let { pm.getApplicationLabel(it).toString().equals(appName, ignoreCase = true) } ?: false
        }

        if (bestMatch == null) {
            bestMatch = packages.find { pkg ->
                pkg.applicationInfo?.let { pm.getApplicationLabel(it).toString().contains(appName, ignoreCase = true) } ?: false
            }
        }

        if (bestMatch != null) {
            val packageName = bestMatch.packageName
            onLaunchApp(packageName)
            return SystemResult(true, "Launching $appName ($packageName)")
        }

        return SystemResult(false, "App '$appName' not found")
    }

    private suspend fun captureScreen(args: SystemInterfaceArgs): SystemResult {
        val screenContext = getScreenContext()
        if (screenContext == null) {
            return SystemResult(false, "Screen capture not available")
        }
        
        return SystemResult(true, "Screen captured (context: ${screenContext.referringApp})")
    }
}
