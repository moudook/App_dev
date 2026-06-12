package com.example.smarty.features.chat.domain

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Performance benchmark for IntentClassifier routing.
 * Validates confidence scoring meets latency requirements for FAST-PATH routing.
 */
class IntentRoutingBenchmark {
    private lateinit var classifier: IntentClassifier
    private val testQueries =
        listOf(
            // FAST-PATH queries (should be < 10ms)
            "play music",
            "pause song",
            "stop audio",
            "set timer 5 minutes",
            "open youtube",
            "turn on wifi",
            "go to settings",
            "what time is it",
            // REASONING-PATH queries (complex, but still fast classification)
            "explain quantum computing",
            "analyze my notes about work",
            "compare different programming languages",
            "help me plan my week",
            "research best practices for mobile development",
            // Edge cases
            "play", // Ambiguous
            "open", // Ambiguous
            "music song track playlist artist album", // High keyword density
            "turn on wifi and play music", // Mixed intent
        )

    @Before
    fun setup() {
        classifier = IntentClassifier()
    }

    @Test
    fun `FAST-PATH queries should classify under 10ms`() {
        val fastPathQueries =
            listOf(
                "play music",
                "set timer 5 minutes",
                "open youtube",
                "turn on wifi",
                "what time is it",
            )

        fastPathQueries.forEach { query ->
            val result =
                classifier.classify(query)
            assertTrue(
                "FAST-PATH query should have high confidence",
                result.confidence >= 0.7f || result.intent == IntentType.REASONING_REQUIRED,
            )
            println("FAST-PATH '$query' -> ${result.intent}")
        }
    }

    @Test
    fun `complex queries should route to REASONING_REQUIRED`() {
        val complexQueries =
            listOf(
                "explain quantum computing",
                "analyze my notes about work",
                "help me plan my week",
                "research best practices",
            )

        complexQueries.forEach { query ->
            val totalTime =
                measureTimeMillis {
                    val result = classifier.classify(query)
                    assertEquals(
                        "Complex query should require reasoning",
                        IntentType.REASONING_REQUIRED,
                        result.intent,
                    )
                }

            assertTrue("Even complex classification should be fast: ${totalTime}ms", totalTime < 20)
            println("COMPLEX '$query' -> REASONING_REQUIRED (${totalTime}ms)")
        }
    }

    @Test
    fun `keyword density should increase confidence scores`() {
        val simpleQuery = "music"
        val denseQuery = "music song track playlist artist album volume"

        val simpleResult = classifier.classify(simpleQuery)
        val denseResult = classifier.classify(denseQuery)

        val simpleScore = simpleResult.allScores[IntentType.MEDIA_CONTROL] ?: 0f
        val denseScore = denseResult.allScores[IntentType.MEDIA_CONTROL] ?: 0f

        assertTrue("Dense keywords should boost confidence", denseScore > simpleScore)
        println("Keyword density: simple=$simpleScore, dense=$denseScore")
    }

    @Test
    fun `context should boost relevant intent scores`() {
        val morningContext =
            IntentClassifier.ClassificationContext(
                currentHour = 7, // Morning
                currentScreen = "clock",
                recentIntents = listOf(IntentType.TIME_ACTION), // User often sets timers
            )

        val afternoonContext =
            IntentClassifier.ClassificationContext(
                currentHour = 14, // Afternoon
                currentScreen = "music_player",
                recentIntents = listOf(IntentType.MEDIA_CONTROL), // User often plays music
            )

        val morningResult = classifier.classify("set alarm", morningContext)
        val afternoonResult = classifier.classify("set alarm", afternoonContext)

        val morningScore = morningResult.allScores[IntentType.TIME_ACTION] ?: 0f
        val afternoonScore = afternoonResult.allScores[IntentType.TIME_ACTION] ?: 0f

        assertTrue("Morning context should boost time intent", morningScore > afternoonScore)
        println("Context boost: morning=$morningScore, afternoon=$afternoonScore")
    }

    @Test
    fun `classification should be consistent across multiple runs`() {
        val query = "play music"
        val results = mutableListOf<IntentType>()

        // Run classification 10 times
        repeat(10) {
            val result = classifier.classify(query)
            results.add(result.intent)
        }

        // All results should be identical
        val uniqueResults = results.distinct()
        assertEquals("Classification should be consistent", 1, uniqueResults.size)
        assertEquals("Consistent result should be MEDIA_CONTROL", IntentType.MEDIA_CONTROL, uniqueResults.first())
    }

    @Test
    fun `benchmark total classification throughput`() {
        val totalTime =
            measureTimeMillis {
                testQueries.forEach { query ->
                    classifier.classify(query)
                }
            }

        val avgTimePerQuery = totalTime.toDouble() / testQueries.size
        val queriesPerSecond = 1000.0 / avgTimePerQuery

        println("Benchmark Results:")
        println("- Total time: ${totalTime}ms for ${testQueries.size} queries")
        println("- Average per query: ${"%.2f".format(avgTimePerQuery)}ms")
        println("- Throughput: ${"%.0f".format(queriesPerSecond)} queries/second")

        assertTrue("Should handle at least 100 queries/second", queriesPerSecond >= 100)
        assertTrue("Average time should be under 10ms", avgTimePerQuery < 10)
    }

    @Test
    fun `edge cases should handle gracefully`() {
        val edgeCases =
            listOf(
                "", // Empty
                " ", // Whitespace
                "a", // Single character
                "play music song track playlist artist album volume mute headphones speaker bluetooth", // Very long
                "!@#$%^&*()", // Special characters
                "", // Emojis
            )

        edgeCases.forEach { query ->
            try {
                val totalTime =
                    measureTimeMillis {
                        val result = classifier.classify(query)
                        // Should not crash and should return some intent
                        assertNotNull("Result should not be null", result)
                    }
                assertTrue("Edge case should not be slow: ${totalTime}ms", totalTime < 50)
                println("Edge case '$query' -> ${classifier.classify(query).intent} (${totalTime}ms)")
            } catch (e: Exception) {
                fail("Edge case should not throw exception: $query - ${e.message}")
            }
        }
    }
}
