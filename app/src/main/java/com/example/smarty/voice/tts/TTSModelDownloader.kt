package com.example.smarty.voice.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Downloads and manages Piper TTS voice models from Hugging Face.
 *
 * Models are downloaded as tar.gz archives containing:
 * - {voice}.onnx - The quantized VITS model (~5-7MB)
 * - tokens.txt - Token vocabulary for the model
 * - lexicon.txt (optional) - Pronunciation dictionary
 * - espeak-ng-data/ (optional) - eSpeak phonemizer data
 *
 * All models are stored in app's internal storage under tts_models/{voice}/
 */
class TTSModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "TTSModelDownloader"
        private const val MODELS_DIR = "tts_models"

        // Hugging Face URLs for Piper voice models (Int8 quantized)
        private val MODEL_URLS = mapOf(
            // English voices
            NeuralTTSManager.VOICE_EN_LESSAC to VoiceModelInfo(
                displayName = "Amy (English US)",
                language = "English",
                description = "Clear, natural female voice",
                sizeBytes = 6_000_000L,
                modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
                tokensUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json"
            ),

            // Hindi voices
            NeuralTTSManager.VOICE_HI_PRATHAM to VoiceModelInfo(
                displayName = "Pratham (Hindi Male)",
                language = "Hindi",
                description = "Natural Hindi male voice",
                sizeBytes = 6_500_000L,
                modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx",
                tokensUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/pratham/medium/hi_IN-pratham-medium.onnx.json"
            ),

            NeuralTTSManager.VOICE_HI_PRIYAMVADA to VoiceModelInfo(
                displayName = "Priyamvada (Hindi Female)",
                language = "Hindi",
                description = "Clear Hindi female voice",
                sizeBytes = 6_500_000L,
                modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/priyamvada/medium/hi_IN-priyamvada-medium.onnx",
                tokensUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/hi/hi_IN/priyamvada/medium/hi_IN-priyamvada-medium.onnx.json"
            )
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Get information about all available voice models.
     */
    fun getAvailableVoices(): List<VoiceInfo> {
        return MODEL_URLS.map { (voiceId, info) ->
            VoiceInfo(
                id = voiceId,
                displayName = info.displayName,
                language = info.language,
                description = info.description,
                sizeBytes = info.sizeBytes,
                isDownloaded = isVoiceDownloaded(voiceId)
            )
        }
    }

    /**
     * Check if a voice model is already downloaded or bundled.
     */
    fun isVoiceDownloaded(voiceId: String): Boolean {
        // Check if already extracted to internal storage
        val voiceDir = File(context.filesDir, "$MODELS_DIR/$voiceId")
        val modelFile = File(voiceDir, "$voiceId.onnx")
        val tokensFile = File(voiceDir, "tokens.txt")
        if (modelFile.exists() && tokensFile.exists()) return true

        // Check if bundled in assets (will be extracted on first use)
        return NeuralTTSManager.BUNDLED_VOICES.contains(voiceId)
    }

    /**
     * Download a voice model with progress updates.
     *
     * @param voiceId Voice ID to download
     * @return Flow of download progress (0.0 to 1.0) or error
     */
    fun downloadVoice(voiceId: String): Flow<DownloadState> = flow {
        val modelInfo = MODEL_URLS[voiceId]
        if (modelInfo == null) {
            emit(DownloadState.Error("Unknown voice: $voiceId"))
            return@flow
        }

        val voiceDir = File(context.filesDir, "$MODELS_DIR/$voiceId")
        voiceDir.mkdirs()

        try {
            emit(DownloadState.Downloading(0f, "Downloading model..."))

            // Download the ONNX model file
            val modelFile = File(voiceDir, "$voiceId.onnx")
            var downloadError: String? = null

            downloadFile(modelInfo.modelUrl, modelFile) { }.collect { state ->
                when (state) {
                    is DownloadState.Downloading -> emit(DownloadState.Downloading(state.progress * 0.9f, state.message))
                    is DownloadState.Error -> downloadError = state.message
                    else -> {}
                }
            }

            if (downloadError != null) {
                emit(DownloadState.Error(downloadError!!))
                voiceDir.deleteRecursively()
                return@flow
            }

            emit(DownloadState.Downloading(0.9f, "Downloading configuration..."))

            // Download the JSON config (contains tokens)
            val configFile = File(voiceDir, "$voiceId.onnx.json")
            downloadFile(modelInfo.tokensUrl, configFile) {}.collect { state ->
                when (state) {
                    is DownloadState.Downloading -> emit(DownloadState.Downloading(0.9f + state.progress * 0.05f, state.message))
                    is DownloadState.Error -> downloadError = state.message
                    else -> {}
                }
            }

            if (downloadError != null) {
                emit(DownloadState.Error(downloadError!!))
                voiceDir.deleteRecursively()
                return@flow
            }

            // Extract tokens from JSON config
            emit(DownloadState.Downloading(0.95f, "Extracting tokens..."))
            extractTokensFromConfig(configFile, voiceDir)

            emit(DownloadState.Downloading(1.0f, "Complete"))
            emit(DownloadState.Complete)

            Log.d(TAG, "Voice model downloaded successfully: $voiceId")

        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            // Clean up partial download
            voiceDir.deleteRecursively()
            emit(DownloadState.Error("Download failed: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    private fun downloadFile(
        url: String,
        outputFile: File,
        onProgress: (Float) -> Unit
    ): Flow<DownloadState> = flow {
        try {
            val request = Request.Builder()
                .url(url)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(DownloadState.Error("HTTP ${response.code}: ${response.message}"))
                return@flow
            }

            val body = response.body ?: run {
                emit(DownloadState.Error("Empty response body"))
                return@flow
            }

            val contentLength = body.contentLength()
            var bytesRead = 0L

            body.byteStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read

                        val progress = if (contentLength > 0) {
                            bytesRead.toFloat() / contentLength
                        } else {
                            0f
                        }

                        emit(DownloadState.Downloading(progress, "Downloading..."))
                        onProgress(progress)
                    }
                }
            }

            emit(DownloadState.Complete)
        } catch (e: Exception) {
            emit(DownloadState.Error(e.message ?: "Unknown error"))
        }
    }

    /**
     * Extract tokens.txt from the Piper JSON config file.
     * The JSON contains a "phoneme_id_map" that we convert to tokens.txt format.
     */
    private suspend fun extractTokensFromConfig(configFile: File, voiceDir: File) = withContext(Dispatchers.IO) {
        try {
            val jsonContent = configFile.readText()

            // Parse phoneme_id_map from JSON
            // Format: "phoneme_id_map": {"_": [0], "^": [1], ...}
            val phonemeMapRegex = """"phoneme_id_map"\s*:\s*\{([^}]+)\}""".toRegex()
            val match = phonemeMapRegex.find(jsonContent)

            if (match != null) {
                val mapContent = match.groupValues[1]
                val tokenRegex = """"([^"]+)"\s*:\s*\[(\d+)""".toRegex()
                val tokens = mutableMapOf<Int, String>()

                tokenRegex.findAll(mapContent).forEach { tokenMatch ->
                    val phoneme = tokenMatch.groupValues[1]
                    val id = tokenMatch.groupValues[2].toIntOrNull() ?: return@forEach
                    tokens[id] = phoneme
                }

                // Write tokens.txt in order of ID
                val tokensFile = File(voiceDir, "tokens.txt")
                tokensFile.writeText(
                    tokens.entries
                        .sortedBy { it.key }
                        .joinToString("\n") { "${it.value} ${it.key}" }
                )

                Log.d(TAG, "Extracted ${tokens.size} tokens")
            } else {
                Log.w(TAG, "Could not find phoneme_id_map in config")
                // Create a minimal tokens file
                File(voiceDir, "tokens.txt").writeText("")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract tokens: ${e.message}", e)
        }
    }

    /**
     * Delete a downloaded voice model.
     */
    suspend fun deleteVoice(voiceId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val voiceDir = File(context.filesDir, "$MODELS_DIR/$voiceId")
            voiceDir.deleteRecursively()
            Log.d(TAG, "Deleted voice: $voiceId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete voice: ${e.message}", e)
            false
        }
    }

    /**
     * Get total size of downloaded models.
     */
    fun getDownloadedModelsSize(): Long {
        val modelsDir = File(context.filesDir, MODELS_DIR)
        return modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Voice model metadata for downloads.
     */
    data class VoiceModelInfo(
        val displayName: String,
        val language: String,
        val description: String,
        val sizeBytes: Long,
        val modelUrl: String,
        val tokensUrl: String
    )

    /**
     * Voice information for UI display.
     */
    data class VoiceInfo(
        val id: String,
        val displayName: String,
        val language: String,
        val description: String,
        val sizeBytes: Long,
        val isDownloaded: Boolean
    )

    /**
     * Download state for progress tracking.
     */
    sealed class DownloadState {
        data class Downloading(val progress: Float, val message: String) : DownloadState()
        data object Complete : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
}
