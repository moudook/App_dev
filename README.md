---
title: Smarty - Advanced AI  Agent(AAA)
colorFrom: purple
colorTo: blue
sdk: docker
pinned: true
license: mit
---

# Smarty

Advanced AI Research Agent with deep research capabilities, persistent reasoning, image generation, and a multi-agent architecture designed for Android.

Smarty is an AI companion built with autonomous research agents, real-time reasoning visualization, comprehensive citation management, wellness features, and a privacy-first architecture.

![Platform](https://img.shields.io/badge/Platform-Android-blue)
![Version](https://img.shields.io/badge/Version-6.0.0-purple)
![Architecture](https://img.shields.io/badge/Architecture-Thin_Client-orange)
![License](https://img.shields.io/badge/License-MIT-green)

---

# Overview

Smarty provides an intelligent assistant capable of performing autonomous research, managing structured notes, and interacting with external tools. The system uses a thin-client Android architecture connected to a scalable server backend capable of coordinating multiple specialized AI agents.

Key design goals:

- Autonomous research workflows
- Transparent reasoning
- Structured knowledge storage
- Privacy-first data handling
- Modular multi-agent architecture

---

# Key Features

## Deep Research Agent

The research agent is designed to conduct structured multi-step research sessions with professional intelligence methodologies.

Capabilities include:

- Autonomous multi-source web research (concurrent searches and scrapes)
- Automatic citation tracking with source credibility hierarchy (Tier 1-5)
- Clarification questions before research begins
- Progress persistence during long research tasks
- User interruption and redirection during research
- Automated synthesis into structured note cards
- Built-in research time management with timeout controls
- **Advanced Deep Research v3.0** with:
  - Analysis of Competing Hypotheses (ACH) Matrix
  - Cognitive bias detection (confirmation, recency, anchoring, mirror-imaging, groupthink)
  - ALCOA source verification (Attributable, Legible, Contemporaneous, Original, Accurate)
  - Rule of Three validation (3+ independent Tier 1-2 sources)
  - BLUF-style intelligence reporting (Bottom Line Up Front)
  - OWASP Top 10 for Agentic AI security controls
  - Confidence level calibration (High, Moderate, Low)
  - Knowledge graph construction with entity extraction

## Image Generation

AI-powered image creation using Krea AI with Flux.1 Dev model.

Features include:

- Agent-triggered image generation with Art Director-style prompt enhancement
- Direct image generation endpoint for manual triggering
- Multiple aspect ratios (1:1, 16:9, 9:16)
- Professional prompt crafting with lighting, camera angles, rendering engines
- Real-time generation status tracking
- Automatic safety filtering
- Remix functionality for iterative refinement
- Full-screen expandable view with dominant color extraction

## Vision & Document Processing

Advanced vision capabilities for image and document analysis.

Features include:

- OCR (Optical Character Recognition) for text extraction
- Image analysis and description
- Table and form extraction from images
- Document structure detection
- PDF text extraction (up to 50 pages)
- PDF OCR for image-heavy documents
- Automatic format detection

## Thinking Persistence

The system provides transparent reasoning visibility.

Features include:

- Real-time reasoning display
- Collapsible reasoning sections
- Persistent storage of reasoning content
- Recovery of reasoning data after application restart
- Progressive thinking save during streaming

## Multi-Agent Architecture

Smarty supports multiple specialized agents that can be switched dynamically.

| Agent | Purpose | Tool Access | Timeout |
|------|------|------|------|
| Normal Agent | General assistant tasks | Full tool access | None |
| Research Agent | Structured research | Web search and note creation | 15 minutes |
| Advanced Research Agent | Professional intelligence analysis | Full research + ACH matrix | None |
| Medical Advisor | Health consultations | Medical tools + symptom analysis | None |

## User Experience

The Android application is designed with a modern responsive interface.

Key UI capabilities:

- Dynamic theme support
- Inline citation display
- Chat history and conversation management
- Selection mode for multi-note operations
- Contextual toolbars and action menus
- Searchable knowledge base
- Organic thinking indicator with animations
- Mention/note context system
- Adaptive semantic search engine
- Haptic feedback and completion sounds
- QR code generation
- URL metadata extraction

## Citation Management

All research operations include source tracking.

Features include:

- Automatic citation recording
- Inline citation references
- Expandable source cards
- Clickable links to original sources
- Full bibliography generation for research outputs

## Wellness & Mental Health

Comprehensive wellness features for mental and physical health.

Features include:

- **Guided Breathing Exercises**: 4-7-8 breathing technique with haptic feedback and visual animations
- **Mental Health Support**: Authorized for psychiatric assessments (depression, anxiety, bipolar, ADHD, autism)
- Symptom analysis and diagnosis
- Treatment recommendations including therapy and medications
- Medication safety checks
- Urgent care flagging

## Games & Entertainment

Built-in games for entertainment and quick breaks.

Features include:

- **Coin Toss**: 3D metallic coin flip with physics-based animation and haptic feedback
- **Tic Tac Toe**: Classic game with AI opponent and win/draw detection

## Audio Features

Full audio playback and music discovery capabilities.

Features include:

- Play/pause/resume/stop controls
- Next/previous track navigation
- Seek functionality
- Playlist support
- Device audio file discovery
- Smart audio search with fuzzy matching
- Audio statistics (total tracks, artists, albums)

## Daily/Weekly Digest

Automated AI-powered activity synthesis delivered via push notifications.

Features include:

- Daily digest at configurable time (default 7 AM)
- Weekly digest on configurable day (default Sunday)
- Activity synthesis and summarization
- Goal progress tracking
- Priority identification
- Critical information flagging
- Calendar event creation for digests

## Device Control

Direct device interaction and control capabilities.

Features include:

- App launching
- Media control (play, pause, stop, next, previous, volume)
- Settings toggle (WiFi, Bluetooth, Flashlight, DND, Airplane)
- Device status (battery, storage, network)
- Screenshot capture
- In-app navigation (home, calendar, stacks, archive, settings)
- External sharing to other apps

## Backup & Restore

Google Drive integration for data protection.

Features include:

- Complete backup (database + preferences + attachments)
- Automated scheduled backups
- Restore with rollback on failure
- Progress tracking
- Version compatibility checking
- Manifest verification
- 500MB max backup size validation

## Privacy and Security

Smarty is designed with a privacy-first architecture.

- Bring-Your-Own API keys
- Local encrypted credential storage
- Private conversation storage
- No conversation logging
- Secure communication using HTTPS
- Firebase Authentication with multi-tenant isolation
- Firewall protection with IP allowlisting
- Zero-knowledge vault for encrypted blob storage
- Input validation and XSS prevention
- Security headers (CSP, X-Frame-Options, HSTS)
- Circuit breaker for LLM provider failover
- Client-side encryption with user-controlled keys

---

# Architecture


┌─────────────────────────┐
│ Smarty Android Client │
│ │
│ Jetpack Compose UI │
│ Room Database │
│ Media3 ExoPlayer │
└───────────┬─────────────┘
│ HTTPS / SSE
▼
┌─────────────────────────┐
│ Smarty Server │
│ │
│ Ktor Backend │
│ Multi-Agent System │
│ Tool Orchestration │
└───────────┬─────────────┘
│
▼
┌─────────────────────────┐
│ External Services │
│ │
│ LLM Providers │
│ Tavily Search API │
│ PostgreSQL + pgvector │
└─────────────────────────┘


---

# Complete Feature List

## Core Capabilities

- Voice input and speech-to-text support
- Text chat with streaming responses
- Persistent reasoning display with organic animations
- Deep research agent with ACH matrix analysis
- Advanced research agent with cognitive bias detection
- Multi-agent switching (Normal, Research, Advanced Research, Medical)
- Structured note creation with metadata extraction
- File attachments (image, audio, video, documents)
- PDF processing with OCR support
- Vision/image analysis and OCR
- Image generation via Krea AI (Flux.1 Dev)
- External sharing support
- Calendar integration with event linking
- Timer and alarm management
- Category and stack organization
- Chat history management
- Server configuration within the application
- Task management with priorities and due dates
- Tag system with color coding
- Notification management
- Chat folders for organization
- Note versioning history
- Zero-knowledge encrypted vault
- Home screen widgets (Quick Note)
- App shortcuts for quick actions

## Advanced Capabilities

- Long-running research sessions with progress persistence
- Context overflow handling with conversation summarization
- User redirection during research
- Automated research synthesis
- Multi-select note operations
- Real-time server status monitoring
- Loading state animations
- Unread note indicators
- Concurrent web searches and scrapes
- Knowledge graph construction
- Entity extraction (Person, Organization, Location, Date, Concept, Event, Product)
- Relationship mapping
- Gap analysis and gap-filling searches
- Content security filtering
- Shake detector for quick actions
- File compression/decompression
- Lazy decompressor for large files
- Metadata stripper for privacy
- Related notes provider
- Category share manager
- URL metadata extraction
- QR code generation

## Wellness & Entertainment

- Guided breathing exercises (4-7-8 technique)
- Mental health assessments (depression, anxiety, bipolar, ADHD, autism)
- Symptom analysis and diagnosis
- Treatment recommendations
- Medication safety checks
- Coin Toss game with 3D physics
- Tic Tac Toe with AI opponent
- Audio playback with full controls
- Device music discovery
- Playlist support
- Haptic feedback system
- Completion sound manager

## Automation & Scheduling

- Daily digest generation (configurable time)
- Weekly digest generation (configurable day)
- Automated backup scheduling
- FCM push notification delivery
- Calendar event creation for digests
- Goal-oriented agent execution
- Progress tracking with checkpoints
- Pattern recognition
- Error tracking and recovery

## Research Workflow

Research sessions support:

- Clarification question phase
- Multi-step web search (concurrent)
- Source collection with credibility scoring
- Citation generation with ALCOA verification
- Progress tracking with persistent storage
- Final synthesis into research notes
- ACH matrix analysis
- Cognitive bias detection
- Confidence level calibration
- BLUF-style intelligence reporting
- OWASP security checkpoints

---

# Quick Start

## 1. Deploy the Server

1. Open Hugging Face Spaces  
2. Create a new Space  

Configuration:

- Space Name: `smarty-server`
- SDK: Docker
- Template: Blank

Connect this repository to the Space.

---

## 2. Configure Secrets

Add the following repository secrets.

| Secret | Required | Description |
|------|------|------|
| DB_URL | Yes | PostgreSQL JDBC URL |
| DB_USER | Yes | Database username |
| DB_PASSWORD | Yes | Database password |
| TAVILY_API_KEY | Yes | Tavily search API key |
| ACTIVE_PROVIDER | Optional | GEMINI or OPENAI |
| GEMINI_API_KEY | Conditional | Required if Gemini is used |
| OPENAI_API_KEY | Conditional | Required if OpenAI is used |

---

## 3. Configure Database

Example setup using PostgreSQL.

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

Run the schema file:

COMPLETE_SCHEMA_v3.0_RESEARCH.sql
4. Connect the Android Application
In-App Configuration

Open the application

Navigate to Settings

Select Server Configuration

Enter your Space URL

Example:

https://your-username-smarty.hf.space
Source Configuration

Modify:

app/src/main/java/.../SecurePreferences.kt

Update:

private const val DEFAULT_SERVER_URL = "https://your-username-smarty.hf.space"
5. Verify Deployment
curl https://your-space.hf.space/health

Expected response:

{"status":"ok","module":"smarty-server"}
Local Development
Requirements

Java JDK 17

Docker Desktop

Android Studio

Git

Database Setup
docker-compose up -d db

Load schema:

docker exec -i smarty-db psql -U smarty_user -d smarty_db < COMPLETE_SCHEMA_v3.0_RESEARCH.sql
Server Setup
export DB_URL=jdbc:postgresql://localhost:5432/smarty_db
export DB_USER=smarty_user
export DB_PASSWORD=smarty_pass
export TAVILY_API_KEY=your_key
export GEMINI_API_KEY=your_key

./gradlew :server:run
Android Setup

Open the project in Android Studio, synchronize Gradle, and run the application on an emulator or device.

## Technology Stack

### Android Client

- Kotlin
- Jetpack Compose
- MVVM Architecture
- Room Database with FTS5
- Media3 ExoPlayer
- OkHttp
- Ktor Client
- Server-Sent Events streaming
- Firebase Cloud Messaging (FCM)
- Google Drive API
- Speech-to-text API
- Accessibility services

### Server

- Ktor Server
- PostgreSQL with pgvector
- HikariCP connection pooling
- Apache PDFBox
- Tavily Search API
- Multi-provider LLM routing (Gemini, OpenAI)
- Krea AI API (Flux.1 Dev)
- Firebase Authentication
- Circuit breaker pattern
- Retry policies with exponential backoff
- Input validation and sanitization

### Infrastructure

- Hugging Face Spaces
- Supabase / Neon PostgreSQL
- Git-based CI/CD deployment
- Docker containerization
- Environment-based configuration

## API Endpoints

### Research
| Endpoint | Description |
|----------|-------------|
| POST /api/v1/research/start | Start research session |
| POST /api/v1/research/{id}/answer | Submit clarification answers |
| POST /api/v1/research/{id}/interrupt | Interrupt research |
| GET /api/v1/research/{id}/timeout | Check timeout status |
| GET /api/v1/research/{id} | Retrieve research session |

### Chat
| Endpoint | Description |
|----------|-------------|
| SSE /chat/stream | Streaming chat responses |
| POST /chat/query | Chat with file attachments |
| POST /chat/events | Receive client events |
| POST /briefing/generate | Generate daily briefing |
| GET /api/v1/chat/sessions | List chat sessions |
| POST /api/v1/chat/sessions | Create chat session |

### Image & Vision
| Endpoint | Description |
|----------|-------------|
| POST /api/v1/image/direct | Direct image generation |
| POST /process/image | Image OCR/analysis |
| POST /process/pdf | PDF text extraction |
| POST /upload | File upload for processing |

### Content Analysis
| Endpoint | Description |
|----------|-------------|
| POST /analyze/content | Analyze text content |
| POST /analyze/document | Analyze documents |

### Data Management
| Endpoint | Description |
|----------|-------------|
| GET/POST /api/v1/notes | Note management |
| GET/POST/DELETE /api/v1/calendar | Calendar events |
| GET/POST/DELETE /api/v1/timers | Timer management |
| GET/POST/DELETE /api/v1/vault | Zero-knowledge vault |
| GET /api/v1/export/all | Export all data (cloud backup) |
| POST/DELETE /api/v1/calendar/events/{eventId}/notes/{noteId} | Link notes to events |

### Tasks & Organization (v6.0.0)
| Endpoint | Description |
|----------|-------------|
| GET/POST/PATCH/DELETE /api/tasks | Task management |
| GET/POST/DELETE /api/tags | Tag management |
| GET/POST/PUT/DELETE /api/chat/folders | Chat folder management |
| GET/POST/DELETE /api/notifications | Notification management |

### Synchronization
| Endpoint | Description |
|----------|-------------|
| POST /api/v1/sync/pull | Pull changes |
| POST /api/v1/sync/push | Push changes |

### Session & Health
| Endpoint | Description |
|----------|-------------|
| POST /api/v1/session/init | Session initialization with execution policy |
| GET /health | Health check |
| GET /metrics | Metrics endpoint |
| POST /api/v1/fcm/register | FCM token registration |

---

## Database

Schema version: 6.0.0

Includes systems for:

- Chat sessions and messages with reasoning storage
- Notes and categories with versioning
- Research sessions with citation tracking
- Calendar events with note linking
- Timers and alarms
- Agent memory and context storage
- Synchronization tracking
- Tasks with priorities and due dates
- Tags with color coding
- Notifications
- Chat folders
- User devices
- Search history
- Shared items
- Daily digests
- User vaults (zero-knowledge encrypted storage)
- Generated images tracking
- FCM tokens
- Digest preferences
- Reasoning traces

---

## Security

- No conversation logging
- Encrypted credential storage
- Local database usage
- Secure HTTPS communication
- Optional database row-level security
- Firebase Authentication with multi-tenant isolation
- Firewall protection with IP allowlisting
- Input validation and XSS prevention
- Security headers (CSP, X-Frame-Options, HSTS)
- Circuit breaker for LLM provider failover
- Client-side encryption with user-controlled keys
- Zero-knowledge vault for sensitive data

---

## License

MIT License

---

## Support

- GitHub Issues
- GitHub Discussions
- Repository documentation

---

**Version 6.0.0**