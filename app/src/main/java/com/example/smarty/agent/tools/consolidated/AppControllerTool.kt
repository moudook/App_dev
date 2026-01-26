package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

@Serializable
data class AppControlArgs(
    @property:LLMDescription("The action to perform: 'toggle_theme', 'clear_cache', 'set_privacy_mode', 'sync_memory', 'backup_data'")
    val action: String,
    @property:LLMDescription("The value for the action (e.g., 'dark', 'light', 'true', 'false', 'high', 'standard')")
    val value: String? = null
)

@Serializable
data class AppControlResult(
    val success: Boolean,
    val message: String
)

/**
 * Tool for controlling app-wide settings and maintenance.
 * Part of the hybridized toolset for full AI control.
 */
class AppControllerTool(
    private val onToggleTheme: (Boolean) -> Unit,
    private val onClearCache: () -> Unit,
    private val onSyncMemory: () -> Unit,
    private val onBackupData: () -> Unit,
    private val onSetPrivacyMode: (String) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) : Tool<AppControlArgs, AppControlResult>(
    argsSerializer = AppControlArgs.serializer(),
    resultSerializer = AppControlResult.serializer(),
    name = "app_controller",
    description = """
        Controls app-wide settings and maintenance tasks.

        ACTIONS:
        - toggle_theme: Change between light and dark mode. value="dark" or "light"
        - clear_cache: Free up storage by clearing app cache.
        - sync_memory: Force a synchronization of AI memory from your notes.
        - backup_data: Trigger an export/backup of all your notes and data.
        - set_privacy_mode: Adjust the global privacy level. value="high" (masking) or "standard"
    """.trimIndent()
) {
    override suspend fun execute(args: AppControlArgs): AppControlResult {
        return try {
            when (args.action) {
                "toggle_theme" -> {
                    val isDark = args.value?.lowercase() == "dark"
                    onToggleTheme(isDark)
                    AppControlResult(true, "Theme changed to ${if (isDark) "dark" else "light"} mode")
                }
                "clear_cache" -> {
                    onStatusUpdate("Clearing cache...")
                    onClearCache()
                    AppControlResult(true, "App cache cleared successfully")
                }
                "sync_memory" -> {
                    onStatusUpdate("Syncing AI memory...")
                    onSyncMemory()
                    AppControlResult(true, "AI memory synchronization started")
                }
                "backup_data" -> {
                    onStatusUpdate("Preparing backup...")
                    onBackupData()
                    AppControlResult(true, "Data backup initiated")
                }
                "set_privacy_mode" -> {
                    val mode = args.value ?: "standard"
                    onSetPrivacyMode(mode)
                    AppControlResult(true, "Privacy mode set to: $mode")
                }
                else -> AppControlResult(false, "Unknown action: ${args.action}")
            }
        } catch (e: Exception) {
            AppControlResult(false, "Error: ${e.message}")
        }
    }
}
