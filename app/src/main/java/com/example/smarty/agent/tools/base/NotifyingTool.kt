package com.example.smarty.agent.tools.base

import ai.koog.agents.core.tools.Tool
import com.example.smarty.agent.AgentCallbacks

/**
 * A wrapper for tools that notifies AgentCallbacks about execution lifecycle.
 * This allows the UI to display "Using tool: KnowledgeMaster..."
 */
class NotifyingTool<TArgs : Any, TResult : Any>(
    private val delegate: Tool<TArgs, TResult>,
    private val callbacks: AgentCallbacks
) : Tool<TArgs, TResult>(
    argsSerializer = delegate.argsSerializer,
    resultSerializer = delegate.resultSerializer,
    name = delegate.name,
    description = "Wrapper for ${delegate.name}" // Placeholder to fix compilation
) {
    override suspend fun execute(args: TArgs): TResult {
        // Notify start
        // Use simpler name for display (e.g., "knowledge_master" -> "Knowledge Master")
        val displayName = delegate.name.split("_")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        
        callbacks.onToolExecutionStarted(delegate.name, displayName)
        
        try {
            return delegate.execute(args)
        } finally {
            // Notify end
            callbacks.onToolExecutionCompleted(delegate.name)
        }
    }
}