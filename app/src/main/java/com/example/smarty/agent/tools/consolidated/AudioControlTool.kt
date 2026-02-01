package com.example.smarty.agent.tools.consolidated

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.viewmodel.managers.AudioFeatureManager.AudioSearchResult
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
    private val onFindAudio: (String) -> AudioSearchResult,
    private val getCurrentTrack: () -> AudioTrack?,
    private val getCurrentPosition: () -> Long,
    private val getDuration: () -> Long,
    private val isPlaying: () -> Boolean,
    private val onStatusUpdate: (String) -> Unit,
    private val onPlayList: (List<AudioTrack>) -> Unit = {}
) : Tool<AudioControlArgs, AudioControlResult>(
    argsSerializer = AudioControlArgs.serializer(),
    resultSerializer = AudioControlResult.serializer(),
    name = "audio_control",
    description = """
        Controls audio playback on the device.

        ACTIONS:
        - play: Start playing audio. STRICT RULE: ONLY use this action if the user's message STARTS with the word "play" (case-insensitive). If "play" appears in the middle of a sentence (e.g., "I want to play..."), DO NOT use this tool.
        - pause: Pause the current track
        - resume: Resume the paused track
        - stop: Stop playback completely
        - seek: Jump to a specific position (use 'target' for seconds or 'percentage' for %)
        - toggle: Toggle between play and pause

        EXAMPLES:
        - "Play Imagine" -> action="play", target="Imagine" (Allowed: Starts with 'Play')
        - "Can you play Imagine?" -> DO NOT CALL (Denied: Doesn't start with 'Play')
        - "Pause the music" -> action="pause"
    """.trimIndent()
) {
    override suspend fun execute(args: AudioControlArgs): AudioControlResult {
        return try {
            when (args.action.lowercase()) {
                "play" -> {
                    val query = args.target ?: return AudioControlResult(
                        success = false,
                        message = "Please specify what audio to play."
                    )

                    onStatusUpdate("Finding audio...")
                    val result = onFindAudio(query)

                    when (result) {
                        is AudioSearchResult.ExactMatch -> {
                            val track = result.track
                            onPlay(track)
                            AudioControlResult(
                                success = true,
                                message = "Now playing: ${track.title}",
                                currentTrack = track.title,
                                isPlaying = true
                            )
                        }
                        is AudioSearchResult.Fallback -> {
                            val tracks = result.tracks
                            if (tracks.isNotEmpty()) {
                                // Play first, queue rest
                                onPlayList(tracks)
                                val count = tracks.size
                                AudioControlResult(
                                    success = true,
                                    message = "No exact match for '$query'. Queued $count random tracks from library.",
                                    currentTrack = tracks.first().title,
                                    isPlaying = true
                                )
                            } else {
                                AudioControlResult(
                                    success = false,
                                    message = result.reason
                                )
                            }
                        }
                    }
                }

                "pause" -> {
                    onPause()
                    val track = getCurrentTrack()
                    AudioControlResult(
                        success = true,
                        message = "Playback paused.",
                        currentTrack = track?.title,
                        isPlaying = false
                    )
                }

                "resume" -> {
                    onResume()
                    val track = getCurrentTrack()
                    AudioControlResult(
                        success = true,
                        message = "Playback resumed.",
                        currentTrack = track?.title,
                        isPlaying = true
                    )
                }

                "stop" -> {
                    onStop()
                    AudioControlResult(
                        success = true,
                        message = "Playback stopped.",
                        isPlaying = false
                    )
                }

                "toggle" -> {
                    onToggle()
                    val playing = isPlaying()
                    val track = getCurrentTrack()
                    AudioControlResult(
                        success = true,
                        message = if (playing) "Playback resumed." else "Playback paused.",
                        currentTrack = track?.title,
                        isPlaying = playing
                    )
                }

                "seek" -> {
                    val duration = getDuration()
                    if (duration <= 0) {
                        return AudioControlResult(
                            success = false,
                            message = "No audio loaded to seek."
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
                                message = "Invalid seek target: ${args.target}"
                            )
                            (seconds * 1000L).coerceIn(0, duration)
                        }
                        else -> return AudioControlResult(
                            success = false,
                            message = "Missing seek parameters."
                        )
                    }

                    onSeek(targetPosition)
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
                    message = "Unknown action: ${args.action}"
                )
            }
        } catch (e: Exception) {
            AudioControlResult(
                success = false,
                message = "Audio control error: ${e.message ?: "Unknown"}"
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
