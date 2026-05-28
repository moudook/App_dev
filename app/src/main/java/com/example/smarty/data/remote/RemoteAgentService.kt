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
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
     * Decode an SSE event into the correct AgentEvent subclass.
     * The server sends the type in the SSE `event:` field, NOT in the JSON data.
     *
     * Self-healing: If decoding fails for any reason (malformed JSON, oversized payload,
     * unknown type), we return a synthetic Error event instead of crashing the stream.
     */
    private fun decodeAgentEvent(
        eventType: String,
        data: String,
    ): AgentEvent {
        return try {
            when (eventType) {
                "processing" -> json.decodeFromString<AgentEvent.Processing>(data)
                "tool_call" -> json.decodeFromString<AgentEvent.ToolCall>(data)
                "result" -> json.decodeFromString<AgentEvent.Result>(data)
                "error" -> json.decodeFromString<AgentEvent.Error>(data)
                "command" -> json.decodeFromString<AgentEvent.Command>(data)
                "state_sync" -> json.decodeFromString<AgentEvent.StateSync>(data)
                "tool_blocked" -> json.decodeFromString<AgentEvent.ToolBlocked>(data)
                "question" -> json.decodeFromString<AgentEvent.Question>(data)
                "note_block" -> json.decodeFromString<AgentEvent.NoteBlock>(data)
                "agent_step" -> json.decodeFromString<AgentEvent.AgentStep>(data)
                "opencode_raw" -> json.decodeFromString<AgentEvent.OpencodeRawEvent>(data)
                "approval_requested" -> json.decodeFromString<AgentEvent.ApprovalRequested>(data)
                "approval_granted" -> json.decodeFromString<AgentEvent.ApprovalGranted>(data)
                "approval_denied" -> json.decodeFromString<AgentEvent.ApprovalDenied>(data)
                else -> {
                    // Inject type discriminator if missing for the new canonical events
                    val jsonStr =
                        if (!data.contains("\"type\"")) {
                            data.trim().removeSuffix("}") + ",\"type\":\"$eventType\"}"
                        } else {
                            data
                        }
                    try {
                        json.decodeFromString<AgentEvent>(jsonStr)
                    } catch (e: Exception) {
                        Log.w(TAG, "Unknown SSE event type: '$eventType', falling back to Processing")
                        json.decodeFromString<AgentEvent.Processing>(data)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode '$eventType' event (data length: ${data.length}): ${e.message}")
            AgentEvent.Error(
                eventId = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                message = "[Decode Error: $eventType] ${e.message?.take(200)}",
                code = "DECODE_ERROR",
            )
        }
    }

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
        messageId: String? = null,
    ): Flow<AgentEvent> =
        flow {
            val baseUrl = serverUrlProvider().replace("http://", "ws://").replace("https://", "wss://")
            val token = getFirebaseToken()

            val timezone = java.util.TimeZone.getDefault().id
            val clientTime = System.currentTimeMillis()

            val url =
                buildString {
                    append("$baseUrl/chat/ws")
                    append("?token=${token ?: ""}")
                    if (sessionId != null) append("&sessionId=${sessionId.encodeURLParameter()}")
                }

            Log.d(TAG, "Connecting to Remote Agent WS: $url")
            _connectionState.value = ConnectionStatus.CONNECTING

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
                    
                    // Send the query request frame to start the run
                    val requestObj = ChatQueryRequest(
                        query = query,
                        sessionId = sessionId,
                        provider = provider,
                        providerUrl = providerUrl,
                        model = model,
                        timezone = timezone,
                        clientTime = clientTime,
                        personality = personality,
                        messageId = messageId,
                    )
                    
                    val requestJson = json.encodeToString(ChatQueryRequest.serializer(), requestObj)
                    send(Frame.Text(requestJson))
                    Log.d(TAG, "Sent WS ChatQueryRequest")

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val data = frame.readText()
                                if (data.isBlank()) continue
                                try {
                                    val jsonElement = json.parseToJsonElement(data).jsonObject
                                    val eventType = jsonElement["type"]?.jsonPrimitive?.content ?: "processing"
                                    
                                    val agentEvent = decodeAgentEvent(eventType, data)
                                    val shouldStop = handleEvent(agentEvent, this@flow)
                                    if (shouldStop) {
                                        throw EndStreamException()
                                    }
                                } catch (e: Exception) {
                                    if (e is EndStreamException) throw e
                                    Log.e(TAG, "Failed to process WS event (length: ${data.length}): ${e.message}", e)
                                }
                            }
                        }
                    } catch (e: EndStreamException) {
                        Log.d(TAG, "WS Stream completed normally")
                    }
                }
                if (_connectionState.value != ConnectionStatus.OFFLINE) {
                    _connectionState.value = ConnectionStatus.DISCONNECTED
                }
            } catch (e: Exception) {
                Log.e(TAG, "WS connection failed: ${e.message}", e)
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
        try {
            Log.i(TAG, "Sending approval: toolId=$toolId approved=$approved feedback=${feedback?.take(100)}")
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            val response =
                client.post("$baseUrl/api/v1/chat/events/approval") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(ApprovalRequest(toolId, approved, feedback))
                }

            if (response.status.isSuccess()) {
                Log.i(TAG, "Approval sent OK: $approved for $toolId (${response.status})")
            } else {
                Log.e(TAG, "Approval send failed: ${response.status}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send approval for $toolId", e)
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
     * Fetch available OpenCode models from the server.
     * Returns list of (modelId, label) pairs.
     * Falls back to cached models if server call fails.
     */
    suspend fun getOpencodeModels(refresh: Boolean = false): List<Pair<String, String>> {
        return try {
            val baseUrl = serverUrlProvider()
            val token = getFirebaseToken()

            Log.d(TAG, "Fetching opencode models: refresh=$refresh, url=$baseUrl/api/v1/opencode/models")

            val response =
                client.get("$baseUrl/api/v1/opencode/models") {
                    if (token != null) {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    parameter("refresh", refresh)
                }

            Log.d(TAG, "Models API response status: ${response.status}")

            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                Log.d(TAG, "Models API response body (first 500 chars): ${body.take(500)}")

                val jsonObject = com.google.gson.JsonParser.parseString(body).asJsonObject

                // Check if models array exists
                if (!jsonObject.has("models")) {
                    Log.e(TAG, "Response missing 'models' field: ${body.take(200)}")
                    return emptyList()
                }

                val modelsArray = jsonObject.getAsJsonArray("models")
                Log.d(TAG, "Models array size: ${modelsArray.size()}")

                val resultList = mutableListOf<Pair<String, String>>()
                for (i in 0 until modelsArray.size()) {
                    val element = modelsArray.get(i)
                    if (element.isJsonObject) {
                        val mObj = element.asJsonObject
                        val id = mObj.get("id")?.asString
                        val label = mObj.get("label")?.asString

                        if (id != null && label != null) {
                            resultList.add(id to label)
                            Log.d(TAG, "  Model[$i]: $id -> $label")
                        } else {
                            Log.w(TAG, "  Model[$i] missing id or label: $element")
                        }
                    } else {
                        Log.w(TAG, "  Model[$i] is not a JSON object: $element")
                    }
                }

                Log.d(TAG, "Successfully parsed ${resultList.size} models from server")
                resultList
            } else {
                Log.e(TAG, "Failed to fetch opencode models: ${response.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching opencode models: ${e.message}", e)
            emptyList()
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
            val urlResponse = client.post("$baseUrl/files/upload-url") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(mapOf("fileName" to fileName, "mimeType" to contentType))
            }

            if (!urlResponse.status.isSuccess()) {
                Log.e(TAG, "Failed to get upload URL: ${urlResponse.status}")
                return null
            }

            val uploadUrl = try {
                val jsonBody = com.google.gson.JsonParser.parseString(urlResponse.bodyAsText()).asJsonObject
                jsonBody.get("uploadUrl")?.asString
            } catch (e: Exception) { null }

            if (uploadUrl == null) {
                Log.e(TAG, "Missing uploadUrl in response")
                return null
            }

            // 2. Upload directly to Google Drive via PUT
            val uploadResponse = client.put(uploadUrl) {
                // Do NOT send Firebase token here, this goes directly to Google APIs
                setBody(fileBytes)
            }

            if (uploadResponse.status.isSuccess()) {
                // Google Drive returns the File resource metadata
                try {
                    val fileMeta = com.google.gson.JsonParser.parseString(uploadResponse.bodyAsText()).asJsonObject
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
            val urlResponse = client.get("$baseUrl/files/download-url/$fileId") {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            }

            if (!urlResponse.status.isSuccess()) {
                Log.e(TAG, "Failed to get download URL: ${urlResponse.status}")
                return null
            }

            val downloadUrl = try {
                val jsonBody = com.google.gson.JsonParser.parseString(urlResponse.bodyAsText()).asJsonObject
                jsonBody.get("downloadUrl")?.asString
            } catch (e: Exception) { null }

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
            is AgentEvent.AgentStep -> {
                Log.d(TAG, "Agent step: ${event.stepType} - ${event.stepTitle} (${event.stepStatus})")
                false
            }
            else -> false // Handle all other canonical events seamlessly
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
            is AgentEvent.AgentStep -> {
                Log.d(TAG, "Agent step: ${event.stepType} - ${event.stepTitle} (${event.stepStatus})")
                flowCollector.emit(event)
                false
            }
            else -> {
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

    @Serializable
    data class ApprovalRequest(
        val toolId: String,
        val approved: Boolean,
        val feedback: String? = null,
    )

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
    val personality: String? = null,
    val messageId: String? = null,
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
