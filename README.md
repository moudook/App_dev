<p align="center">
  <img src="Cogni_Icon.svg" width="120" height="120" alt="Cogni Logo">
</p>

<h1 align="center">Cogni</h1>

<p align="center">
  <strong>Your AI-Powered Second Brain for Android</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-1.1.0-blue?style=flat-square" alt="Version">
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

##  Table of Contents

- [Overview](#-overview)
- [Features](#-features)
- [Technologies Used](#-technologies-used)
- [Architecture](#-architecture)
- [Getting Started](#-getting-started)
- [Installation](#-installation)
- [Configuration](#-configuration)
- [Usage](#-usage)
- [AI Providers](#-ai-providers)
- [Testing](#-testing)
- [Project Structure](#-project-structure)
- [Design System](#-design-system)
- [Permissions](#-permissions)
- [Security](#-security)
- [Contributing](#-contributing)
- [License](#-license)
- [Contact](#-contact)
- [Changelog](#-changelog)

---

##  Overview

Cogni is designed to be your **second brain** — a place where you can dump any content (links, images, documents, voice notes, thoughts) and have AI automatically categorize, summarize, and make it searchable through natural conversation. The app runs entirely on-device with optional cloud AI integration, ensuring your private notes stay private.

### Why Cogni?

- **Zero-friction capture**: Share anything from any app, and Cogni handles the rest
- **AI-powered intelligence**: Automatic categorization, smart summaries, and conversational search
- **Privacy-first design**: Private notes are completely invisible to AI processing
- **Multi-provider support**: Choose from 6 AI providers with automatic failover
- **Offline-capable**: Core functionality works without internet; AI features gracefully degrade

---

##  Features

###  AI-Powered Intelligence

| Feature | Description |
|---------|-------------|
| **Automatic Categorization** | AI analyzes your content and assigns it to the most relevant category |
| **Smart Summaries** | Get concise AI-generated summaries of your notes |
| **"Why You Saved This"** | AI explains the potential value of saved content |
| **Chat Mode** | Ask questions about your notes in natural language (shake to activate) |
| **Multi-Provider Support** | Choose from 6 AI providers with automatic fallback |
| **Automatic Fallback** | If one provider fails, automatically tries the next available provider |
| **Private Notes** | Option to exclude specific notes from AI chat context |
| **Agentic Tool Calling** | AI can perform actions like creating notes, managing todos, web search |

###  Supported Content Types

| Type | Description | Auto-Detection |
|------|-------------|----------------|
| Brain Dump | Quick text notes and thoughts |  |
| YouTube | Video links with metadata extraction |  |
| Twitter/X | Tweet links and content |  |
| Instagram | Instagram post links |  |
| Website | Any web URL with content analysis |  |
| Image | Photos and images |  |
| Document | PDFs, Word docs, text files |  |
| Spreadsheet | Excel, CSV files |  |
| Presentation | PowerPoint, slides |  |
| Video | Video files |  |
| Audio | Audio files with native playback |  |
| Code | Code snippets and files |  |
| Archive | ZIP, RAR, and compressed files |  |
| APK | Android application packages |  |
| File | Any other file type |  |

###  Native Audio Player

- **Mini Player**: Collapsible bottom bar showing current track
- **Full Player**: Expanded view with waveform visualization
- **Background Playback**: Continue listening while using other apps
- **Seek Support**: Tap on waveform to jump to any position
- **Media Controls**: Play/pause, skip forward/back 10 seconds
- **Media Session Integration**: Control from notification & lock screen

###  Organization

- **Categories (Stacks)**: Organize notes into customizable categories
  - Default: Learn, Read, Watch, Idea, Todo, Buy, Meet, Code, Quote, Inspo, Recipe, Health, Finance, Work, Play, Note
- **Archive**: Soft-delete notes to archive for later retrieval
- **Todos**: Add checklist items within any note
- **Fast Scroll**: Alphabetic fast scroller for quick navigation
- **Pin Notes**: Pin important notes to the top
- **Reminders**: Set time-based reminders on any note
- **Full-Text Search**: FTS5-powered instant search across all content
- **Bulk Operations**: Select and archive/delete multiple notes

###  Security

- **PIN Protection**: Optional PIN lock for app access
- **Encrypted Storage**: All sensitive data (API keys, PIN) stored using encrypted preferences
- **Private Notes**: Exclude sensitive notes from AI context
- **PII Masking**: Automatic detection and masking of personal information
- **Content Security Filter**: Validates and sanitizes all content

###  Backup & Sync

- **Google Drive Backup**: Securely backup all notes to your Google Drive
- **Auto-Backup**: Schedule automatic backups (configurable interval)
- **Restore**: Restore notes from any previous backup
- **Firebase Integration**: Optional cloud features with Firebase

###  Share Integration

- **Share Target**: Accept shared content from any app
- **QR Code Sharing**: Share category links via QR codes
- **Deep Link Support**: Open specific notes or categories via `cogni://` URLs
- **Home Screen Widget**: Quick note capture without opening the app

###  UI/UX

- **Material 3 Design**: Modern Material You design language
- **Dark/Light Theme**: Animated smooth transitions between themes
- **OLED Optimized**: Pure black dark theme for OLED displays
- **Golden Ratio Spacing**: Harmonious spacing system based on Fibonacci sequence
- **Shake Gestures**: Shake to toggle chat mode or AI exclusion while typing
- **Haptic Feedback**: Tactile feedback for interactions
- **Smooth Animations**: Choreographed animations for delightful UX

###  Voice Features

- **Voice-to-Text**: Native speech recognition for note input
- **Wake Word Detection**: Offline wake word detection with Vosk
- **Text-to-Speech**: AI responses can be read aloud
- **Speaker Identification**: Voice enrollment for personalized experience

###  Calendar Integration

- **Calendar Screen**: View notes organized by date
- **Timer/Alarm**: Set timers and alarms from the app
- **Daily Digest**: Morning notification summarizing yesterday's notes

---

##  Technologies Used

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
| **Navigation** | Jetpack Navigation Compose 2.8.5 |
| **Database** | Room 2.7.2 (SQLite with FTS5) |
| **DI Pattern** | Manual singleton injection |

### Data & Storage

| Library | Version | Purpose |
|---------|---------|---------|
| Room | 2.7.2 | Local SQLite database with paging |
| DataStore | 1.1.1 | Preferences storage |
| EncryptedPrefs | 1.1.1 | Secure encrypted storage |
| Gson | 2.11.0 | JSON serialization |
| Paging 3 | 3.3.5 | Large list pagination |

### Networking & APIs

| Library | Version | Purpose |
|---------|---------|---------|
| OkHttp | 4.12.0 | HTTP client |
| Ktor Client | 3.0.0 | Async HTTP for AI providers |
| Google Drive API | v3 | Cloud backup |
| Play Services Auth | 21.3.0 | Google Sign-In |

### AI & Machine Learning

| Library | Version | Purpose |
|---------|---------|---------|
| Koog AI Agent | 0.5.4 | Agentic AI framework |
| Vosk | 0.3.75 | Offline speech recognition |
| Tavily API | - | Web search for AI agent |

### Media & Content

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

### Code Quality & Testing

| Tool | Version | Purpose |
|------|---------|---------|
| Detekt | 1.23.7 | Static code analysis |
| LeakCanary | 2.14 | Memory leak detection (debug) |
| MockK | 1.13.9 | Mocking framework |
| Turbine | 1.0.0 | Flow testing |
| Robolectric | 4.14.1 | Unit testing Android |
| Espresso | 3.7.0 | UI testing |

---

##  Architecture

Cogni follows a clean **MVVM architecture** with clear separation of concerns:

```
┌─────────────────────────────────────────────────────────────┐
│                        UI Layer                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │   Screens   │  │ Components  │  │    Animations       │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└─────────────────────────────┬───────────────────────────────┘
                              │ StateFlow/Events
┌─────────────────────────────▼───────────────────────────────┐
│                     ViewModel Layer                          │
│  ┌─────────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │  CogniViewModel │  │ ChatManager  │  │  AudioPlayer   │  │
│  └─────────────────┘  └──────────────┘  └────────────────┘  │
└─────────────────────────────┬───────────────────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────┐
│                      Agent Layer                             │
│  ┌─────────────────┐  ┌──────────────────────────────────┐  │
│  │   CogniAgent    │──│  Tools (Notes, Todos, Calendar)  │  │
│  └─────────────────┘  └──────────────────────────────────┘  │
└─────────────────────────────┬───────────────────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────┐
│                      Data Layer                              │
│  ┌────────────┐  ┌──────────────┐  ┌─────────────────────┐  │
│  │    Room    │  │  AI Service  │  │   API Providers     │  │
│  │   (SQLite) │  │   (Remote)   │  │   (Gemini, etc.)    │  │
│  └────────────┘  └──────────────┘  └─────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Key Patterns

- **StateFlow** for reactive state management
- **Room** with FTS5 for efficient full-text search
- **Paging 3** for efficient large list handling
- **WorkManager** for reliable background processing
- **Circuit Breaker** pattern for AI provider failover
- **Rate Limiting** for API call management

---

##  Getting Started

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

##  Installation

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
└── app/
    └── src/
        └── main/
            └── assets/
                └── vosk-model-small-hi-0.22/   ← Place the entire folder here
                    ├── am/                      # Acoustic model
                    ├── conf/                    # Configuration files
                    ├── graph/                   # Language model graph
                    ├── ivector/                 # Speaker adaptation
                    └── README                   # Model documentation
```

> **That's it!** The app automatically loads the model from assets at runtime. No code changes needed.

#### How the Integration Works

1. **VoskWakeWordManager.kt** handles model loading from assets
2. Model is loaded lazily on first voice activation
3. The app uses the `com.alphacephei:vosk-android:0.3.75` library
4. Decompression happens on-demand via `LazyDecompressor.kt` for memory efficiency

#### Alternative Models

You can use other Vosk models from [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models). Just ensure:
- The model folder name matches what the app expects, OR
- Update `VoskWakeWordManager.kt` to reference your model folder name

> **Note**: The model is gitignored to prevent bloating the repository. Each developer must download it separately.

### 3. Open in Android Studio

1. Open Android Studio
2. Select **File → Open**
3. Navigate to the cloned `Smarty` folder
4. Click **OK** and wait for Gradle sync

### 4. Build and Run

```bash
# Debug build
./gradlew assembleDebug

# Or use Android Studio's Run button (▶)
```

### Common Installation Issues

| Issue | Solution |
|-------|----------|
| Gradle sync fails | Ensure JDK 17 is configured in Project Structure |
| Missing SDK | Install SDK 36 via SDK Manager |
| Vosk model not found | Verify the model folder is in `app/src/main/assets/` |
| Firebase config missing | Add `google-services.json` to `app/` folder |

---

##  Configuration

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
2. Tap the **Settings** icon ()
3. Scroll to **AI Providers**
4. Enter your API key(s)

### Firebase Setup (Optional)

1. Create a Firebase project at [console.firebase.google.com](https://console.firebase.google.com)
2. Add an Android app with package name `com.example.smarty`
3. Download `google-services.json` and place in `app/` folder
4. Enable required services: Auth, Firestore, Crashlytics

### Security Best Practices

-  Never commit API keys to version control
-  Use `.env` files for local development
-  API keys are stored encrypted on-device
-  Enable PIN protection for sensitive notes

---

##  Usage

### Creating a Note

1. **Quick Input**: Tap the input field at the bottom, type or paste content
2. **Share to Cogni**: Share any content from another app → Select Cogni
3. **Voice Input**: Tap the microphone icon for speech-to-text
4. **Widget**: Use the home screen widget for quick capture

### Chat Mode (AI Conversation)

1. **Activate**: Shake your phone or tap the chat icon
2. **Ask Questions**: "What notes do I have about cooking?"
3. **Perform Actions**: "Create a note about today's meeting"
4. **Web Search**: "Search the web for the latest news on AI"

### AI Agent Capabilities

| Command | Action |
|---------|--------|
| "Create a note about..." | Creates a new note |
| "Find notes about..." | Searches your notes |
| "Archive the note about..." | Archives matching notes |
| "Add todo: ..." | Adds a todo item |
| "Search web for..." | Performs web search (requires Tavily) |
| "Play the audio in..." | Plays audio attachments |

### Private Notes

1. While typing, shake to toggle AI exclusion
2. Private notes show a  icon
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

##  AI Providers

Cogni supports multiple AI providers with automatic fallback. Configure one or more in Settings.

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
1. Gemini      → 2. DeepSeek   → 3. Groq
       ↓               ↓              ↓
4. OpenAI      → 5. OpenRouter → 6. HuggingFace
       ↓
7. Smart Keyword Fallback (offline)
```

If all providers fail, smart keyword-based categorization is used as a final fallback.

---

##  Testing

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
├── AIResponseParserTest.kt        # AI response parsing
├── ContentSecurityFilterTest.kt   # Security filter validation
├── PrivacyGuardTest.kt            # Privacy feature tests
├── ExampleUnitTest.kt             # Basic unit tests
└── agent/                         # AI agent tool tests
    └── ...
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

##  Project Structure

```
app/
├── src/main/java/com/example/smarty/
│   ├── CogniApplication.kt          # Application class
│   ├── MainActivity.kt              # Main entry point
│   │
│   ├── agent/                        # AI Agent (Koog framework)
│   │   ├── CogniAgent.kt             # Main agent wrapper
│   │   ├── CogniAgentProvider.kt     # Provider management
│   │   ├── execution/                # Agent execution logic
│   │   ├── prompts/                  # System prompts & examples
│   │   ├── reflexion/                # Self-reflection logic
│   │   └── tools/                    # Agent tools
│   │       ├── notes/                # Note CRUD tools
│   │       ├── todos/                # Todo management
│   │       ├── calendar/             # Calendar integration
│   │       ├── research/             # Web search (Tavily)
│   │       └── external/             # External integrations
│   │
│   ├── calendar/                     # Calendar feature
│   │
│   ├── data/                         # Data layer
│   │   ├── backup/                   # Backup logic
│   │   ├── cache/                    # Caching strategies
│   │   ├── local/                    # Room database & DAOs
│   │   ├── model/                    # Data models
│   │   ├── remote/                   # AI providers & APIs
│   │   ├── repository/               # Repository pattern
│   │   └── worker/                   # Background workers
│   │
│   ├── navigation/                   # Navigation graph
│   │
│   ├── service/                      # Android services
│   │   ├── AudioPlayerService.kt     # Media playback
│   │   ├── FileOperationService.kt   # File operations
│   │   └── AlarmReceiver.kt          # Alarm handling
│   │
│   ├── ui/                           # UI components
│   │   ├── animation/                # Animation helpers
│   │   ├── components/               # Reusable components
│   │   ├── screens/                  # App screens
│   │   ├── theme/                    # Material theme
│   │   └── utils/                    # UI utilities
│   │
│   ├── util/                         # Core utilities
│   │   ├── api/                      # API utilities
│   │   ├── retry/                    # Retry strategies
│   │   ├── search/                   # Search utilities
│   │   ├── toon/                     # TOON serialization
│   │   ├── ContentTypeDetector.kt    # MIME type detection
│   │   ├── PrivacyGuard.kt           # Privacy filtering
│   │   ├── PIIMasker.kt              # PII detection
│   │   ├── ShakeDetector.kt          # Shake gesture
│   │   └── ...
│   │
│   ├── viewmodel/                    # ViewModels
│   │   ├── CogniViewModel.kt         # Main ViewModel
│   │   ├── AudioPlayerViewModel.kt   # Audio playback
│   │   ├── ChatManager.kt            # Chat logic
│   │   └── ...
│   │
│   ├── voice/                        # Voice features
│   │   ├── VoskWakeWordManager.kt    # Wake word detection
│   │   ├── VoiceNoteRecorder.kt      # Voice recording
│   │   └── speaker/                  # Speaker ID
│   │
│   ├── widget/                       # Home screen widget
│   │
│   └── worker/                       # WorkManager workers
│
├── res/                              # Android resources
│   ├── drawable/                     # Vector assets
│   ├── mipmap-*/                     # App icons
│   ├── values/                       # Colors, strings, themes
│   └── xml/                          # Widget config, backup rules
│
└── AndroidManifest.xml               # App manifest
```

---

##  Design System

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
| Headlines | System Default | Based on φ (1.618) |
| Body | System Default | Base 16sp |
| Input | Monospace | Fixed width |
| Code | Monospace | Syntax highlighting |

### Spacing System

Based on **Fibonacci sequence** for harmonious proportions:

```
2dp  →  4dp  →  8dp  →  13dp  →  16dp  →  21dp  →  34dp  →  55dp
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

##  Permissions

| Permission | Purpose | Required |
|------------|---------|----------|
| `INTERNET` | AI API calls, content fetching |  |
| `ACCESS_NETWORK_STATE` | Network availability checks |  |
| `FOREGROUND_SERVICE` | Background audio playback |  |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Media playback notification |  |
| `FOREGROUND_SERVICE_DATA_SYNC` | Background file operations |  |
| `RECORD_AUDIO` | Voice input, wake word detection | Optional |
| `VIBRATE` | Haptic feedback | Optional |
| `POST_NOTIFICATIONS` | Reminders, daily digest (Android 13+) | Optional |
| `READ_MEDIA_*` | Access media files (Android 13+) | Optional |
| `SCHEDULE_EXACT_ALARM` | Timer/alarm functionality | Optional |

---

##  Security

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

-  API keys never logged or exposed
-  Network traffic uses HTTPS only
-  No hardcoded credentials
-  ProGuard/R8 minification enabled
-  Backup data encrypted
-  Input validation on all user input

---

##  Contributing

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

##  License

This project is licensed under the **Creative Commons Attribution 4.0 International License (CC BY 4.0)**.

### You are free to:

- **Share** — copy and redistribute the material in any medium or format
- **Adapt** — remix, transform, and build upon the material for any purpose

### Under the following terms:

- **Attribution** — You must give appropriate credit, provide a link to the license, and indicate if changes were made.

### Attribution Requirements

When using this software, include the following in your project:

```
This project uses code from Cogni/Smarty Project
(https://github.com/YOUR-USERNAME/Smarty)
Licensed under CC BY 4.0
```

For the full license text, see [LICENSE](LICENSE) or visit:
[creativecommons.org/licenses/by/4.0](https://creativecommons.org/licenses/by/4.0/)

---

##  Contact

- **Project**: [github.com/YOUR-USERNAME/Smarty](https://github.com/YOUR-USERNAME/Smarty)
- **Issues**: [github.com/YOUR-USERNAME/Smarty/issues](https://github.com/YOUR-USERNAME/Smarty/issues)

---

##  Changelog

### Version 1.1.0 (Current)

**Features**
-  AI Agent with tool calling (Koog framework)
-  Calendar screen for date-based organization
-  Timer and alarm functionality
-  Daily digest notifications
-  Home screen widget
-  Wake word detection (Vosk)
-  Text-to-speech for AI responses
-  Enhanced privacy with PII masking

**Improvements**
- Performance optimizations for large note collections
- Better error handling and retry logic
- Circuit breaker pattern for AI providers
- FTS5 full-text search

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
  <strong>Built with  using Kotlin, Jetpack Compose, and AI</strong>
</p>

<p align="center">
  <em>Last updated: December 26, 2024</em>
</p>
