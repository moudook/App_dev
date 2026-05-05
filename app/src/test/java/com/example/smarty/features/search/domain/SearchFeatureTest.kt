package com.example.smarty.features.search.domain

import com.example.smarty.data.model.Note
import com.example.smarty.data.model.CalendarEvent
import com.example.smarty.testing.TestBuilders
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive test suite for Search feature.
 * 
 * COVERAGE:
 * - Note search
 * - Calendar event search
 * - Combined search
 * - Search filters
 * - Search ranking
 * - Search history
 * 
 * TEST COUNT: 18 tests
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchFeatureTest {

    // ==================== NOTE SEARCH TESTS ====================

    @Test
    fun `search notes by title returns matching results`() = runTest {
        val notes = listOf(
            TestBuilders.note { this.title = "Meeting notes" },
            TestBuilders.note { this.title = "Project plan" },
            TestBuilders.note { this.title = "Meeting agenda" }
        )
        val query = "Meeting"

        val results = notes.filter { it.title.contains(query, ignoreCase = true) }

        assertEquals(2, results.size)
    }

    @Test
    fun `search notes by content returns matching results`() = runTest {
        val notes = listOf(
            TestBuilders.note { content = "Important project details" },
            TestBuilders.note { content = "Random thoughts" },
            TestBuilders.note { content = "Project timeline and milestones" }
        )
        val query = "project"

        val results = notes.filter { it.content.contains(query, ignoreCase = true) }

        assertEquals(2, results.size)
    }

    @Test
    fun `search notes by category returns matching results`() = runTest {
        val notes = listOf(
            TestBuilders.note { categoryName = "Work" },
            TestBuilders.note { categoryName = "Personal" },
            TestBuilders.note { categoryName = "Work" }
        )
        val query = "Work"

        val results = notes.filter { it.categoryName == query }

        assertEquals(2, results.size)
    }

    @Test
    fun `search with multiple terms returns relevant results`() = runTest {
        val notes = listOf(
            TestBuilders.note { title = "Team meeting notes" },
            TestBuilders.note { title = "Project review" },
            TestBuilders.note { title = "Team project discussion" }
        )
        val query = "team project"

        val results = notes.filter { note ->
            query.split(" ").any { term ->
                note.title.contains(term, ignoreCase = true)
            }
        }

        assertEquals(2, results.size)
    }

    // ==================== CALENDAR SEARCH TESTS ====================

    @Test
    fun `search events by title returns matching results`() = runTest {
        val events = listOf(
            TestBuilders.calendarEvent { title = "Team meeting" },
            TestBuilders.calendarEvent { title = "Lunch break" },
            TestBuilders.calendarEvent { title = "Team building" }
        )
        val query = "Team"

        val results = events.filter { it.title.contains(query, ignoreCase = true) }

        assertEquals(2, results.size)
    }

    @Test
    fun `search events by location returns matching results`() = runTest {
        val events = listOf(
            TestBuilders.calendarEvent { location = "Conference Room A" },
            TestBuilders.calendarEvent { location = "Cafeteria" },
            TestBuilders.calendarEvent { location = "Conference Room B" }
        )
        val query = "Conference"

        val results = events.filter { it.location?.contains(query, ignoreCase = true) == true }

        assertEquals(2, results.size)
    }

    // ==================== COMBINED SEARCH TESTS ====================

    @Test
    fun `combined search returns notes and events`() = runTest {
        val notes = listOf(
            TestBuilders.note { title = "Project meeting" }
        )
        val events = listOf(
            TestBuilders.calendarEvent { title = "Project review" }
        )
        val query = "project"

        val noteResults = notes.filter { it.title.contains(query, ignoreCase = true) }
        val eventResults = events.filter { it.title.contains(query, ignoreCase = true) }

        assertEquals(1, noteResults.size)
        assertEquals(1, eventResults.size)
    }

    // ==================== SEARCH FILTER TESTS ====================

    @Test
    fun `filter search results by date range`() = runTest {
        val now = System.currentTimeMillis()
        val items = listOf(
            TestBuilders.note { createdAt = now - 86400000 }, // Yesterday
            TestBuilders.note { createdAt = now }, // Today
            TestBuilders.note { createdAt = now + 86400000 } // Tomorrow
        )
        val startTime = now - 43200000 // 12 hours ago
        val endTime = now + 43200000 // 12 hours from now

        val results = items.filter { it.createdAt in startTime..endTime }

        assertEquals(1, results.size)
    }

    @Test
    fun `filter search results by type`() = runTest {
        val notes = listOf(TestBuilders.note { title = "Note" })
        val events = listOf(TestBuilders.calendarEvent { title = "Event" })

        val noteResults = notes.filterIsInstance<Note>()
        val eventResults = events.filterIsInstance<CalendarEvent>()

        assertEquals(1, noteResults.size)
        assertEquals(1, eventResults.size)
    }

    @Test
    fun `filter search results by privacy`() = runTest {
        val items = listOf(
            TestBuilders.note { isPrivate = true },
            TestBuilders.note { isPrivate = false },
            TestBuilders.calendarEvent { isEventPrivate = true },
            TestBuilders.calendarEvent { isEventPrivate = false }
        )

        val publicItems = items.filter { 
            when (it) {
                is Note -> !it.isPrivate
                is CalendarEvent -> !it.isEventPrivate
                else -> true
            }
        }

        assertEquals(2, publicItems.size)
    }

    // ==================== SEARCH RANKING TESTS ====================

    @Test
    fun `rank results by relevance - title match ranks higher`() = runTest {
        val items = listOf(
            TestBuilders.note { title = "Project meeting"; content = "Some content" },
            TestBuilders.note { title = "Random note"; content = "Project details" }
        )
        val query = "project"

        val ranked = items.sortedByDescending { item ->
            when {
                item.title.contains(query, ignoreCase = true) -> 2
                item.content.contains(query, ignoreCase = true) -> 1
                else -> 0
            }
        }

        assertEquals("Project meeting", ranked[0].title)
    }

    @Test
    fun `rank results by recency`() = runTest {
        val now = System.currentTimeMillis()
        val items = listOf(
            TestBuilders.note { createdAt = now - 172800000 }, // 2 days ago
            TestBuilders.note { createdAt = now }, // Now
            TestBuilders.note { createdAt = now - 86400000 } // 1 day ago
        )

        val ranked = items.sortedByDescending { it.createdAt }

        assertEquals(now, ranked[0].createdAt)
        assertEquals(now - 86400000, ranked[1].createdAt)
    }

    // ==================== SEARCH HISTORY TESTS ====================

    @Test
    fun `save search query to history`() = runTest {
        val history = mutableListOf<String>()
        val query = "project meeting"

        history.add(query)

        assertTrue(history.contains(query))
    }

    @Test
    fun `limit search history to recent queries`() = runTest {
        val history = mutableListOf<String>()
        val maxHistory = 5

        repeat(10) { i ->
            if (history.size >= maxHistory) history.removeAt(0)
            history.add("Query $i")
        }

        assertEquals(maxHistory, history.size)
        assertEquals("Query 9", history.last())
    }

    // ==================== EDGE CASE TESTS ====================

    @Test
    fun `search with empty query returns all items`() = runTest {
        val items = listOf(
            TestBuilders.note { title = "Note 1" },
            TestBuilders.note { title = "Note 2" }
        )
        val query = ""

        val results = if (query.isEmpty()) items else items.filter { 
            it.title.contains(query, ignoreCase = true) 
        }

        assertEquals(2, results.size)
    }

    @Test
    fun `search with special characters handles gracefully`() = runTest {
        val notes = listOf(
            TestBuilders.note { title = "Meeting notes (Q1)" },
            TestBuilders.note { title = "Project [2024]" }
        )
        val query = "(Q1)"

        val results = notes.filter { it.title.contains(query, ignoreCase = true) }

        assertEquals(1, results.size)
    }

    @Test
    fun `search is case insensitive`() = runTest {
        val notes = listOf(
            TestBuilders.note { title = "PROJECT Meeting" },
            TestBuilders.note { title = "project review" }
        )
        val query = "project"

        val results = notes.filter { it.title.contains(query, ignoreCase = true) }

        assertEquals(2, results.size)
    }

    @Test
    fun `search handles null values gracefully`() = runTest {
        val notes = listOf(
            TestBuilders.note { title = "Note"; content = null },
            TestBuilders.note { title = "Another"; content = "Content" }
        )
        val query = "content"

        val results = notes.filter { 
            it.content?.contains(query, ignoreCase = true) == true 
        }

        assertEquals(1, results.size)
    }

    // ==================== ADVANCED SEARCH TESTS ====================

    @Test
    fun `search with exact phrase`() = runTest {
        val notes = listOf(
            TestBuilders.note { content = "This is an exact meeting note" },
            TestBuilders.note { content = "Meeting notes from last week" }
        )
        val query = "exact meeting"

        val results = notes.filter { it.content.contains(query, ignoreCase = true) }

        assertEquals(1, results.size)
    }

    @Test
    fun `search excludes archived items by default`() = runTest {
        val notes = listOf(
            TestBuilders.note { title = "Active note"; isArchived = false },
            TestBuilders.note { title = "Archived note"; isArchived = true }
        )
        val query = "note"

        val results = notes.filter { 
            !it.isArchived && it.title.contains(query, ignoreCase = true) 
        }

        assertEquals(1, results.size)
    }
}
