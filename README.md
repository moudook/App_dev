---
title: Smarty - Advanced AI Agent
colorFrom: purple
colorTo: blue
sdk: docker
pinned: true
license: mit
---

# Smarty

Modern AI companion powered by **OpenCode CLI**, **Model Context Protocol (MCP)**, and a modular **Multi-Agent Architecture** designed for Android and Hugging Face Spaces.

Smarty has evolved into a fully autonomous, privacy-first AI assistant featuring real-time canonical event streaming, transparent reasoning timelines, and seamless integration with local and free-tier AI models.

![Platform](https://img.shields.io/badge/Platform-Android-blue)
![Version](https://img.shields.io/badge/Version-7.0.0-purple)
![Architecture](https://img.shields.io/badge/Architecture-Ktor_Backend-orange)
![License](https://img.shields.io/badge/License-MIT-green)

---
words refinment 2.1

# Architecture Overview

Smarty utilizes a thin-client Android architecture connected to a scalable Ktor backend. The server orchestrates LLM interactions using the OpenCode CLI daemon, natively supporting the Model Context Protocol (MCP) for tool execution.

```text
┌──────────────────────────────┐
│    Smarty Android Client     │
│                              │
│  - Jetpack Compose UI        │
│  - Room DB & Timeline UI     │
│  - AgentEvent SSE Consumer   │
└──────────────┬───────────────┘
               │ HTTPS / SSE
               ▼
┌──────────────────────────────┐
│        Smarty Server         │
│                              │
│  - Ktor Backend              │
│  - Canonical Event Streamer  │
│  - MCP Tools Server          │
└──────────────┬───────────────┘
               │ localhost:4096 (JSON-RPC)
               ▼
┌──────────────────────────────┐
│     OpenCode CLI Daemon      │
│  (Local / Free AI Models)    │
└──────────────────────────────┘
```

---

# Key Features

## OpenCode CLI & Local AI Integration
- **Direct Integration**: Seamless connection to `opencode serve` for robust LLM inference.
- **Model Support**: **Currently, ONLY the `north-mini` model is working.** Other models are currently not working due to API constraints.
- **Dynamic Discovery**: Automatic model discovery and fallback management directly from the Android UI.

## Agentic Timeline UI & Transparent Reasoning
- **Canonical Event Streaming**: Real-time SSE streaming of `Thinking`, `ReasoningDelta`, `ToolCallStarted`, and `FinalAnswerFinished` events.
- **Persistent Timeline**: The Android client automatically logs and reconstructs the agent's step-by-step reasoning using `TimelineEventEntity` in the Room Database, surviving app restarts and screen navigation.
- **Collapsible Reasoning Sections**: Watch the AI think, execute tools, and formulate answers live in the UI.

## Model Context Protocol (MCP)
- Fully compliant MCP server built natively into Ktor.
- Secure, JSON-RPC 2.0 based tool orchestration between the LLM and the server backend.
- Extensible tool definitions allowing the agent to perform web searches, manage structured notes, and execute complex workflows autonomously.

## Privacy & Security
- **Local Database Usage**: Private conversation storage on Android via Room DB.
- **No Conversation Logging**: Strict zero-logging policies on the server.
- **Firewall & Isolation**: IP allowlisting, Firebase Auth multi-tenant isolation, and secure communication using HTTPS/SSE.
- **Bring-Your-Own API Keys**: Configure exactly what models and external providers the system uses.

## Additional Capabilities
- **Structured Knowledge Storage**: Note creation with metadata and context extraction.
- **Deep Research**: Autonomous multi-source web research with automatic citation tracking and source credibility hierarchy.
- **Media Support**: File attachments, document processing, and OCR capabilities.
- **Image Generation**: Built-in support for Krea AI / Flux.1 models.
- **Wellness & Entertainment**: Interactive mental health assessments, guided breathing exercises, Tic Tac Toe, and a physics-based Coin Toss game.

---

# Quick Start

## 1. Deploy the Server on Hugging Face Spaces

1. Create a new Space on Hugging Face.
2. Configuration:
   - Space Name: `smarty-server`
   - SDK: **Docker**
   - Template: **Blank**
3. Connect this repository to the Space. The included `entrypoint.sh` will automatically:
   - Install dependencies.
   - Start the Ktor server on port `7860` with JVM memory optimizations.
   - Run robust health-checks to ensure Ktor is ready.
   - Boot the OpenCode CLI daemon for LLM inference on port `4096`.

## 2. Server Configuration

Ensure the `opencode.json` configuration file is properly set to point the MCP bridge to `localhost:7860`.

```json
{
  "mcpServers": {
    "smarty-backend": {
      "command": "node",
      "args": ["..."],
      "url": "http://localhost:7860/mcp/sse"
    }
  }
}
```

## 3. Local Development

### Requirements
- Java JDK 17
- Docker Desktop
- Android Studio
- OpenCode CLI (`npm install -g opencode-ai`)

### Running the Server
```bash
./gradlew :server:run
```
Make sure `opencode serve` is running in the background.

### Running the Android Client
Open the project in Android Studio, sync Gradle, and run the `:app` configuration on an emulator or physical device.

---

# Security Notes

## Admin Email Whitelist (⚠️ REMOVE BEFORE PUBLIC RELEASE)

The server enforces an **admin-only email whitelist** at the authentication layer. Only `forpblcusz@gmail.com` can access the server.

### Where to remove/change:
1. `server/.../plugins/Security.kt` → `ADMIN_EMAIL` constant and `isAdminEmail()` function
2. `server/.../routes/AuthRoutes.kt` → email check in `/auth/verify` handler
3. To disable: Set `ADMIN_EMAIL` to `null` or remove the checks, or delete the `isAdminEmail()` calls

### Why it exists:
This allows the Hugging Face Space to be public (enabling direct WebSocket connections) while ensuring only the owner's Google account can access data. Remove this when the app is ready for multi-user support.

### The last commit worked what it have right now is I can only see the final response that being sand all at once not even streaming not even other components like web search thinking steps etc
### Sorry in the last commit the web searches and the streaming is working MCP tools are not working The streaming is working fine here I can see the web search outputs also the markdown rendering is working fine here The thinking sections are not appearing, Surveillance are called Task And we can see parallel sub agents now, What we cannot see is the to do list, Sub agent sections are expandable But not entered properly,[Additional UI changes Fixed the speech input! It now waits 0.7s before sending, smoothly combines what you type with what you say instead of overwriting it, and cleanly resets the text box afterward.]

### Next push may be a very huge improvements
### ths commti wasnt worth it might make improvements on the way

# License
MIT License
