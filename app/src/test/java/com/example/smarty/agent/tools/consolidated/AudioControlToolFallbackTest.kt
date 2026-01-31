package com.example.smarty.agent.tools.consolidated

import android.content.Context
import com.example.smarty.data.model.AudioTrack
import com.example.smarty.viewmodel.managers.AudioFeatureManager.AudioSearchResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioControlToolFallbackTest {

    private lateinit var mockContext: Context

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        // Mock string resources needed by the tool
        every { mockContext.getString(any()) } returns "Mocked String"
        every { mockContext.getString(any(), any()) } returns "Mocked String with args"
    }

    @Test
    fun `execute play with exact match calls onPlay`() = runBlocking {
        var playedTrack: AudioTrack? = null
        val testTrack = AudioTrack(
            id = "1",
            title = "Dubstep Song",
            uri = "content://media/1",
            duration = 1000,
            artist = "Artist",
            album = "Album"
        )

        val tool = AudioControlTool(
            context = mockContext,
            onPlay = { playedTrack = it },
            onPause = {},
            onResume = {},
            onStop = {},
            onSeek = {},
            onToggle = {},
            onFindAudio = { AudioSearchResult.ExactMatch(testTrack) },
            getCurrentTrack = { null },
            getCurrentPosition = { 0L },
            getDuration = { 0L },
            isPlaying = { false },
            onStatusUpdate = {}
        )

        val args = AudioControlArgs(action = "play", target = "dubstep")
        val result = tool.execute(args)

        assertTrue(result.success)
        assertEquals("Dubstep Song", result.currentTrack)
        assertEquals(testTrack, playedTrack)
    }

    @Test
    fun `execute play with fallback calls onPlayList and returns fallback message`() = runBlocking {
        var playedList: List<AudioTrack>? = null
        val fallbackTracks = listOf(
            AudioTrack(id = "1", title = "Random 1", uri = "u1", duration = 100),
            AudioTrack(id = "2", title = "Random 2", uri = "u2", duration = 100)
        )

        val tool = AudioControlTool(
            context = mockContext,
            onPlay = { },
            onPause = {},
            onResume = {},
            onStop = {},
            onSeek = {},
            onToggle = {},
            onFindAudio = { AudioSearchResult.Fallback(fallbackTracks, "No match") },
            getCurrentTrack = { null },
            getCurrentPosition = { 0L },
            getDuration = { 0L },
            isPlaying = { false },
            onStatusUpdate = {},
            onPlayList = { playedList = it }
        )

        val args = AudioControlArgs(action = "play", target = "missing song")
        val result = tool.execute(args)

        assertTrue(result.success)
        assertTrue(result.message.contains("No exact match"))
        assertTrue(result.message.contains("Queued 2 random tracks"))
        assertEquals(fallbackTracks, playedList)
    }
}
