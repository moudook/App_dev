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
    LOCAL_PC;  // Local LLM server via USB/WiFi connection

    val displayName: String
        get() = when (this) {
            GEMINI -> "Google Gemini"
            DEEPSEEK -> "DeepSeek"
            GROQ -> "Groq"
            CEREBRAS -> "Cerebras"
            COHERE -> "Cohere"
            OPENAI -> "OpenAI"
            OPENROUTER -> "OpenRouter"
            ANTHROPIC -> "Anthropic"
            HUGGINGFACE -> "Hugging Face"
            GITHUB -> "GitHub Models"
            LOCAL_PC -> "Local LLM"
        }
}
