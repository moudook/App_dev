# Cogni

An intelligent, AI-powered note-taking app for Android that automatically organizes, summarizes, and helps you interact with your knowledge.

## Overview

Cogni is designed to be your second brain - a place where you can dump any content (links, images, documents, voice notes, thoughts) and have AI automatically categorize, summarize, and make it searchable through natural conversation.

## Features

### AI-Powered Intelligence
- **Automatic Categorization**: AI analyzes your content and assigns it to the most relevant category
- **Smart Summaries**: Get concise AI-generated summaries of your notes
- **"Why You Saved This"**: AI explains the potential value of saved content
- **Chat Mode**: Ask questions about your notes in natural language (shake to activate)
- **Multi-Provider Support**: Choose from 6 AI providers with automatic fallback
- **Automatic Fallback**: If one provider fails, automatically tries the next available provider
- **Private Notes**: Option to exclude specific notes from AI chat context while still getting categorization

### Supported Content Types
| Type | Description |
|------|-------------|
| Brain Dump | Quick text notes and thoughts |
| YouTube | Video links with metadata extraction |
| Twitter/X | Tweet links and content |
| Instagram | Instagram post links |
| Website | Any web URL with content analysis |
| Image | Photos and images |
| Document | PDFs, Word docs, text files |
| Spreadsheet | Excel, CSV files |
| Presentation | PowerPoint, slides |
| Video | Video files |
| Audio | Audio files with native playback |
| Code | Code snippets and files |
| Archive | ZIP, RAR, and compressed files |
| APK | Android application packages |
| File | Any other file type |

### Native Audio Player
- **Mini Player**: Collapsible bottom bar showing current track
- **Full Player**: Expanded view with waveform visualization
- **Background Playback**: Continue listening while using other apps
- **Seek Support**: Tap on waveform to jump to any position
- **Media Controls**: Play/pause, skip forward/back 10 seconds

### Organization
- **Categories (Stacks)**: Organize notes into customizable categories
  - Default categories: Learn, Read, Watch, Idea, Todo, Buy, Meet, Code, Quote, Inspo, Recipe, Health, Finance, Work, Play, Note
- **Archive**: Soft-delete notes to archive for later retrieval
- **Todos**: Add checklist items within any note
- **Fast Scroll**: Alphabetic fast scroller for quick navigation

### Security
- **PIN Protection**: Optional PIN lock for app access
- **Encrypted Storage**: All sensitive data (API keys, PIN) stored using encrypted preferences
- **Private Notes**: Exclude sensitive notes from AI context

### Backup & Sync
- **Google Drive Backup**: Securely backup all notes to your Google Drive
- **Auto-Backup**: Schedule automatic backups (configurable interval)
- **Restore**: Restore notes from any previous backup

### Share Integration
- **Share Target**: Accept shared content from any app
- **QR Code Sharing**: Share category links via QR codes
- **Deep Link Support**: Open specific notes or categories via links

### UI/UX
- **Material 3 Design**: Modern Material You design language
- **Dark/Light Theme**: Animated smooth transitions between themes
- **OLED Optimized**: Pure black dark theme for OLED displays
- **Golden Ratio Spacing**: Harmonious spacing system based on Fibonacci sequence
- **Shake Gestures**: Shake to toggle chat mode or AI exclusion while typing
- **Haptic Feedback**: Tactile feedback for interactions

## Tech Stack

### Core
| Technology | Purpose |
|------------|---------|
| **Kotlin** | Primary programming language |
| **Jetpack Compose** | Modern declarative UI toolkit |
| **Material 3** | Design system and components |
| **Kotlin Coroutines** | Asynchronous programming |
| **StateFlow** | Reactive state management |

### Architecture
| Component | Technology |
|-----------|------------|
| **UI Layer** | Jetpack Compose with MVVM pattern |
| **ViewModel** | AndroidX ViewModel with StateFlow |
| **Navigation** | Jetpack Navigation Compose |
| **Database** | Room (SQLite with type-safe queries) |
| **Dependency Injection** | Manual DI with singleton pattern |

### Data & Storage
| Library | Version | Purpose |
|---------|---------|---------|
| Room | 2.6.1 | Local SQLite database |
| DataStore | 1.1.1 | Preferences storage |
| EncryptedPrefs | 1.1.1 | Secure encrypted storage |
| Gson | 2.11.0 | JSON serialization |

### Networking & APIs
| Library | Version | Purpose |
|---------|---------|---------|
| OkHttp | 4.12.0 | HTTP client |
| Google Drive API | v3 | Cloud backup |
| Play Services Auth | 21.3.0 | Google Sign-In |

### Media & Content
| Library | Version | Purpose |
|---------|---------|---------|
| Media3 ExoPlayer | 1.5.1 | Audio/video playback |
| Coil | 2.7.0 | Image loading |
| PDFBox Android | 2.0.27.0 | PDF text extraction |
| ZXing | 3.5.3 | QR code generation |

### Background Processing
| Library | Version | Purpose |
|---------|---------|---------|
| WorkManager | 2.10.0 | Scheduled background tasks |

## Architecture

```
app/
├── data/
│   ├── local/           # Room database, DAOs, migrations
│   ├── model/           # Data classes (Note, Category, etc.)
│   ├── remote/          # AI services, Google Drive
│   ├── repository/      # Data repository pattern
│   ├── backup/          # Backup management
│   └── worker/          # WorkManager workers
├── navigation/          # Navigation graph and routes
├── service/             # Android services (audio player)
├── ui/
│   ├── animation/       # Custom animations
│   ├── components/      # Reusable UI components
│   ├── screens/         # Screen composables
│   └── theme/           # Colors, typography, spacing
├── util/                # Utilities (shake detector, PDF extractor)
└── viewmodel/           # ViewModels for each feature
```

## Requirements

- **Minimum SDK**: 26 (Android 8.0)
- **Target SDK**: 36
- **Kotlin**: 2.0.21
- **Gradle**: 8.13.2

## Setup

1. Clone the repository
2. Open in Android Studio
3. Add your API keys in Settings (at least one provider required):
   - Gemini API key (recommended - best quality)
   - DeepSeek API key (cost-effective alternative)
   - Groq API key (ultra-fast inference)
   - OpenAI API key (GPT-4o-mini)
   - OpenRouter API key (access to multiple models)
   - HuggingFace API key (free tier available)
4. Build and run

## AI Providers

Cogni supports multiple AI providers with automatic fallback. Configure one or more providers in Settings.

| Provider | Model | Best For | Pricing |
|----------|-------|----------|---------|
| **Gemini** | gemini-1.5-flash | Best overall quality and speed | Free tier available |
| **DeepSeek** | deepseek-chat | Cost-effective, good quality | Very affordable |
| **Groq** | llama-3.3-70b | Ultra-fast inference | Free tier available |
| **OpenAI** | gpt-4o-mini | Reliable, well-documented | Pay per use |
| **OpenRouter** | llama-3.1-8b (free) | Access to many models | Free and paid options |
| **HuggingFace** | Mistral-7B | Good fallback option | Free tier available |

### Getting API Keys

- **Gemini**: [Google AI Studio](https://aistudio.google.com/app/apikey)
- **DeepSeek**: [DeepSeek Platform](https://platform.deepseek.com/)
- **Groq**: [Groq Console](https://console.groq.com/)
- **OpenAI**: [OpenAI Platform](https://platform.openai.com/api-keys)
- **OpenRouter**: [OpenRouter](https://openrouter.ai/keys)
- **HuggingFace**: [HuggingFace Settings](https://huggingface.co/settings/tokens)

### Fallback Order

When analyzing content, Cogni tries providers in this order:
1. Gemini
2. DeepSeek
3. Groq
4. OpenAI
5. OpenRouter
6. HuggingFace

If all providers fail, smart keyword-based categorization is used as a final fallback.

## Permissions

| Permission | Purpose |
|------------|---------|
| `INTERNET` | AI API calls, content fetching |
| `FOREGROUND_SERVICE` | Background audio playback |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Media playback notification |

## Design System

### Colors
- **Primary (Dark)**: Acid Green (#CCFF00)
- **Primary (Light)**: Bright Orange (#FF6B00)
- **Secondary**: Neon Purple (#BB86FC)
- **Error/Warning**: Safety Orange (#FF4D00)
- **Audio Accent**: Apple Pink (#FF2D55)

### Typography
- **Font**: System default with monospace for input
- **Scale**: Based on golden ratio (φ = 1.618)

### Spacing
- Based on Fibonacci sequence: 2, 4, 8, 13, 16, 21, 34, 55dp
- Card corner radius: 18dp
- Screen padding: 16dp

## License

[Add your license here]

## Contributing

[Add contribution guidelines here]
