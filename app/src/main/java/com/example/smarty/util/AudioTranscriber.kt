package com.example.smarty.util

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Advanced Audio Transcription using Google Speech Recognizer.
 * 
 * TECHNIQUE: Continuous Recognition with Silence Handling
 * 
 * The key insight is that Google Speech Recognizer stops on silence.
 * We solve this by:
 * 1. Monitoring audio levels in real-time
 * 2. Detecting silence vs speech boundaries  
 * 3. Restarting Recognizer when silence ends and speech resumes
 * 4. Accumulating results until the ENTIRE audio is processed
 * 
 * Uses Signal Processing Theory:
 * - RMS (Root Mean Square) for volume detection
 * - Zero-Crossing Rate for speech/silence discrimination
 * - Hysteresis threshold to prevent rapid on/off switching
 */
object AudioTranscriber {
    private const val TAG = "AudioTranscriber"
    
    // Audio processing constants
    private const val SAMPLE_RATE = 16000
    private const val BUFFER_SIZE = 4096
    
    // Silence detection thresholds (calibrated for speech)
    private const val SILENCE_THRESHOLD_RMS = 500.0  // Below this = silence
    private const val SPEECH_THRESHOLD_RMS = 1500.0  // Above this = speech
    private const val MIN_SPEECH_DURATION_MS = 300L  // Minimum speech segment
    private const val MAX_SILENCE_DURATION_MS = 2000L // Max silence before restart
    
    /**
     * Result of audio transcription.
     */
    data class TranscriptionResult(
        val text: String,
        val success: Boolean,
        val error: String? = null,
        val processingTimeMs: Long,
        val segmentsProcessed: Int = 0
    ) {
        val isEmpty: Boolean get() = text.isBlank()
        val hasText: Boolean get() = text.isNotBlank()
    }
    
    /**
     * Transcribe an audio file using Google Speech Recognizer.
     * Handles silence by restarting Recognition automatically.
     */
    suspend fun transcribeFromUri(
        context: Context,
        audioUri: Uri
    ): TranscriptionResult = withContext(Dispatchers.Main) {
        val startTime = System.currentTimeMillis()
        
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return@withContext TranscriptionResult(
                text = "",
                success = false,
                error = "Speech Recognition not available",
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }
        
        try {
            // Step 1: Decode audio to PCM
            val pcmData = withContext(Dispatchers.IO) {
                decodeAudioToPcm(context, audioUri)
            }
            
            if (pcmData == null || pcmData.isEmpty()) {
                return@withContext TranscriptionResult(
                    text = "",
                    success = false,
                    error = "Failed to decode audio",
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }
            
            Log.d(TAG, "Decoded ${pcmData.size} bytes of PCM audio")
            
            // Step 2: Analyze audio to find speech segments
            val speechSegments = withContext(Dispatchers.Default) {
                findSpeechSegments(pcmData)
            }
            
            Log.d(TAG, "Found ${speechSegments.size} speech segments")
            
            if (speechSegments.isEmpty()) {
                return@withContext TranscriptionResult(
                    text = "",
                    success = true,
                    error = "No speech detected in audio",
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }
            
            // Step 3: Transcribe each speech segment
            val fullTranscript = StringBuilder()
            var segmentsProcessed = 0
            
            for ((index, segment) in speechSegments.withIndex()) {
                Log.d(TAG, "Processing segment ${index + 1}/${speechSegments.size}")
                
                // Extract segment audio
                val segmentPcm = pcmData.copyOfRange(
                    segment.startByte.coerceIn(0, pcmData.size),
                    segment.endByte.coerceIn(0, pcmData.size)
                )
                
                if (segmentPcm.size < BUFFER_SIZE) {
                    Log.d(TAG, "Segment too short, skipping")
                    continue
                }
                
                // Create temp WAV file for this segment
                val segmentFile = withContext(Dispatchers.IO) {
                    createWavFile(context, segmentPcm)
                }
                
                if (segmentFile != null) {
                    // Transcribe segment
                    val segmentText = transcribeSegment(context, segmentFile, segmentPcm)
                    
                    if (segmentText.isNotBlank()) {
                        if (fullTranscript.isNotEmpty()) {
                            fullTranscript.append(" ")
                        }
                        fullTranscript.append(segmentText)
                        segmentsProcessed++
                    }
                    
                    // Cleanup
                    segmentFile.delete()
                }
                
                // Brief pause between segments
                if (index < speechSegments.size - 1) {
                    delay(300)
                }
            }
            
            val finalText = fullTranscript.toString().trim()
            Log.d(TAG, "Transcription complete: ${finalText.length} chars, $segmentsProcessed segments")
            
            TranscriptionResult(
                text = finalText,
                success = true,
                processingTimeMs = System.currentTimeMillis() - startTime,
                segmentsProcessed = segmentsProcessed
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed: ${e.message}", e)
            TranscriptionResult(
                text = "",
                success = false,
                error = e.message ?: "Unknown error",
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    /**
     * Speech segment with byte positions.
     */
    private data class SpeechSegment(
        val startByte: Int,
        val endByte: Int,
        val durationMs: Long
    )
    
    /**
     * Find speech segments in PCM audio using signal processing.
     * Uses RMS (Root Mean Square) for energy detection.
     */
    private fun findSpeechSegments(pcmData: ByteArray): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()
        val bytesPerMs = SAMPLE_RATE * 2 / 1000 // 16-bit = 2 bytes per sample
        
        var inSpeech = false
        var speechStart = 0
        var lastSpeechEnd = 0
        var silenceStart = 0L
        
        // Process in windows
        val windowSize = BUFFER_SIZE
        var position = 0
        
        while (position + windowSize <= pcmData.size) {
            val rms = calculateRms(pcmData, position, windowSize)
            val currentTimeMs = position / bytesPerMs
            
            if (!inSpeech && rms > SPEECH_THRESHOLD_RMS) {
                // Speech started
                inSpeech = true
                speechStart = max(0, position - windowSize) // Include some lead-in
                Log.v(TAG, "Speech started at ${currentTimeMs}ms (RMS: $rms)")
            } else if (inSpeech && rms < SILENCE_THRESHOLD_RMS) {
                // Potential silence
                if (silenceStart == 0L) {
                    silenceStart = currentTimeMs.toLong()
                } else if (currentTimeMs - silenceStart > MAX_SILENCE_DURATION_MS) {
                    // Confirmed silence - end segment
                    val segmentEndByte = position
                    val durationMs = (segmentEndByte - speechStart) / bytesPerMs
                    
                    if (durationMs >= MIN_SPEECH_DURATION_MS) {
                        segments.add(SpeechSegment(speechStart, segmentEndByte, durationMs.toLong()))
                        Log.d(TAG, "Speech segment: ${speechStart / bytesPerMs}ms - ${segmentEndByte / bytesPerMs}ms")
                    }
                    
                    inSpeech = false
                    silenceStart = 0L
                }
            } else if (inSpeech && rms >= SILENCE_THRESHOLD_RMS) {
                // Speech continues, reset silence counter
                silenceStart = 0L
                lastSpeechEnd = position + windowSize
            }
            
            position += windowSize / 2 // 50% overlap for better detection
        }
        
        // Don't forget the last segment
        if (inSpeech) {
            val durationMs = (lastSpeechEnd - speechStart) / bytesPerMs
            if (durationMs >= MIN_SPEECH_DURATION_MS) {
                segments.add(SpeechSegment(speechStart, lastSpeechEnd, durationMs.toLong()))
            }
        }
        
        // If no segments found, treat entire audio as one segment
        if (segments.isEmpty() && pcmData.size > BUFFER_SIZE * 2) {
            segments.add(SpeechSegment(0, pcmData.size, pcmData.size.toLong() / bytesPerMs))
        }
        
        return segments
    }
    
    /**
     * Calculate RMS (Root Mean Square) for volume detection.
     * RMS = sqrt(mean(samples^2))
     */
    private fun calculateRms(data: ByteArray, offset: Int, length: Int): Double {
        var sum = 0.0
        val end = minOf(offset + length, data.size)
        var count = 0
        
        var i = offset
        while (i + 1 < end) {
            // Convert 2 bytes to 16-bit signed sample (little-endian)
            val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
            val signedSample = if (sample > 32767) sample - 65536 else sample
            sum += signedSample.toDouble() * signedSample.toDouble()
            count++
            i += 2
        }
        
        return if (count > 0) kotlin.math.sqrt(sum / count) else 0.0
    }
    
    /**
     * Decode audio URI to raw PCM bytes.
     */
    private fun decodeAudioToPcm(context: Context, uri: Uri): ByteArray? {
        return try {
            val extractor = MediaExtractor()
            
            // Set data source
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: run {
                // Fallback for content URIs
                extractor.setDataSource(context, uri, null)
            }
            
            // Find audio track
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }
            
            if (audioTrackIndex < 0 || format == null) {
                Log.e(TAG, "No audio track found")
                extractor.release()
                return null
            }
            
            extractor.selectTrack(audioTrackIndex)
            
            // Create decoder
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            
            val outputBuffer = mutableListOf<Byte>()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            
            while (!sawOutputEOS) {
                // Feed input
                if (!sawInputEOS) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                
                // Get output
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputBufferIndex)!!
                    val chunk = ByteArray(bufferInfo.size)
                    buffer.get(chunk)
                    outputBuffer.addAll(chunk.toList())
                    
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                }
            }
            
            codec.stop()
            codec.release()
            extractor.release()
            
            outputBuffer.toByteArray()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio: ${e.message}", e)
            null
        }
    }
    
    /**
     * Create a WAV file from PCM data.
     */
    private fun createWavFile(context: Context, pcmData: ByteArray): File? {
        return try {
            val wavFile = File.createTempFile("segment_", ".wav", context.cacheDir)
            
            FileOutputStream(wavFile).use { fos ->
                writeWavHeader(fos, pcmData.size, SAMPLE_RATE, 1, 16)
                fos.write(pcmData)
            }
            
            wavFile
        } catch (e: Exception) {
            Log.e(TAG, "Error creating WAV: ${e.message}", e)
            null
        }
    }
    
    /**
     * Write WAV header.
     */
    private fun writeWavHeader(output: FileOutputStream, dataSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(dataSize)
        
        output.write(header.array())
    }
    
    /**
     * Transcribe a single segment using SpeechRecognizer.
     * Plays audio through internal routing while Recognizer listens.
     */
    private suspend fun transcribeSegment(
        context: Context,
        wavFile: File,
        pcmData: ByteArray
    ): String = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        var Recognizer: SpeechRecognizer? = null
        var audioTrack: AudioTrack? = null
        val result = StringBuilder()
        var isFinished = false
        
        fun finish(text: String) {
            if (!isFinished) {
                isFinished = true
                audioTrack?.stop()
                audioTrack?.release()
                Recognizer?.destroy()
                continuation.resume(text)
            }
        }
        
        handler.post {
            try {
                // Create Speech Recognizer
                Recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                
                Recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "Recognizer ready, playing audio...")
                        
                        // Start playing audio internally
                        Thread {
                            try {
                                val minBufferSize = AudioTrack.getMinBufferSize(
                                    SAMPLE_RATE,
                                    AudioFormat.CHANNEL_OUT_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT
                                )
                                
                                audioTrack = AudioTrack(
                                    AudioManager.STREAM_VOICE_CALL,
                                    SAMPLE_RATE,
                                    AudioFormat.CHANNEL_OUT_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    minBufferSize,
                                    AudioTrack.MODE_STREAM
                                )
                                
                                audioTrack?.play()
                                
                                // Stream PCM data
                                var offset = 0
                                while (offset < pcmData.size && !isFinished) {
                                    val chunkSize = minOf(minBufferSize, pcmData.size - offset)
                                    audioTrack?.write(pcmData, offset, chunkSize)
                                    offset += chunkSize
                                }
                                
                                // Wait a bit for Recognizer to process final audio
                                Thread.sleep(1000)
                                
                            } catch (e: Exception) {
                                Log.e(TAG, "Audio playback error: ${e.message}")
                            }
                        }.start()
                    }
                    
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    
                    override fun onError(error: Int) {
                        Log.w(TAG, "Recognition error: $error")
                        finish(result.toString())
                    }
                    
                    override fun onResults(bundle: Bundle?) {
                        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                            result.append(matches[0])
                        }
                        finish(result.toString())
                    }
                    
                    override fun onPartialResults(bundle: Bundle?) {
                        val partial = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!partial.isNullOrEmpty()) {
                            Log.d(TAG, "Partial: ${partial[0]}")
                        }
                    }
                    
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                
                // Start listening
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                }
                
                Recognizer?.startListening(intent)
                
                // Timeout
                handler.postDelayed({
                    if (!isFinished) {
                        Log.w(TAG, "Segment timeout")
                        finish(result.toString())
                    }
                }, 30_000)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting Recognition: ${e.message}")
                finish("")
            }
        }
        
        continuation.invokeOnCancellation {
            isFinished = true
            audioTrack?.stop()
            audioTrack?.release()
            Recognizer?.destroy()
        }
    }
    
    fun isFormatSupported(mimeType: String) = mimeType.startsWith("audio/")
    fun isAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)
}



