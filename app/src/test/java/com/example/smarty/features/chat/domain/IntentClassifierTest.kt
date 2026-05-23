package com.example.smarty.features.chat.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for IntentClassifier confidence scoring.
 * Validates threshold-based routing logic.
 */
class IntentClassifierTest {
    private val classifier = IntentClassifier()

    @Test
    fun `play music should classify as MEDIA_CONTROL with high confidence`() {
        val result = classifier.classify("play music")

        assertEquals(IntentType.MEDIA_CONTROL, result.intent)
        assertTrue("Confidence should be >= 0.7", result.confidence >= 0.7f)
        println("'play music' -> ${result.intent} (confidence: ${result.confidence})")
    }

    @Test
    fun `set timer should classify as TIME_ACTION with high confidence`() {
        val result = classifier.classify("set a timer for 5 minutes")

        assertEquals(IntentType.TIME_ACTION, result.intent)
        assertTrue("Confidence should be >= 0.7", result.confidence >= 0.7f)
        println("'set timer' -> ${result.intent} (confidence: ${result.confidence})")
    }

    @Test
    fun `complex research query should require reasoning`() {
        val result = classifier.classify("explain the difference between quantum computing and classical computing")

        assertEquals(IntentType.REASONING_REQUIRED, result.intent)
        println("Complex query -> ${result.intent} (confidence: ${result.confidence})")
    }

    @Test
    fun `analyze my notes should require reasoning`() {
        val result = classifier.classify("analyze my notes and find patterns")

        assertEquals(IntentType.REASONING_REQUIRED, result.intent)
        println("Note analysis -> ${result.intent} (confidence: ${result.confidence})")
    }

    @Test
    fun `open youtube should classify as APP_LAUNCH`() {
        val result = classifier.classify("open youtube")

        assertEquals(IntentType.APP_LAUNCH, result.intent)
        assertTrue("Confidence should be >= 0.7", result.confidence >= 0.7f)
    }

    @Test
    fun `turn on wifi should classify as DEVICE_TOGGLE`() {
        val result = classifier.classify("turn on wifi")

        assertEquals(IntentType.DEVICE_TOGGLE, result.intent)
        assertTrue("Confidence should be >= 0.7", result.confidence >= 0.7f)
    }

    @Test
    fun `context should boost relevant intent scores`() {
        val morningContext =
            IntentClassifier.ClassificationContext(
                currentHour = 7, // Morning
                currentScreen = "clock",
                recentIntents = emptyList(),
            )

        val result = classifier.classify("set alarm", morningContext)

        // Time context should boost TIME_ACTION score
        assertTrue("Morning + clock screen should boost time intent", result.confidence > 0.5f)
    }

    @Test
    fun `ambiguous query should show multiple scores`() {
        val result = classifier.classify("open")

        // Should have multiple low scores, none above threshold
        assertEquals(IntentType.REASONING_REQUIRED, result.intent)
        println("Ambiguous 'open' scores: ${result.allScores}")
    }

    @Test
    fun `keyword density affects scoring`() {
        val result = classifier.classify("music song track playlist artist album")

        // Multiple media keywords should boost MEDIA_CONTROL
        val mediaScore = result.allScores[IntentType.MEDIA_CONTROL] ?: 0f
        assertTrue("Multiple media keywords should boost score", mediaScore > 0.5f)
    }
}
