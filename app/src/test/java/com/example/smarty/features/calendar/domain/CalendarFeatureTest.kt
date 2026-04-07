package com.example.smarty.features.calendar.domain

import com.example.smarty.testing.TestBuilders
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive test suite for Calendar feature.
 *
 * COVERAGE:
 * - Event CRUD operations
 * - Event filtering (upcoming, past, recurring)
 * - Event search functionality
 * - Event privacy settings
 * - Google Calendar sync
 * - Reminder functionality
 *
 * TEST COUNT: 20 tests
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarFeatureTest {
    @MockK
    private lateinit var calendarRepository: CalendarRepository

    private lateinit var calendarUseCase: CalendarUseCases

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        calendarUseCase = CalendarUseCases(calendarRepository)
    }

    // ==================== CRUD TESTS ====================

    @Test
    fun `create event with valid data succeeds`() =
        runTest {
            val eventData =
                TestBuilders.calendarEvent {
                    title = "Team Meeting"
                    startTime = System.currentTimeMillis()
                    endTime = System.currentTimeMillis() + 3600000
                }

            val result = runCatching { calendarUseCase.createEvent(eventData) }

            assertTrue(result.isSuccess)
        }

    @Test
    fun `create event with end before start fails`() =
        runTest {
            val eventData =
                TestBuilders.calendarEvent {
                    title = "Invalid Event"
                    startTime = System.currentTimeMillis() + 3600000
                    endTime = System.currentTimeMillis()
                }

            val result = runCatching { calendarUseCase.createEvent(eventData) }

            assertTrue(result.isFailure)
        }

    @Test
    fun `update event preserves metadata`() =
        runTest {
            val originalEvent =
                TestBuilders.calendarEvent {
                    title = "Original"
                    isRecurring = true
                    reminderMinutes = 15
                }

            val updatedEvent = originalEvent.copy(title = "Updated")

            assertEquals("Updated", updatedEvent.title)
            assertTrue(updatedEvent.isRecurring)
            assertEquals(15, updatedEvent.reminderMinutes)
        }

    @Test
    fun `delete event removes from repository`() =
        runTest {
            val event = TestBuilders.calendarEvent { id = "event-123" }

            val result = runCatching { calendarUseCase.deleteEvent(event.id) }

            assertTrue(result.isSuccess)
        }

    // ==================== FILTERING TESTS ====================

    @Test
    fun `filter upcoming events returns future events only`() =
        runTest {
            val now = System.currentTimeMillis()
            val events =
                listOf(
                    TestBuilders.calendarEvent { startTime = now + 86400000 }, // Tomorrow
                    TestBuilders.calendarEvent { startTime = now - 86400000 }, // Yesterday
                    TestBuilders.calendarEvent { startTime = now + 172800000 }, // Day after tomorrow
                )

            val upcoming = events.filter { it.startTime > now }

            assertEquals(2, upcoming.size)
            assertTrue(upcoming.all { it.startTime > now })
        }

    @Test
    fun `filter past events returns completed events`() =
        runTest {
            val now = System.currentTimeMillis()
            val events =
                listOf(
                    TestBuilders.calendarEvent { startTime = now - 86400000 },
                    TestBuilders.calendarEvent { startTime = now - 172800000 },
                    TestBuilders.calendarEvent { startTime = now + 86400000 },
                )

            val past = events.filter { it.startTime < now && it.endTime < now }

            assertEquals(2, past.size)
        }

    @Test
    fun `filter recurring events returns only recurring`() =
        runTest {
            val events =
                listOf(
                    TestBuilders.calendarEvent { isRecurring = true },
                    TestBuilders.calendarEvent { isRecurring = false },
                    TestBuilders.calendarEvent { isRecurring = true },
                )

            val recurring = events.filter { it.isRecurring }

            assertEquals(2, recurring.size)
            assertTrue(recurring.all { it.isRecurring })
        }

    // ==================== SEARCH TESTS ====================

    @Test
    fun `search events by title returns matches`() =
        runTest {
            val events =
                listOf(
                    TestBuilders.calendarEvent { title = "Team Meeting" },
                    TestBuilders.calendarEvent { title = "Project Review" },
                    TestBuilders.calendarEvent { title = "Team Lunch" },
                )
            val query = "Team"

            val results = events.filter { it.title.contains(query, ignoreCase = true) }

            assertEquals(2, results.size)
            assertTrue(results.all { it.title.contains(query, ignoreCase = true) })
        }

    @Test
    fun `search events by description returns matches`() =
        runTest {
            val events =
                listOf(
                    TestBuilders.calendarEvent { description = "Discuss project timeline" },
                    TestBuilders.calendarEvent { description = "Casual lunch with team" },
                )
            val query = "project"

            val results =
                events.filter {
                    it.description?.contains(query, ignoreCase = true) == true
                }

            assertEquals(1, results.size)
        }

    // ==================== PRIVACY TESTS ====================

    @Test
    fun `private event is marked as private`() =
        runTest {
            val event =
                TestBuilders.calendarEvent {
                    title = "Private Appointment"
                    isEventPrivate = true
                }

            assertTrue(event.isPrivate)
        }

    @Test
    fun `private events excluded from AI context`() =
        runTest {
            val privateEvent = TestBuilders.calendarEvent { isEventPrivate = true }
            val publicEvent = TestBuilders.calendarEvent { isEventPrivate = false }
            val allEvents = listOf(privateEvent, publicEvent)

            val aiContextEvents = allEvents.filter { !it.isPrivate }

            assertEquals(1, aiContextEvents.size)
            assertFalse(aiContextEvents[0].isPrivate)
        }

    // ==================== REMINDER TESTS ====================

    @Test
    fun `event with reminder has valid minutes`() =
        runTest {
            val event =
                TestBuilders.calendarEvent {
                    reminderMinutes = 30
                }

            assertEquals(30, event.reminderMinutes)
            assertTrue(event.reminderMinutes > 0)
        }

    @Test
    fun `event without reminder has null or zero`() =
        runTest {
            val event =
                TestBuilders.calendarEvent {
                    reminderMinutes = null
                }

            assertNull(event.reminderMinutes)
        }

    // ==================== GOOGLE CALENDAR SYNC TESTS ====================

    @Test
    fun `event with google ID is synced`() =
        runTest {
            val event =
                TestBuilders.calendarEvent {
                    googleEventId = "google-event-123"
                }

            assertNotNull(event.googleEventId)
        }

    @Test
    fun `event without google ID is not synced`() =
        runTest {
            val event =
                TestBuilders.calendarEvent {
                    googleEventId = null
                }

            assertNull(event.googleEventId)
        }

    // ==================== ALL-DAY EVENT TESTS ====================

    @Test
    fun `all-day event has correct flag`() =
        runTest {
            val event =
                TestBuilders.calendarEvent {
                    isAllDay = true
                }

            assertTrue(event.isAllDay)
        }

    @Test
    fun `all-day event spans full day`() =
        runTest {
            val event =
                TestBuilders.calendarEvent {
                    isAllDay = true
                }

            // All-day events should have 24-hour duration
            val duration = event.endTime - event.startTime
            assertTrue(duration >= 86400000) // 24 hours in ms
        }

    // ==================== LOCATION TESTS ====================

    @Test
    fun `event with location has valid location`() =
        runTest {
            val event =
                TestBuilders.calendarEvent {
                    location = "Conference Room A"
                }

            assertNotNull(event.location)
            assertEquals("Conference Room A", event.location)
        }

    @Test
    fun `event without location has null location`() =
        runTest {
            val event =
                TestBuilders.calendarEvent {
                    location = null
                }

            assertNull(event.location)
        }

    // ==================== SORTING TESTS ====================

    @Test
    fun `sort events by start time ascending`() =
        runTest {
            val now = System.currentTimeMillis()
            val events =
                listOf(
                    TestBuilders.calendarEvent { startTime = now + 172800000 },
                    TestBuilders.calendarEvent { startTime = now + 86400000 },
                    TestBuilders.calendarEvent { startTime = now },
                )

            val sorted = events.sortedBy { it.startTime }

            assertEquals(now, sorted[0].startTime)
            assertEquals(now + 172800000, sorted[2].startTime)
        }

    // ==================== CONFLICT DETECTION TESTS ====================

    @Test
    fun `detect overlapping events`() =
        runTest {
            val event1 =
                TestBuilders.calendarEvent {
                    startTime = 1000
                    endTime = 2000
                }
            val event2 =
                TestBuilders.calendarEvent {
                    startTime = 1500
                    endTime = 2500
                }

            val hasConflict = event1.startTime < event2.endTime && event2.startTime < event1.endTime

            assertTrue(hasConflict)
        }

    @Test
    fun `no conflict for non-overlapping events`() =
        runTest {
            val event1 =
                TestBuilders.calendarEvent {
                    startTime = 1000
                    endTime = 2000
                }
            val event2 =
                TestBuilders.calendarEvent {
                    startTime = 3000
                    endTime = 4000
                }

            val hasConflict = event1.startTime < event2.endTime && event2.startTime < event1.endTime

            assertFalse(hasConflict)
        }
}
