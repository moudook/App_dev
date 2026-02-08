package com.example.smarty.util.api

import com.example.smarty.data.local.AIProvider

/**
 * Resolves the ordered list of AI providers to try.
 * Combines user priority preferences with all available providers.
 *
 * This eliminates duplicate provider ordering logic across the codebase.
 */
object ProviderPriorityResolver {

    /**
     * Get ordered list of providers to try.
     * User priority providers come first, followed by remaining providers.
     *
     * Example:
     * - User priority: [GROQ, ANTHROPIC]
     * - Result: [GROQ, ANTHROPIC, GEMINI, DEEPSEEK, OPENAI, OPENROUTER, HUGGINGFACE]
     *
     * @param userPriority User-configured provider priority order
     * @return Complete list of providers in order to try
     */
    fun getOrderedProviders(userPriority: List<AIProvider>): List<AIProvider> {
        return (userPriority + AIProvider.entries).distinct()
    }

    /**
     * Get ordered list of providers, filtered to only enabled ones.
     *
     * @param userPriority User-configured provider priority order
     * @param enabledProviders Set of providers that are currently enabled
     * @return Filtered and ordered list of enabled providers
     */
    fun getOrderedEnabledProviders(
        userPriority: List<AIProvider>,
        enabledProviders: Set<AIProvider>
    ): List<AIProvider> {
        return getOrderedProviders(userPriority).filter { it in enabledProviders }
    }

    /**
     * Check if a provider should be tried based on its config.
     *
     * @param provider The provider to check
     * @param isEnabled Whether the provider is enabled
     * @param hasKeys Whether the provider has API keys configured
     * @return True if this provider should be attempted
     */
    fun shouldTryProvider(
        isEnabled: Boolean,
        hasKeys: Boolean
    ): Boolean {
        return isEnabled && hasKeys
    }
}
