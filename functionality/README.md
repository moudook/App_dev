# Smarty Project Documentation

Welcome to the technical documentation for **Smarty**, a high-performance, privacy-centric digital assistant for Android. Smarty is designed with a "Local-First" philosophy, ensuring data sovereignty while providing a sophisticated, agentic AI experience.

## 🚀 Project Overview

Smarty represents a new category of personal assistants that unify note-taking, task management, and generative AI into a single, cohesive **Input Stream**.

- **Privacy-First**: Built-in PII masking and local-first storage ensure your data stays yours.
- **Hybrid Intelligence**: Intelligent routing between local models and cloud providers (Gemini, Anthropic, OpenAI) for the best balance of speed and power.
- **System Integration**: Deeply integrated into Android as a Default Digital Assistant with screen context awareness.
- **Organic Design**: A minimalist monochrome aesthetic powered by physics-based animations and custom-built UI components.

## ✨ Key Features

- **Organic Thinking Indicator (Living Orb)**: A fluid, morphing visualization that provides intuitive feedback on the AI's internal state and voice activity.
- **Offline Wake Word**: Privacy-preserving "hear me out" trigger powered by a local Vosk engine.
- **CO-STAR Prompt Framework**: High-precision agent behavior guided by a structured Context-Objective-Style-Tone-Audience-Response framework.
- **Hybrid Intent Routing**: A dual-path system that routes tasks through "FAST-PATH" for speed or "REASONING-PATH" for complex problem-solving.
- **PII Masking & Privacy Guard**: Automated redaction and isolation layers that prevent sensitive data from being shared with external LLMs.
- **Liquid Loader**: A high-performance particle engine using custom canvas rendering for organic loading states.
- **AI System Architecture**: Comprehensive technical mapping of the Smarty Agent orchestrator, tool-use protocols, and cross-platform communication.

---

## 📂 Documentation Index

### [Architecture & Core Logic](./architecture/)
Deep dives into the structural foundations of the application.
- [**App Architecture**](./architecture/App_Architecture.md): MVVM patterns, ServiceLocator, and the three-tier Agent Transport Layer.
- [**Business Logic**](./architecture/Business_Logic.md): Feature managers, Execution Planning, and Context Management.
- [**Common & Server**](./architecture/Common_and_Server.md): Kotlin Multiplatform shared logic, Ktor backend, and Vector Store implementation.

### [AI Deep Dives](./ai/)
Technical specifications of the agentic engine.
- [**AI System Architecture & Tools**](./ai/AI_System_Architecture.md): Comprehensive breakdown of agent orchestration, tool definitions, and system-wide AI integration.

### [Features](./features/)
Detailed explorations of user-facing capabilities.
- [**AI & Agents**](./features/AI_Agents.md): Multi-provider orchestrator, tool-use protocol, and memory systems.
- [**UI & UX**](./features/UI_UX.md): Design system, custom components, and advanced animations.
- [**Voice Features**](./features/Voice_Features.md): Offline wake word, continuous transcription, and audio visualization.

### [Data & Services](./data/)
Technical details on persistence and background operations.
- [**Data Storage**](./data/Data_Storage.md): Room DB schema, FTS5 indexing, and encrypted preferences.
- [**Services**](./data/Services.md): Foreground services (Audio, Assistant) and background WorkManager tasks.

### [High-Level Overviews](./overviews/)
Quick reference guides for specific domains.
- [**AI Strategy**](./overviews/AI_and_Agents.md) | [**Security**](./overviews/Authentication_and_Security.md) | [**Core Features**](./overviews/Core_Features.md)
- [**Data Persistence**](./overviews/Data_and_Persistence.md) | [**Server Side**](./overviews/Server_Side.md) | [**Background Work**](./overviews/Services_and_Background_Work.md)
- [**UI & Screens**](./overviews/UI_Components_and_Screens.md)

---
🤖 *Generated and maintained by the Smarty Documentation Agent.*
