package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.model.AudioTrack
import kotlinx.serialization.Serializable

@Serializable
data class AudioControlArgs(
    @property:LLMDescription("The action to perform: 'play', 'pause', 'resume', 'stop', 'seek', 'toggle'")
    val action: String,
    @property:LLMDescription("For 'play': audio track name/query. For 'seek': position in seconds.")
    val target: String? = null,
    @property:LLMDescription("For 'seek': position as percentage (0-100)")
    val percentage: Int? = null
)

@Serializable
data class AudioControlResult(
    val success: Boolean,
    val message: String,
    val currentTrack: String? = null,
    val position: String? = null,
    val isPlaying: Boolean = false
)

/**
 * Hybridized Audio Playback Control Tool.
 *
 * Allows the AI agent to control audio playback beyond just starting tracks.
 * Delegates all operations to AudioPlaybackManager via callbacks.
 *
 * RESPONSIBILITIES:
 * - Play/pause/resume/stop playback
 * - Seek to specific positions
 * - Query current playback state
 *
 * INTEGRATION POINTS:
 * - AudioPlaybackManager (via callbacks)
 * - SystemFeatureManager (for track lookup)
 * - AudioPlayerService (indirectly via manager)
 */
class AudioControlTool(
    private val onPlay: (AudioTrack) -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onStop: () -> Unit,
    private val onSeek: (Long) -> Unit,
    private val onToggle: () -> Unit,
    private val onFindAudio: (String) -> AudioTrack?,
    private val getCurrentTrack: () -> AudioTrack?,
    private val getCurrentPosition: () -> Long,
    private val getDuration: () -> Long,
    private val isPlaying: () -> Boolean,
    private val onStatusUpdate: (String) -> Unit
) : Tool<AudioControlArgs, AudioControlResult>(
    argsSerializer = AudioControlArgs.serializer(),
    resultSerializer = AudioControlResult.serializer(),
    name = "audio_control",
    description = """
        Controls audio playback on the device.

        ACTIONS:
        - play: Start playing an audio track by name or query
        - pause: Pause the current track
        - resume: Resume the paused track
        - stop: Stop playback completely
        - seek: Jump to a specific position (use 'target' for seconds or 'percentage' for %)
        - toggle: Toggle between play and pause

        EXAMPLES:
        - "Pause the music" -> action="pause"
        - "Skip to 2 minutes" -> action="seek", target="120"
        - "Jump to 50%" -> action="seek", percentage=50
    """.trimIndent()
) {
    override suspend fun execute(args: AudioControlArgs): AudioControlResult {
        return try {
            when (args.action.lowercase()) {
                "play" -> {
                    val query = args.target ?: return AudioControlResult(
                        success = false,
                        message = "Please specify which audio to play"
                    )

                    onStatusUpdate("Finding audio: $query")
                    val track = onFindAudio(query)

                    if (track != null) {
                        onPlay(track)
                        AudioControlResult(
                            success = true,
                            message = "Now playing: ${track.title}",
                            currentTrack = track.title,
                            isPlaying = true
                        )
                    } else {
                        AudioControlResult(
                            success = false,
                            message = "Could not find audio matching: $query"
                        )
                    }
                }

                "pause" -> {
                    onPause()
                    val track = getCurrentTrack()
                    AudioControlResult(
                        success = true,
                        message = "Playback paused",
                        currentTrack = track?.title,
                        isPlaying = false
                    )
                }

                "resume" -> {
                    onResume()
                    val track = getCurrentTrack()
                    AudioControlResult(
                        success = true,
                        message = "Playback resumed",
                        currentTrack = track?.title,
                        isPlaying = true
                    )
                }

                "stop" -> {
                    onStop()
                    AudioControlResult(
                        success = true,
                        message = "Playback stopped",
                        isPlaying = false
                    )
                }

                "toggle" -> {
                    onToggle()
                    val playing = isPlaying()
                    val track = getCurrentTrack()
                    AudioControlResult(
                        success = true,
                        message = if (playing) "Playback resumed" else "Playback paused",
                        currentTrack = track?.title,
                        isPlaying = playing
                    )
                }

                "seek" -> {
                    val duration = getDuration()
                    if (duration <= 0) {
                        return AudioControlResult(
                            success = false,
                            message = "No audio is currently loaded"
                        )
                    }

                    val targetPosition = when {
                        args.percentage != null -> {
                            val pct = args.percentage.coerceIn(0, 100)
                            (duration * pct / 100.0).toLong()
                        }
                        args.target != null -> {
                            // Parse as seconds
                            val seconds = args.target.toIntOrNull() ?: return AudioControlResult(
                                success = false,
                                message = "Invalid seek position: ${args.target}"
                            )
                            (seconds * 1000L).coerceIn(0, duration)
                        }
                        else -> return AudioControlResult(
                            success = false,
                            message = "Please specify 'target' (seconds) or 'percentage' for seek"
                        )
                    }

                    onSeek(targetPosition)
                    val positionSec = targetPosition / 1000
                    val track = getCurrentTrack()

                    AudioControlResult(
                        success = true,
                        message = "Seeked to ${formatTime(targetPosition)}",
                        currentTrack = track?.title,
                        position = formatTime(targetPosition),
                        isPlaying = isPlaying()
                    )
                }

                else -> AudioControlResult(
                    success = false,
                    message = "Unknown action: ${args.action}. Use: play, pause, resume, stop, seek, toggle"
                )
            }
        } catch (e: Exception) {
            AudioControlResult(
                success = false,
                message = "Audio control error: ${e.message}"
            )
        }
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / 60000) % 60
        val hours = millis / 3600000

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}
