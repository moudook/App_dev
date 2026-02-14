package com.example.smarty.core.common.worker

import android.util.Log
import com.example.smarty.features.chat.agent.AgentEventSink
import com.example.smarty.features.chat.agent.models.ImageDisplayItem
import com.example.smarty.features.chat.agent.models.WebCitation
import com.example.smarty.protocol.AgentCommand

/**
 * A no-op EventSink for background workers that don't need to handle UI events.
 */
class BackgroundAgentEventSink : AgentEventSink {
    companion object {
        private const val TAG = "BackgroundAgentEventSink"
    }

    override fun onToolExecutionStarted(toolName: String, toolDisplayName: String) {
        Log.d(TAG, "Tool started: $toolName")
    }

    override fun onToolExecutionCompleted(toolName: String) {
        Log.d(TAG, "Tool completed: $toolName")
    }

    override fun onStatusUpdate(status: String) {
        Log.d(TAG, "Status: $status")
    }

    override fun onCitationsFound(citations: List<WebCitation>) {
        Log.d(TAG, "Citations found: ${citations.size}")
    }

    override fun onDisplayImages(images: List<ImageDisplayItem>) {
        Log.d(TAG, "Images received: ${images.size}")
    }

    override fun onPlanStatusChanged(status: String?) {
        Log.d(TAG, "Plan status: $status")
    }

    override fun onStateSync(syncType: String, data: String) {
        Log.d(TAG, "State sync received: $syncType")
    }

    override fun emit(command: AgentCommand) {
        Log.d(TAG, "Command received: $command")
    }
}
