package com.example.smarty.data.remote

import android.util.Log
import com.example.smarty.agent.AgentEventSink
import com.example.smarty.protocol.AgentCommand
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.protocol.ClientEvent
import com.example.smarty.ui.components.ConnectionStatus
import com.example.smarty.BuildConfig
import io.ktor.client.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.Json

/**
 * Client-side service that connects to the Cloud Agent's SSE stream.
 * Acts as the bridge between the Android app and the "Remote Brain".
 */
class RemoteAgentService(
    private val client: HttpClient,
    private val eventSink: AgentEventSink,
    private val serverUrlProvider: () -> String
) {
    // Secondary constructor for fixed URL
    constructor(client: HttpClient, eventSink: AgentEventSink, serverUrl: String) :
        this(client, eventSink, { serverUrl })

    private val json = Json { ignoreUnknownKeys = true }

    private val _connectionState = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionState: StateFlow<ConnectionStatus> = _connectionState.asStateFlow()

    /**
     * Send a query to the remote agent and process the event stream.
     * Returns a Flow of partial/final results (content chunks) to be displayed in the UI.
     *
     * Side effects (Commands, UI status updates) are dispatched to [eventSink].
     */
    fun sendQuery(query: String, provider: String? = null, providerUrl: String? = null, model: String? = null, accessToken: String? = null, sessionId: String? = null): Flow<String> = flow {
        val baseUrl = serverUrlProvider()
        val url = buildString {
            append("$baseUrl/chat/stream")
            append("?query=${query.encodeURLParameter()}")
            if (provider != null) append("&provider=${provider.encodeURLParameter()}")
            if (providerUrl != null) append("&providerUrl=${providerUrl.encodeURLParameter()}")
            if (model != null) append("&model=${model.encodeURLParameter()}")
            if (accessToken != null) append("&token=${accessToken.encodeURLParameter()}")
            if (sessionId != null) append("&sessionId=${sessionId.encodeURLParameter()}")
        }

        Log.d(TAG, "Connecting to Remote Agent: $url")
        _connectionState.value = ConnectionStatus.CONNECTING

        try {
            client.sse(urlString = url) {
                _connectionState.value = ConnectionStatus.CONNECTED
                incoming.collect { event ->
                    val data = event.data ?: return@collect
                    try {
                        val agentEvent = json.decodeFromString<AgentEvent>(data)
                        handleEvent(agentEvent, this@flow)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse SSE event: $data", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSE connection failed", e)
            _connectionState.value = ConnectionStatus.OFFLINE
            emit("\n[Connection Error: ${e.message}]")
        } finally {
            // Only reset to DISCONNECTED if not already OFFLINE (error state)
            if (_connectionState.value != ConnectionStatus.OFFLINE) {
                _connectionState.value = ConnectionStatus.DISCONNECTED
            }
        }
    }

    /**
     * Send a client event (e.g., tool result, app state) back to the remote agent.
     */
    suspend fun sendEvent(sessionId: String, event: ClientEvent) {
        try {
            Log.d(TAG, "Sending client event: $event")
            val baseUrl = serverUrlProvider()
            val response = client.post("$baseUrl/chat/events") {
                parameter("sessionId", sessionId)
                contentType(ContentType.Application.Json)
                setBody(event)
            }
            Log.d(TAG, "Event sent successfully: ${response.status}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send client event", e)
        }
    }

    private suspend fun handleEvent(event: AgentEvent, flowCollector: kotlinx.coroutines.flow.FlowCollector<String>) {
        when (event) {
            is AgentEvent.Thinking -> {
                // Update "Thinking..." UI status
                eventSink.onStatusUpdate(event.content)
            }
            is AgentEvent.ToolCall -> {
                // Show tool execution UI
                eventSink.onToolExecutionStarted(event.toolName, event.displayName)
                if (event.status == "completed") {
                    eventSink.onToolExecutionCompleted(event.toolName)
                }
            }
            is AgentEvent.Command -> {
                // Execute command locally
                Log.d(TAG, "Received remote command: ${event.command}")
                eventSink.emit(event.command)
            }
            is AgentEvent.Result -> {
                // Stream content to UI
                flowCollector.emit(event.content)
            }
            is AgentEvent.Error -> {
                Log.e(TAG, "Remote Agent Error: ${event.message}")
                flowCollector.emit("\n[Error: ${event.message}]")
            }
        }
    }

    companion object {
        private const val TAG = "RemoteAgentService"
    }
}
