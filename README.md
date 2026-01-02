<p align="center">
  <img src="Cogni_Icon.svg" width="120" height="120" alt="Loum Logo">
</p>

<h1 align="center">Loum</h1>

<p align="center">
  <strong>Your AI-Powered Second Brain for Android</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-1.2.0-blue?style=flat-square" alt="Version">
  <img src="https://img.shields.io/badge/platform-Android-green?style=flat-square" alt="Platform">
  <img src="https://img.shields.io/badge/min%20SDK-26-orange?style=flat-square" alt="Min SDK">
  <img src="https://img.shields.io/badge/target%20SDK-36-brightgreen?style=flat-square" alt="Target SDK">
  <img src="https://img.shields.io/badge/license-CC%20BY%204.0-lightgrey?style=flat-square" alt="License">
  <img src="https://img.shields.io/badge/kotlin-2.2.21-blueviolet?style=flat-square" alt="Kotlin">
  <img src="https://img.shields.io/badge/compose-BOM%202024.12-purple?style=flat-square" alt="Compose">
</p>

<p align="center">
  An intelligent, privacy-first note-taking app that automatically organizes, summarizes, and helps you interact with your knowledge through natural conversation.
</p>

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
  - [Note Management](#note-management)
  - [AI Intelligence](#ai-intelligence)
  - [AI Agent and Tool Calling](#ai-agent-and-tool-calling)
  - [Voice Features](#voice-features)
  - [AI Assistant](#ai-assistant)
  - [Audio Player](#audio-player)
  - [Calendar and Scheduling](#calendar-and-scheduling)
  - [Backup and Sync](#backup-and-sync)
  - [Security and Privacy](#security-and-privacy)
  - [UI and UX](#ui-and-ux)
- [Technologies Used](#technologies-used)
- [Architecture](#architecture)
- [Getting Started](#getting-started)
- [Installation](#installation)
- [Configuration](#configuration)
- [Usage](#usage)
- [AI Providers](#ai-providers)
- [Local LLM Server](#local-llm-server-usbwifi)
- [Mention System](#mention-system)
- [Deep Document Analysis](#deep-document-analysis)
- [Web Search Integration](#web-search-integration)
- [Testing](#testing)
- [Project Structure](#project-structure)
- [Design System](#design-system)
- [Permissions](#permissions)
- [Security](#security)
- [Contributing](#contributing)
- [License](#license)
- [Contact](#contact)
- [Changelog](#changelog)

---

## Overview

Loum is designed to be your **second brain** — a place where you can dump any content (links, images, documents, voice notes, thoughts) and have AI automatically categorize, summarize, and make it searchable through natural conversation. The app runs entirely on-device with optional cloud AI integration, ensuring your private notes stay private.

### Why Loum?

- **Zero-friction capture**: Share anything from any app, and Loum handles the rest
- **AI-powered intelligence**: Automatic categorization, smart summaries, and conversational search
- **Privacy-first design**: Private notes are completely invisible to AI processing
- **Multi-provider support**: Choose from multiple AI providers with automatic failover
- **Offline-capable**: Core functionality works without internet; AI features gracefully degrade
- **Advanced voice features**: Wake word detection and voice enrollment
- **Rich media support**: Audio player with waveform visualization, PDF text extraction, and more

---

## Features

### Note Management

The core note-taking functionality with intelligent organization.

| Feature | Description |
|---------|-------------|
| **Quick Capture** | Create notes via text input, voice, share intent, or home screen widget |
| **Content Types** | Auto-detects 15+ content types including YouTube, Twitter, Instagram, websites, documents, audio, video, code, and more |
| **Stacks (Categories)** | Organize notes into customizable categories: Learn, Read, Watch, Idea, Todo, Buy, Meet, Code, Quote, Inspo, Recipe, Health, Finance, Work, Play, Note |
| **Full-Text Search** | FTS5-powered instant search across all note content |
| **Archive** | Soft-delete notes to archive for later retrieval |
| **Pin Notes** | Pin important notes to the top of your list |
| **Reminders** | Set time-based reminders on any note |
| **Todos** | Add checklist items within any note |
| **Version History** | Track changes to notes over time |
| **Bulk Operations** | Select and archive/delete multiple notes at once |
| **Fast Scroll** | Alphabetic fast scroller for quick navigation |

#### Supported Content Types

| Type | Description | Auto-Detection |
|------|-------------|----------------|
| Brain Dump | Quick text notes and thoughts | Yes |
| YouTube | Video links with metadata extraction | Yes |
| Twitter/X | Tweet links and content | Yes |
| Instagram | Instagram post links | Yes |
| Website | Any web URL with content analysis | Yes |
| Image | Photos and images | Yes |
| Document | PDFs, Word docs, text files | Yes |
| Spreadsheet | Excel, CSV files | Yes |
| Presentation | PowerPoint, slides | Yes |
| Video | Video files | Yes |
| Audio | Audio files with native playback | Yes |
| Code | Code snippets and files | Yes |
| Archive | ZIP, RAR, and compressed files | Yes |
| APK | Android application packages | Yes |
| File | Any other file type | Yes |

---

### AI Intelligence

Automatic content analysis and intelligent processing.

| Feature | Description |
|---------|-------------|
| **Automatic Categorization** | AI analyzes your content and assigns it to the most relevant category |
| **Smart Summaries** | Get concise AI-generated summaries of your notes |
| **"Why You Saved This"** | AI explains the potential value of saved content |
| **Chat Mode** | Ask questions about your notes in natural language (shake to activate) |
| **Multi-Provider Support** | Choose from 7+ AI providers with automatic fallback |
| **Automatic Failover** | If one provider fails, automatically tries the next available provider |
| **Private Notes** | Option to exclude specific notes from AI chat context |
| **Batch Processing** | Process multiple notes at once using AI |
| **Context-Aware Responses** | AI understands conversation context and history |
| **Smart Category Fallback** | When AI fails, keyword-based categorization (Todo, Idea, Learn, Code, etc.) instead of generic "Saved Files" |
| **Inline Image Viewing** | View images from notes directly in chat responses |

---

### AI Agent and Tool Calling

Advanced agentic AI capabilities using the Koog framework.

| Tool Category | Available Tools |
|---------------|-----------------|
| **Notes** | Create, search, update, archive, unarchive, delete, summarize notes |
| **Todos** | Add todos, toggle completion, delete todos |
| **Calendar** | Create events, get events, delete events |
| **Timer/Alarm** | Create timers, cancel timers |
| **Research** | Web search with citations (Tavily integration) |
| **External** | Open apps, play audio files, save screenshots |
| **Media** | View images from notes inline in chat, show recent notes |

#### Agent Commands

| Command | Action |
|---------|--------|
| "Create a note about..." | Creates a new note with AI-generated title |
| "Find notes about..." | Searches your notes semantically |
| "Archive the note about..." | Archives matching notes |
| "Add todo: ..." | Adds a todo item to a note |
| "Search web for..." | Performs web search with citations |
| "Play the audio in..." | Plays audio attachments from notes |
| "Set a timer for 10 minutes" | Creates a timer notification |
| "Create a calendar event for tomorrow" | Adds an event to your calendar |
| "Show me the image from..." | Displays images inline in chat |
| "Show my recent notes" | Lists your most recent notes |

---

### Voice Features

Hands-free interaction and voice-powered input.

| Feature | Description |
|---------|-------------|
| **Voice-to-Text** | Native speech recognition for note input |
| **Wake Word Detection** | Offline wake word detection using Vosk |
| **Speaker Identification** | Voice enrollment for personalized experience |
| **Voice Notes** | Capture thoughts hands-free with voice commands |

---

### AI Assistant

A voice-activated assistant similar to Google Assistant for hands-free control.

| Feature | Description |
|---------|-------------|
| **Voice Activation** | Wake word detection for hands-free activation |
| **Screenshot Capture** | Take screenshots with AI-generated titles based on your description |
| **Smart Categorization** | Screenshots auto-categorized with tags (app name, keywords) |
| **Open Apps** | Launch any installed app by name or shortcut |
| **Play Music** | Play audio files from your notes |
| **Playback Control** | Stop/pause currently playing audio |
| **Context Awareness** | Assistant understands what you're viewing and can act on it |
| **Offline Commands** | Open, Play, Stop commands work offline without AI |
| **Transparent Overlay** | Floating assistant overlay with transparent background |

#### Open App Commands

Launch any installed app by saying its name. Works offline.

| Command Format | Example |
|----------------|---------|
| open [app] | "Open YouTube" |
| launch [app] | "Launch Instagram" |
| start [app] | "Start Chrome" |
| run [app] | "Run Settings" |

**Supported Shortcuts:**

| Shortcut | Opens |
|----------|-------|
| yt | YouTube |
| ig, insta | Instagram |
| fb | Facebook |
| wa | WhatsApp |
| tw, x | Twitter/X |
| tg | Telegram |
| snap | Snapchat |
| tt | TikTok |
| chrome, browser | Chrome/Browser |
| gmail, email, mail | Gmail/Email |
| maps | Google Maps |
| photos, gallery | Photos/Gallery |
| camera, cam | Camera |
| settings | Settings |
| clock, alarm, timer | Clock |
| calc | Calculator |
| calendar, cal | Calendar |
| messages, sms, text | Messages |
| phone, dialer, call | Phone |
| contacts | Contacts |
| store, play store | Play Store |
| music | Spotify/Music |
| files | File Manager |
| notes, note | Notes/Keep |
| discord | Discord |
| reddit | Reddit |
| netflix | Netflix |
| zoom | Zoom |

#### Play Music Commands

Play audio files stored in your notes. Works offline.

| Command Format | Example |
|----------------|---------|
| play [music] | "Play my favorite song" |
| play me [music] | "Play me some jazz" |
| play some [music] | "Play some workout music" |
| put on [music] | "Put on relaxing sounds" |

The assistant searches your notes for matching audio files by filename, note title, content, and tags.

#### Stop/Pause Commands

Control audio playback. Works offline.

| Command | Example |
|---------|---------|
| stop | "Stop" |
| pause | "Pause" |
| stop playing | "Stop playing" |
| pause music | "Pause music" |

#### Screenshot Commands

Capture the current screen and save it as a note.

| Command | Example |
|---------|---------|
| save this page | "Save this page from my trip" |
| save this screen | "Save this screen" |
| capture this | "Capture this" |
| remember this page | "Remember this page from my trip" |
| screenshot this | "Screenshot this" |
| take a screenshot | "Take a screenshot" |

**Tip:** You can add a description after the command and the AI will generate a title based on it:
- "Save this page about Python tutorials" creates a note with an appropriate title
- "Remember this from my vacation planning" creates a title reflecting the hint

---

### Audio Player

Full-featured native audio playback with modern UI.

| Feature | Description |
|---------|-------------|
| **Mini Player** | Collapsible bottom bar showing current track |
| **Full Player** | Expanded view with waveform visualization |
| **Background Playback** | Continue listening while using other apps |
| **Seek Support** | Tap on waveform to jump to any position |
| **Media Controls** | Play/pause, skip forward/back 10 seconds |
| **Media Session Integration** | Control from notification and lock screen |

---

### Calendar and Scheduling

Date-based organization and time management.

| Feature | Description |
|---------|-------------|
| **Calendar Screen** | View notes organized by date |
| **Timer/Alarm** | Set timers and alarms from the app |
| **Daily Digest** | Morning notification summarizing yesterday's notes |
| **Calendar Events** | Create and manage calendar events with reminders |
| **Event Integration** | Link notes to calendar events |

---

### Backup and Sync

Keep your data safe and synchronized.

| Feature | Description |
|---------|-------------|
| **Google Drive Backup** | Securely backup all notes to your Google Drive |
| **Auto-Backup** | Schedule automatic backups (configurable interval) |
| **Restore** | Restore notes from any previous backup |
| **Firebase Integration** | Optional cloud features with Firebase |

---

### Security and Privacy

Protect your sensitive information.

| Feature | Description |
|---------|-------------|
| **PIN Protection** | Optional PIN lock for app access |
| **Encrypted Storage** | All sensitive data (API keys, PIN) stored using encrypted preferences |
| **Private Notes** | Exclude sensitive notes from AI context |
| **PII Masking** | Automatic detection and masking of personal information |
| **Content Security Filter** | Validates and sanitizes all content |
| **Voice Fingerprinting** | Personalized voice recognition for enhanced privacy |

---

### UI and UX

Modern, polished user experience.

| Feature | Description |
|---------|-------------|
| **Material 3 Design** | Modern Material You design language |
| **Dark/Light Theme** | Animated smooth transitions between themes |
| **OLED Optimized** | Pure black dark theme for OLED displays |
| **Golden Ratio Spacing** | Harmonious spacing system based on Fibonacci sequence |
| **Shake Gestures** | Shake to toggle chat mode or AI exclusion while typing |
| **Haptic Feedback** | Tactile feedback for interactions |
| **Smooth Animations** | Choreographed animations for delightful UX |

---

### Share Integration

Seamless sharing and deep linking.

| Feature | Description |
|---------|-------------|
| **Share Target** | Accept shared content from any app |
| **QR Code Sharing** | Share category links via QR codes |
| **Deep Link Support** | Open specific notes or categories via `cogni://` URLs |
| **Home Screen Widget** | Quick note capture without opening the app |

---

## Technologies Used

### Core Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| **Kotlin** | 2.2.21 | Primary programming language |
| **Jetpack Compose** | BOM 2024.12.01 | Modern declarative UI toolkit |
| **Material 3** | Latest | Design system and components |
| **Kotlin Coroutines** | 1.10.2 | Asynchronous programming |
| **Kotlin Serialization** | 1.8.1 | JSON/data serialization |

### Architecture Components

| Component | Technology |
|-----------|------------|
| **UI Layer** | Jetpack Compose with MVVM pattern |
| **ViewModel** | AndroidX ViewModel with StateFlow |
| **Navigation** | Jetpack Navigation Compose |
| **Database** | Room 2.7.2 (SQLite with FTS5) |
| **DI Pattern** | Manual singleton injection |

### Data and Storage

| Library | Version | Purpose |
|---------|---------|---------|
| Room | 2.7.2 | Local SQLite database with paging |
| DataStore | 1.1.1 | Preferences storage |
| EncryptedPrefs | 1.1.1 | Secure encrypted storage |
| Gson | 2.11.0 | JSON serialization |
| Paging 3 | 3.3.5 | Large list pagination |

### Networking and APIs

| Library | Version | Purpose |
|---------|---------|---------|
| OkHttp | 4.12.0 | HTTP client |
| Ktor Client | 3.0.0 | Async HTTP for AI providers |
| Google Drive API | v3 | Cloud backup |
| Play Services Auth | 21.3.0 | Google Sign-In |

### AI and Machine Learning

| Library | Version | Purpose |
|---------|---------|---------|
| Koog AI Agent | Latest | Agentic AI framework |
| Vosk | 0.3.75 | Offline speech recognition |
| Tavily API | - | Web search for AI agent |

### Media and Content

| Library | Version | Purpose |
|---------|---------|---------|
| Media3 ExoPlayer | 1.5.1 | Audio/video playback |
| Coil | 2.7.0 | Image loading |
| PDFBox Android | 2.0.27.0 | PDF text extraction |
| ZXing | 3.5.3 | QR code generation |
| YouTube Player | 12.1.1 | YouTube video embedding |
| Compose RichText | 0.17.0 | Markdown rendering |

### Firebase Suite

| Service | Purpose |
|---------|---------|
| Firebase Auth | User authentication |
| Firestore | Cloud database (optional) |
| Crashlytics | Crash reporting |
| Analytics | Usage analytics |
| Cloud Messaging | Push notifications |
| Remote Config | Feature flags |

### Background Processing

| Library | Version | Purpose |
|---------|---------|---------|
| WorkManager | 2.10.0 | Scheduled background tasks |

### Code Quality and Testing

| Tool | Version | Purpose |
|------|---------|---------|
| Detekt | 1.23.7 | Static code analysis |
| LeakCanary | 2.14 | Memory leak detection (debug) |
| MockK | 1.13.9 | Mocking framework |
| Turbine | 1.0.0 | Flow testing |
| Robolectric | 4.14.1 | Unit testing Android |
| Espresso | 3.7.0 | UI testing |

---

## Architecture

Loum follows a clean **MVVM architecture** with clear separation of concerns:

```
+-------------------------------------------------------------+
|                        UI Layer                              |
|  +-----------+  +-----------+  +---------------------+       |
|  |  Screens  |  | Components|  |    Animations       |       |
|  +-----------+  +-----------+  +---------------------+       |
+-----------------------------+-------------------------------+
                              | StateFlow/Events
+-----------------------------v-------------------------------+
|                     ViewModel Layer                          |
|  +---------------+  +------------+  +--------------+         |
|  | CogniViewModel|  | ChatManager|  |  AudioPlayer |         |
|  +---------------+  +------------+  +--------------+         |
+-----------------------------+-------------------------------+
                              |
+-----------------------------v-------------------------------+
|                      Agent Layer                             |
|  +---------------+  +----------------------------------+     |
|  |  CogniAgent   |--| Tools (Notes, Todos, Calendar)  |     |
|  +---------------+  +----------------------------------+     |
+-----------------------------+-------------------------------+
                              |
+-----------------------------v-------------------------------+
|                      Data Layer                              |
|  +----------+  +------------+  +-------------------+         |
|  |   Room   |  | AI Service |  |   API Providers   |         |
|  | (SQLite) |  |  (Remote)  |  |   (Multiple)      |         |
|  +----------+  +------------+  +-------------------+         |
+-------------------------------------------------------------+
```

### Key Patterns

- **StateFlow** for reactive state management
- **Room** with FTS5 for efficient full-text search
- **Paging 3** for efficient large list handling
- **WorkManager** for reliable background processing
- **Circuit Breaker** pattern for AI provider failover
- **Rate Limiting** for API call management
- **Koog AI Agent Framework** for agentic AI capabilities
- **Lazy Initialization** for resource-intensive components
- **SavedStateHandle** for process death resilience

---

## Getting Started

### Prerequisites

| Requirement | Version |
|-------------|---------|
| Android Studio | Ladybug or newer |
| JDK | 17 |
| Android SDK | 26+ (target 36) |
| Gradle | 8.13.2 |
| Kotlin | 2.2.21 |

### System Requirements

- **Development Machine**: 8GB RAM minimum, 16GB recommended
- **Disk Space**: ~5GB for SDK, Gradle cache, and project
- **Target Device**: Android 8.0 (API 26) or higher

---

## Installation

### 1. Clone the Repository

```bash
git clone https://github.com/YOUR-USERNAME/Smarty.git
cd Smarty
```

### 2. Download Vosk Speech Model (Required for Wake Word Detection)

The **Vosk offline speech recognition model** is required for wake word detection functionality. This model enables the app to listen for voice commands without an internet connection.

#### Why is this needed?

- **Offline Wake Word**: Detect voice activation without internet
- **Privacy**: Speech processing happens entirely on-device
- **Low Latency**: No network round-trip for wake word detection

#### Installation Steps

| Step | Command / Action |
|------|------------------|
| **Download** | [vosk-model-small-hi-0.22.zip](https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip) (~50MB) |
| **Extract** | Unzip the downloaded file |
| **Place** | Copy the extracted `vosk-model-small-hi-0.22` folder to the location below |

#### Required Folder Structure

```
Smarty/
  app/
    src/
      main/
        assets/
          vosk-model-small-hi-0.22/   <-- Place the entire folder here
            am/                        # Acoustic model
            conf/                      # Configuration files
            graph/                     # Language model graph
            ivector/                   # Speaker adaptation
            README                     # Model documentation
```

The app automatically loads the model from assets at runtime. No code changes needed.

#### How the Integration Works

1. **VoskWakeWordManager.kt** handles model loading from assets
2. Model is loaded lazily on first voice activation
3. The app uses the `com.alphacephei:vosk-android:0.3.75` library
4. Decompression happens on-demand via `LazyDecompressor.kt` for memory efficiency

#### Alternative Models

You can use other Vosk models from [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models). Just ensure:
- The model folder name matches what the app expects, OR
- Update `VoskWakeWordManager.kt` to reference your model folder name

**Note**: The model is gitignored to prevent bloating the repository. Each developer must download it separately.

### 3. Open in Android Studio

1. Open Android Studio
2. Select **File - Open**
3. Navigate to the cloned `Smarty` folder
4. Click **OK** and wait for Gradle sync

### 4. Build and Run

```bash
# Debug build
./gradlew assembleDebug

# Or use Android Studio's Run button
```

### Common Installation Issues

| Issue | Solution |
|-------|----------|
| Gradle sync fails | Ensure JDK 17 is configured in Project Structure |
| Missing SDK | Install SDK 36 via SDK Manager |
| Vosk model not found | Verify the model folder is in `app/src/main/assets/` |
| Firebase config missing | Add `google-services.json` to `app/` folder |

---

## Configuration

### Environment Variables

Create a `.env` file in the project root (already gitignored):

```env
# AI Provider Keys (at least one required)
GEMINI_API_KEY=your_gemini_key
DEEPSEEK_API_KEY=your_deepseek_key
GROQ_API_KEY=your_groq_key
OPENAI_API_KEY=your_openai_key
OPENROUTER_API_KEY=your_openrouter_key
HUGGINGFACE_API_KEY=your_huggingface_key

# Optional: Web Search
TAVILY_API_KEY=your_tavily_key
```

### In-App Configuration

API keys can also be configured directly in the app **Settings** screen:

1. Open the app
2. Tap the **Settings** icon
3. Scroll to **AI Providers**
4. Enter your API key(s)

### Firebase Setup (Optional)

1. Create a Firebase project at [console.firebase.google.com](https://console.firebase.google.com)
2. Add an Android app with package name `com.example.smarty`
3. Download `google-services.json` and place in `app/` folder
4. Enable required services: Auth, Firestore, Crashlytics

### Security Best Practices

- Never commit API keys to version control
- Use `.env` files for local development
- API keys are stored encrypted on-device
- Enable PIN protection for sensitive notes

---

## Usage

### Creating a Note

1. **Quick Input**: Tap the input field at the bottom, type or paste content
2. **Share to Loum**: Share any content from another app, select Loum
3. **Voice Input**: Tap the microphone icon for speech-to-text
4. **Widget**: Use the home screen widget for quick capture
5. **Voice Notes**: Use the voice note widget for hands-free capture
6. **Camera Notes**: Use the camera widget to capture images directly

### Chat Mode (AI Conversation)

1. **Activate**: Shake your phone or tap the chat icon
2. **Ask Questions**: "What notes do I have about cooking?"
3. **Perform Actions**: "Create a note about today's meeting"
4. **Web Search**: "Search the web for the latest news on AI"

### Private Notes

1. While typing, shake to toggle AI exclusion
2. Private notes show a lock icon
3. Private notes are invisible to chat mode
4. AI still processes categorization (locally)

### Keyboard Shortcuts

| Gesture | Action |
|---------|--------|
| Shake | Toggle chat mode |
| Shake while typing | Toggle AI exclusion |
| Long press note | Open action menu |
| Swipe left | Archive note |
| Swipe right | Delete note |

---

## AI Providers

Loum supports multiple AI providers with automatic fallback. Configure one or more in Settings.

| Provider | Model | Best For | Pricing |
|----------|-------|----------|---------|
| **Gemini** | gemini-1.5-flash | Best overall quality and speed | Free tier available |
| **DeepSeek** | deepseek-chat | Cost-effective, good quality | Very affordable |
| **Groq** | llama-3.3-70b | Ultra-fast inference | Free tier available |
| **OpenAI** | gpt-4o-mini | Reliable, well-documented | Pay per use |
| **OpenRouter** | llama-3.1-8b (free) | Access to many models | Free and paid options |
| **HuggingFace** | Mistral-7B | Good fallback option | Free tier available |

### Getting API Keys

| Provider | URL |
|----------|-----|
| Gemini | [aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey) |
| DeepSeek | [platform.deepseek.com](https://platform.deepseek.com/) |
| Groq | [console.groq.com](https://console.groq.com/) |
| OpenAI | [platform.openai.com/api-keys](https://platform.openai.com/api-keys) |
| OpenRouter | [openrouter.ai/keys](https://openrouter.ai/keys) |
| HuggingFace | [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens) |
| Tavily (Search) | [app.tavily.com](https://app.tavily.com/) |

### Fallback Order

```
1. Gemini      -> 2. DeepSeek   -> 3. Groq
       |               |              |
4. OpenAI      -> 5. OpenRouter -> 6. HuggingFace
       |
7. Local LLM   -> 8. Smart Keyword Fallback (offline)
```

If all providers fail, smart keyword-based categorization is used as a final fallback.

---

## Local LLM Server (USB/WiFi)

Run AI models on your own PC and connect your phone via USB tethering or WiFi. **Local LLM is a first-class citizen** - all features work exactly the same as cloud providers.

- **Free AI** - No API costs
- **Privacy** - Data never leaves your network
- **Speed** - Local inference can be faster than cloud
- **Offline** - Works without internet
- **Full Feature Parity** - Agent tools, categorization, chat - everything works

### Requirements

| Component | Description |
|-----------|-------------|
| **llama.cpp** | Local LLM server with OpenAI-compatible API |
| **GGUF Model** | Quantized model file (e.g., Qwen, Llama, Mistral) |
| **Caddy** (optional) | For HTTPS encryption |

### Quick Start

1. **Download llama.cpp**: Get the latest release from [llama.cpp releases](https://github.com/ggerganov/llama.cpp/releases)
2. **Download a model**: Get a GGUF model (e.g., `qwen2.5-3b-instruct-q4_k_m.gguf`)
3. **Place files** in `LLM_THROUGH_USB_THEARING/llama-b7541-bin-win-cuda-12.4-x64/`

### Folder Structure

```
LLM_THROUGH_USB_THEARING/
  llama-b7541-bin-win-cuda-12.4-x64/
    llama-server.exe              # LLM server executable
    start_local_llm.bat           # HTTP startup script
    start_local_llm_secure.bat    # HTTPS startup script (requires Caddy)
    caddy_windows_amd64.exe       # Caddy reverse proxy (optional)
    models/
      qwen2.5-3b-instruct-q4_k_m.gguf  # Your model file
```

### Connection Methods

#### Option 1: USB Tethering (Recommended for beginners)

1. Connect phone to PC via USB cable
2. Enable **USB Tethering** on your phone (Settings - Hotspot - USB Tethering)
3. Run `start_local_llm.bat` on your PC
4. Note the IP address shown (e.g., `10.166.18.183`)
5. In the app: Settings - AI Providers - Local LLM Server
6. Enter the IP and port `8000`, tap **Test and Save**

#### Option 2: WiFi (Same Network)

1. Ensure phone and PC are on the **same WiFi network**
2. Run `start_local_llm.bat` on your PC
3. Note your PC's WiFi IP (e.g., `192.168.1.100` or `10.x.x.x`)
4. In the app: Settings - AI Providers - Local LLM Server
5. Enter the IP and port `8000`, tap **Test and Save**

### HTTPS Encryption (Recommended)

For encrypted connections between your phone and PC:

#### What is Caddy?

**Caddy** is a modern web server that automatically handles HTTPS/TLS encryption. It acts as a reverse proxy:

```
Phone (HTTPS:8443) -> Caddy -> llama-server (HTTP:8000 localhost)
     ^                              ^
  Encrypted               Internal only (secure)
```

#### Setup HTTPS

1. **Download Caddy**: [caddyserver.com/download](https://caddyserver.com/download) - Windows amd64
2. **Place `caddy.exe`** in the same folder as `start_local_llm_secure.bat`
3. **Run `start_local_llm_secure.bat`** instead of the regular script
4. In the app: Enable **"Use HTTPS"** toggle
5. Set port to `8443`
6. Tap **Test and Save**

#### Why Use HTTPS?

| Without HTTPS | With HTTPS |
|---------------|------------|
| Data sent in plain text | Data encrypted with TLS |
| Can be intercepted on shared networks | Secure even on public WiFi |
| Fine for home networks | Recommended for sensitive data |

### Troubleshooting

| Issue | Solution |
|-------|----------|
| "Connection refused" | Ensure the server is running and firewall allows the port |
| "HTTP traffic not allowed" | Use private IP ranges (192.168.x.x, 10.x.x.x) or enable HTTPS |
| Phone can't find PC | Verify both are on same network; try USB tethering |
| Slow responses | Use a smaller/faster model or enable GPU acceleration |
| HTTPS certificate warning | Normal for self-signed certs; the connection is still encrypted |

### Supported IP Ranges

The app allows HTTP traffic only to private network IPs (RFC 1918):

| Range | Typical Use |
|-------|-------------|
| `10.0.0.0/8` | USB tethering, enterprise networks |
| `192.168.0.0/16` | Home WiFi routers |
| `172.16.0.0/12` | Corporate networks |

For public IPs or additional security, use HTTPS mode.

---

## Mention System

Reference your notes directly in chat using `@` mentions for context-aware AI responses.

### Syntax

| Format | Description | Example |
|--------|-------------|---------|
| `@note_title` | Reference by title (underscores = spaces) | `@shopping_list` |
| `@"note title"` | Quoted for exact match | `@"My Research Paper"` |
| `@audios` | Filter by type | `@documents`, `@images` |
| `@recent` | Last 10 notes | `@recent summarize these` |
| `@pinned` | Pinned notes | `@pinned what's important?` |

### Type Filters

| Filter | Note Type |
|--------|-----------|
| `@audios`, `@audio` | Audio files |
| `@documents`, `@docs` | PDFs, Word docs |
| `@images`, `@photos` | Images |
| `@videos` | Video files |
| `@code` | Code snippets |
| `@websites`, `@links` | Web URLs |
| `@youtube`, `@yt` | YouTube videos |

### Inline Autocomplete

When typing `@` in the chat input, a dropdown appears showing matching notes. Features:
- **Live search** - Results filter as you type
- **Type icons** - Visual indicators for note types (document, image, audio, etc.)
- **Quick selection** - Tap to insert the full mention

### Examples

```
@"Project Proposal" what's the timeline?
@documents find contracts mentioning payment terms
@recent summarize my notes from today
@pinned remind me what I marked as important
@images show me photos from vacation
```

---

## Deep Document Analysis

When you have a note with a document attached (PDF, etc.), use `@thinking` to have the AI read the **entire document** - not just the summary.

### How It Works

1. Normal mode: AI sees note title + summary (~500 chars)
2. `@thinking` mode: AI reads **full document content** in chunks

### Usage

```
@thinking @"Research Paper" what are the main findings?
@thinking @"Contract" list all the terms and conditions
@thinking @recent analyze these documents in depth
```

### Aliases

| Command | Description |
|---------|-------------|
| `@thinking` | Deep document analysis |
| `@think` | Alias for @thinking |
| `@analyze` | Alias for @thinking |

### When to Use

| Scenario | Use |
|----------|-----|
| Quick question about a note | Regular `@mention` |
| Need details from a 50-page PDF | `@thinking @note` |
| Comparing multiple documents | `@thinking @documents` |
| Finding specific info in long docs | `@thinking @"Doc Name" find X` |

---

## Web Search Integration

Loum's AI agent can search the web in real-time to answer questions beyond your personal notes, powered by the **Tavily API**.

### What is Web Search?

When the AI agent doesn't have enough information in your notes to answer a question, it can automatically search the web and provide answers with **proper citations**. This makes Loum not just a second brain for your saved content, but also a gateway to current information from the internet.

### Setup

#### 1. Get a Tavily API Key

| Step | Action |
|------|--------|
| **Sign Up** | Visit [app.tavily.com](https://app.tavily.com/) and create a free account |
| **Get API Key** | Navigate to your dashboard and copy your API key |
| **Free Tier** | Tavily offers a generous free tier for personal use |

#### 2. Configure in Loum

**Option A: Environment Variable** (Recommended for developers)

Create/edit `.env` file in project root:

```env
TAVILY_API_KEY=your_tavily_api_key_here
```

**Option B: In-App Settings**

1. Open Loum - Tap **Settings**
2. Scroll to **AI Providers** section
3. Find **Tavily API Key** field
4. Paste your API key
5. Tap **Save**

### How to Use

#### Automatic Web Search

The AI agent automatically decides when to search the web based on your query:

```
"What's the latest news on AI advancements?"
"Who won the 2024 Nobel Prize in Physics?"
"What's the weather like in Tokyo today?"
"Explain quantum computing in simple terms"
```

#### Explicit Web Search Commands

Force a web search using these commands:

| Command | Example |
|---------|---------|
| `search web for...` | `search web for best Android development practices` |
| `web search:` | `web search: latest Kotlin updates` |
| `look up...` | `look up the capital of Iceland` |
| `find online...` | `find online tutorials for Jetpack Compose` |

### Features

| Feature | Description |
|---------|-------------|
| **Real-Time Results** | Get current information from the web |
| **Citation Support** | All web results include source URLs |
| **Context-Aware** | AI combines web results with your notes when relevant |
| **Automatic Fallback** | If Tavily fails, AI uses knowledge from your notes |
| **Privacy-First** | Your notes are never sent to Tavily, only search queries |

### When Web Search is Used

| Scenario | Web Search? |
|----------|-------------|
| Question about your saved notes | No - Uses local notes |
| Current events or news | Yes - Searches web |
| General knowledge not in notes | Yes - Searches web |
| Combining personal + public info | Yes - Uses both |
| Private notes (locked) | No - Never searches web |

### Privacy and Security

- **Your notes stay private**: Only search queries are sent to Tavily, never your note content
- **No tracking**: Tavily doesn't track your search history across sessions
- **Optional feature**: Web search is completely optional; the app works fine without it
- **Respects private notes**: Notes marked as private are never included in any web search context

### Troubleshooting

| Issue | Solution |
|-------|----------|
| "Web search unavailable" | Verify your Tavily API key is correctly configured |
| No citations in results | Check your internet connection; Tavily requires network access |
| Rate limit errors | You've exceeded Tavily's free tier limits; wait or upgrade plan |
| Slow responses | Web search adds latency; this is normal for real-time web queries |

### API Limits

Tavily's free tier includes:

- **1,000 searches/month** for personal use
- **Rate limit**: Reasonable usage (no hard limit for free tier)
- **Upgrade**: Paid plans available for higher usage

For current pricing and limits, visit [tavily.com/pricing](https://tavily.com/pricing).

---

## Testing

### Running Tests

```bash
# Unit tests
./gradlew test

# Android instrumented tests
./gradlew connectedAndroidTest

# All tests with coverage
./gradlew testDebugUnitTest createDebugCoverageReport
```

### Test Structure

```
app/src/test/java/com/example/smarty/
  AIResponseParserTest.kt        # AI response parsing
  ContentSecurityFilterTest.kt   # Security filter validation
  PrivacyGuardTest.kt            # Privacy feature tests
  ExampleUnitTest.kt             # Basic unit tests
  agent/                         # AI agent tool tests
    ...
```

### Writing New Tests

1. Unit tests go in `app/src/test/java/`
2. Use MockK for mocking dependencies
3. Use Turbine for Flow testing
4. Use Robolectric for Android-specific unit tests
5. Instrumented tests go in `app/src/androidTest/java/`

### Code Quality

```bash
# Run Detekt static analysis
./gradlew detekt

# Check for lint issues
./gradlew lint
```

---

## Project Structure

```
app/
  src/main/java/com/example/smarty/
    CogniApplication.kt          # Application class
    MainActivity.kt              # Main entry point
    
    agent/                       # AI Agent (Koog framework)
      CogniAgent.kt              # Main agent wrapper
      CogniAgentProvider.kt      # Provider management
      execution/                 # Agent execution logic
      prompts/                   # System prompts and examples
      reflexion/                 # Self-reflection logic
      tools/                     # Agent tools
        notes/                   # Note CRUD tools
        todos/                   # Todo management
        calendar/                # Calendar integration
        research/                # Web search (Tavily)
        external/                # External integrations
    
    calendar/                    # Calendar feature
    
    data/                        # Data layer
      backup/                    # Backup logic
      cache/                     # Caching strategies
      local/                     # Room database and DAOs
      model/                     # Data models
      remote/                    # AI providers and APIs
      repository/                # Repository pattern
      worker/                    # Background workers
    
    navigation/                  # Navigation graph
    
    service/                     # Android services
      AudioPlayerService.kt      # Media playback
      FileOperationService.kt    # File operations
      AlarmReceiver.kt           # Alarm handling
      LocalCommandProcessor.kt   # Offline command processing
      ScreenCaptureService.kt    # Screenshot functionality
    
    ui/                          # UI components
      animation/                 # Animation helpers
      components/                # Reusable components
      screens/                   # App screens
      theme/                     # Material theme
      utils/                     # UI utilities
    
    util/                        # Core utilities
      api/                       # API utilities
      retry/                     # Retry strategies
      search/                    # Search utilities
      toon/                      # TOON serialization
      ContentTypeDetector.kt     # MIME type detection
      PrivacyGuard.kt            # Privacy filtering
      PIIMasker.kt               # PII detection
      ShakeDetector.kt           # Shake gesture
      ...
    
    viewmodel/                   # ViewModels
      CogniViewModel.kt          # Main ViewModel
      AudioPlayerViewModel.kt    # Audio playback
      ChatManager.kt             # Chat logic
      ...
    
    voice/                       # Voice features
      VoskWakeWordManager.kt     # Wake word detection
      VoiceNoteRecorder.kt       # Voice recording
      speaker/                   # Speaker ID
    
    widget/                      # Home screen widget
    
    worker/                      # WorkManager workers
  
  res/                           # Android resources
    drawable/                    # Vector assets
    mipmap-*/                    # App icons
    values/                      # Colors, strings, themes
    xml/                         # Widget config, backup rules
  
  AndroidManifest.xml            # App manifest
```

---

## Design System

### Color Palette

| Color | Light Mode | Dark Mode | Usage |
|-------|------------|-----------|-------|
| **Primary** | #FF6B00 (Bright Orange) | #CCFF00 (Acid Green) | Buttons, highlights |
| **Background** | #FFFFFF | #0D0C11 | Screen background |
| **Surface** | #F5F5F5 | #181822 | Cards, dialogs |
| **Secondary** | #BB86FC | #BB86FC | Neon Purple accents |
| **Error** | #FF4D00 | #FF4D00 | Safety Orange |
| **Audio Accent** | #FF2D55 | #FF2D55 | Apple Pink |

### Typography

| Style | Font | Scale |
|-------|------|-------|
| Headlines | System Default | Based on phi (1.618) |
| Body | System Default | Base 16sp |
| Input | Monospace | Fixed width |
| Code | Monospace | Syntax highlighting |

### Spacing System

Based on **Fibonacci sequence** for harmonious proportions:

```
2dp  ->  4dp  ->  8dp  ->  13dp  ->  16dp  ->  21dp  ->  34dp  ->  55dp
```

| Token | Value | Usage |
|-------|-------|-------|
| `xs` | 4dp | Tight spacing |
| `sm` | 8dp | Component internal |
| `md` | 16dp | Standard padding |
| `lg` | 21dp | Card padding |
| `xl` | 34dp | Section spacing |
| `corner` | 18dp | Card corner radius |

---

## Permissions

| Permission | Purpose | Required |
|------------|---------|----------|
| `INTERNET` | AI API calls, content fetching | Yes |
| `ACCESS_NETWORK_STATE` | Network availability checks | Yes |
| `FOREGROUND_SERVICE` | Background audio playback | Yes |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Media playback notification | Yes |
| `FOREGROUND_SERVICE_DATA_SYNC` | Background file operations | Yes |
| `RECORD_AUDIO` | Voice input, wake word detection | Optional |
| `VIBRATE` | Haptic feedback | Optional |
| `POST_NOTIFICATIONS` | Reminders, daily digest (Android 13+) | Optional |
| `READ_MEDIA_*` | Access media files (Android 13+) | Optional |
| `SCHEDULE_EXACT_ALARM` | Timer/alarm functionality | Optional |

---

## Security

### Data Protection

- **Encrypted Storage**: API keys and PIN stored using Android Keystore
- **No Plain Text Secrets**: All sensitive data encrypted at rest
- **PII Detection**: Automatic masking of personal information
- **Content Filtering**: XSS and injection prevention

### Privacy Features

- **Private Notes**: Excluded from all AI processing
- **On-Device Processing**: Core features work offline
- **No Tracking**: No third-party analytics without consent
- **Secure Backup**: Encrypted backups to Google Drive

### Security Checklist

- API keys never logged or exposed
- Network traffic uses HTTPS only
- No hardcoded credentials
- ProGuard/R8 minification enabled
- Backup data encrypted
- Input validation on all user input

---

## Contributing

We welcome contributions! Please follow these guidelines:

### Getting Started

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Commit your changes: `git commit -m 'Add amazing feature'`
4. Push to the branch: `git push origin feature/amazing-feature`
5. Open a Pull Request

### Coding Standards

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful commit messages
- Write tests for new features
- Run `./gradlew detekt` before submitting
- Keep PRs focused and atomic

### Branch Naming

| Type | Pattern | Example |
|------|---------|---------|
| Feature | `feature/description` | `feature/voice-notes` |
| Bugfix | `fix/description` | `fix/audio-crash` |
| Refactor | `refactor/description` | `refactor/viewmodel` |
| Docs | `docs/description` | `docs/readme-update` |

### Pull Request Process

1. Update the README.md with details of changes if applicable
2. Update the CHANGELOG with your changes
3. Ensure all tests pass
4. Request review from maintainers

### Issue Reporting

When reporting issues, please include:

- Device model and Android version
- App version
- Steps to reproduce
- Expected vs actual behavior
- Relevant logs (with sensitive data redacted)

---

## License

This project is licensed under the **Creative Commons Attribution 4.0 International License (CC BY 4.0)**.

### You are free to:

- **Share** - copy and redistribute the material in any medium or format
- **Adapt** - remix, transform, and build upon the material for any purpose

### Under the following terms:

- **Attribution** - You must give appropriate credit, provide a link to the license, and indicate if changes were made.

### Attribution Requirements

When using this software, include the following in your project:

```
This project uses code from Loum/Smarty Project
(https://github.com/YOUR-USERNAME/Smarty)
Licensed under CC BY 4.0
```

For the full license text, see [LICENSE](LICENSE) or visit:
[creativecommons.org/licenses/by/4.0](https://creativecommons.org/licenses/by/4.0/)

---

## Contact

- **Project**: [github.com/YOUR-USERNAME/Smarty](https://github.com/YOUR-USERNAME/Smarty)
- **Issues**: [github.com/YOUR-USERNAME/Smarty/issues](https://github.com/YOUR-USERNAME/Smarty/issues)

---

## Changelog

### Version 1.2.0 (Current)

**Features**
- **AI Assistant Overlay** - Voice-activated assistant with transparent background
- **Screenshot Save** - Capture any screen with AI-generated titles based on your description
- **Inline Image Viewing** - View images from notes directly in chat responses
- **@ Mentions in Chat** - Reference notes, filter by type (@documents, @images, @recent)
- **Get Recent Notes Tool** - AI can show your most recent notes
- **Smart Category Fallback** - Keyword-based categorization when AI fails (Todo, Idea, Learn, etc.)

**Improvements**
- Local LLM is now a first-class citizen with full feature parity
- Screenshot notes auto-tagged with app name and keywords
- Screenshots categorized into "Screenshots" stack
- AI title generation with strict word limits (2-4 words)
- Better phrase cleanup for screenshot titles
- Completion sounds when notecards finish processing

### Version 1.1.0

**Features**
- AI Agent with tool calling (Koog framework)
- Calendar screen for date-based organization
- Timer and alarm functionality
- Daily digest notifications
- Home screen widget
- Wake word detection (Vosk)
- Enhanced privacy with PII masking and voice fingerprinting
- Version history tracking for notes
- Batch operations for processing multiple notes
- Advanced search with history
- Calendar event integration

**Improvements**
- Performance optimizations for large note collections
- Better error handling and retry logic
- Circuit breaker pattern for AI providers
- FTS5 full-text search
- Voice enrollment for personalized wake word detection

### Version 1.0.0

**Initial Release**
- Multi-provider AI support (6 providers)
- Automatic categorization and summarization
- Chat mode with shake gesture
- Native audio player with waveform
- Google Drive backup
- PIN protection
- Private notes
- Material 3 design

---

<p align="center">
  <strong>Built with Kotlin, Jetpack Compose, and AI</strong>
</p>

<p align="center">
  <em>Last updated: January 2026</em>
</p>
