package com.example.smarty.data.remote

import android.util.Base64
import android.util.Log
import com.example.smarty.BuildConfig
import com.example.smarty.core.common.util.HttpClientProvider
import com.example.smarty.features.chat.agent.AgentEventSink
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.protocol.ClientEvent
import com.example.smarty.ui.components.ConnectionStatus
import com.google.firebase.auth.FirebaseAuth
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    /**
     * Long-running Ktor client for POST requests that can take >5 minutes
     * (e.g., [sendQueryWithContext] which waits for the full server agent loop).
     * Uses [HttpClientProvider.longRunning] OkHttp client (10 min read timeout).
     */
    private val longRunningClient: HttpClient by lazy {
        val cfg = HttpClientProvider.longRunning
        val jsonConfig = json
        HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
            engine { preconfigured = cfg }
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(jsonConfig)
            }
        }
    }

    /**
     * Decode an SSE event into an AgentEvent using the JSON class discriminator.
     * Injects the type discriminator if missing so all 11 event types resolve via
     * a single [kotlinx.serialization.modules.PolymorphicModule].
     */
    private fun decodeAgentEvent(
        eventType: String,
        data: String,
    ): AgentEvent? = try {
        val jsonStr = if (!data.contains("\"type\"")) {
            data.trim().removeSuffix("}") + ",\"type\":\"$eventType\"}"
        } else data
        json.decodeFromString<AgentEvent>(jsonStr)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decode '$eventType' event: ${e.message}")
        null
    }

    private val _connectionState = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionState: StateFlow<ConnectionStatus> = _connectionState.asStateFlow()

    /**
     * Get Firebase ID token for authentication.
     * Returns null if user is not signed in.
     */
    private suspend fun getFirebaseToken(): String? =
        try {
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

    /**
     * Get a unique device identifier for security handshake.
     */
    private fun getDeviceId(): String =
        try {
            deviceIdProvider()
        } catch (e: Exception) {
            "smarty-unknown"
        }

    // Custom exception to exit the flow gracefully
    private class EndStreamException : Exception()

    /**
     * Send a query to the remote agent and process the event stream.
     * Returns a Flow of partial/final results (content chunks) to be displayed in the UI.
     *
     * Side effects (Commands, UI status updates) are dispatched to [eventSink].
     *
     * Auto-reconnects on unexpected WebSocket disconnects with exponential backoff.
     * Terminal events (Done, Error) and PENDING_APPROVAL stop retrying.
     */
    fun sendQuery(
        query: String,
        provider: String? = null,
        providerUrl: String? = null,
        model: String? = null,
        variant: String? = null,
        sessionId: String? = null,
        personality: String? = null,
        messageId: String? = null,
        section: String? = null,
    ): Flow<AgentEvent> =
        flow {
            val baseUrl = serverUrlProvider().replace("http://", "ws://").replace("https://", "wss://")
            val token = getFirebaseToken()

            val timezone =
                java.util.TimeZone
                    .getDefault()
                    .id
            val clientTime = System.currentTimeMillis()

            val url =
                buildString {
                    append("$baseUrl/chat/ws")
                    if (sessionId != null) append("?sessionId=${sessionId.encodeURLParameter()}")
                }

            Log.i(TAG, ">>> SEND_QUERY: query=${query.take(100)}, sessionId=$sessionId, model=$model, messageId=$messageId")
            Log.i(TAG, ">>> SEND_QUERY: url=${url.substringBefore("?")} (token hidden)")

            var retryDelay = 1_000L
            val maxRetryDelay = 30_000L
            var retryCount = 0
            val maxRetries = 5

            while (retryCount < maxRetries) {
                _connectionState.value = ConnectionStatus.CONNECTING
                Log.i(TAG, ">>> SEND_QUERY: attempt ${retryCount + 1}/$maxRetries")

                var isTerminal = false
                var receivedDone = false
                val seenEventIds = mutableSetOf<String>()
                try {
                    client.webSocket(
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
                        retryDelay = 1_000L // Reset backoff on connect
                        Log.i(TAG, ">>> WS_CONNECTED: WebSocket connected successfully (attempt ${retryCount + 1})")

                        // §3.2 Offline image generation: fetch any images that completed while disconnected
                        if (sessionId != null && retryCount > 0) {
                            try {
                                val images = fetchPendingImages(sessionId)
                                images.forEach { imageEvent ->
                                    handleEvent(imageEvent, this@flow)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, ">>> WS_PENDING_IMAGES_ERROR: Failed to fetch pending images", e)
                            }
                        }

                        // Send the query request frame to start the run.
                        val requestObj =
                            ChatQueryRequest(
                                query = query,
                                sessionId = sessionId,
                                provider = provider,
                                providerUrl = providerUrl,
                                model = model,
                                variant = variant,
                                timezone = timezone,
                                clientTime = clientTime,
                                personality = personality,
                                section = section,
                                messageId = messageId,
                            )

                        val requestJson = json.encodeToString(ChatQueryRequest.serializer(), requestObj)
                        send(Frame.Text(requestJson))
                        Log.i(TAG, ">>> WS_SENT: ChatQueryRequest sent (length=${requestJson.length})")

                        // seenEventIds is tracked outside to survive retry loop
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val data = frame.readText()
                                    if (data.isBlank()) {
                                        Log.d(TAG, ">>> WS_RECEIVED: blank frame, skipping")
                                        continue
                                    }
                                    try {
                                        Log.d(TAG, ">>> WS_RECEIVED: length=${data.length}, preview=${data.take(200)}")
                                        val jsonElement = json.parseToJsonElement(data).jsonObject
                                        val eventId = jsonElement["eventId"]?.jsonPrimitive?.content
                                        if (eventId != null) {
                                            if (seenEventIds.contains(eventId)) {
                                                Log.d(TAG, ">>> WS_DEDUP: skipping duplicate eventId=$eventId")
                                                continue
                                            }
                                            seenEventIds.add(eventId)
                                            if (seenEventIds.size > 5_000) {
                                                // Remove oldest 1000 IDs to prevent unbounded growth.
                                                // Collect first so we don't mutate during iteration.
                                                val toRemove = seenEventIds.take(1_000)
                                                seenEventIds.removeAll(toRemove.toSet())
                                            }
                                        }
                                        val eventType = jsonElement["type"]?.jsonPrimitive?.content ?: "processing"

                                        // Skip server keepalive pings
                                        if (eventType == "ping") continue

                                        Log.d(TAG, ">>> WS_DECODE: eventType=$eventType")
                                        val agentEvent = decodeAgentEvent(eventType, data) ?: continue
                                        Log.d(TAG, ">>> WS_DECODED: ${agentEvent::class.simpleName}")

                                        // PENDING_APPROVAL means the server refused the query
                                        // because a prior approval gate is still waiting.
                                        // This is not a transient error — give up immediately.
                                        if (agentEvent is AgentEvent.Error && agentEvent.code == "PENDING_APPROVAL") {
                                            Log.w(TAG, ">>> WS_PENDING_APPROVAL: Server has pending approval, giving up")
                                            isTerminal = true
                                            emit(agentEvent)
                                            break
                                        }

                                        val shouldStop = handleEvent(agentEvent, this@flow)
                                        if (shouldStop) {
                                            Log.i(TAG, ">>> WS_STOP: handleEvent returned true, stopping stream")
                                            isTerminal = true
                                            break
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        if (e is EndStreamException) throw e
                                        Log.e(TAG, "Failed to process WS event (length: ${data.length}): ${e.message}", e)
                                    }
                                }
                            }
                        } catch (e: EndStreamException) {
                            Log.i(TAG, ">>> WS_COMPLETE: Stream completed normally")
                            isTerminal = true
                            receivedDone = true
                        }
                    }
                    if (isTerminal) {
                        Log.i(TAG, ">>> WS_TERMINAL: Stream ended (terminal event or PENDING_APPROVAL)")
                        if (_connectionState.value != ConnectionStatus.OFFLINE) {
                            _connectionState.value = ConnectionStatus.DISCONNECTED
                        }
                        break // Exit retry loop — agent finished or refused
                    }
                    // Clean disconnect without terminal event
                    if (_connectionState.value != ConnectionStatus.OFFLINE) {
                        _connectionState.value = ConnectionStatus.DISCONNECTED
                        Log.i(TAG, ">>> WS_DISCONNECTED: WebSocket closed normally (no terminal event)")
                    }
                    if (!receivedDone && seenEventIds.isNotEmpty()) {
                        Log.e(TAG, ">>> WS_SILENT_DROP: WebSocket closed cleanly mid-stream without Done event")
                        emit(AgentEvent.Error(
                            eventId = java.util.UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = "Connection lost mid-stream"
                        ))
                    }
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "WS connection failed (attempt ${retryCount + 1}/$maxRetries): ${e.message}", e)
                    if (seenEventIds.isNotEmpty()) {
                        Log.e(TAG, "Connection lost mid-stream, aborting retries to prevent duplicates")
                        emit(AgentEvent.Error(
                            eventId = java.util.UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = "Connection lost mid-stream"
                        ))
                        break
                    }
                    retryCount++
                    if (retryCount < maxRetries) {
                        _connectionState.value = ConnectionStatus.OFFLINE
                        Log.i(TAG, ">>> WS_RETRY: waiting ${retryDelay}ms before retry")
                        kotlinx.coroutines.delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(maxRetryDelay)
                    } else {
                        Log.e(TAG, ">>> WS_EXHAUSTED: All $maxRetries attempts failed")
                        _connectionState.value = ConnectionStatus.OFFLINE
                        emit(
                            AgentEvent.Error(
                                eventId =
                                    java.util.UUID
                                        .randomUUID()
                                        .toString(),
                                timestamp = System.currentTimeMillis(),
                                message = "[Connection Error: ${e.message ?: "All retries exhausted"}]",
                                code = "CONNECTION_ERROR",
                            ),
                        )
                    }
                }
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
                client.post("$baseUrl/chat/events?sessionId=${sessionId.encodeURLParameter()}") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                    contentType(ContentType.Application.Json)
                    setBody(event)
                }
            Log.d(TAG, "Event sent successfully: ${response.status}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send client event", e)
        }
    }

    /**
     * Send the user's approval/denial decision back to the paused server agent.
     * The server resumes the TLSM stream and continues tool execution (or aborts it).
     *
     * @param toolId  The unique approval gate ID the server sent in ApprovalRequested
     * @param approved true = Approve,  false = Deny
     * @param feedback free-text rationale sent to the agent as tool output
     */
    suspend fun sendApproval(
        toolId: String,
        approved: Boolean,
        feedback: String? = null,
    ) {
        Log.i(TAG, ">>> SEND_APPROVAL: toolId=$toolId, approved=$approved, feedback=${feedback?.take(100)}")
        try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.post("$baseUrl/api/v1/chat/events/approval") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                    contentType(ContentType.Application.Json)
                    setBody(ApprovalRequest(toolId, approved, feedback))
                }

            if (response.status.isSuccess()) {
                Log.i(TAG, ">>> SEND_APPROVAL_OK: $approved for $toolId (${response.status})")
            } else {
                Log.e(TAG, ">>> SEND_APPROVAL_FAILED: ${response.status}")
            }
        } catch (e: Exception) {
            Log.e(TAG, ">>> SEND_APPROVAL_ERROR: $toolId", e)
        }
    }

    /**
     * Submit user answers to an ask_user interactive session (§2.2).
     * Called after the client receives an AskUserRequest SSE event and the user
     * has responded (via taps or voice-to-text). POSTs answers to the server
     * which resumes the agent by injecting a TOOL message into history.
     *
     * @param toolCallId  The toolCallId from the AskUserRequest event
     * @param sessionId   The chat session ID
     * @param answers     Map of questionIndex -> answerText
     */
    suspend fun submitAskUserResponse(
        toolCallId: String,
        sessionId: String,
        answers: Map<Int, String>,
    ) {
        Log.i(TAG, ">>> SUBMIT_ASK_USER: toolCallId=$toolCallId sessionId=$sessionId answers=${answers.size}")
        try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()
            val answersArray = answers.entries.sortedBy { it.key }.joinToString(",") {
                """{"questionIndex":${it.key},"answer":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(it.value))}"""
            }
            val body = """{"toolCallId":"$toolCallId","sessionId":"$sessionId","answers":[$answersArray]}"""
            val response = client.post("$baseUrl/webhook/ask_user_response") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status.isSuccess()) {
                Log.i(TAG, ">>> SUBMIT_ASK_USER_OK: toolCallId=$toolCallId (${response.status})")
            } else {
                Log.e(TAG, ">>> SUBMIT_ASK_USER_FAILED: ${response.status} for toolCallId=$toolCallId")
            }
        } catch (e: Exception) {
            Log.e(TAG, ">>> SUBMIT_ASK_USER_ERROR: toolCallId=$toolCallId", e)
        }
    }

    /**
     * ACK a launch_ui command result to the server (§3.2).
     * Called after the Android app has executed (or failed to execute) a LaunchUiRequest.
     *
     * @param commandId     The commandId from the LaunchUiRequest SSE event
     * @param success       Whether the launch succeeded
     * @param resultMessage Human-readable status message (sent to LLM as tool output)
     */
    suspend fun submitLaunchResult(
        commandId: String,
        success: Boolean,
        resultMessage: String,
    ) {
        Log.i(TAG, ">>> SUBMIT_LAUNCH_RESULT: commandId=$commandId success=$success")
        try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()
            val body = """{"commandId":"$commandId","success":$success,"resultMessage":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(resultMessage))}}"""
            val response = client.post("$baseUrl/webhook/launch_result") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (response.status.isSuccess()) {
                Log.i(TAG, ">>> SUBMIT_LAUNCH_RESULT_OK: commandId=$commandId (${response.status})")
            } else {
                Log.e(TAG, ">>> SUBMIT_LAUNCH_RESULT_FAILED: ${response.status} for commandId=$commandId")
            }
        } catch (e: Exception) {
            Log.e(TAG, ">>> SUBMIT_LAUNCH_RESULT_ERROR: commandId=$commandId", e)
        }
    }

    /**
     * Fetch pending offline-completed images for a session (§3.2).
     */
    suspend fun fetchPendingImages(sessionId: String): List<AgentEvent> {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()
            val response = client.get("$baseUrl/images/pending?sessionId=${sessionId.encodeURLParameter()}") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }
            if (response.status.isSuccess()) {
                val data = response.bodyAsText()
                val jsonArray = kotlinx.serialization.json.Json.parseToJsonElement(data).takeIf { it is kotlinx.serialization.json.JsonArray } as? kotlinx.serialization.json.JsonArray
                jsonArray?.mapNotNull { decodeAgentEvent("image_ready", it.toString()) } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch pending images", e)
            emptyList()
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
                    val jsonMap =
                        com.google.gson.JsonParser
                            .parseString(body)
                            .asJsonObject
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
     * Fetch available Zen models from the server, including variant info.
     * Returns list of ModelInfo with id, label, and available variants.
     * Falls back to cached models if server call fails.
     */
    suspend fun getZenModels(refresh: Boolean = false): List<ModelInfo> {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()
            val response = client.get("$baseUrl/api/v1/models") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                url { if (refresh) parameters.append("refresh", "true") }
            }
            if (response.status.isSuccess()) {
                val state = response.body<ServerModelState>()
                state.models.map {
                    ModelInfo(id = it.id, label = it.label, variants = emptyList())
                }.takeIf { it.isNotEmpty() } ?: getFallbackModels()
            } else {
                getFallbackModels()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch models", e)
            getFallbackModels()
        }
    }

    private fun getFallbackModels(): List<ModelInfo> =
        listOf(ModelInfo(id = "default", label = "Default Model", variants = emptyList()))

    /**
     * Legacy variant — returns plain (modelId, label) pairs for backward compat.
     */
    suspend fun getZenModelPairs(refresh: Boolean = false): List<Pair<String, String>> =
        getZenModels(refresh).map { it.id to it.label }

    /**
     * Analyze content on the server.
     */
    suspend fun analyzeContent(
        content: String,
        attachments: List<AttachmentInfo>? = null,
    ): AIResponse? =
        try {
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

    /**
     * Analyze a document on the server.
     */
    suspend fun analyzeDocument(
        text: String,
        fileName: String? = null,
        userContext: String? = null,
    ): DocumentAnalysisResponse? =
        try {
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

    /**
     * Process an image with OCR on the server.
     */
    suspend fun processImage(
        imageBytes: ByteArray,
        mimeType: String,
        analysisType: String = "ocr",
    ): ImageProcessingResult? =
        try {
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

    /**
     * Process a PDF on the server.
     */
    suspend fun processPdf(
        pdfBytes: ByteArray,
        fileName: String? = null,
        useOcr: Boolean = true,
    ): PdfProcessingResult? =
        try {
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

    /**
     * Upload a file using the direct-to-Drive Signed URL approach.
     */
    suspend fun uploadFile(
        fileBytes: ByteArray,
        fileName: String,
        contentType: String,
        analysisType: String = "content",
    ): String? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            // 1. Get Signed URL from Ktor
            val urlResponse =
                client.post("$baseUrl/files/upload-url") {
                    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("fileName" to fileName, "mimeType" to contentType))
                }

            if (!urlResponse.status.isSuccess()) {
                Log.e(TAG, "Failed to get upload URL: ${urlResponse.status}")
                return null
            }

            val uploadUrl =
                try {
                    val jsonBody =
                        com.google.gson.JsonParser
                            .parseString(urlResponse.bodyAsText())
                            .asJsonObject
                    jsonBody.get("uploadUrl")?.asString
                } catch (e: Exception) {
                    null
                }

            if (uploadUrl == null) {
                Log.e(TAG, "Missing uploadUrl in response")
                return null
            }

            // 2. Upload directly to Google Drive via PUT
            val uploadResponse =
                client.put(uploadUrl) {
                    // Do NOT send Firebase token here, this goes directly to Google APIs
                    setBody(fileBytes)
                }

            if (uploadResponse.status.isSuccess()) {
                // Google Drive returns the File resource metadata
                try {
                    val fileMeta =
                        com.google.gson.JsonParser
                            .parseString(uploadResponse.bodyAsText())
                            .asJsonObject
                    val fileId = fileMeta.get("id")?.asString
                    return fileId ?: uploadUrl // fallback to URL if ID parsing fails
                } catch (e: Exception) {
                    return uploadUrl
                }
            } else {
                Log.e(TAG, "Direct to Drive upload failed: ${uploadResponse.status}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "File upload error: ${e.message}", e)
            return null
        }
    }

    /**
     * Download a file using the direct-to-Drive Signed URL approach.
     */
    suspend fun downloadFile(fileId: String): ByteArray? {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            // 1. Get Signed Download URL from Ktor
            val urlResponse =
                client.get("$baseUrl/files/download-url/$fileId") {
                    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                }

            if (!urlResponse.status.isSuccess()) {
                Log.e(TAG, "Failed to get download URL: ${urlResponse.status}")
                return null
            }

            val downloadUrl =
                try {
                    val jsonBody =
                        com.google.gson.JsonParser
                            .parseString(urlResponse.bodyAsText())
                            .asJsonObject
                    jsonBody.get("downloadUrl")?.asString
                } catch (e: Exception) {
                    null
                }

            if (downloadUrl == null) {
                Log.e(TAG, "Missing downloadUrl in response")
                return null
            }

            // 2. Download directly from Google Drive
            val downloadResponse = client.get(downloadUrl)

            if (downloadResponse.status.isSuccess()) {
                downloadResponse.readRawBytes()
            } else {
                Log.e(TAG, "Direct from Drive download failed: ${downloadResponse.status}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "File download error: ${e.message}", e)
            return null
        }
    }

    /**
     * Test connection to the server.
     */
    suspend fun testConnection(): Boolean =
        try {
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
        variant: String? = null,
        personality: String? = null, // Fix: Add personality parameter
    ): Flow<String> =
        flow {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            Log.d(TAG, "Sending query with context: hasFileContext=${!fileContext.isNullOrBlank()}, attachments=${attachments?.size ?: 0}")
            _connectionState.value = ConnectionStatus.CONNECTING

            try {
                val timezone =
                    java.util.TimeZone
                        .getDefault()
                        .id
                val clientTime = System.currentTimeMillis()

                val request =
                    ChatQueryRequest(
                        query = query,
                        sessionId = sessionId,
                        provider = provider,
                        model = model,
                        variant = variant,
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
                    longRunningClient.post("$baseUrl/chat/query") {
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
    suspend fun generateBriefing(prompt: String): String? =
        try {
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

    /**
     * Workflow B: Direct Image Generation.
     * Bypasses the Agent and generates an image directly on the server.
     */
    suspend fun generateImageDirect(
        prompt: String,
        aspectRatio: String = "1:1",
    ): DirectImageGenerationResponse? =
        try {
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

    private suspend fun handleStringEvent(
        event: AgentEvent,
        flowCollector: kotlinx.coroutines.flow.FlowCollector<String>,
    ): Boolean = when (event) {
        is AgentEvent.Error -> {
            Log.e(TAG, "Remote Agent Error: ${event.message}")
            flowCollector.emit("\n[Error: ${event.message}]")
            true
        }
        else -> false
    }

    private suspend fun handleEvent(
        event: AgentEvent,
        flowCollector: kotlinx.coroutines.flow.FlowCollector<AgentEvent>,
    ): Boolean {
        Log.d(TAG, ">>> HANDLE_EVENT: ${event::class.simpleName}")
        flowCollector.emit(event)
        return event is AgentEvent.Done || event is AgentEvent.Error
    }

    /**
     * Perform a security/capability handshake with the remote agent.
     */
    suspend fun performHandshake(request: com.example.smarty.protocol.HandshakeRequest): com.example.smarty.protocol.HandshakeResponse? =
        try {
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

    /**
     * Interrupt ongoing agent execution for a session.
     * Used to stop long-running tasks.
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

    @Serializable
    data class ApprovalRequest(
        val toolId: String,
        val approved: Boolean,
        val feedback: String? = null,
    )

    /**
     * Fetch the effective tool permission decisions for the current user.
     * Returns null on auth failure or network error. The settings UI
     * falls back to SMARTY_DEFAULT (hard-coded in common) if the
     * call fails.
     */
    suspend fun getToolPermissions(): ToolPermissionsListResponse? {
        val baseUrl = serverUrlProvider()
        val token = getFirebaseToken() ?: return null
        return try {
            val response =
                client.get("$baseUrl/api/v1/permissions/tools") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            if (response.status.isSuccess()) {
                response.body<ToolPermissionsListResponse>()
            } else {
                Log.e(TAG, "getToolPermissions failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getToolPermissions error: ${e.message}", e)
            null
        }
    }

    /**
     * Upsert a single tool's override. Pass `decision = "INHERIT"`
     * to delete the override (fall back to SMARTY_DEFAULT).
     * Returns the response on success, null on failure.
     */
    suspend fun setToolPermission(
        toolName: String,
        decision: String,
        reason: String? = null,
    ): ToolPermissionUpsertResponse? {
        val baseUrl = serverUrlProvider()
        val token = getFirebaseToken() ?: return null
        return try {
            val response =
                client.put("$baseUrl/api/v1/permissions/tools/$toolName") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(ToolPermissionUpsertRequest(decision = decision, reason = reason))
                }
            if (response.status.isSuccess()) {
                response.body<ToolPermissionUpsertResponse>()
            } else {
                Log.e(TAG, "setToolPermission($toolName, $decision) failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "setToolPermission error: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "RemoteAgentService"
    }
}

private fun String.encodeURLParameter(): String =
    java.net.URLEncoder.encode(this, "UTF-8")

@Serializable
data class ToolPermissionDto(
    val toolName: String,
    val decision: String,
    val isOverridden: Boolean = false,
    val overrideSource: String? = null,
    val overrideUpdatedAt: String? = null,
    val overrideExpiresAt: String? = null,
)

@Serializable
data class ToolPermissionsListResponse(
    val tools: List<ToolPermissionDto> = emptyList(),
    val defaultPolicy: Map<String, String> = emptyMap(),
)

@Serializable
data class ToolPermissionUpsertRequest(
    val decision: String,
    val reason: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class ToolPermissionUpsertResponse(
    val toolName: String,
    val effectiveDecision: String,
    val persisted: Boolean,
)

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
data class SyncResponse(
    val status: String,
    val count: Int,
    val message: String? = null,
)

@Serializable
data class ServerModelState(
    val defaultModel: String? = null,
    val activeModel: String? = null,
    val models: List<ServerModelInfo> = emptyList()
)

@Serializable
data class ServerModelInfo(
    val id: String,
    val label: String,
    val provider: String? = null
)

@Serializable
data class ChatQueryAttachment(
    val type: String,
    val name: String,
    val mimeType: String? = null,
)

/**
 * Model info returned from the server, including available variants.
 */
data class ModelInfo(
    val id: String,
    val label: String,
    val variants: List<String> = emptyList(),
)

@Serializable
data class ChatQueryRequest(
    val query: String,
    val sessionId: String? = null,
    val provider: String? = null,
    val providerUrl: String? = null,
    val model: String? = null,
    val variant: String? = null,
    val token: String? = null,
    val fileContext: String? = null,
    val attachments: List<ChatQueryAttachment>? = null,
    val timezone: String? = null,
    val clientTime: Long? = null,
    val personality: String? = null,
    val messageId: String? = null,
    val section: String? = null,
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
