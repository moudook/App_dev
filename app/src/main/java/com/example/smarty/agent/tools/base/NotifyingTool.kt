package com.example.smarty.agent.tools.base

import ai.koog.agents.core.tools.Tool
import com.example.smarty.agent.AgentEventSink

/**
 * A wrapper for tools that notifies AgentEventSink about execution lifecycle.
 * This allows the UI to display "Using tool: KnowledgeMaster..."
 */
class NotifyingTool<TArgs : Any, TResult : Any>(
    private val delegate: Tool<TArgs, TResult>,
    private val eventSink: AgentEventSink
) : Tool<TArgs, TResult>(
    argsSerializer = delegate.argsSerializer,
    resultSerializer = delegate.resultSerializer,
    name = delegate.name,
    description = "Wrapper for ${delegate.name}"
) {
    override suspend fun execute(args: TArgs): TResult {
        // Notify start
        // Use simpler name for display (e.g., "knowledge_master" -> "Knowledge Master")
        val displayName = delegate.name.split("_")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

        eventSink.onToolExecutionStarted(delegate.name, displayName)

        try {
            return delegate.execute(args)
        } finally {
            // Notify end
            eventSink.onToolExecutionCompleted(delegate.name)
        }
    }
}