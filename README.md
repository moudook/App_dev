---
title: Smarty - Advanced AI Agent
colorFrom: purple
colorTo: blue
sdk: docker
pinned: true
license: mit
---

# Smarty

Modern AI companion powered by **OpenCode Zen API**, **Model Context Protocol (MCP)**, and a modular **Multi-Agent Architecture** designed for Android and Hugging Face Spaces.

Smarty has evolved into a fully autonomous, privacy-first AI assistant featuring real-time canonical event streaming, transparent reasoning timelines, and seamless integration with OpenCode's cloud-based AI models.

![Platform](https://img.shields.io/badge/Platform-Android-blue)
![Version](https://img.shields.io/badge/Version-7.0.0-purple)
![Architecture](https://img.shields.io/badge/Architecture-Ktor_Backend-orange)
![License](https://img.shields.io/badge/License-MIT-green)

---

# Architecture Overview

Smarty utilizes a thin-client Android architecture connected to a scalable Ktor backend. The server orchestrates LLM interactions natively using the OpenCode Zen API and supports the Model Context Protocol (MCP) for complex tool execution.

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
               │ HTTPS
               ▼
┌──────────────────────────────┐
│     OpenCode Zen API         │
│   (Cloud / Free AI Models)   │
└──────────────────────────────┘
```

---

# Key Features

## OpenCode Zen API Integration
- **Direct Integration**: Seamless, native Ktor connection to the `OpenCode Zen API` for robust LLM inference, completely bypassing legacy CLI daemons.
- **Model Support**: **As of June 11, 2026, ONLY the `north-mini-code-free` model is fully functional.** Other models are currently not working due to API constraints.
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

## 2. Server Configuration

Set the following Environment Variables in your Hugging Face Space settings:
- `OPENCODE_API_KEY` (Optional): Required if you are not using free-tier Zen models.
- `OPENCODE_ZEN_BASE_URL` (Optional): Overrides the default Zen API URL (`https://opencode.ai/zen/v1`).
- `FIREBASE_CREDENTIALS`: Raw JSON content for Firebase Admin SDK initialization.
- `KREA_API_KEY` (Optional): Used for image generation features.

## 3. Local Development

### Requirements
- Java JDK 17
- Docker Desktop
- Android Studio

### Running the Server
```bash
./gradlew :server:run
```

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

# License
MIT License
