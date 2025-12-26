package com.example.smarty.voice.speaker

import kotlin.math.*

/**
 * Mel-Frequency Cepstral Coefficients (MFCC) Feature Extractor
 *
 * Extracts voice biometric features from audio for speaker verification.
 * Based on research: "Speaker Identification Using GMM with MFCC"
 *
 * Features extracted per frame:
 * - 13 MFCC coefficients (base features)
 * - 13 Delta MFCC (velocity/first derivative)
 * - 13 Delta-Delta MFCC (acceleration/second derivative)
 * = 39 total features per frame (standard in speaker recognition)
 *
 * Algorithm:
 * 1. Pre-emphasis filter (boost high frequencies)
 * 2. Frame the signal with overlapping windows
 * 3. Apply Hamming window
 * 4. Compute FFT power spectrum
 * 5. Apply Mel filterbank
 * 6. Take logarithm
 * 7. Apply DCT to get cepstral coefficients
 * 8. Compute delta and delta-delta features
 *
 * @param sampleRate Audio sample rate (typically 16000 Hz for speech)
 * @param numCoeffs Number of MFCC coefficients (default 13)
 * @param numFilters Number of Mel filterbank filters (default 26)
 * @param frameSize Frame size in samples (default 512 = 32ms at 16kHz)
 * @param hopSize Hop size in samples (default 256 = 16ms, 50% overlap)
 */
class MFCC(
    private val sampleRate: Float = 16000f,
    private val numCoeffs: Int = 13,
    private val numFilters: Int = 26,
    private val frameSize: Int = 512,
    private val hopSize: Int = 256
) {
    companion object {
        private const val PRE_EMPHASIS = 0.97
        private const val MIN_FREQ = 0.0
        private const val MAX_FREQ = 8000.0  // Nyquist for 16kHz
        private const val LIFTER_COEFF = 22  // Cepstral liftering coefficient
    }

    // Precomputed mel filterbank weights
    private val melFilterbank: Array<DoubleArray>

    // Precomputed DCT matrix
    private val dctMatrix: Array<DoubleArray>

    // Precomputed Hamming window
    private val hammingWindow: DoubleArray

    // Precomputed liftering coefficients
    private val lifterWeights: DoubleArray

    init {
        // Initialize Hamming window
        hammingWindow = DoubleArray(frameSize) { n ->
            0.54 - 0.46 * cos(2.0 * PI * n / (frameSize - 1))
        }

        // Initialize Mel filterbank
        melFilterbank = createMelFilterbank()

        // Initialize DCT matrix
        dctMatrix = createDCTMatrix()

        // Initialize liftering weights (emphasize higher coefficients)
        lifterWeights = DoubleArray(numCoeffs) { n ->
            1.0 + (LIFTER_COEFF / 2.0) * sin(PI * (n + 1) / LIFTER_COEFF)
        }
    }

    /**
     * Extract full MFCC features from audio samples.
     * Returns 39-dimensional feature vectors (13 MFCC + 13 Delta + 13 Delta-Delta)
     *
     * @param audioSamples Raw audio samples (16-bit PCM as ShortArray)
     * @return List of 39-dimensional feature vectors, one per frame
     */
    fun extractFeatures(audioSamples: ShortArray): List<DoubleArray> {
        if (audioSamples.size < frameSize) {
            return emptyList()
        }

        // Convert to double and normalize
        val samples = audioSamples.map { it.toDouble() / 32768.0 }.toDoubleArray()

        // Apply pre-emphasis filter
        val emphasized = preEmphasis(samples)

        // Extract MFCC for each frame
        val mfccFrames = mutableListOf<DoubleArray>()
        var start = 0

        while (start + frameSize <= emphasized.size) {
            val frame = emphasized.sliceArray(start until start + frameSize)
            val mfcc = computeMFCC(frame)
            mfccFrames.add(mfcc)
            start += hopSize
        }

        if (mfccFrames.isEmpty()) {
            return emptyList()
        }

        // Compute delta and delta-delta features
        val deltaFeatures = computeDeltas(mfccFrames)
        val deltaDeltaFeatures = computeDeltas(deltaFeatures)

        // Combine: MFCC + Delta + Delta-Delta = 39 features
        return mfccFrames.indices.map { i ->
            mfccFrames[i] + deltaFeatures[i] + deltaDeltaFeatures[i]
        }
    }

    /**
     * Extract speaker embedding from audio.
     * Averages all frame features to create a fixed-size embedding.
     *
     * @param audioSamples Raw audio samples
     * @return 39-dimensional speaker embedding (averaged features)
     */
    fun extractEmbedding(audioSamples: ShortArray): DoubleArray {
        val features = extractFeatures(audioSamples)

        if (features.isEmpty()) {
            return DoubleArray(numCoeffs * 3) // Return zero embedding
        }

        // Average all frames to get single embedding
        val embedding = DoubleArray(numCoeffs * 3)
        for (frame in features) {
            for (i in frame.indices) {
                embedding[i] += frame[i]
            }
        }

        val numFrames = features.size.toDouble()
        for (i in embedding.indices) {
            embedding[i] /= numFrames
        }

        // L2 normalize the embedding
        return normalizeL2(embedding)
    }

    /**
     * Pre-emphasis filter to boost high frequencies.
     * y[n] = x[n] - alpha * x[n-1]
     */
    private fun preEmphasis(samples: DoubleArray): DoubleArray {
        val result = DoubleArray(samples.size)
        result[0] = samples[0]
        for (i in 1 until samples.size) {
            result[i] = samples[i] - PRE_EMPHASIS * samples[i - 1]
        }
        return result
    }

    /**
     * Compute MFCC for a single frame.
     */
    private fun computeMFCC(frame: DoubleArray): DoubleArray {
        // Apply Hamming window
        val windowed = DoubleArray(frameSize) { i ->
            frame[i] * hammingWindow[i]
        }

        // Compute FFT
        val (real, imag) = fft(windowed)

        // Compute power spectrum
        val powerSpectrum = DoubleArray(frameSize / 2 + 1) { i ->
            real[i] * real[i] + imag[i] * imag[i]
        }

        // Apply Mel filterbank
        val melEnergies = DoubleArray(numFilters) { f ->
            var energy = 0.0
            for (k in powerSpectrum.indices) {
                energy += powerSpectrum[k] * melFilterbank[f][k]
            }
            // Take log (add small value to avoid log(0))
            ln(max(energy, 1e-10))
        }

        // Apply DCT to get cepstral coefficients
        val mfcc = DoubleArray(numCoeffs) { c ->
            var sum = 0.0
            for (f in 0 until numFilters) {
                sum += dctMatrix[c][f] * melEnergies[f]
            }
            sum
        }

        // Apply liftering
        for (i in mfcc.indices) {
            mfcc[i] *= lifterWeights[i]
        }

        return mfcc
    }

    /**
     * Create Mel filterbank matrix.
     * Triangular filters spaced according to Mel scale.
     */
    private fun createMelFilterbank(): Array<DoubleArray> {
        val fftBins = frameSize / 2 + 1

        // Convert frequency range to Mel scale
        val melMin = hzToMel(MIN_FREQ)
        val melMax = hzToMel(min(MAX_FREQ, sampleRate / 2.0))

        // Create equally spaced Mel points
        val melPoints = DoubleArray(numFilters + 2) { i ->
            melMin + i * (melMax - melMin) / (numFilters + 1)
        }

        // Convert back to Hz
        val hzPoints = melPoints.map { melToHz(it) }.toDoubleArray()

        // Convert to FFT bin indices
        val binPoints = hzPoints.map { hz ->
            ((frameSize + 1) * hz / sampleRate).toInt().coerceIn(0, fftBins - 1)
        }.toIntArray()

        // Create triangular filterbank
        val filterbank = Array(numFilters) { DoubleArray(fftBins) }

        for (m in 0 until numFilters) {
            val fStart = binPoints[m]
            val fCenter = binPoints[m + 1]
            val fEnd = binPoints[m + 2]

            // Rising edge
            for (k in fStart until fCenter) {
                if (fCenter != fStart) {
                    filterbank[m][k] = (k - fStart).toDouble() / (fCenter - fStart)
                }
            }

            // Falling edge
            for (k in fCenter until fEnd) {
                if (fEnd != fCenter) {
                    filterbank[m][k] = (fEnd - k).toDouble() / (fEnd - fCenter)
                }
            }
        }

        return filterbank
    }

    /**
     * Create DCT matrix for cepstral conversion.
     */
    private fun createDCTMatrix(): Array<DoubleArray> {
        val matrix = Array(numCoeffs) { DoubleArray(numFilters) }
        val scale = sqrt(2.0 / numFilters)

        for (c in 0 until numCoeffs) {
            for (f in 0 until numFilters) {
                matrix[c][f] = scale * cos(PI * c * (f + 0.5) / numFilters)
            }
        }

        return matrix
    }

    /**
     * Compute delta (derivative) features.
     * Uses a 2-frame window on each side.
     */
    private fun computeDeltas(features: List<DoubleArray>): List<DoubleArray> {
        val numFrames = features.size
        val featureSize = features[0].size
        val deltas = mutableListOf<DoubleArray>()

        for (t in 0 until numFrames) {
            val delta = DoubleArray(featureSize)

            // Delta computation with window = 2
            var denominator = 0.0
            for (n in 1..2) {
                denominator += 2.0 * n * n
            }

            for (d in 0 until featureSize) {
                var numerator = 0.0
                for (n in 1..2) {
                    val tPlus = min(t + n, numFrames - 1)
                    val tMinus = max(t - n, 0)
                    numerator += n * (features[tPlus][d] - features[tMinus][d])
                }
                delta[d] = numerator / denominator
            }

            deltas.add(delta)
        }

        return deltas
    }

    /**
     * Simple radix-2 FFT implementation.
     * Returns real and imaginary parts.
     */
    private fun fft(input: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = input.size
        val real = input.copyOf()
        val imag = DoubleArray(n)

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }

        // Cooley-Tukey FFT
        var mmax = 1
        while (n > mmax) {
            val step = mmax * 2
            val theta = -PI / mmax
            var wR = 1.0
            var wI = 0.0
            val wpR = cos(theta)
            val wpI = sin(theta)

            for (m in 0 until mmax) {
                for (i in m until n step step) {
                    val j2 = i + mmax
                    val tempR = wR * real[j2] - wI * imag[j2]
                    val tempI = wR * imag[j2] + wI * real[j2]
                    real[j2] = real[i] - tempR
                    imag[j2] = imag[i] - tempI
                    real[i] += tempR
                    imag[i] += tempI
                }
                val newWR = wR * wpR - wI * wpI
                wI = wR * wpI + wI * wpR
                wR = newWR
            }
            mmax = step
        }

        return Pair(real, imag)
    }

    /**
     * Convert Hz to Mel scale.
     */
    private fun hzToMel(hz: Double): Double {
        return 2595.0 * log10(1.0 + hz / 700.0)
    }

    /**
     * Convert Mel scale to Hz.
     */
    private fun melToHz(mel: Double): Double {
        return 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
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
     * Compute cosine similarity between two embeddings.
     * Returns value between -1 and 1 (1 = identical)
     */
    fun cosineSimilarity(embedding1: DoubleArray, embedding2: DoubleArray): Double {
        if (embedding1.size != embedding2.size) return 0.0

        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (i in embedding1.indices) {
            dotProduct += embedding1[i] * embedding2[i]
            norm1 += embedding1[i] * embedding1[i]
            norm2 += embedding2[i] * embedding2[i]
        }

        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0) dotProduct / denominator else 0.0
    }
}
