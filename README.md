<p align="center">
  <img src="Smarty_Icon.svg" width="120" height="120" alt="Smarty Logo">
</p>

<h1 align="center">Smarty</h1>

<p align="center">
  <strong>An AI-Powered Knowledge Companion for Android</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-1.2.0-blue?style=flat-square" alt="Version">
  <img src="https://img.shields.io/badge/platform-Android-pink?style=flat-square" alt="Platform">
  <img src="https://img.shields.io/badge/Privacy-First-32a852?style=flat-square" alt="Privacy">
  <img src="https://img.shields.io/badge/Local-AI-orange?style=flat-square" alt="Local AI">
</p>

<p align="center">
  Smarty transforms how you interact with information—capturing what matters, organizing it intelligently, and surfacing insights through natural conversation. Think less about managing knowledge, and more about using it.
</p>

---

## Philosophy

We live in an age of information abundance, yet spend disproportionate time curating rather than creating. Smarty addresses this fundamental inefficiency by inverting the traditional knowledge management paradigm.

**Capture without constraint.** The moment an idea strikes, preserve it—no taxonomies, no metadata, no friction. Whether it's a fleeting thought, a web article, or a two-hour lecture recording, Smarty accepts it all.

**Intelligence that works for you.** Your notes shouldn't require active maintenance. Smarty autonomously categorizes, synthesizes, and surfaces connections across your knowledge base, transforming static information into dynamic understanding.

**Privacy as a foundation.** Your intellectual work deserves protection. Smarty operates entirely on-device or through your own infrastructure, ensuring your thoughts remain yours. Sensitive information can be cryptographically isolated from even the AI's context.

---

## Core Capabilities

### Autonomous Agency

Smarty transcends traditional chatbot interactions through the **Koog Framework**, functioning as a genuine agent capable of multi-step reasoning and action:

- **Contextual Research**: Synthesize personal notes with real-time web data to answer complex queries.
- **Task Orchestration**: Manage todos, reminders, and workflows across your knowledge graph.
- **Content Generation**: Draft communications, summaries, and creative work informed by your existing knowledge.
- **Media Intelligence**: Navigate, search, and extract insights from audio recordings and multimedia content.

### Frictionless Capture

Information enters Smarty through the path of least resistance:

- **Universal Integration**: Share content from any Android application directly into your knowledge base.
- **Intelligent Detection**: Automatically recognizes and processes 15+ content types—from videos and social media posts to PDFs and code snippets.
- **Voice-First Design**: Offline wake-word detection enables hands-free capture, ideal for driving, exercise, or spontaneous ideation.

### Privacy-Centric Architecture

Your data sovereignty is non-negotiable:

- **Local LLM Support**: Connect to models like Llama 3 or Mistral running on your local hardware via USB or network connection.
- **Thin Client Design**: Cloud-based intelligence is managed via the **Smarty Server**, ensuring the mobile app remains lightweight and secure.
- **Zero-Knowledge Operation**: When using local models, no data traverses external networks.
- **Selective Isolation**: Privacy mode cryptographically separates sensitive notes from AI context with a simple gesture.

### Advanced Audio Processing

Audio is a first-class citizen in Smarty's ecosystem:

- Native playback with waveform visualization and precise navigation
- Background playback with system media controls
- AI-powered transcription, summarization, and semantic search across recordings

---

## Applications

### For Researchers

Transform academic papers, articles, and reference materials into actionable insights without manual synthesis. Query: *"Summarize the methodology across these three papers and identify contradicting findings."*

### For Students

Convert lecture recordings into searchable, quizzable knowledge. Query: *"Generate practice questions from today's lecture on neural networks."*

### For Developers

Maintain a living knowledge base of solutions, patterns, and learnings. Query: *"Search for how I solved the authentication bug in the mobile app last month."*

### For Creators

Capture inspiration in the moment and develop it when ready. Query: *"Find all my video ideas about AI and create an outline combining the best elements."*

---

## Technical Architecture

Smarty employs a sophisticated agentic loop that orchestrates multiple capabilities:

**Reasoning Phase**: Analyzes user intent and decomposes requests into actionable steps, determining which tools—search, calendar, database queries, or external APIs—are necessary.

**Execution Phase**: Coordinates tool invocation, manages state, and handles errors gracefully across potentially long-running operations.

**Synthesis Phase**: Integrates results from personal knowledge, external sources, and tool outputs into coherent, contextually-aware responses.

For comprehensive architectural documentation, refer to [AGENT_DOCUMENTATION.md](AGENT_DOCUMENTATION.md).

---

## Getting Started

### System Requirements

- Android 8.0 (Oreo) or higher
- **Smarty Server**: Required for AI features. The app acts as a **Thin Client**, connecting to a single Smarty Server (Local LLM or Remote) via a secure connection.
- **Connection Credentials**: Access tokens or connection keys are configured once to establish the server link. Cloud provider keys (OpenAI, Anthropic, etc.) are managed entirely on the **Server**.

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/moudook/App_dev.git
   ```

2. Open the project in Android Studio

3. Build and deploy to your device or emulator

### Audio Configuration

For offline voice activation, download the [Vosk acoustic model](https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip) and extract it to:

```
app/src/main/assets/vosk-model-small-en-us-0.15/
```

---

## Development

Smarty is architected with contemporary Android development practices:

- **Kotlin** with **Jetpack Compose** for reactive, declarative UI
- **Room Database** with full-text search (FTS5) for performant local storage
- **MVVM architecture** with clean separation of concerns
- **WorkManager** for reliable background task execution

Developers interested in contributing should consult [DEVELOPER_QUICK_REFERENCE.md](DEVELOPER_QUICK_REFERENCE.md) for detailed setup instructions, architectural guidelines, and contribution protocols.

---

## License

<p align="center">
  <sub>Licensed under Creative Commons Attribution 4.0 International (CC BY 4.0)</sub>
</p>