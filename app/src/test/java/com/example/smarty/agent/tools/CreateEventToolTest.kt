package com.example.smarty.agent.tools

import com.example.smarty.agent.tools.calendar.CreateEventArgs
import com.example.smarty.agent.tools.calendar.CreateEventTool
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.repository.JarvisRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Tests for CreateEventTool - AI agent calendar event creation.
 */
class CreateEventToolTest {

    private lateinit var repository: JarvisRepository
    private lateinit var tool: CreateEventTool

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
        tool = CreateEventTool(repository)
    }

    @Test
    fun `tool has correct name and description`() {
        assertEquals("create_event", tool.name)
        assertTrue(tool.description.contains("calendar event"))
    }

    @Test
    fun `creates event with basic parameters`() = runBlocking {
        // Given
        val eventSlot = slot<CalendarEvent>()
        coEvery { repository.insertCalendarEvent(capture(eventSlot)) } returns Unit

        val args = CreateEventArgs(
            title = "Team Meeting",
            description = "Discuss Q1 goals",
            startTime = "tomorrow 2pm"
        )

        // When
        val result = tool.execute(args)

        // Then
        assertTrue("Event creation should succeed", result.success)
        assertEquals("Team Meeting", result.eventTitle)
        assertNotNull(result.eventId)
        assertTrue(result.message?.contains("Team Meeting") == true)

        // Verify event was inserted
        coVerify { repository.insertCalendarEvent(any()) }
        assertEquals("Team Meeting", eventSlot.captured.title)
        assertEquals("Discuss Q1 goals", eventSlot.captured.description)
    }

    @Test
    fun `fails with empty title`() = runBlocking {
        // Given
        val args = CreateEventArgs(
            title = "",
            startTime = "tomorrow 2pm"
        )

        // When
        val result = tool.execute(args)

        // Then
        assertFalse("Should fail with empty title", result.success)
        assertEquals("Empty title", result.error)
    }

    @Test
    fun `fails with invalid time format`() = runBlocking {
        // Given
        val args = CreateEventArgs(
            title = "Meeting",
            startTime = "invalid time format xyz"
        )

        // When
        val result = tool.execute(args)

        // Then
        assertFalse("Should fail with invalid time", result.success)
        assertTrue(result.error?.contains("Invalid") == true || result.message?.contains("Could not parse") == true)
    }

    @Test
    fun `parses tomorrow time correctly`() = runBlocking {
        // Given
        val eventSlot = slot<CalendarEvent>()
        coEvery { repository.insertCalendarEvent(capture(eventSlot)) } returns Unit

        val args = CreateEventArgs(
            title = "Morning Standup",
            startTime = "tomorrow 9am"
        )

        // When
        val result = tool.execute(args)

        // Then
        assertTrue(result.success)

        val tomorrow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Check the event start time is approximately tomorrow 9am (within 1 minute tolerance)
        val timeDiff = kotlin.math.abs(eventSlot.captured.startTime - tomorrow.timeInMillis)
        assertTrue("Start time should be tomorrow 9am", timeDiff < 60000)
    }

    @Test
    fun `parses today time correctly`() = runBlocking {
        // Given
        val eventSlot = slot<CalendarEvent>()
        coEvery { repository.insertCalendarEvent(capture(eventSlot)) } returns Unit

        val args = CreateEventArgs(
            title = "Quick Sync",
            startTime = "today 3:30pm"
        )

        // When
        val result = tool.execute(args)

        // Then
        assertTrue(result.success)

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val timeDiff = kotlin.math.abs(eventSlot.captured.startTime - today.timeInMillis)
        assertTrue("Start time should be today 3:30pm", timeDiff < 60000)
    }

    @Test
    fun `parses in X hours correctly`() = runBlocking {
        // Given
        val eventSlot = slot<CalendarEvent>()
        coEvery { repository.insertCalendarEvent(capture(eventSlot)) } returns Unit

        val args = CreateEventArgs(
            title = "Reminder",
            startTime = "in 2 hours"
        )

        // When
        val result = tool.execute(args)

        // Then
        assertTrue(result.success)

        val expected = System.currentTimeMillis() + (2 * 60 * 60 * 1000)
        val timeDiff = kotlin.math.abs(eventSlot.captured.startTime - expected)
        assertTrue("Start time should be ~2 hours from now", timeDiff < 60000)
    }

    @Test
    fun `sets default end time to 1 hour after start`() = runBlocking {
        // Given
        val eventSlot = slot<CalendarEvent>()
        coEvery { repository.insertCalendarEvent(capture(eventSlot)) } returns Unit

        val args = CreateEventArgs(
            title = "Meeting",
            startTime = "tomorrow 2pm"
            // No endTime specified
        )

        // When
        val result = tool.execute(args)

        // Then
        assertTrue(result.success)

        val duration = eventSlot.captured.endTime - eventSlot.captured.startTime
        assertEquals("Default duration should be 1 hour", 3600000L, duration)
    }

    @Test
    fun `corrects end time if before start time`() = runBlocking {
        // Given
        val eventSlot = slot<CalendarEvent>()
        coEvery { repository.insertCalendarEvent(capture(eventSlot)) } returns Unit

        val args = CreateEventArgs(
            title = "Meeting",
            startTime = "tomorrow 3pm",
            endTime = "tomorrow 2pm" // End before start (invalid)
        )

        // When
        val result = tool.execute(args)

        // Then
        assertTrue(result.success)
        assertTrue("End time should be after start time",
            eventSlot.captured.endTime > eventSlot.captured.startTime)
    }

    @Test
    fun `creates private event when requested`() = runBlocking {
        // Given
        val eventSlot = slot<CalendarEvent>()
        coEvery { repository.insertCalendarEvent(capture(eventSlot)) } returns Unit

        val args = CreateEventArgs(
            title = "Doctor Appointment",
            startTime = "tomorrow 10am",
            isPrivate = true
        )

        // When
        val result = tool.execute(args)

        // Then
        assertTrue(result.success)
        assertTrue("Event should be marked as private", eventSlot.captured.isEventPrivate)
    }

    @Test
    fun `creates all-day event when requested`() = runBlocking {
        // Given
        val eventSlot = slot<CalendarEvent>()
        coEvery { repository.insertCalendarEvent(capture(eventSlot)) } returns Unit

        val args = CreateEventArgs(
            title = "Company Holiday",
            startTime = "tomorrow",
            isAllDay = true
        )

        // When
        val result = tool.execute(args)

        // Then
        assertTrue(result.success)
        assertTrue("Event should be all-day", eventSlot.captured.isAllDay)
    }

    @Test
    fun `sets reminder when specified`() = runBlocking {
        // Given
        val eventSlot = slot<CalendarEvent>()
        coEvery { repository.insertCalendarEvent(capture(eventSlot)) } returns Unit

        val args = CreateEventArgs(
            title = "Important Meeting",
            startTime = "tomorrow 2pm",
            reminderMinutes = 30
        )

        // When
        val result = tool.execute(args)

        // Then
        assertTrue(result.success)
        assertEquals(30, eventSlot.captured.reminderMinutes)
    }

    @Test
    fun `sets location when specified`() = runBlocking {
        // Given
        val eventSlot = slot<CalendarEvent>()
        coEvery { repository.insertCalendarEvent(capture(eventSlot)) } returns Unit

        val args = CreateEventArgs(
            title = "Lunch Meeting",
            startTime = "tomorrow 12pm",
            location = "Cafe Milano"
        )

        // When
        val result = tool.execute(args)

        // Then
        assertTrue(result.success)
        assertEquals("Cafe Milano", eventSlot.captured.location)
    }

    @Test
    fun `parses next Monday correctly`() = runBlocking {
        // Given
        val eventSlot = slot<CalendarEvent>()
        coEvery { repository.insertCalendarEvent(capture(eventSlot)) } returns Unit

        val args = CreateEventArgs(
            title = "Weekly Review",
            startTime = "next Monday 10am"
        )

        // When
        val result = tool.execute(args)

        // Then
        assertTrue(result.success)

        // Verify it's a Monday
        val eventCal = Calendar.getInstance().apply { timeInMillis = eventSlot.captured.startTime }
        assertEquals("Should be Monday", Calendar.MONDAY, eventCal.get(Calendar.DAY_OF_WEEK))
        assertEquals("Should be 10am", 10, eventCal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `parses month date format correctly`() = runBlocking {
        // Given
        val eventSlot = slot<CalendarEvent>()
        coEvery { repository.insertCalendarEvent(capture(eventSlot)) } returns Unit

        val args = CreateEventArgs(
            title = "Birthday Party",
            startTime = "January 15th at 7pm"
        )

        // When
        val result = tool.execute(args)

        // Then
        assertTrue(result.success)

        val eventCal = Calendar.getInstance().apply { timeInMillis = eventSlot.captured.startTime }
        assertEquals("Should be January", Calendar.JANUARY, eventCal.get(Calendar.MONTH))
        assertEquals("Should be 15th", 15, eventCal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Should be 7pm", 19, eventCal.get(Calendar.HOUR_OF_DAY))
    }
}
