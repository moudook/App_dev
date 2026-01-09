package com.example.smarty.agent.tools

import com.example.smarty.data.local.CalendarDao
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.data.repository.JarvisRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Security tests for Calendar privacy enforcement.
 *
 * CRITICAL: These tests verify that private calendar events are NEVER
 * accessible through AI agent operations.
 *
 * Vulnerability addressed: VULN-2 - Calendar Search Bypasses Privacy
 */
class CalendarPrivacyTest {

    private lateinit var calendarDao: CalendarDao
    private lateinit var repository: JarvisRepository
    private lateinit var testEvents: List<CalendarEvent>

    private fun createEvent(
        id: String,
        title: String,
        description: String? = null,
        isPrivate: Boolean = false
    ): CalendarEvent {
        return CalendarEvent(
            id = id,
            title = title,
            description = description,
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis() + 3600000,
            isEventPrivate = isPrivate
        )
    }

    @Before
    fun setup() {
        calendarDao = mockk(relaxed = true)

        testEvents = listOf(
            // Private event - should NEVER be accessible
            createEvent(
                id = "private-event-1",
                title = "Doctor Appointment",
                description = "Annual checkup",
                isPrivate = true
            ),
            // Another private event
            createEvent(
                id = "private-event-2",
                title = "Therapy Session",
                description = "Weekly session",
                isPrivate = true
            ),
            // Public event - should be accessible
            createEvent(
                id = "public-event-1",
                title = "Team Meeting",
                description = "Sprint planning"
            ),
            // Another public event
            createEvent(
                id = "public-event-2",
                title = "Lunch with Bob",
                description = "Discuss project"
            )
        )

        coEvery { calendarDao.getAllEventsOnce() } returns testEvents
        coEvery { calendarDao.getEventById(any()) } answers {
            testEvents.find { it.id == firstArg() }
        }

        repository = JarvisRepository(
            noteDao = mockk(relaxed = true),
            categoryDao = mockk(relaxed = true),
            calendarDao = calendarDao
        )
    }

    // =========================================================================
    // SEARCH PRIVACY TESTS
    // =========================================================================

    @Test
    fun `searchCalendarEvents excludes private events`() = runTest {
        val results = repository.searchCalendarEvents("appointment")

        assertTrue("Should not find private event by title", results.isEmpty())
    }

    @Test
    fun `searchCalendarEvents excludes private events by description`() = runTest {
        val results = repository.searchCalendarEvents("checkup")

        assertTrue("Should not find private event by description", results.isEmpty())
    }

    @Test
    fun `searchCalendarEvents finds only public events`() = runTest {
        val results = repository.searchCalendarEvents("meeting")

        assertEquals("Should find exactly one public event", 1, results.size)
        assertEquals("Team Meeting", results[0].title)
        assertFalse("Result should not be private", results[0].isEventPrivate)
    }

    @Test
    fun `searchCalendarEvents returns empty for private-only matches`() = runTest {
        val results = repository.searchCalendarEvents("therapy")

        assertTrue("Should not find private event", results.isEmpty())
    }

    // =========================================================================
    // DIRECT ID ACCESS TESTS
    // =========================================================================

    @Test
    fun `getCalendarEventByIdForAi returns null for private event`() = runTest {
        val result = repository.getCalendarEventByIdForAi("private-event-1")

        assertNull("Should not return private event", result)
    }

    @Test
    fun `getCalendarEventByIdForAi returns public event`() = runTest {
        val result = repository.getCalendarEventByIdForAi("public-event-1")

        assertNotNull("Should return public event", result)
        assertEquals("Team Meeting", result?.title)
    }

    @Test
    fun `getCalendarEventByIdForAi blocks all private events`() = runTest {
        val privateIds = listOf("private-event-1", "private-event-2")

        for (id in privateIds) {
            val result = repository.getCalendarEventByIdForAi(id)
            assertNull("Event '$id' should not be accessible", result)
        }
    }

    // =========================================================================
    // SECURITY INVARIANT TESTS
    // =========================================================================

    @Test
    fun `no private events ever returned by search`() = runTest {
        // Search terms that would match private events
        val searchTerms = listOf(
            "doctor",
            "appointment",
            "therapy",
            "session",
            "checkup",
            "weekly"
        )

        for (term in searchTerms) {
            val results = repository.searchCalendarEvents(term)

            for (event in results) {
                assertFalse(
                    "Search for '$term' should never return private events",
                    event.isEventPrivate
                )
            }
        }
    }

    @Test
    fun `search returns only non-private matching events`() = runTest {
        // Search for "Sprint" which is in public event description
        val results = repository.searchCalendarEvents("sprint")

        assertEquals("Should find one event", 1, results.size)
        assertEquals("public-event-1", results[0].id)
        assertFalse(results[0].isEventPrivate)
    }

    @Test
    fun `case insensitive search still respects privacy`() = runTest {
        // Try different cases for private event titles
        val variations = listOf("DOCTOR", "Doctor", "doctor", "THERAPY", "Therapy")

        for (term in variations) {
            val results = repository.searchCalendarEvents(term)
            assertTrue(
                "Case variation '$term' should not expose private events",
                results.isEmpty()
            )
        }
    }

    // =========================================================================
    // INFORMATION LEAKAGE TESTS
    // =========================================================================

    @Test
    fun `private event count is not exposed`() = runTest {
        val allSearchResults = repository.searchCalendarEvents("")

        // Only public events should be returned
        assertEquals("Should only return 2 public events", 2, allSearchResults.size)

        for (event in allSearchResults) {
            assertFalse("No private events should be exposed", event.isEventPrivate)
        }
    }
}
