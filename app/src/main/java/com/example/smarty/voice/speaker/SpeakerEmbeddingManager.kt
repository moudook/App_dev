package com.example.smarty.voice.speaker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * Speaker Embedding Manager
 *
 * Manages voice biometric embeddings for speaker verification.
 * Uses MFCC-based embeddings with cosine similarity matching.
 *
 * Features:
 * - Multi-sample enrollment (combines 4 phrases for robust embedding)
 * - Gaussian Mixture Model (GMM) inspired averaging
 * - Cosine similarity verification with configurable threshold
 * - Persistent storage of embeddings
 *
 * Based on research:
 * - "Speaker Identification Using GMM with MFCC" (ResearchGate)
 * - "Voice Biometrics: The Essential Guide" (Phonexia)
 *
 * Enrollment phrases (phonetically balanced for Hindi):
 * 1. Numbers: "एक दो तीन चार पाँच छह सात आठ नौ दस"
 * 2. Wake word phrase: "जादूगर सुनो"
 * 3. Identity phrase: "यह मेरी आवाज़ है"
 * 4. Common phrase: "आज का दिन बहुत अच्छा है"
 */
class SpeakerEmbeddingManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeakerEmbedding"
        // Changed filename to v2 to force re-enrollment with new VAD-based algorithm
        private const val EMBEDDING_FILE = "speaker_embedding_v2.json"
        private const val PREFS_NAME = "voice_enrollment_prefs"
        private const val KEY_IS_ENROLLED = "is_enrolled"
        private const val KEY_ENROLLMENT_DATE = "enrollment_date"
        private const val KEY_ENROLLMENT_QUALITY = "enrollment_quality"
        private const val KEY_ENROLLMENT_SKIPPED = "enrollment_skipped"

        // Verification threshold (0.0 to 1.0)
        // Higher = stricter (fewer false accepts, more false rejects)
        // Lower = more lenient (more false accepts, fewer false rejects)
        // Reduced to 0.75 based on user testing (0.80 was too strict for some environments)
        const val VERIFICATION_THRESHOLD = 0.75

        // Minimum samples required for enrollment
        const val MIN_ENROLLMENT_SAMPLES = 3

        // Embedding dimension (13 MFCC + 13 Delta + 13 Delta-Delta)
        const val EMBEDDING_DIM = 39

        // Current version of embedding algorithm
        private const val EMBEDDING_VERSION = 2
    }

    private val mfcc = MFCC()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Cached embedding for faster verification
    private var cachedEmbedding: DoubleArray? = null

    /**
     * Enrollment phrases for Hindi voice enrollment.
     * Updated with diverse sentences to capture different vocal tones and variations.
     */
    val enrollmentPhrases = listOf(
        EnrollmentPhrase(
            id = "news_tech",
            hindi = "सैम ऑल्टमैन ने ग्लोबल DRAM सप्लाई का 60% खरीद लिया",
            transliteration = "Sam Altman ne global DRAM supply ka 60% khareed liya",
            english = "Sam Altman buys 60% of the global DRAM supply",
            purpose = "Factual/News tone"
        ),
        EnrollmentPhrase(
            id = "question_complex",
            hindi = "तो जादूगर एजेंटिक काम में स्पीड कैसे लाएगा?",
            transliteration = "Toh Jadugar agentic kaam mein speed kaise layega?",
            english = "Then how will Jadugar achieve speed in agentic tasks?",
            purpose = "Inquisitive/Complex tone"
        ),
        EnrollmentPhrase(
            id = "trigger_direct",
            hindi = "हे जादूगर, यह ट्रिगर वर्ड है",
            transliteration = "Hey Jadugar, yeh trigger word hai",
            english = "\"Hey Jadugar\" is the trigger word",
            purpose = "Command/Direct tone"
        ),
        EnrollmentPhrase(
            id = "comparison",
            hindi = "औरों में और जादूगर में क्या फर्क है?",
            transliteration = "Auron mein aur Jadugar mein kya fark hai?",
            english = "What is the difference between others and Jadugar?",
            purpose = "Comparative tone"
        )
    )

    /**
     * Check if user has completed voice enrollment.
     */
    fun isEnrolled(): Boolean {
        return prefs.getBoolean(KEY_IS_ENROLLED, false) && getEmbeddingFile().exists()
    }

    /**
     * Check if user has skipped voice enrollment.
     * If true, don't show enrollment prompt on app start.
     */
    fun hasSkippedEnrollment(): Boolean {
        return prefs.getBoolean(KEY_ENROLLMENT_SKIPPED, false)
    }

    /**
     * Mark enrollment as skipped by user.
     * Call this when user taps "Skip for now" button.
     */
    fun setEnrollmentSkipped(skipped: Boolean) {
        prefs.edit().putBoolean(KEY_ENROLLMENT_SKIPPED, skipped).apply()
        Log.d(TAG, "Enrollment skipped set to: $skipped")
    }

    /**
     * Get enrollment date if enrolled.
     */
    fun getEnrollmentDate(): Long {
        return prefs.getLong(KEY_ENROLLMENT_DATE, 0)
    }

    /**
     * Get enrollment quality score (0.0 to 1.0).
     */
    fun getEnrollmentQuality(): Float {
        return prefs.getFloat(KEY_ENROLLMENT_QUALITY, 0f)
    }

    /**
     * Create speaker embedding from audio samples.
     * This is used during enrollment.
     *
     * @param audioSamples Raw 16-bit PCM audio samples
     * @return Speaker embedding (39-dimensional vector)
     */
    suspend fun createEmbedding(audioSamples: ShortArray): DoubleArray = withContext(Dispatchers.Default) {
        Log.d(TAG, "Creating embedding from ${audioSamples.size} samples")
        mfcc.extractEmbedding(audioSamples)
    }

    /**
     * Combine multiple embeddings into a single robust embedding.
     * Uses weighted averaging based on embedding quality.
     *
     * @param embeddings List of embeddings from enrollment samples
     * @return Combined embedding
     */
    suspend fun combineEmbeddings(embeddings: List<DoubleArray>): DoubleArray = withContext(Dispatchers.Default) {
        if (embeddings.isEmpty()) {
            return@withContext DoubleArray(EMBEDDING_DIM)
        }

        if (embeddings.size == 1) {
            return@withContext normalizeL2(embeddings[0])
        }

        // Compute mean embedding
        val combined = DoubleArray(EMBEDDING_DIM)
        for (embedding in embeddings) {
            for (i in embedding.indices) {
                combined[i] += embedding[i]
            }
        }

        val numEmbeddings = embeddings.size.toDouble()
        for (i in combined.indices) {
            combined[i] /= numEmbeddings
        }

        // L2 normalize the combined embedding
        normalizeL2(combined)
    }

    /**
     * Calculate enrollment quality based on embedding consistency.
     * Higher consistency = better quality enrollment.
     *
     * @param embeddings List of embeddings from enrollment samples
     * @param combinedEmbedding The combined embedding
     * @return Quality score (0.0 to 1.0)
     */
    suspend fun calculateEnrollmentQuality(
        embeddings: List<DoubleArray>,
        combinedEmbedding: DoubleArray
    ): Float = withContext(Dispatchers.Default) {
        if (embeddings.size < 2) return@withContext 1.0f

        // Calculate average similarity of each sample to the combined embedding
        var totalSimilarity = 0.0
        for (embedding in embeddings) {
            totalSimilarity += mfcc.cosineSimilarity(embedding, combinedEmbedding)
        }

        (totalSimilarity / embeddings.size).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Save speaker embedding to persistent storage.
     *
     * @param embedding The speaker embedding to save
     * @param quality Enrollment quality score
     */
    suspend fun saveEmbedding(embedding: DoubleArray, quality: Float) = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("version", EMBEDDING_VERSION)
                put("dimension", embedding.size)
                put("quality", quality)
                put("timestamp", System.currentTimeMillis())
                put("embedding", JSONArray(embedding.toList()))
            }

            getEmbeddingFile().writeText(json.toString())

            prefs.edit()
                .putBoolean(KEY_IS_ENROLLED, true)
                .putLong(KEY_ENROLLMENT_DATE, System.currentTimeMillis())
                .putFloat(KEY_ENROLLMENT_QUALITY, quality)
                .putBoolean(KEY_ENROLLMENT_SKIPPED, false)  // Clear skipped flag on successful enrollment
                .apply()

            // Update cache
            cachedEmbedding = embedding

            Log.i(TAG, "Speaker embedding saved (quality: $quality)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save embedding: ${e.message}", e)
            throw e
        }
    }

    /**
     * Load speaker embedding from storage.
     *
     * @return Speaker embedding or null if not enrolled
     */
    suspend fun loadEmbedding(): DoubleArray? = withContext(Dispatchers.IO) {
        // Return cached if available
        cachedEmbedding?.let { return@withContext it }

        try {
            val file = getEmbeddingFile()
            if (!file.exists()) {
                Log.d(TAG, "No embedding file found")
                return@withContext null
            }

            val json = JSONObject(file.readText())
            val embeddingArray = json.getJSONArray("embedding")
            val embedding = DoubleArray(embeddingArray.length()) { i ->
                embeddingArray.getDouble(i)
            }

            // Cache for faster access
            cachedEmbedding = embedding

            Log.d(TAG, "Speaker embedding loaded (dim: ${embedding.size})")
            embedding
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load embedding: ${e.message}", e)
            null
        }
    }

    /**
     * Verify if audio matches the enrolled speaker.
     *
     * @param audioSamples Raw 16-bit PCM audio samples
     * @return VerificationResult with match status and confidence
     */
    suspend fun verify(audioSamples: ShortArray): VerificationResult = withContext(Dispatchers.Default) {
        val enrolledEmbedding = loadEmbedding()
        if (enrolledEmbedding == null) {
            Log.w(TAG, "Cannot verify - no enrolled embedding")
            return@withContext VerificationResult(
                isMatch = false,
                confidence = 0.0,
                threshold = VERIFICATION_THRESHOLD,
                reason = "Not enrolled"
            )
        }

        // Extract embedding from input audio
        val inputEmbedding = createEmbedding(audioSamples)

        // Calculate cosine similarity
        val similarity = mfcc.cosineSimilarity(inputEmbedding, enrolledEmbedding)
        val isMatch = similarity >= VERIFICATION_THRESHOLD

        Log.d(TAG, "Speaker verification: similarity=${"%.3f".format(similarity)}, " +
                "threshold=$VERIFICATION_THRESHOLD, match=$isMatch")

        VerificationResult(
            isMatch = isMatch,
            confidence = similarity,
            threshold = VERIFICATION_THRESHOLD,
            reason = if (isMatch) "Voice matched" else "Voice did not match"
        )
    }

    /**
     * Quick verification for real-time wake word detection.
     * Uses cached embedding for speed.
     *
     * @param audioSamples Raw audio samples
     * @return true if speaker is verified, false otherwise
     */
    suspend fun quickVerify(audioSamples: ShortArray): Boolean = withContext(Dispatchers.Default) {
        // SECURITY FIX: If not enrolled, REJECT (false) - don't allow any voice through
        val enrolledEmbedding = cachedEmbedding ?: loadEmbedding() ?: return@withContext false

        val inputEmbedding = mfcc.extractEmbedding(audioSamples)
        val similarity = mfcc.cosineSimilarity(inputEmbedding, enrolledEmbedding)

        val isMatch = similarity >= VERIFICATION_THRESHOLD
        Log.v(TAG, "Quick verify: ${if (isMatch) "PASS" else "FAIL"} (sim=${"%.2f".format(similarity)})")

        isMatch
    }

    /**
     * Delete enrolled voice data.
     */
    suspend fun deleteEnrollment() = withContext(Dispatchers.IO) {
        try {
            getEmbeddingFile().delete()
            prefs.edit()
                .remove(KEY_IS_ENROLLED)
                .remove(KEY_ENROLLMENT_DATE)
                .remove(KEY_ENROLLMENT_QUALITY)
                .apply()

            cachedEmbedding = null

            Log.i(TAG, "Enrollment deleted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete enrollment: ${e.message}", e)
        }
    }

    /**
     * Clear the cached embedding to force reload from disk.
     * Call this after enrollment changes from another instance.
     */
    fun clearCache() {
        cachedEmbedding = null
        Log.d(TAG, "Cache cleared - will reload from disk on next verification")
    }

    /**
     * Get the embedding storage file.
     */
    private fun getEmbeddingFile(): File {
        return File(context.filesDir, EMBEDDING_FILE)
    }

    /**
     * L2 normalize a vector.
     */
    private fun normalizeL2(vector: DoubleArray): DoubleArray {
        val norm = sqrt(vector.sumOf { it * it })
        return if (norm > 0) {
            vector.map { it / norm }.toDoubleArray()
        } else {
            vector
        }
    }

    /**
     * Data class for enrollment phrases.
     */
    data class EnrollmentPhrase(
        val id: String,
        val hindi: String,
        val transliteration: String,
        val english: String,
        val purpose: String
    )

    /**
     * Result of speaker verification.
     */
    data class VerificationResult(
        val isMatch: Boolean,
        val confidence: Double,
        val threshold: Double,
        val reason: String
    )
}
