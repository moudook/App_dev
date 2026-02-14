# Smarty Project Documentation

Welcome to the technical documentation for **Smarty**, a high-performance, privacy-centric digital assistant for Android. Smarty is built on a **Three-Tier "Thin Client" Architecture**, ensuring data sovereignty while providing a sophisticated, agentic AI experience.

##  Project Overview

Smarty represents a new category of personal assistants that unify note-taking, task management, and generative AI into a single, cohesive **Input Stream**. The project has evolved from a monolithic Android app into a distributed system:

- **Thin Client (Android Body)**: Handles UI rendering, high-performance animations, local I/O (voice, screen capture), and device-specific actions.
- **Remote Brain (Ktor Server)**: Manages all AI reasoning, tool selection, context retrieval (RAG), and memory persistence.
- **Privacy-First**: Built-in PII masking, secure storage in PostgreSQL/pgvector, and abstract conversation summarization.
- **Agentic Logic**: Intelligent routing between local servers and cloud providers (Gemini, Anthropic, OpenAI) using a state-of-the-art tool-use protocol.

##  Key Features

- **Agentic Tool Loop**: A sophisticated "Remote Brain" that can plan, execute tools, and reason over results before responding to the user.
- **Observability & Durability (KOOG)**: Integrated step-by-step tracing (`PostgresTracer`) and session checkpointing (`PersistenceManager`) for industrial-grade reliability.
- **LLM Performance Caching**: Transparent response caching (`LlmCache`) to eliminate redundant API calls and latency.
- **Atomic Workflow Tools**: A library of specialized tools (Notes, Calendar, Knowledge Query, Web Search) that perform precise actions.    
- **Hybrid Context & Memory (RAG)**: Uses PostgreSQL with `pgvector` for semantic recall of user facts and episodic history.
- **Intelligent Sliding Window**: Manages large context windows by automatically summarizing older conversation turns.
- **Living Orb (Visual Feedback)**: A fluid, morphing visualization that provides intuitive feedback on the AI's internal state.
- **Offline Wake Word**: Privacy-preserving "Friday" trigger powered by a local Vosk engine.
- **SSE-Based Streaming**: Real-time communication between the Body and Brain using a polymorphic protocol.
- **PII Guard & Privacy-First Summarization**: Redaction layers that prevent sensitive data leakage to external LLMs.

---

##  Documentation Index

### [Architecture & Core Logic](./architecture/)
Deep dives into the structural foundations of the application.
- [**App Architecture**](./architecture/App_Architecture.md): The Three-Tier Architecture, ServiceLocator, and the Agent Transport Layer.
- [**Business Logic**](./architecture/Business_Logic.md): Feature managers, Execution Planning, and Context Management.
- [**Common & Server**](./architecture/Common_and_Server.md): Kotlin Multiplatform shared protocols and the Ktor backend implementation.

### [AI Deep Dives](./ai/)
Technical specifications of the agentic engine.
- [**AI System Architecture & Tools**](./ai/AI_System_Architecture.md): Comprehensive breakdown of the ServerAgent, tool definitions, and RAG implementation.

### [Features](./features/)
Detailed explorations of user-facing capabilities.
- [**AI & Agents**](./features/AI_Agents.md): Multi-provider orchestrator, tool-use protocol, and history/context systems.
- [**UI & UX**](./features/UI_UX.md): Design system, custom components, and advanced animations.
- [**Voice Features**](./features/Voice_Features.md): Offline wake word, continuous transcription, and audio visualization.

### [Data & Services](./data/)
Technical details on persistence and background operations.
- [**Data Storage**](./data/Data_Storage.md): PostgreSQL/Room schema, FTS5 indexing, and pgvector storage.
- [**Services**](./data/Services.md): Foreground services (Audio, Assistant) and server-side background tasks.

### [High-Level Overviews](./overviews/)
Quick reference guides for specific domains.
- [**AI Strategy**](./overviews/AI_and_Agents.md) | [**Security**](./overviews/Authentication_and_Security.md) | [**Core Features**](./overviews/Core_Features.md)
- [**Data Persistence**](./overviews/Data_and_Persistence.md) | [**Server Side**](./overviews/Server_Side.md) | [**Background Work**](./overviews/Services_and_Background_Work.md)
- [**UI & Screens**](./overviews/UI_Components_and_Screens.md)

---
 *Generated and maintained by the Smarty Documentation Agent.*

