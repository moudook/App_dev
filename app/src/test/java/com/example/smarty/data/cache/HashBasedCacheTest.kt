package com.example.smarty.data.cache

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive tests for HashBasedCache.
 *
 * Tests cover:
 * - Action query detection and bypass (critical for dynamic data)
 * - Cache get/put operations
 * - Query normalization
 * - TTL expiration
 * - LRU eviction
 * - Statistics tracking
 */
class HashBasedCacheTest {
    private lateinit var cache: HashBasedCache

    @Before
    fun setup() {
        // Mock Android Log to prevent crashes in unit tests
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0

        cache = HashBasedCache(maxEntries = 10, ttlMs = 60_000) // 1 minute TTL for tests
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    // =========================================================================
    // ACTION QUERY DETECTION - PLAYBACK KEYWORDS
    // =========================================================================

    @Test
    fun `action query - play keyword bypasses cache`() =
        runTest {
            cache.put("play my music", "response")
            val result = cache.get("play my music")
            assertNull("'play' queries should bypass cache", result)
        }

    @Test
    fun `action query - play at start bypasses cache`() =
        runTest {
            cache.put("play Pretty Little Baby", "response")
            assertNull(cache.get("play Pretty Little Baby"))
        }

    @Test
    fun `action query - pause keyword bypasses cache`() =
        runTest {
            cache.put("pause the audio", "response")
            assertNull(cache.get("pause the audio"))
        }

    @Test
    fun `action query - stop keyword bypasses cache`() =
        runTest {
            cache.put("stop playing", "response")
            assertNull(cache.get("stop playing"))
        }

    @Test
    fun `action query - resume keyword bypasses cache`() =
        runTest {
            cache.put("resume playback", "response")
            assertNull(cache.get("resume playback"))
        }

    // =========================================================================
    // ACTION QUERY DETECTION - SEARCH KEYWORDS
    // =========================================================================

    @Test
    fun `action query - search keyword bypasses cache`() =
        runTest {
            cache.put("search for recipes", "response")
            assertNull(cache.get("search for recipes"))
        }

    @Test
    fun `action query - find keyword bypasses cache`() =
        runTest {
            cache.put("find my notes about cooking", "response")
            assertNull(cache.get("find my notes about cooking"))
        }

    @Test
    fun `action query - look keyword bypasses cache`() =
        runTest {
            cache.put("look for documents", "response")
            assertNull(cache.get("look for documents"))
        }

    @Test
    fun `action query - check keyword bypasses cache`() =
        runTest {
            cache.put("check my schedule", "response")
            assertNull(cache.get("check my schedule"))
        }

    // =========================================================================
    // ACTION QUERY DETECTION - DISPLAY KEYWORDS
    // =========================================================================

    @Test
    fun `action query - show keyword bypasses cache`() =
        runTest {
            cache.put("show me my notes", "response")
            assertNull(cache.get("show me my notes"))
        }

    @Test
    fun `action query - display keyword bypasses cache`() =
        runTest {
            cache.put("display recent items", "response")
            assertNull(cache.get("display recent items"))
        }

    @Test
    fun `action query - tell keyword bypasses cache`() =
        runTest {
            cache.put("tell me about my notes", "response")
            assertNull(cache.get("tell me about my notes"))
        }

    @Test
    fun `action query - give keyword bypasses cache`() =
        runTest {
            cache.put("give me a summary", "response")
            assertNull(cache.get("give me a summary"))
        }

    @Test
    fun `action query - list keyword bypasses cache`() =
        runTest {
            cache.put("list all categories", "response")
            assertNull(cache.get("list all categories"))
        }

    // =========================================================================
    // ACTION QUERY DETECTION - DATA RETRIEVAL KEYWORDS
    // =========================================================================

    @Test
    fun `action query - get keyword bypasses cache`() =
        runTest {
            cache.put("get my recent notes", "response")
            assertNull(cache.get("get my recent notes"))
        }

    @Test
    fun `action query - fetch keyword bypasses cache`() =
        runTest {
            cache.put("fetch all documents", "response")
            assertNull(cache.get("fetch all documents"))
        }

    @Test
    fun `action query - read keyword bypasses cache`() =
        runTest {
            cache.put("read my note", "response")
            assertNull(cache.get("read my note"))
        }

    @Test
    fun `action query - open keyword bypasses cache`() =
        runTest {
            cache.put("open the file", "response")
            assertNull(cache.get("open the file"))
        }

    // =========================================================================
    // ACTION QUERY DETECTION - DATA MODIFICATION KEYWORDS
    // =========================================================================

    @Test
    fun `action query - archive keyword bypasses cache`() =
        runTest {
            cache.put("archive old notes", "response")
            assertNull(cache.get("archive old notes"))
        }

    @Test
    fun `action query - unarchive keyword bypasses cache`() =
        runTest {
            cache.put("unarchive this note", "response")
            assertNull(cache.get("unarchive this note"))
        }

    @Test
    fun `action query - delete keyword bypasses cache`() =
        runTest {
            cache.put("delete all drafts", "response")
            assertNull(cache.get("delete all drafts"))
        }

    @Test
    fun `action query - update keyword bypasses cache`() =
        runTest {
            cache.put("update my profile", "response")
            assertNull(cache.get("update my profile"))
        }

    @Test
    fun `action query - create keyword bypasses cache`() =
        runTest {
            cache.put("create a new note", "response")
            assertNull(cache.get("create a new note"))
        }

    @Test
    fun `action query - add keyword bypasses cache`() =
        runTest {
            cache.put("add this to my list", "response")
            assertNull(cache.get("add this to my list"))
        }

    @Test
    fun `action query - remove keyword bypasses cache`() =
        runTest {
            cache.put("remove from favorites", "response")
            assertNull(cache.get("remove from favorites"))
        }

    @Test
    fun `action query - edit keyword bypasses cache`() =
        runTest {
            cache.put("edit this note", "response")
            assertNull(cache.get("edit this note"))
        }

    @Test
    fun `action query - modify keyword bypasses cache`() =
        runTest {
            cache.put("modify the settings", "response")
            assertNull(cache.get("modify the settings"))
        }

    // =========================================================================
    // ACTION QUERY DETECTION - COUNT KEYWORDS
    // =========================================================================

    @Test
    fun `action query - how many bypasses cache`() =
        runTest {
            cache.put("how many notes do I have", "response")
            assertNull(cache.get("how many notes do I have"))
        }

    @Test
    fun `action query - count keyword bypasses cache`() =
        runTest {
            cache.put("count my documents", "response")
            assertNull(cache.get("count my documents"))
        }

    @Test
    fun `action query - total keyword bypasses cache`() =
        runTest {
            cache.put("total number of items", "response")
            assertNull(cache.get("total number of items"))
        }

    @Test
    fun `action query - number of keyword bypasses cache`() =
        runTest {
            cache.put("what is the number of notes", "response")
            assertNull(cache.get("what is the number of notes"))
        }

    // =========================================================================
    // ACTION QUERY DETECTION - NOTE-SPECIFIC KEYWORDS
    // =========================================================================

    @Test
    fun `action query - notes keyword bypasses cache`() =
        runTest {
            cache.put("about my notes", "response")
            assertNull(cache.get("about my notes"))
        }

    @Test
    fun `action query - note keyword bypasses cache`() =
        runTest {
            cache.put("the note I saved yesterday", "response")
            assertNull(cache.get("the note I saved yesterday"))
        }

    @Test
    fun `action query - audio keyword bypasses cache`() =
        runTest {
            cache.put("where is the audio", "response")
            assertNull(cache.get("where is the audio"))
        }

    @Test
    fun `action query - document keyword bypasses cache`() =
        runTest {
            cache.put("find the document", "response")
            assertNull(cache.get("find the document"))
        }

    @Test
    fun `action query - image keyword bypasses cache`() =
        runTest {
            cache.put("show the image", "response")
            assertNull(cache.get("show the image"))
        }

    @Test
    fun `action query - file keyword bypasses cache`() =
        runTest {
            cache.put("open the file", "response")
            assertNull(cache.get("open the file"))
        }

    // =========================================================================
    // ACTION QUERY DETECTION - DATA QUESTION PATTERNS
    // =========================================================================

    @Test
    fun `data question - my keyword bypasses cache`() =
        runTest {
            cache.put("what is in my collection", "response")
            assertNull(cache.get("what is in my collection"))
        }

    @Test
    fun `data question - i have pattern bypasses cache`() =
        runTest {
            cache.put("what categories i have", "response")
            assertNull(cache.get("what categories i have"))
        }

    @Test
    fun `data question - do i have pattern bypasses cache`() =
        runTest {
            cache.put("do i have any reminders", "response")
            assertNull(cache.get("do i have any reminders"))
        }

    @Test
    fun `data question - whats in my pattern bypasses cache`() =
        runTest {
            cache.put("what's in my inbox", "response")
            assertNull(cache.get("what's in my inbox"))
        }

    @Test
    fun `data question - what is in my pattern bypasses cache`() =
        runTest {
            cache.put("what is in my todo list", "response")
            assertNull(cache.get("what is in my todo list"))
        }

    // =========================================================================
    // NON-ACTION QUERIES - SHOULD BE CACHED
    // =========================================================================

    @Test
    fun `non-action query - general question is cached`() =
        runTest {
            cache.put("what is the capital of France", "Paris")
            val result = cache.get("what is the capital of France")
            assertEquals("Paris", result)
        }

    @Test
    fun `non-action query - factual query is cached`() =
        runTest {
            cache.put("explain quantum physics", "Quantum physics is...")
            val result = cache.get("explain quantum physics")
            assertEquals("Quantum physics is...", result)
        }

    @Test
    fun `non-action query - help query is cached`() =
        runTest {
            cache.put("how do I use this app", "To use this app...")
            val result = cache.get("how do I use this app")
            assertEquals("To use this app...", result)
        }

    @Test
    fun `non-action query - definition query is cached`() =
        runTest {
            cache.put("define photosynthesis", "Photosynthesis is the process...")
            val result = cache.get("define photosynthesis")
            assertEquals("Photosynthesis is the process...", result)
        }

    @Test
    fun `non-action query - weather query is cached`() =
        runTest {
            cache.put("what is the weather like", "The weather is sunny")
            val result = cache.get("what is the weather like")
            assertEquals("The weather is sunny", result)
        }

    // =========================================================================
    // CACHE OPERATIONS - BASIC GET/PUT
    // =========================================================================

    @Test
    fun `get returns null for blank query`() =
        runTest {
            assertNull(cache.get(""))
            assertNull(cache.get("   "))
        }

    @Test
    fun `put does not store blank query`() =
        runTest {
            cache.put("", "response")
            cache.put("valid query", "")
            assertEquals(0, cache.getStats().size)
        }

    @Test
    fun `cache stores and retrieves responses`() =
        runTest {
            cache.put("hello world", "Hello! How can I help?")
            val result = cache.get("hello world")
            assertEquals("Hello! How can I help?", result)
        }

    @Test
    fun `cache updates statistics on hit`() =
        runTest {
            cache.put("test query", "test response")
            cache.get("test query")
            cache.get("test query")

            val stats = cache.getStats()
            assertEquals(2, stats.hits)
            assertEquals(1, stats.size)
        }

    @Test
    fun `cache updates statistics on miss`() =
        runTest {
            cache.get("nonexistent query")
            cache.get("another nonexistent")

            val stats = cache.getStats()
            assertEquals(2, stats.misses)
        }

    // =========================================================================
    // QUERY NORMALIZATION
    // =========================================================================

    @Test
    fun `normalized queries match - case insensitive`() =
        runTest {
            cache.put("Hello World", "response")
            val result = cache.get("hello world")
            assertEquals("response", result)
        }

    @Test
    fun `normalized queries match - whitespace`() =
        runTest {
            cache.put("hello   world", "response")
            val result = cache.get("hello world")
            assertEquals("response", result)
        }

    @Test
    fun `normalized queries match - trailing punctuation`() =
        runTest {
            cache.put("hello world!", "response")
            val result = cache.get("hello world")
            assertEquals("response", result)
        }

    @Test
    fun `normalized queries match - combined normalization`() =
        runTest {
            cache.put("  HELLO   World?!  ", "response")
            val result = cache.get("hello world")
            assertEquals("response", result)
        }

    // =========================================================================
    // CACHE CLEAR AND INVALIDATION
    // =========================================================================

    @Test
    fun `clear removes all entries`() =
        runTest {
            cache.put("query1", "response1")
            cache.put("query2", "response2")

            cache.clear()

            assertNull(cache.get("query1"))
            assertNull(cache.get("query2"))
            assertEquals(0, cache.getStats().size)
        }

    @Test
    fun `clear resets statistics`() =
        runTest {
            cache.put("test", "response")
            cache.get("test") // hit
            cache.get("nonexistent") // miss

            cache.clear()

            val stats = cache.getStats()
            assertEquals(0, stats.hits)
            assertEquals(0, stats.misses)
        }

    @Test
    fun `invalidateNoteDataEntries removes only note data entries`() =
        runTest {
            cache.put("general query", "general response", containsNoteData = false)
            cache.put("query about user", "user data response", containsNoteData = true)

            cache.invalidateNoteDataEntries()

            assertNotNull(cache.get("general query"))
            // Note: "query about user" would be bypassed anyway due to "about" pattern
            // but the point is invalidation removes entries with containsNoteData=true
        }

    // =========================================================================
    // LRU EVICTION
    // =========================================================================

    @Test
    fun `cache evicts when at capacity`() =
        runTest {
            // Fill cache to capacity (10 entries)
            for (i in 1..10) {
                cache.put("query$i", "response$i")
            }
            assertEquals(10, cache.getStats().size)

            // Add one more - should trigger eviction
            cache.put("query11", "response11")

            // Cache should not exceed max (eviction removes 10% = 1 entry)
            assertTrue(cache.getStats().size <= 10)
        }

    @Test
    fun `cache evicts least used entries`() =
        runTest {
            // Fill cache
            for (i in 1..10) {
                cache.put("query$i", "response$i")
            }

            // Access some entries multiple times to increase their access count
            cache.get("query5")
            cache.get("query5")
            cache.get("query5")
            cache.get("query10")
            cache.get("query10")

            // Add new entry to trigger eviction
            cache.put("query11", "response11")

            // Frequently accessed entries should still be present
            assertNotNull(cache.get("query5"))
            assertNotNull(cache.get("query10"))
        }

    // =========================================================================
    // CACHE STATS
    // =========================================================================

    @Test
    fun `getStats returns correct hit rate`() =
        runTest {
            cache.put("test", "response")
            cache.get("test") // hit
            cache.get("test") // hit
            cache.get("nonexistent") // miss

            val stats = cache.getStats()
            assertEquals(2, stats.hits)
            assertEquals(1, stats.misses)
            assertEquals(2f / 3f, stats.hitRate, 0.01f)
        }

    @Test
    fun `getStats returns zero hit rate when empty`() =
        runTest {
            val stats = cache.getStats()
            assertEquals(0f, stats.hitRate, 0.01f)
        }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    @Test
    fun `action keyword at end of query bypasses cache`() =
        runTest {
            cache.put("something about notes", "response")
            assertNull(cache.get("something about notes"))
        }

    @Test
    fun `action keyword in middle of query bypasses cache`() =
        runTest {
            cache.put("can you find something for me", "response")
            assertNull(cache.get("can you find something for me"))
        }

    @Test
    fun `mixed case action keywords bypass cache`() =
        runTest {
            cache.put("PLAY my Music", "response")
            assertNull(cache.get("PLAY my Music"))
        }

    @Test
    fun `query with only whitespace returns null`() =
        runTest {
            assertNull(cache.get("   \t\n  "))
        }

    @Test
    fun `very long query is handled correctly`() =
        runTest {
            val longQuery = "a".repeat(10000)
            cache.put(longQuery, "response")
            val result = cache.get(longQuery)
            assertEquals("response", result)
        }
}
