---
title: Smarty - Advanced AI Research Agent
emoji: 🧠
colorFrom: purple
colorTo: blue
sdk: docker
pinned: true
license: mit
---

# Smarty 🧠

> **Advanced AI Research Agent** with Deep Research capabilities, thinking persistence, and multi-agent architecture for Android.

Smarty is a next-generation AI companion featuring **autonomous research agents**, **real-time thinking display**, **comprehensive citations**, and **privacy-first architecture**. Built with advanced agentic frameworks, tool calling, and intelligent automation.

![Platform](https://img.shields.io/badge/Platform-Android-blue)
![Version](https://img.shields.io/badge/Version-3.2.0-purple)
![Architecture](https://img.shields.io/badge/Architecture-Thin_Client-orange)
![License](https://img.shields.io/badge/License-MIT-green)

---

## 🚀 Key Features

### **Deep Research Agent** 🔬
- **Autonomous Research**: Conducts comprehensive web research with automatic citation tracking
- **Clarification Questions**: Always asks 2-5 questions before starting research
- **Progress Tracking**: Saves findings to progress files during long research sessions
- **Timeout System**: 12-minute warning, 15-minute forced completion
- **User Interruption**: Redirect research mid-flow with user feedback
- **Auto-Synthesis**: Creates comprehensive note cards with full citations

### **Thinking Persistence** 💭
- **Real-Time Display**: Shows AI reasoning in collapsible thinking sections
- **Persistent Storage**: Thinking content saved to database and survives app restarts
- **Emoji Indicators**: Animated 🧠👻🌻 emojis while agent is reasoning
- **Transparent Process**: See exactly how AI reaches conclusions

### **Multi-Agent Architecture** 🤖
- **Normal Agent**: General-purpose assistant with tool calling
- **Research Agent**: Specialized for deep web research (limited toolset)
- **Agent Switching**: Toggle between agents via UI
- **Specialized Tools**: Each agent has purpose-built tool access

### **Advanced UI/UX** 🎨
- **Theme-Aware**: Adapts to light/dark themes with accent colors
- **Selection Pill Bar**: Replaces input field when notes selected
- **Research Mode Toggle**: Icon + "Deep Research" text in chat input
- **Citation Display**: Inline citations with expandable source cards
- **Chat History**: Full conversation management with search

### **Comprehensive Citations** 📚
- **Auto-Tracking**: All web sources automatically cited
- **Inline Display**: Citation pills expand to show full sources
- **Click-Through**: Tap citations to open source URLs
- **Research Reports**: Full bibliography in research notes

### **Privacy-First** 🔒
- **BYO-Key**: Bring Your Own API keys
- **Local Storage**: Room database with FTS5 search
- **Encrypted Preferences**: Secure credential storage
- **No Data Logging**: Private conversations stay private

---

## 🏗️ Architecture

```
┌─────────────────────────┐
│  Smarty Android App     │
│  (Thin Client)          │
│  - Jetpack Compose UI   │
│  - Room Database        │
│  - Media3 ExoPlayer     │
└───────────┬─────────────┘
            │ HTTPS/SSE
            ▼
┌─────────────────────────┐
│  Smarty Server          │
│  (Hugging Face Spaces)  │
│  - Ktor Server          │
│  - Multi-Agent System   │
│  - Tool Orchestrator    │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│  External Services      │
│  - LLM (Gemini/OpenAI)  │
│  - Tavily Search        │
│  - PostgreSQL+pgvector  │
└─────────────────────────┘
```

### **Agent Types**

| Agent | Purpose | Tools | Timeout |
|-------|---------|-------|---------|
| **Normal Agent** | General assistant | All tools | None |
| **Research Agent** | Deep research | Web search, notes only | 15 min |

---

## 📋 Complete Feature List

### **Core Features**
- ✅ Voice input (hold-to-talk, release-to-stop)
- ✅ Text chat with SSE streaming
- ✅ Thinking persistence with collapsible UI
- ✅ Deep Research Agent with citations
- ✅ Multi-agent switching (Normal/Research)
- ✅ Note creation with AI categorization
- ✅ File attachments (images, audio, documents, video)
- ✅ Share from other apps (auto-saves files)
- ✅ Calendar integration with Google Calendar sync
- ✅ Timer and alarm management
- ✅ Stacks/Categories for note organization
- ✅ Archive with bulk operations
- ✅ Search with filters
- ✅ Chat history with session management
- ✅ Settings with theme toggle
- ✅ Server configuration in-app

### **Advanced Features**
- ✅ Real-time thinking display (🧠👻🌻 emojis)
- ✅ Citation tracking with inline display
- ✅ Progress file for long research sessions
- ✅ Context overflow handling
- ✅ User interruption during research
- ✅ Timeout warnings (12min) and forced completion (15min)
- ✅ Auto-create note cards from research
- ✅ Selection mode with multi-select actions
- ✅ Pill bar replaces input when selecting
- ✅ Theme-aware UI colors
- ✅ Shimmer loading effects
- ✅ Unread indicators for notes
- ✅ Server status indicator (real-time connection)

### **Research Agent Features**
- ✅ Clarification questions (2-5 before research)
- ✅ Limited toolset (web search + notes only)
- ✅ Progress file tracking
- ✅ Citation auto-generation
- ✅ User redirection mid-research
- ✅ Timeout system with warnings
- ✅ Auto-synthesis of findings
- ✅ Full bibliography in notes

---

## 🚀 Quick Start

### **Step 1: Deploy Server to Hugging Face**

1. Go to [Hugging Face Spaces](https://huggingface.co/spaces)
2. Click **New Space**
3. Configure:
   - **Space Name**: `smarty-server`
   - **SDK**: Docker
   - **Template**: Blank
4. Connect this repository

### **Step 2: Add Secrets**

In your Space settings → **Repository secrets**, add:

| Secret | Required | Description |
|--------|----------|-------------|
| `DB_URL` | ✅ Yes | PostgreSQL JDBC (Supabase/Neon) |
| `DB_USER` | ✅ Yes | Database username |
| `DB_PASSWORD` | ✅ Yes | Database password |
| `TAVILY_API_KEY` | ✅ Yes | Tavily API for web search |
| `ACTIVE_PROVIDER` | ⚠️ Optional | `GEMINI` or `OPENAI` (default: GEMINI) |
| `GEMINI_API_KEY` | ⚠️ Conditional | Required if using Gemini |
| `OPENAI_API_KEY` | ⚠️ Conditional | Required if using OpenAI |

### **Step 3: Setup Database**

Hugging Face doesn't provide persistent storage. Use **Supabase** or **Neon**:

```sql
-- 1. Create PostgreSQL database
-- 2. Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 3. Run the schema
-- Copy contents of COMPLETE_SCHEMA_v3.0_RESEARCH.sql
-- Paste into Supabase SQL Editor and run
```

### **Step 4: Connect Android App**

**Option 1: In-App Settings** (Recommended)
1. Open Smarty app
2. Go to **Settings** → **Server Configuration**
3. Enter your Space URL: `https://your-username-smarty.hf.space`

**Option 2: Modify Source**
1. Open `app/src/main/java/com/example/smarty/data/local/SecurePreferences.kt`
2. Change line 101:
```kotlin
private const val DEFAULT_SERVER_URL = "https://your-username-smarty.hf.space"
```
3. Rebuild app

### **Step 5: Verify Deployment**

```bash
# Test server health
curl https://your-space.hf.space/health

# Expected response:
# {"status":"ok","module":"smarty-server","timestamp":...}
```

---

## 🎯 Using Deep Research

### **Activate Research Mode**
1. Open AI Chat in the app
2. Look for **"Deep Research"** button (🔬 icon + text) next to History button
3. Tap to activate (turns purple when active)

### **Start Research**
1. Enter your research topic
2. Agent will ask 2-5 clarification questions
3. Answer the questions
4. Agent begins research with web searches
5. Watch real-time progress with citations
6. Interrupt anytime to redirect research
7. Receive comprehensive note card with full citations

### **Timeout System**
- **12 minutes**: ⚠️ Warning ("3 minutes left!")
- **15 minutes**: 🛑 Forced completion (synthesizes available findings)

---

## 🛠️ Local Development

### **Prerequisites**
- Java JDK 17
- Docker Desktop
- Android Studio Ladybug or later
- Git

### **Database Setup**
```bash
# Start PostgreSQL with pgvector
docker-compose up -d db

# Run schema
docker exec -i smarty-db psql -U smarty_user -d smarty_db < COMPLETE_SCHEMA_v3.0_RESEARCH.sql
```

### **Server Setup**
```bash
# Set environment variables
export DB_URL=jdbc:postgresql://localhost:5432/smarty_db
export DB_USER=smarty_user
export DB_PASSWORD=smarty_pass
export TAVILY_API_KEY=your_tavily_key
export GEMINI_API_KEY=your_gemini_key

# Run server
./gradlew :server:run
```

### **App Setup**
```bash
# Open in Android Studio
# Sync Gradle
# Run on device/emulator
```

---

## 📊 Tech Stack

### **Android Client**
- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Architecture**: MVVM
- **Database**: Room with FTS5
- **Media**: Media3 ExoPlayer
- **Networking**: OkHttp 4, Ktor Client
- **Streaming**: SSE (Server-Sent Events)

### **Server**
- **Framework**: Ktor Server
- **Database**: PostgreSQL + pgvector
- **Connection Pool**: HikariCP
- **PDF Processing**: Apache PDFBox 3.0
- **Search**: Tavily API
- **LLM**: Gemini/OpenAI with multi-provider routing

### **Infrastructure**
- **Hosting**: Hugging Face Spaces (Docker)
- **Database**: Supabase/Neon PostgreSQL
- **CI/CD**: Git push to deploy

---

## 📝 Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DB_URL` | ✅ Yes | - | PostgreSQL JDBC URL |
| `DB_USER` | ✅ Yes | - | Database username |
| `DB_PASSWORD` | ✅ Yes | - | Database password |
| `TAVILY_API_KEY` | ✅ Yes | - | Tavily search API key |
| `ACTIVE_PROVIDER` | ⚠️ No | `GEMINI` | `OPENAI` or `GEMINI` |
| `GEMINI_API_KEY` | ⚠️ If using Gemini | - | Google Gemini API key |
| `OPENAI_API_KEY` | ⚠️ If using OpenAI | - | OpenAI API key |
| `SERVER_PORT` | ⚠️ No | `7860` | Server port |

---

## 🔧 API Endpoints

### **Research Agent**
- `POST /api/v1/research/start` - Start research session
- `POST /api/v1/research/{id}/answer` - Submit clarification answers
- `POST /api/v1/research/{id}/interrupt` - User interruption
- `GET /api/v1/research/{id}/timeout` - Check timeout status
- `GET /api/v1/research/{id}` - Get session status

### **Chat & Messages**
- `POST /api/v1/chat/stream` - SSE chat stream
- `GET /api/v1/chat/sessions` - List sessions
- `POST /api/v1/chat/sessions` - Create session

### **Sync**
- `POST /api/v1/sync/pull` - Pull changes from server
- `POST /api/v1/sync/push` - Push changes to server

---

## 📚 Database Schema

Version **3.0** includes:
- Chat system (sessions, messages with thinking)
- Notes system (notes, categories)
- Research system (sessions, searches, citations, logs, progress files)
- Calendar system (events)
- Timers & alarms
- Agent memory & context
- Digest system
- Security & sync

Full schema in `COMPLETE_SCHEMA_v3.0_RESEARCH.sql`

---

## 🎨 UI Components

### **Input Block Features**
- Voice button (animated waveform)
- Deep Research toggle (🔬 icon + text)
- History/Add button
- Scroll indicators
- Attachment picker (+ menu)
- Theme-aware colors

### **Message Display**
- Thinking section (collapsible with animated emojis)
- Citations inline (expandable source cards)
- Agent activity indicator
- Message grouping (Today/Yesterday/Earlier)

### **Selection Mode**
- Pill bar replaces input when notes selected
- Multi-select with checkboxes
- Actions: Pin, Categorize, Share, Archive, Delete
- Count display

---

## 🔒 Privacy & Security

- **No Data Logging**: Conversations not logged
- **Encrypted Storage**: Preferences encrypted with Android Keystore
- **Local Database**: Room with encrypted preferences
- **BYO-Key**: You control API keys
- **Secure Communication**: HTTPS/SSE only
- **Row Level Security**: Optional database isolation

---

## 📄 License

MIT License - See [LICENSE](LICENSE) file

---

## 🙏 Acknowledgments

- **Tavily** for web search API
- **Google** for Gemini AI
- **Hugging Face** for hosting
- **Supabase/Neon** for PostgreSQL
- **Jetpack Compose** for modern UI

---

## 📞 Support

- **Issues**: GitHub Issues
- **Discussions**: GitHub Discussions
- **Documentation**: This README + inline code comments

---

**Built with ❤️ using Kotlin, Ktor, and Jetpack Compose**

**Version 3.2.0** | **Last Updated**: March 2026
