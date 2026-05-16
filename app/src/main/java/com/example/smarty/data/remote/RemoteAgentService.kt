package com.example.smarty.data.remote

import android.util.Base64
import android.util.Log
import com.example.smarty.BuildConfig
import com.example.smarty.features.chat.agent.AgentEventSink
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.protocol.ClientEvent
import com.example.smarty.ui.components.ConnectionStatus
import com.google.firebase.auth.FirebaseAuth
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client-side service that connects to the Cloud Agent's SSE stream.
 * Acts as the bridge between the Android app and the "Remote Brain".
 *
 * Handles Firebase authentication automatically for all requests.
 */
class RemoteAgentService(
    private val client: HttpClient,
    private val eventSink: AgentEventSink,
    private val serverUrlProvider: () -> String,
    private val deviceIdProvider: () -> String,
) {
    // Secondary constructor for fixed URL (Legacy/Test)
    constructor(client: HttpClient, eventSink: AgentEventSink, serverUrl: String) :
        this(client, eventSink, { serverUrl }, { "smarty-test-device" })

    private val json = Json { ignoreUnknownKeys = true }

    private val _connectionState = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionState: StateFlow<ConnectionStatus> = _connectionState.asStateFlow()

    /**
     * Get Firebase ID token for authentication.
     * Returns null if user is not signed in.
     */
    private suspend fun getFirebaseToken(): String? {
        return try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                val tokenResult = user.getIdToken(false).await()
                tokenResult.token
            } else {
                Log.w(TAG, "No Firebase user signed in")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Firebase token: ${e.message}")
            null
        }
    }

    /**
     * Get a unique device identifier for security handshake.
     */
    private fun getDeviceId(): String {
        return try {
            deviceIdProvider()
        } catch (e: Exception) {
            "smarty-unknown"
        }
    }

    // Custom exception to exit the flow gracefully
    private class EndStreamException : Exception()

    /**
     * Send a query to the remote agent and process the event stream.
     * Returns a Flow of partial/final results (content chunks) to be displayed in the UI.
     *
     * Side effects (Commands, UI status updates) are dispatched to [eventSink].
     */
    fun sendQuery(
        query: String,
        provider: String? = null,
        providerUrl: String? = null,
        model: String? = null,
        sessionId: String? = null,
        personality: String? = null,
    ): Flow<AgentEvent> =
        flow {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val timezone = java.util.TimeZone.getDefault().id
            val clientTime = System.currentTimeMillis()

            val url =
                buildString {
                    append("$baseUrl/chat/stream")
                    append("?query=${query.encodeURLParameter()}")
                    if (provider != null) append("&provider=${provider.encodeURLParameter()}")
                    if (providerUrl != null) append("&providerUrl=${providerUrl.encodeURLParameter()}")
                    if (model != null) append("&model=${model.encodeURLParameter()}")
                    if (sessionId != null) append("&sessionId=${sessionId.encodeURLParameter()}")
                    if (personality != null) append("&personality=${personality.encodeURLParameter()}")
                    append("&timezone=${timezone.encodeURLParameter()}")
                    append("&clientTime=$clientTime")
                }

            Log.d(TAG, "Connecting to Remote Agent: $url")
            _connectionState.value = ConnectionStatus.CONNECTING

            try {
                client.sse(
                    urlString = url,
                    request = {
                        if (token != null) {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                        header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                        header("X-Smarty-Device-Id", getDeviceId())
                    },
                ) {
                    _connectionState.value = ConnectionStatus.CONNECTED
                    try {
                        incoming.collect { event ->
                            val data = event.data ?: return@collect
                            try {
                                val agentEvent = json.decodeFromString<AgentEvent>(data)
                                val shouldStop = handleEvent(agentEvent, this@flow)
                                if (shouldStop) {
                                    throw EndStreamException()
                                }
                            } catch (e: Exception) {
                                if (e is EndStreamException) throw e
                                Log.e(TAG, "Failed to parse SSE event: $data", e)
                            }
                        }
                    } catch (e: EndStreamException) {
                        Log.d(TAG, "Stream completed normally")
                    }
                }
                if (_connectionState.value != ConnectionStatus.OFFLINE) {
                    _connectionState.value = ConnectionStatus.DISCONNECTED
                }
            } catch (e: Exception) {
                Log.e(TAG, "SSE connection failed: ${e.message}", e)
                _connectionState.value = ConnectionStatus.OFFLINE
                emit(
                    AgentEvent.Error(
                        eventId = java.util.UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        message = "[Connection Error: ${e.message}]",
                        code = "CONNECTION_ERROR",
                    ),
                )
            }
        }

    /**
     * Send a client event (e.g., tool result, app state) back to the remote agent.
     */
    suspend fun sendEvent(
        sessionId: String,
        event: ClientEvent,
    ) {
        try {
            Log.d(TAG, "Sending client event: $event")
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.post("$baseUrl/chat/events") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    parameter("sessionId", sessionId)
                    contentType(ContentType.Application.Json)
                    setBody(event)
                }
            Log.d(TAG, "Event sent successfully: ${response.status}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send client event", e)
        }
    }

    /**
     * Delete a chat session from the server (Supabase sync).
     */
    suspend fun deleteChatSession(sessionId: String): Boolean {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return false

            val response =
                client.delete("$baseUrl/api/v1/chat/sessions/$sessionId") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            response.status.isSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete remote chat session", e)
            false
        }
    }

    /**
     * Delete a message and all messages after it.
     * Used for Edit & Resend feature.
     */
    suspend fun deleteMessageAndAfter(messageId: String): Int {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return 0

            val response =
                client.delete("$baseUrl/chat/messages/$messageId/and-after") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                try {
                    val jsonMap = com.google.gson.JsonParser.parseString(body).asJsonObject
                    jsonMap.get("deletedCount")?.asInt ?: 0
                } catch (e: Exception) {
                    0
                }
            } else {
                0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete messages", e)
            0
        }
    }

    /**
     * Analyze content on the server.
     */
    suspend fun analyzeContent(
        content: String,
        attachments: List<AttachmentInfo>? = null,
    ): AIResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.post("$baseUrl/analyze/content") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(ContentAnalysisRequest(content, attachments))
                }

            if (response.status.isSuccess()) {
                response.body<AIResponse>()
            } else {
                Log.e(TAG, "Content analysis failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Content analysis error: ${e.message}", e)
            null
        }
    }

    /**
     * Analyze a document on the server.
     */
    suspend fun analyzeDocument(
        text: String,
        fileName: String? = null,
        userContext: String? = null,
    ): DocumentAnalysisResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.post("$baseUrl/analyze/document") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(DocumentAnalysisRequest(text, fileName, userContext))
                }

            if (response.status.isSuccess()) {
                response.body<DocumentAnalysisResponse>()
            } else {
                Log.e(TAG, "Document analysis failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Document analysis error: ${e.message}", e)
            null
        }
    }

    /**
     * Process an image with OCR on the server.
     */
    suspend fun processImage(
        imageBytes: ByteArray,
        mimeType: String,
        analysisType: String = "ocr",
    ): ImageProcessingResult? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

            val response =
                client.post("$baseUrl/process/image") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(ImageProcessingRequest(base64Image, mimeType, analysisType))
                }

            if (response.status.isSuccess()) {
                response.body<ImageProcessingResult>()
            } else {
                Log.e(TAG, "Image processing failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image processing error: ${e.message}", e)
            null
        }
    }

    /**
     * Process a PDF on the server.
     */
    suspend fun processPdf(
        pdfBytes: ByteArray,
        fileName: String? = null,
        useOcr: Boolean = true,
    ): PdfProcessingResult? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()
            val base64Pdf = Base64.encodeToString(pdfBytes, Base64.NO_WRAP)

            val response =
                client.post("$baseUrl/process/pdf") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(PdfProcessingRequest(base64Pdf, fileName, useOcr))
                }

            if (response.status.isSuccess()) {
                response.body<PdfProcessingResult>()
            } else {
                Log.e(TAG, "PDF processing failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDF processing error: ${e.message}", e)
            null
        }
    }

    /**
     * Upload a file for processing.
     */
    suspend fun uploadFile(
        fileBytes: ByteArray,
        fileName: String,
        contentType: String,
        analysisType: String = "content",
    ): Any? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.submitFormWithBinaryData(
                    url = "$baseUrl/upload",
                    formData =
                        formData {
                            append(
                                "file",
                                fileBytes,
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                    append(HttpHeaders.ContentType, contentType)
                                },
                            )
                            append("analysisType", analysisType)
                        },
                ) {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                }

            if (response.status.isSuccess()) {
                response.bodyAsText()
            } else {
                Log.e(TAG, "File upload failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "File upload error: ${e.message}", e)
            null
        }
    }

    /**
     * Test connection to the server.
     */
    suspend fun testConnection(): Boolean {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.get("$baseUrl/health") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                }

            val success = response.status.isSuccess()
            Log.i(TAG, "Connection test: ${if (success) "SUCCESS" else "FAILED"}")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.message}")
            false
        }
    }

    /**
     * Send a query with optional file context (for large payloads like PDF text).
     * Uses POST /chat/query endpoint which can handle larger bodies.
     *
     * @param query The user's query
     * @param sessionId The chat session ID (optional, server will create one if not provided)
     * @param fileContext Extracted text from uploaded files (e.g., PDF text, OCR text)
     * @param attachments List of attachment metadata
     * @param provider AI provider strategy
     * @param model Specific model to use
     *
     * @return Flow of response content chunks
     */
    fun sendQueryWithContext(
        query: String,
        sessionId: String? = null,
        fileContext: String? = null,
        attachments: List<ChatAttachment>? = null,
        provider: String? = null,
        model: String? = null,
        personality: String? = null, // Fix: Add personality parameter
    ): Flow<String> =
        flow {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            Log.d(TAG, "Sending query with context: hasFileContext=${!fileContext.isNullOrBlank()}, attachments=${attachments?.size ?: 0}")
            _connectionState.value = ConnectionStatus.CONNECTING

            try {
                val timezone = java.util.TimeZone.getDefault().id
                val clientTime = System.currentTimeMillis()

                val request =
                    ChatQueryRequest(
                        query = query,
                        sessionId = sessionId,
                        provider = provider,
                        model = model,
                        fileContext = fileContext,
                        attachments =
                            attachments?.map {
                                ChatQueryAttachment(type = it.type, name = it.name, mimeType = it.mimeType)
                            },
                        timezone = timezone,
                        clientTime = clientTime,
                        personality = personality, // Fix: Include personality in POST request
                    )

                val response =
                    client.post("$baseUrl/chat/query") {
                        if (token != null) {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                        header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                        header("X-Smarty-Device-Id", getDeviceId())
                        contentType(ContentType.Application.Json)
                        setBody(request)
                    }

                _connectionState.value = ConnectionStatus.CONNECTED

                if (response.status.isSuccess()) {
                    val result = response.body<ChatQueryResponse>()

                    result.events.forEach { eventJson ->
                        try {
                            val event = json.decodeFromString<AgentEvent>(eventJson)
                            val shouldStop = handleStringEvent(event, this@flow)
                            if (shouldStop) {
                                // Do nothing for non flow-collecting stream
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse event: $eventJson", e)
                        }
                    }

                    if (result.response.isNotBlank()) {
                        emit(result.response)
                    }
                } else {
                    val errorBody = response.bodyAsText()
                    Log.e(TAG, "Chat query failed: ${response.status} - $errorBody")
                    emit("\n[Error: ${response.status}]")
                }
                if (_connectionState.value != ConnectionStatus.OFFLINE) {
                    _connectionState.value = ConnectionStatus.DISCONNECTED
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chat query error: ${e.message}", e)
                _connectionState.value = ConnectionStatus.OFFLINE
                emit("\n[Connection Error: ${e.message}]")
            }
        }

    /**
     * Generate a daily briefing.
     */
    suspend fun generateBriefing(prompt: String): String? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.post("$baseUrl/briefing/generate") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(BriefingRequest(prompt))
                }

            if (response.status.isSuccess()) {
                val result = response.body<BriefingResponse>()
                result.briefing
            } else {
                Log.e(TAG, "Briefing generation failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Briefing generation error: ${e.message}", e)
            null
        }
    }

    /**
     * Workflow B: Direct Image Generation.
     * Bypasses the Agent and generates an image directly on the server.
     */
    suspend fun generateImageDirect(
        prompt: String,
        aspectRatio: String = "1:1",
    ): DirectImageGenerationResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.post("$baseUrl/api/v1/image/direct") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(DirectImageGenerationRequest(prompt, aspectRatio))
                }

            // Both success (200) and error (500/503) responses use the same schema
            val body = response.body<DirectImageGenerationResponse>()
            body
        } catch (e: Exception) {
            Log.e(TAG, "Direct image generation error: ${e.message}", e)
            null
        }
    }

    /**
     * Get current authenticated user info.
     */
    fun getCurrentUser(): UserInfo? {
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        return UserInfo(
            userId = user.uid,
            email = user.email,
            displayName = user.displayName,
        )
    }

    /**
     * Handles events streaming from the SSE endpoint and emits raw string chunks
     */
    private suspend fun handleStringEvent(
        event: AgentEvent,
        flowCollector: kotlinx.coroutines.flow.FlowCollector<String>,
    ): Boolean {
        return when (event) {
            is AgentEvent.Processing -> {
                if (!event.content.isNullOrEmpty()) {
                    flowCollector.emit(event.content)
                }
                false
            }
            is AgentEvent.ToolCall -> {
                eventSink.onToolExecutionStarted(event.toolName, event.displayName)
                if (event.status == "completed") {
                    eventSink.onToolExecutionCompleted(event.toolName)
                }
                false
            }
            is AgentEvent.Command -> {
                Log.d(TAG, "Received remote command: ${event.command}")
                eventSink.emit(event.command)
                false
            }
            is AgentEvent.Result -> {
                if (event.content.isNotEmpty()) {
                    flowCollector.emit(event.content)
                }
                event.isFinal
            }
            is AgentEvent.Error -> {
                Log.e(TAG, "Remote Agent Error: ${event.message}")
                flowCollector.emit("\n[Error: ${event.message}]")
                true // Stop stream on error
            }
            is AgentEvent.StateSync -> {
                Log.d(TAG, "Received state sync: ${event.syncType}")
                eventSink.onStateSync(event.syncType, event.data)
                false
            }
            is AgentEvent.ToolBlocked -> {
                Log.w(TAG, "Tool blocked: ${event.toolName} - ${event.reason}")
                // Don't stop stream, just log and continue
                false
            }
            is AgentEvent.Question -> {
                Log.d(TAG, "Received question: ${event.question}")
                // Question events from POST endpoint - notify via eventSink if possible
                // Note: Full Question handling requires architectural changes to pass structured events
                false
            }
            is AgentEvent.NoteBlock -> {
                Log.d(TAG, "Received note block: ${event.noteId} - ${event.title}")
                // NoteBlock events from POST endpoint - notify via eventSink if possible
                // Note: Full NoteBlock handling requires architectural changes to pass structured events
                false
            }
        }
    }

    /**
     * Handles events streaming from the SSE endpoint and emits whole AgentEvents
     */
    private suspend fun handleEvent(
        event: AgentEvent,
        flowCollector: kotlinx.coroutines.flow.FlowCollector<AgentEvent>,
    ): Boolean {
        return when (event) {
            is AgentEvent.Processing -> {
                flowCollector.emit(event)
                false
            }
            is AgentEvent.ToolCall -> {
                eventSink.onToolExecutionStarted(event.toolName, event.displayName)
                if (event.status == "completed") {
                    eventSink.onToolExecutionCompleted(event.toolName)
                }
                // Emit the ToolCall event into the flow so AssistViewModel.processRemoteQuery()
                // can accumulate it in pendingToolCalls and display the result (including image URLs).
                flowCollector.emit(event)
                false
            }
            is AgentEvent.Command -> {
                Log.d(TAG, "Received remote command: ${event.command}")
                eventSink.emit(event.command)
                false
            }
            is AgentEvent.Result -> {
                flowCollector.emit(event)
                event.isFinal
            }
            is AgentEvent.Error -> {
                Log.e(TAG, "Remote Agent Error: ${event.message}")
                flowCollector.emit(event)
                true // Stop stream on error
            }
            is AgentEvent.StateSync -> {
                Log.d(TAG, "Received state sync: ${event.syncType}")
                eventSink.onStateSync(event.syncType, event.data)
                false
            }
            is AgentEvent.ToolBlocked -> {
                Log.w(TAG, "Tool blocked: ${event.toolName} - ${event.reason}")
                flowCollector.emit(event)
                false
            }
            is AgentEvent.Question -> {
                Log.d(TAG, "Received question: ${event.question}")
                flowCollector.emit(event)
                false
            }
            is AgentEvent.NoteBlock -> {
                Log.d(TAG, "Received note block: ${event.noteId} - ${event.title}")
                flowCollector.emit(event)
                false
            }
        }
    }

    /**
     * Perform a security/capability handshake with the remote agent.
     */
    suspend fun performHandshake(request: com.example.smarty.protocol.HandshakeRequest): com.example.smarty.protocol.HandshakeResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            Log.d(TAG, "Initiating handshake with Cloud Agent: $baseUrl")

            val response =
                client.post("$baseUrl/api/v1/session/init") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    // Add security handshake headers
                    header("X-Smarty-Version", BuildConfig.VERSION_NAME)
                    header("X-Smarty-Device-Id", getDeviceId())
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            if (response.status.isSuccess()) {
                val handshakeResponse = response.body<com.example.smarty.protocol.HandshakeResponse>()
                Log.i(TAG, "Handshake successful. Session ID: ${handshakeResponse.sessionId}")
                handshakeResponse
            } else {
                Log.e(TAG, "Handshake failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handshake error: ${e.message}", e)
            null
        }
    }

    /**
     * Interrupt ongoing agent execution for a session.
     * Used to stop deep research or long-running tasks.
     */
    suspend fun interruptSession(sessionId: String): InterruptResponse {
        val baseUrl = serverUrlProvider()
        val token = getFirebaseToken()

        return try {
            val response =
                client.post("$baseUrl/chat/interrupt") {
                    contentType(ContentType.Application.Json)
                    bearerAuth(token ?: "")
                    setBody(InterruptRequest(sessionId))
                }
            if (response.status.isSuccess()) {
                response.body<InterruptResponse>()
            } else {
                InterruptResponse(success = false, message = "Interrupt failed: ${response.status}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Interrupt error: ${e.message}")
            InterruptResponse(success = false, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Push local changes to the server (Optimized Sync).
     */
    suspend fun pushSync(request: com.example.smarty.protocol.SyncPushRequest): com.example.smarty.protocol.SyncPushResponse? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken() ?: return null

            val response =
                client.post("$baseUrl/api/v1/sync/push") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            if (response.status.isSuccess()) {
                response.body<com.example.smarty.protocol.SyncPushResponse>()
            } else {
                Log.e(TAG, "Sync push failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync push error: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "RemoteAgentService"
    }
}

// Request/Response DTOs

@Serializable
data class BriefingRequest(
    val prompt: String,
)

@Serializable
data class BriefingResponse(
    val briefing: String,
    val success: Boolean = true,
)

@Serializable
data class AttachmentInfo(
    val fileName: String,
    val fileType: String,
)

@Serializable
data class ContentAnalysisRequest(
    val content: String,
    val attachments: List<AttachmentInfo>? = null,
)

@Serializable
data class DocumentAnalysisRequest(
    val text: String,
    val fileName: String? = null,
    val userContext: String? = null,
)

@Serializable
data class ImageProcessingRequest(
    val base64Image: String,
    val mimeType: String? = null,
    val analysisType: String? = "ocr",
)

@Serializable
data class ImageProcessingResult(
    val text: String,
    val contentType: String,
    val success: Boolean = true,
    val error: String? = null,
)

@Serializable
data class PdfProcessingRequest(
    val base64Pdf: String,
    val fileName: String? = null,
    val useOcr: Boolean? = true,
)

@Serializable
data class PdfProcessingResult(
    val text: String,
    val pageCount: Int,
    val hasImages: Boolean,
    val success: Boolean = true,
    val error: String? = null,
)

data class UserInfo(
    val userId: String,
    val email: String?,
    val displayName: String?,
)

// Chat with context DTOs

/**
 * Attachment info for chat messages.
 */
data class ChatAttachment(
    val type: String, // "image", "pdf", "document"
    val name: String,
    val mimeType: String? = null,
    val extractedText: String? = null, // Text extracted from the attachment
)

@Serializable
data class ChatQueryAttachment(
    val type: String,
    val name: String,
    val mimeType: String? = null,
)

@Serializable
data class ChatQueryRequest(
    val query: String,
    val sessionId: String? = null,
    val provider: String? = null,
    val providerUrl: String? = null,
    val model: String? = null,
    val token: String? = null,
    val fileContext: String? = null,
    val attachments: List<ChatQueryAttachment>? = null,
    val timezone: String? = null,
    val clientTime: Long? = null,
    val personality: String? = null, // Fix: Add personality to POST endpoint
)

@Serializable
data class ChatQueryResponse(
    val sessionId: String? = null,
    val response: String = "",
    val events: List<String> = emptyList(),
)

@Serializable
data class DirectImageGenerationRequest(
    val prompt: String,
    val aspectRatio: String = "1:1",
)

@Serializable
data class DirectImageGenerationResponse(
    val type: String = "",
    val url: String = "",
    val source: String = "",
    val prompt: String = "",
    val jobId: String = "",
    val error: String? = null,
    val message: String? = null,
) {
    val success: Boolean get() = type == "image" && url.isNotBlank()
}

@Serializable
data class InterruptRequest(
    val sessionId: String,
)

@Serializable
data class InterruptResponse(
    val success: Boolean,
    val message: String,
)
