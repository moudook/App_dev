package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.agent.ImageDisplayItem
import com.example.smarty.agent.models.ScreenContext
import com.example.smarty.data.model.AudioTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SystemInterfaceArgs(
    @property:LLMDescription("The action to perform: 'play_media', 'display_media', 'launch_app', 'capture_screen', 'navigate', 'share'")
    val action: String,
    @property:LLMDescription("The resource or destination: file path, app name, screen name, or content to share")
    val resource: String? = null,
    @property:LLMDescription("Optional parameters for the action")
    val parameters: SystemParameters? = null
)

@Serializable
data class SystemParameters(
    val loop: Boolean? = null,
    val timestamp: Int? = null,
    val title: String? = null // Used for 'share' action as subject
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

/**
 * Hybridized System Interface Tool.
 * 100% logic-free. Delegates to SystemFeatureManager.
 */
class SystemInterfaceTool(
    private val onLaunchApp: (String) -> Unit,
    private val onFindPackage: (String) -> String?,
    private val onPlayAudio: (AudioTrack) -> Unit,
    private val onFindAudio: (String) -> AudioTrack?,
    private val onDisplayImages: (List<ImageDisplayItem>) -> Unit,
    private val getScreenContext: () -> ScreenContext?,
    private val onNavigate: (String) -> Unit,
    private val onShare: (String, String?) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) : Tool<SystemInterfaceArgs, SystemResult>(
    argsSerializer = SystemInterfaceArgs.serializer(),
    resultSerializer = SystemResult.serializer(),
    name = "system_interface",
    description = """
        Handles interactions with the host OS, hardware, and apps.

        ACTIONS:
        - play_media: Play audio by name or filename.
        - display_media: Display image by filename.
        - launch_app: Open an application by name.
        - capture_screen: Capture and save screenshot.
        - navigate: Go to a specific screen in THIS app.
        - share: Send text content to other apps.
    """.trimIndent()
) {
    override suspend fun execute(args: SystemInterfaceArgs): SystemResult {
        return try {
            when (args.action) {
                "play_media" -> {
                    val query = args.resource ?: return SystemResult(false, "Resource required")
                    onStatusUpdate("Finding audio...")
                    val match = onFindAudio(query)
                    if (match != null) {
                        onPlayAudio(match)
                        SystemResult(true, "Playing: ${match.title}")
                    } else {
                        // Fallback: direct URI
                        onPlayAudio(AudioTrack(id = query.hashCode().toString(), title = query, uri = query))
                        SystemResult(true, "Playing audio: $query")
                    }
                }
                "display_media" -> {
                    val fileName = args.resource ?: return SystemResult(false, "Filename required")
                    onDisplayImages(listOf(ImageDisplayItem(uri = fileName, fileName = fileName, noteTitle = "Image")))
                    SystemResult(true, "Displaying image")
                }
                "launch_app" -> {
                    val appName = args.resource ?: return SystemResult(false, "App name required")
                    onStatusUpdate("Finding $appName...")
                    val packageName = onFindPackage(appName)
                    if (packageName != null) {
                        onLaunchApp(packageName)
                        SystemResult(true, "Launching $appName ($packageName)")
                    } else {
                        SystemResult(false, "App '$appName' not found")
                    }
                }
                "capture_screen" -> {
                    onStatusUpdate("Capturing...")
                    val ctx = getScreenContext() ?: return SystemResult(false, "Capture failed")
                    SystemResult(true, "Screen captured", ctx.referringApp)
                }
                "navigate" -> {
                    val dest = args.resource ?: return SystemResult(false, "Destination required")
                    onNavigate(dest)
                    SystemResult(true, "Navigating to $dest")
                }
                "share" -> {
                    val content = args.resource ?: return SystemResult(false, "Content required")
                    onShare(content, args.parameters?.title)
                    SystemResult(true, "Shared content")
                }
                else -> SystemResult(false, "Unknown action")
            }
        } catch (e: Exception) {
            SystemResult(false, "Error: ${e.message}")
        }
    }
}
