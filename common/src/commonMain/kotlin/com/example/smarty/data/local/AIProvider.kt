package com.example.smarty.data.local

/**
 * Supported AI Providers.
 */
enum class AIProvider {
    GEMINI,
    DEEPSEEK,
    GROQ,
    CEREBRAS,
    COHERE,
    OPENAI,
    OPENROUTER,
    ANTHROPIC,
    HUGGINGFACE,
    GITHUB,
    LOCAL_PC  // Local LLM server via USB/WiFi connection
}
