# Smarty Application Features

A comprehensive list of features currently implemented in the Smarty application.

## &#x1F4DA; Feature Managers Architecture (Modular Design)
-   **Feature-First Modular Architecture**:
    -   **AudioFeatureManager**: Centralized audio playback control, device audio discovery, and playback state management.
    -   **AuthFeatureManager**: Firebase Email/Password Auth, Google Sign-In integration, password recovery, and session management.
    -   **CalendarFeatureManager**: Calendar event CRUD, event queries, and alarm scheduling coordination.
    -   **ChatFeatureManager**: AI agent interaction, session management, mention resolution, and command orchestration.
    -   **SearchFeatureManager**: Semantic note search, hybrid/vector/keyword algorithms, query analysis, and semantic recall.
    -   **SettingsFeatureManager**: Server connection settings, UI preferences, and app lifecycle flags.
    -   **SystemFeatureManager**: App launching, internal navigation, media playback, screen capture, and device audio.
    -   **VoiceFeatureManager**: Voice interactions, wake word detection, audio focus, and phone call state observation.
    -   **MentionFeatureManager**: @-mention parsing, type discovery, and contextual note retrieval.
    -   **StyleFeatureManager**: User writing style analysis and tone detection.
    -   **WorkflowManager**: Complex multi-step AI workflows (Deep Research, Batch Processing, Scheduled Tasks).
    -   **BackupFeatureManager**: Local and cloud backup orchestration.
    -   **MemoryFeatureManager**: AI memory CRUD operations and retrieval.

## &#x1F9E0; Core Note-Taking
-   **Multi-Modal Notes**: Create notes with varied content types:
    -   **Text**: Quick thoughts and brain dumps.
    -   **Images**: Add images with AI-powered analysis/tagging.
    -   **Audio**: Record voice notes (stored locally).
    -   **Web Links**: Smart handling for detailed previews of:
        -   YouTube (Video ID extraction supporting Shorts, Live, and Embeds)
        -   Twitter/X (Tweet extraction)
        -   Instagram (Post preview)
    -   **Documents**: Attach PDF, DOCX, and other files.
    -   **Code**: Syntax highlighting for code snippets.
-   **Organization & History**:
    -   **Git-like Versioning**: Automatically saves history snapshots of notes, allowing you to track change and revert to earlier versions (`note_versions` table).
    -   **Categories**: Custom colored categories with syncable counts.
    -   **Stacks**: Group related notes together using a specialized `NoteStackManager`.
    -   **Pinning**: Keep important notes at the top (optimized with composite indices).
    -   **Archived View**: Separate section for archived notes to keep the main view clean.
    -   **Smart Tagging**: AI automatically tags content based on type (e.g., "Visual", "Read", "Watch").
    -   **Intelligent O(1) Discovery**: Uses pre-compiled Regex/HashMaps to instantly detect Idea, Task, Learning, Quote, and Code patterns in brain-dumps for auto-categorization (`ContentTypeDetector`).
    -   **Smart "Unread" Discovery**: Calm, non-distracting indicators (`NewNoteIndicatorDot`) for notes you haven't viewed yet.
    -   **Batch Operations**: Optimized database writing for bulk updates.
    -   **File Locking (RX-05)**: Prevents accidental deletion of files currently being processed by AI.
    -   **Knowledge Card View**: Rich note detail view with version history, @Mention support ("Ask Smarty"), related notes discovery, and full-screen viewers for documents/images/videos (`KnowledgeCardScreen`).
-   **Home Screen Widgets**:
    -   **Quick Note Widget**: Add notes directly from the Android Home Screen.
    -   **Auto-Syncing View**: Widgets automatically update their state whenever notes are processed or categories change.

## &#x1F916; AI & Intelligence
-   **Smarty Chat Agent (Thin Client Architecture)**:
    -   **Conversational Assistant**: Chat with your notes and external knowledge.
    -   **Iterative Reasoning Engine**: A sophisticated state machine that tracks the agent's progress through `ExecutingTool`, `WaitingForResult`, and `ProcessingResult`. Includes **Individual Tool Timeouts** (e.g., 45s for search, 60s for batch).
    -   **Workflow Orchestration**: Capability to execute complex, multi-step AI workflows (Deep Research, Batch Processing, Scheduled Tasks).
    -   **Mention System (@)**: Deep contextual references in chat:
        -   **Type Discovery**: `@images`, `@documents`, `@audio`.
        -   **Temporal Discovery**: `@recent`, `@pinned`, `@all`.
        -   **Action Commands**: `@analyze` to trigger deep content analysis.
        -   **Relevance Scoring**: Suggestions include sparkle icons for high-confidence matches.
    -   **System Context Injection**: The AI can request a "State-of-the-World" snapshot (Battery, Theme, Network, Cache, OS/Device info) to provide context-aware help (`getSystemStatus`).
    -   **Contextual Quick Replies**: AI-powered suggestion chips that proactively offer relevant follow-ups based on the conversation state.
    -   **Screen Context Aware**: The agent can "see" what's on your screen and capture screenshots for reasoning.
    -   **App Orchestration**: Can execute system-wide commands like launching apps, navigating to screens, or toggling settings (Theme, Volume, Haptics).
    -   **Explicit Protocol**: Uses a strict JSON Schema to ensure the AI uses standardized actions.
        -   **Action Set**: `CREATE_NOTE`, `SEARCH_NOTES`, `DELETE_NOTE`, `UPDATE_NOTE`, `SUMMARIZE_NOTE`, `ADD_TODOS`, `WEB_SEARCH`, `PLAY_AUDIO`, `BATCH_ACTIONS`, etc.
        -   **Strict Timings**: `BatchActions` (60s), `WebSearch` (45s), `Summarize` (30s), Default (15s).
    -   **Streaming Responses**: Real-time text generation with protocol-based feedback via Server-Sent Events (SSE).
    -   **Command Transport Layer**: `LocalCommandTransport` for pure on-device execution, `ShadowRemoteTransport` for server coordination, `CompositeTransport` for hybrid execution.
-   **Adaptive Semantic Search Engine**:
    -   **Dynamic Algorithm Selection**: Automatically shifts between `KEYWORD_HEAVY`, `SEMANTIC_HEAVY`, `VECTOR_HEAVY`, and `HYBRID` strategies based on content characteristics.
    -   **Content Analysis & Intent Detection**: Calculates Keyword Density, Semantic Clustering, and Diversity to adjust search weights (Keyword, Semantic, Vector) and relevance thresholds in real-time. Detects query "Complexity" to suggest optimal search strategies.
    -   **Hybrid Retrieval Strategy**: Uses a linear combination of `KEYWORD_WEIGHT` (0.3), `SEMANTIC_WEIGHT` (0.5), and `TEMPORAL_WEIGHT` (0.2) to rank results.
    -   **Fast Vector Search**: Implements TF-IDF and Cosine Similarity for document-heavy search tasks.
    -   **SearchFeatureManager**: Centralized search orchestration with privacy filtering, attachment/mime type filtering, time-range filtering, and contextual semantic recall.
-   **Fast-Path Heuristics (Local Processing)**:
    -   **Offline Command Intelligence**: A specialized `LocalCommandProcessor` that handles common requests (Time, Date, Battery, Flashlight, Volume, Apps) locally without ever hitting the AI server.
    -   **Fuzzy Audio Matching**: Uses a 3-stage fallback system (Exact -> High-Confidence Fuzzy -> Suggestions) to verify song requests before playing, preventing "hallucinated" playback.
    -   **Preprocessing Pipeline**: Strips conversational filler ("please", "hey smarty") and punctuation for cleaner regex matching.
-   **Smart Web Clipper (Reader Mode)**:
    -   **Article Extraction**: Proactively strips ads, scripts, and navigation to extract the "meat" of a webpage.
    -   **Semantic Storage**: Converts webpages into clean Markdown-like text (up to 10KB) so the AI can "read" the full context later.
-   **Long-Term AI Memory & Learning**:
    -   **Impressed Learning Engine**: A secure background log that tracks user "satisfaction signals" (e.g., user saying "Thanks!", "Perfect", or immediate reuse of a suggestion). It uses **abstract context** (no private content) to learn what the user likes.
    -   **Memory Synchronization**: Sophisticated `MemorySyncManager` that ensures AI "memories" stay consistent with changes in physical notes.
    -   **Context Continuity**: AI stores "memories" about the user to provide more personalized help over time.
    -   **Confidence Scoring**: AI decreases the "confidence" of memories if the user contradicts them.
    -   **Memory Pruning**: Automatically clears old or low-confidence memories to stay efficient (`MAX_CACHE_AGE_DAYS`).
    -   **Memory Consolidation**: Background worker that consolidates and syncs memories from notes (`MemorySyncWorker`).
-   **Knowledge Discovery Engine**:
    -   **Semantic Relationship Engine**: Automatically finds connections between notes by generating concept-heavy queries from **Signal Prioritization** (Title > Tags > Summary > Category).
    -   **Human-Readable Match Reasons**: Explains *why* notes are related (e.g., "Sounds like...", "Exactly matches...", "Shares keywords: 'project'") instead of raw scores.
-   **Deep Document Analysis (@thinking)**:
    -   **Large Doc Handling**: Splits huge PDFs into overlapping "chunks" (`OVERLAP_CHARS`) to ensure context isn't lost at the boundaries.
    -   **Recurrent Chunking Architecture**: Sequentially processes document segments while maintaining reference to the target note.
    -   **Reasoning Step**: Uses a specialized "ThinkingMode" to reason through complex documents before answering.
    -   **Thinking Mode Processor**: Dedicated `ThinkingModeProcessor` for @thinking deep document analysis with PDF extraction and chunk management.
    -   **Thinking Parser**: Parses thinking tags from reasoning models like Falcon-H1R-7B, separating reasoning process from final answer.
-   **Style Analysis & "Voice Adoption"**:
    -   **Stylistic Fingerprinting**: Analyzes recent notes to detect if you prefer bullet points or paragraphs.
    -   **Tone Detection**: Detects "Enthusiastic" or "Inquisitive" tones based on punctuation density.
    -   **Length Analysis**: Adapts to your preference for short vs. detailed notes.
-   **Advanced Search & Recall**:
    -   **FTS5 Fast Search**: Utilizes advanced SQLite FTS5 (Virtual Table) with BM25 ranking for instantaneous retrieval across thousands of entries. Triggers keep the index in sync with the `notes` table.
    -   **Search Index Maintenance**: Periodic optimization tasks to keep search speed consistent as data grows.
    -   **Search History Intelligence**: Privacy-focused `SearchHistoryManager` for persistent, searchable query logging.
-   **Signal-Processing Transcription**:
    -   **On-Device Internal Playback**: A specialized engine that decodes audio to PCM and "pipes" it back through the system's `VoiceCommunication` stream so the on-device `SpeechRecognizer` can process files as if they were live mic input.
    -   **RMS Energy Detection**: Uses Root Mean Square signal processing to detect when speech starts and stops.
    -   **Continuous Segment Stitching**: Breaks long audio into segments based on silence, transcribes them individually, and stitches them back together for perfectly aligned results.
    -   **Hysteresis Protection**: Uses dual thresholds (Speech vs Silence) with a 2000ms buffer to prevent rapid on/off switching.
-   **Conversation Summarization**:
    -   **AI-Powered Summarizer**: `ConversationSummarizer` generates concise, privacy-safe summaries of chat sessions.
    -   **Automatic Triggers**: Summarizes on new session start, 30+ min inactivity, or 15+ messages.
    -   **Privacy-Safe**: Never includes raw private note content, only abstract descriptions.
    -   **Context Persistence**: Summaries stored for future conversation context.
-   **Request Batching (DataLoader Pattern)**:
    -   **Intelligent Aggregation**: `RequestBatcher` aggregates multiple requests within a time window (50ms) into single batch operations.
    -   **30-50% Latency Reduction**: Significant improvement for burst requests.
    -   **Lifecycle-Aware**: Uses external scope to prevent memory leaks (TECH-001 fix).
    -   **Statistics Tracking**: Tracks total requests, batched requests, and batching efficiency.

## &#x1F44C; Smarty Assistant & "Assist" System
-   **Native Assistant Replacement**:
    -   **Default Device Assistant**: Smarty can be set as the system-wide digital assistant, replacing Google Assistant/Gemini.
    -   **Gestural Triggering**: Trigger Smarty from any app via system gestures (Edge Swipe, Home Button Long-Press).
    -   **Assist Session Management**: `AssistInteractionService` communicates directly with the Android OS to handle assist events even when the app is closed.
-   **"Soft Tech" Assistant Overlay**:
    -   **Floating Transparent UI**: A sleek, non-intrusive overlay (`AssistActivity`) that slides in over your current work using "Soft Tech" design principles.
    -   **Zero-Friction Context**: Automatically detects and reads selected text or URIs from the app you are currently using.
    -   **Referrer Intelligence**: Identifies the "Referring Package" (the app you were in) to provide context-aware suggestions.
-   **Infinite Vision (Screen Context)**:
    -   **Instant Screen Capture**: A specialized background service (`ScreenCaptureService`) that uses `MediaProjection` to capture what's on your screen instantly.
    -   **Mutex-Locked Safety**: Ensures thread-safe access to the screen buffer to prevent tearing or race conditions.
    -   **Virtual Display Pool**: Maintains a warm `VirtualDisplay` and `ImageReader` to reduce capture latency to near-zero.
    -   **Cross-App Reasoning**: Allows the AI to "see" your screen content across other apps to answer questions like "What is this recipe mentioning?" or "Summarize this post."
-   **Hands-Free Communication**:
    -   **Vosk Wake-Word Engine**: High-accuracy "Always-On" wake-word detection (e.g., "friday").
    -   **High-Sensitivity Audio Boost**: Applies up to **4x software gain amplification** (`GAIN_HIGH_SENSITIVITY`) to boost quiet audio for long-range detection (phone across the room).
    -   **Speaker Verification Buffer**: Keeps a **3-second rolling buffer** of *original* (non-amplified) audio to ensure speaker features aren't distorted during verification.
    -   **Global Microphone Orchestration**: A `isGloballyPaused` mechanism that allows the Assistant Overlay to "take" the microphone from the wake-word engine instantly without resource conflicts.
    -   **Self-Healing Mic Logic**: Detects when the system takes the microphone away and automatically triggers recovery/re-initialization.

## &#x1F4F1; System Integration
-   **App Integration**:
    -   **Fuzzy App Discovery**: Automatically finds installed apps by display name using a search heuristic (Exact Match -> Contains Match).
    -   **Handoff Ecosystem Integration**: The AI can "hand off" content to other apps using the Android Share Sheet.
    -   **Process Text**: Select text in *any* app and "Process with Smarty" via the Android system context menu.
    -   **Share Targets**: Share text, images, or files from any app directly to Smarty.
    -   **Dynamic App Shortcuts**: High-speed access to `NEW_NOTE`, `CHAT`, `CALENDAR`, and `STACKS` directly from the Android Home Screen.
    -   **App Launching**: Agent can launch installed apps by package or display name.
    -   **Visualizer**: Waveform visualization for playing audio.
-   **Hardware Control Layer**:
    -   **Flashlight**: Toggle device torch via AI command.
    -   **Battery Level**: AI can check device battery percentage.
    -   **Volume**: AI can adjust system volume (Up/Down/Mute) across various Android API versions.

## &#x1F517; Knowledge Sharing & Transfer
-   **Dynamic Category Sharing**:
    -   **Deep-Link Portability**: Generates Base64-encoded `smarty://import` links that contain the full JSON representation of a category and its notes.
    -   **AI-Generated QR Codes**: Instantly creates QR codes for categories using a high-speed `setPixels` batch-rendering engine.
-   **Smart Import System**: Automatically parses shared data and re-creates local versions of categories, notes, and tags.

## &#x1F3AE; Games & "Mental Breaks"
-   **Tic-Tac-Toe (AI Enhanced)**:
    -   **Simulated Intelligence**: A computer opponent with a dynamic "Thinking..." delay (`500ms..1500ms`) to simulate real-world gameplay feel.
    -   **Pop Animations**: Uses `scaleIn` with `EaseOutBack` curves for satisfying piece placement.
-   **3D Coin Toss**:
    -   **Metallic Material UI**: 3D-flipping coin using `metallicGradient` with Etched Rim and Grooved Ring effects.
    -   **Physical Orbit Logic**: Orchestrates independent axis animations (`rotationY` for spins, `translationY` for arc, `shadowScale` for depth) to land perfectly on the correct side after 5+ spins.
    -   **Perspective Distortion**: Uses `cameraDistance` to simulate real 3D depth during the toss.

## &#x1F4C5; Productivity Tools
-   **Calendar & Tasks**:
    -   **Google Calendar Two-Way Sync**: Deep integration via `GoogleCalendarSyncManager` for seamless event synchronization (using `googleEventId`).
    -   **Natural Language Parsing**: Understands "tomorrow at 10am", "next Friday", or "in 5 minutes".
    -   **Automated Alarms**: Schedules system notifications/reminders for every event.
    -   **Linked Entities**: Ties notes directly to calendar events for context.
-   **Time Management**:
    -   **Timers**: Set multiple named timers with persistence.
    -   **Alarms**: Create system alarms.
-   **Automated Workflows**:
    -   **Deep Research**: Multi-query web search and report synthesis.
    -   **Scheduled Tasks**: Ability to run complex AI tasks on a delay/schedule.
-   **Todos**: Checkboxes and task lists within notes.
-   **Local Backup Manager**:
    -   **Complete ZIP Backups**: Database + preferences + attachments in shareable ZIP files.
    -   **High-Performance I/O**: 64KB-256KB buffers, NIO zero-copy for large files (>5MB).
    -   **Storage Management**: Max 5 local backups, 500MB total limit.
    -   **FileProvider Sharing**: Secure sharing of backup files.

## &#x1F510; Authentication & Entry
-   **Immersive Login Screen**:
    -   **Audio-Reactive Atmosphere**: Features a custom `welcome_intro.mp3` loop (2.5s fade-in) that drives the UI animations via real-time RMS amplitude analysis.
    -   **Liquid Particle Core**: The central `LiquidLoader` pulses in sync with the audio, creating a living, breathing start screen.
    -   **Monochrome "Pill" UI**: Minimalist, high-contrast input fields using `RoundedCornerShape(26.dp)` to match the modern design system.
    -   **Seamless Mode Switching**: Uses `AnimatedVisibility` (Fade + Expand) for smooth transitions between Login and Sign-Up modes, avoiding layout shifts.
-   **Hybrid Auth Architecture**:
    -   **Unified Manager (`AuthFeatureManager`)**: Centralizes logic for Firebase Email/Password and Google Sign-In flows.
    -   **Critical Data Wipe**: Implements a strict `signOut()` protocol that explicitly wipes all local SQLite data (`clearAllUserData`) and `SecurePreferences` *before* revoking the session token, preventing multi-tenant data leaks.
    -   **Secure Preference Sync**: Automatically syncs the authenticated email to encrypted storage for use by background services (Drive Backup, Calendar).

## &#x1F512; Privacy & Security
-   **Privacy By Design**:
    -   **Metadata Stripping Engine**: Removes 70+ EXIF tags from images and privacy data from videos/audio. Features **Raw PNG Chunk Filtering** to skip metadata without decoding the full image, saving memory on edge devices.
    -   **Private Notes**: Explicitly exclude sensitive notes from AI processing.
    -   **Contextual Shake**:
        -   **Share Privacy**: Shake during sharing to toggle "Full Privacy" Mode.
        -   **AI Exclusion**: Shake while typing to toggle AI exclusion for that specific note.
        -   **Shake Sensitivity Control**: Dynamic adjustment of sensor thresholds (0.5..5.0) to prevent accidental triggers.
        -   **Shake Glow**: A visual "cloud" of the accent color that expands from all 4 screen edges for 0.4s when a shake is detected.
    -   **App-In-Use Detection**: Stops microphone/processing when app is backgrounded.
    -   **Foreground-Only Mic**: Strict checks to ensure mic is only accessed when app is visible.
-   **Cyber-Security Hardening**:
    -   **SSL Certificate Pinning**: Recursive support for `CertificatePinner` on key domains (OpenAI, Google, Anthropic) to mathematically prevent Man-in-the-Middle (MITM) attacks.
    -   **Private-LAN Trust**: Specialized HTTP client that trusts self-signed certificates ONLY for local IP addresses (192.168.x.x), enabling secure local LLM connections.
    -   **Secure Storage**: Encrypted preferences for sensitive data (API keys) via `SecurePreferences` (`AES256_SIV` / `AES256_GCM`).

## &#x1F3A7; Media & Audio
-   **Universal Audio Engine (`AudioPlayerService`)**:
    -   **Multi-source Latency**: Can stream from YouTube, Spotify, or Local library based on the best match.
    -   **Foreground Service Topology**: Runs a prioritized `MediaSessionService` that manages audio focus and notification controls to prevent OS killing.
    -   **Global Media Controls**: Play/Pause/Skip from system media notification.
    -   **Queue Management**: Manage a playlist of audio tracks.
    -   **Mini Player**: Persistent bottom player when navigating the app.
-   **Real-Time Audio Visualization**:
    -   **3-Band FFT**: Visualizes audio using specific frequency bands: **Bass** (20-250Hz), **Mid** (250-2000Hz), and **Treble** (2000Hz+).
    -   **RMS Amplitude**: Measures overall loudness for pulse effects.
    -   **State-Driven Lifecycle**: Visualizer automatically pauses in background to save battery.
-   **AI Title Compression**: Automatically summarizes long, verbose audio titles into clean 2-3 word versions for the UI.
-   **Intelligent Audio Feedback**:
    -   **Music-Aware Notifications**: Smart manager that checks `isMusicActive` before playing completion sounds to ensure your music is never interrupted.
    -   **Foreground/Background Context**: Automatically switches between direct `MediaPlayer` playback (foreground) and custom-sound notifications (background).
-   **Device Music Intelligence**:
    -   **Fuzzy Library Search**: Searches local music by title, artist, or album with similarity scoring.
    -   **Audio Stats**: Tracks total unique artists and albums in your library.

## &#x2601;&#xFE0F; Backup & Cloud
-   **Auto Backup**:
    -   **Google Drive Integration**: Periodic backups (e.g., every 100 days) to your personal Drive.
    -   **Intelligent Scheduling**: Only runs when battery is healthy, device is charging, and WiFi is connected.
    -   **Silent Auth**: Background token refreshing via Google Silent Sign-In.

## &#x2601;&#xFE0F; Cloud & Server Architecture (Friday Server)
-   **Server-Side Agent Runtime (The "Remote Brain")**:
    -   **Ktor-based Microservice**: Lightweight Kotlin server hosting the heavy AI logic, keeping the Android app "thin" and battery-efficient.
    -   **KOOG-Inspired Reasoning Engine**:
        -   **Iterative Loop**: A robust `Agentic Loop` (Cache Check -> Stream LLM -> Tool Call -> Execute -> Feed Result -> Repeat) that handles complex multi-step tasks.
        -   **State Persistence**: Uses `AgentPersistenceManager` to save JSON checkpoints after every tool execution, ensuring the agent can recover exactly where it left off if the server crashes.
        -   **LLM Caching**: Implements a semantic `LlmCache` that returns instant results for identical queries/tools, bypassing expensive API calls.
        -   **Deep Tracing**: Logs every thought, tool call, and result into `agent_traces` for complete observability.
    -   **Hybrid Tool System**:
        -   **Dual Execution Mode**: Intelligently decides whether to execute tools **Server-Side** (e.g., `create_note`, `schedule_event` on Postgres) or emit **Device Commands** (e.g., `take_screenshot`, `launch_app`) to the phone.
        -   **State Sync**: Server-side changes immediately emit `StateSync` events to update the Android UI in real-time.
-   **Advanced RAG & Memory (PostgreSQL + pgvector)**:
    -   **Hybrid Search Algorithm**: Implements a custom SQL function `match_documents_hybrid` that combines **Vector Similarity (70%)** and **Full-Text Search (30%)** for superior recall.
        -   *Formula*: `((1 - cosine_dist) * 0.7 + ts_rank_cd * 0.3)`
    -   **Intelligent Sliding Window**: Automatically detects when conversation history exceeds 20 messages, summarizes the oldest 10 using `ConversationSummarizer`, and embeds the summary as "Episodic Memory" into the vector store.
    -   **Multi-Tenant Isolation**: strict `user_id` segregation across all vector searches and database operations.
    -   **Optimized Indexing**: Uses `HNSW` indices for vectors (fast approximate nearest neighbor) and `GIN` indices for text search.
-   **Backend Infrastructure**:
    -   **PostgreSQL Source-of-Truth**: Centralized Sync Logic for Notes, Calendar, and Timers using `HikariCP` connection pooling (optimized for cloud limits).
    -   **Reactive Architecture**: Uses **Server-Sent Events (SSE)** for real-time token streaming and status updates.
    -   **Granular Rate Limiting**:
        -   **Chat**: 120 req/min (high interactivity).
        -   **Processing**: 30 req/min (heavy tasks).
        -   **Security**: Verifies Firebase Auth tokens on every request.
-   **Connection Failover & Resilience**:
    -   **Circuit Breaker Pattern**: `ConnectionFailoverManager` tracks connection health and prevents hammering failed APIs.
    -   **Health States**: `HEALTHY`, `DEGRADED`, `CIRCUIT_OPEN`, `RECOVERING` states for intelligent routing.
    -   **Error Categorization**: `AUTH_ERROR`, `RATE_LIMIT`, `NETWORK_ERROR`, `SERVER_ERROR`, `MODEL_ERROR`, `CONTEXT_OVERFLOW`, `UNKNOWN`.
    -   **Automatic Recovery**: Tests recovery after cooldown period.
    -   **Thread-Safe**: Uses `@Volatile` fields for visibility across threads (TECH-002 fix).
-   **Global Rate Limiting**:
    -   **Sliding Window**: Per-minute limits (30 calls/min) with sliding window tracking.
    -   **Daily Budget**: 14,400 calls/day with persistent storage.
    -   **Intelligent Queuing**: Automatic wait time calculation when limits reached.
    -   **Per-Provider Distribution**: Awareness of multiple API providers.

## &#x2728; Premium Aesthetics & Interactions
-   **Cosmic Particle Loader**:
    -   **Realistic Solar System**: A physics-based particle engine rendering the Sun, Planets (with moons and rings), and 120+ parallax "Star Dust" particles with depth simulation.
    -   **Audio-Reactive Solar Pulse**: The sun's corona pulses dynamically based on `audioAmplitude` using spring-based physics.
    -   **Orbital Motion Physics**: Each planet has its own orbit period, atmospheric refraction layer, and motion-blur particle trails.
-   **Fisheye Lens Scroller**:
    -   **Gaussian Wave Effect**: A touch-activated sidebar with a physical "bulge" that magnifies and highlights letters near your finger using **Gaussian mathematical distribution**.
    -   **Touch-Responsive Sidewave**: The scroller dynamically offsets letters horizontally as you slide, creating a liquid-like interaction feel.
-   **Modern Design Language**:
    -   **Modern Soft Minimalist Foundation**: A bespoke design system based on an **8pt Airy Grid**, utilizing the **Inter (Geometric Sans-Serif)** typeface with optical tracking optimizations (`-0.02em` for headings).
    -   **Soft Shadow System**: A diffuse shadow architecture using dual-layer `spotColor` and `ambientColor` blends at low opacities (4-6%) to create a "floating" paper feel.
    -   **Electric Blue Active Glow**: Focus states utilize a 25% opacity Electric Blue glow (`0x400066FF`) for high-tech emphasis.
    -   **Super-Rounded Corners**: Employs organic `28dp` corner radii for cards and persistent elements.
-   **Interactive Tutorials**:
    -   **Ghost Hand Gesture Training**: An animated ghost hand with 4 fingers and a thumb that simulates shaking the phone, optimized with **GPU-accelerated graphicsLayer transforms**.
-   **Tactile Design System (Haptics)**:
    -   **PS5-inspired Feedback**: High-definition vibration patterns including:
        -   **Success Pulse (Double Tick)**: `[20ms, 60ms pause, 20ms]` at varying amplitudes.
        -   **Mode Switch (Ascending Wave)**: A sequence of increasing vibrations that feel like state transitions.
        -   **Archive Slide**: A smooth, sliding feedback for archiving notes.
-   **Halftone Shimmer Architecture**: Dynamic dotted texture overlay that changes direction based on state: "Left-to-Right" when the user speaks, and "Right-to-Left" when the AI is working. Optimized to consume zero frame requests when static.
-   **Gestural Fluidity**:
    -   **Rocket Fly-By Animation**: Custom flight path animation (Fly Right + Up + Tilt) with **FastOutSlowInEasing** for sent items.
    -   **Pinterest-style Radial Menu**: Long-press any item to trigger a radial gestural menu for one-handed pinning, sharing, and archiving.
-   **Organic Thinking Indicator**:
    -   **Fluid Breathing Animation**: Pulsing, breathing monochrome orb that feels alive.
    -   **Layered Soft Glows**: Multi-layer radial gradients for depth.
    -   **Wobble Effect**: Secondary animation for organic feel.
    -   **Monochrome Design**: Silver/Grey color scheme for premium aesthetic.

## &#x2699;&#xFE0F; Customization & Engineering (Invisible Engineering)
-   **Performance Optimization**:
    -   **7th-Century Trigonometry (FastMath)**: Uses **Bhaskara I's approximation** for sine/cosine, achieving 99.7% accuracy with O(1) constant time (3-5x faster than standard math).
        -   *Low-Level Formula*: `sin(x) &#8776; 16x(&#x03C0; - x) / [5&#x03C0;&#xB2; - 4x(&#x03C0; - x)]`
    -   **Pad&#x00E9; Exponential Decay**: Optimized `fastExpDecay` for physics simulations (Physics, Shimmers), bypassing heavy standard math libraries.
    -   **Audio processing GC Optimization**: Reuses `cachedByteBuffer` and pre-allocates short arrays to reduce Garbage Collection pressure during high-frequency audio/voice processing.
    -   **Lazy Decompression Engine (LazyDecompressor)**:
        -   **Wait-for-Signal Architecture**: Uses a dormant worker thread (`WorkerState.SLEEPING`) that only wakes on channel requests.
        -   **Device-Class Aware**: Automatically scales cache size (5MB to 50MB) and buffer (16KB to 128KB) based on hardware capability (EDGE..FLAGSHIP).
        -   **Priority Queueing**: Processes critical assets (`Priority.IMMEDIATE`) before background tasks.
    -   **Mention Cache Strategy**: Implements a **5-second validity cache** (`cacheValidityMs`) for note searches to prevent database hammering during rapid typing or autocomplete queries.
-   **Adaptive Resource Tiering (ResourceManager)**:
    -   **Five-Tier Device Classification**: Automatically classifies hardware into `EDGE`, `LOW`, `MEDIUM`, `HIGH`, or `FLAGSHIP` classes.
    -   **Dynamic Buffer Shaping**: Adjusts streaming buffer sizes (16KB to 128KB) and NIO thresholds based on device class and memory pressure (`onTrimMemory`).
-   **Advanced Compression Engine (FileCompressor)**:
    -   **Hybrid Tiered Compression**: Automatically chooses WebP for images, GZIP (Best Speed) for docs, and Transparent Passing for binary media.
    -   **NIO Zero-Copy Transfer**: For large file operations (>5MB), utilizes `FileChannel` to move data directly from kernel space to storage, preventing GC pressure and OOM errors.
-   **Highly Optimized State Persistence**:
    -   **SQLite Transaction Batching**: Caches database writes for `250ms` (or 20 items) in a `DatabaseWriteBatcher` to reduce disk I/O overhead by **up to 300%**.
    -   **Atomic Fault Tolerance**: Automatically falls back to individual inserts if a batch operation fails.
-   **Networking & Protocol Intelligence**:
    -   **Local-First Network Monitor**: Distinguishes between "Internet Validated" (Cloud AI) and "Local Network" (WiFi/Ethernet/VPN). Explicitly treats non-internet local connections as `CONNECTED` to ensure **Local LLM Server (Ollama/Llama.cpp)** access works offline.
    -   **Tiered Timeout Architecture**: Shared singleton `HttpClientProvider` with specific clients for `Quick` (3s metadata), `Default` (AI), and `LongRunning` (Downloads).
    -   **Shared Connection Pooling**: Enforces single-instance `OkHttpClient` to prevent thread/socket leaks.
    -   **Protocol Hardening**: Uses Server-Sent Events (SSE) for real-time Agent streaming.
-   **Command Safety & Observability**:
    -   **Strict Command Validation**: A centralized `validateCommand` layer that pre-checks all AI actions (e.g., `limit <= 100`, `content.isNotBlank`) before execution, returning explicit rejection reasons.
    -   **PII-Safe Logging**: Logs "Safe Summaries" of commands (lengths/IDs/Enums) instead of user content to maintain privacy in debug logs.
    -   **Debug Ring Buffer**: Maintains a rolling buffer of the last 20 commands in memory for instant crash-context debugging.
-   **Self-Healing & Resilience**:
    -   **Process Death Resilience**: Native AI and voice models automatically re-initialize after process death using a `VALIDITY_CACHE` to safely test native memory.
    -   **Deferred File Deletion (RX-05)**: Schedules in-use files for later deletion with background retry loops.
    -   **Smart Orphaned File Cleanup (L-004)**: Automatically cleans up partial files after failed database transactions to prevent storage bloat.
    -   **Connection Testing Suite**: Built-in tester for local servers with **TLSv1.2 & TLSv1.3 hardcasting** and automated ngrok-header injection.
    -   **Intelligent Cache Maintenance**: A `CacheCleanupWorker` runs daily (when battery is healthy) to evict entries older than 7 days, preventing storage bloat.
-   **Engineering Efficiency**:
    -   **Lifecycle-Aware Animations**: Infinite animations automatically pause when backgrounded, consuming zero frame requests.
    -   **Foreground File Service**: Specialized Android service ensuring compression and transfers never pause even if the app process is restricted.
    -   **Parallel Processing Importer**: Uses `coroutineScope` and `async` for multi-attachment processing with mutex-locked progress reporting.
-   **Note Processing Queue**:
    -   **Background Queue**: `NoteProcessingQueueManager` handles pending note processing.
    -   **Automatic Timeout**: 5-minute timeout for stuck notes (increased for slow local LLMs).
    -   **Concurrent Processing**: Up to 3 concurrent small notes (<10KB).
    -   **Retry Logic**: Max 3 retries with 5-second delay between attempts.
    -   **Event Notifications**: Emits `Retry`, `Failed`, `Completed` events for UI feedback.
-   **Share Flow Manager**:
    -   **Preview Interception**: Intercept shared content for bottom sheet preview.
    -   **Full Privacy Mode**: Toggle to disable all AI processing for specific shares.
    -   **Active Share Mode Tracking**: State management during share operations.
    -   **Related Notes Discovery**: Automatic suggestion of related notes during share.

## &#x1F6E0;&#xFE0F; Low-Level Engineering Deep-Dives

### 1. Cosmic Particle Engine (`LiquidLoader`)
*   **Physics-Based Rendering**: A high-frequency `withFrameNanos` driver propels 120+ independent particles.
*   **Atmospheric Refraction**: Calculates light angles using `atan2` relative to the sun to create realistic "dark side" shadowing on planets.
*   **Parallax Depth 2.0**: Particles have `depth` properties that influence drift speed, size, and opacity, simulating a 3D starfield on a 2D canvas.

### 2. Optimized Orb Visualizer (`OptimizedOrbVisualizer`)
*   **Exponential Smoothing**: Applies a "Fast Attack" (0.5) / "Slow Decay" (0.15) algorithm to raw audio amplitude to prevent visual jitter.
*   **3-Layer Draw Optimization**: Reduces GPU overdraw by rendering only 3 composite layers:
    1.  **Outer Glow**: GPU-accelerated radial gradient.
    2.  **Core Orb**: Breathing effect using pre-computed Sine approximation.
    3.  **Amplitude Ring**: Stroke-based ring that expands/contracts with volume.

### 3. Adaptive Search Heuristics (`AdaptiveSemanticSearchEngine`)
*   **Threshold-Driven Strategy**: Detects content characteristics in real-time to adjust retrieval weights:
    *   **Keyword Density (>15%)**: Shifts to `KEYWORD_HEAVY` (0.5 Keyword / 0.3 Semantic / 0.2 Vector).
    *   **Semantic Clustering (>0.7)**: Shifts to `SEMANTIC_HEAVY` (0.2 Keyword / 0.6 Semantic / 0.2 Vector).
    *   **Document Volume (>1000 chars avg)**: Shifts to `VECTOR_HEAVY` (0.2 Keyword / 0.3 Semantic / 0.5 Vector).
*   **Temporal Normalization**: Recurrent content is weighted via `1.0 / (1.0 + (avgAge / (30 days)))`, ensuring relevance decays mathematically over time.

### 4. Audio Signal "Piping" Pipeline (`AudioTranscriber`)
*   **Hardware Extraction**: Utilizes `MediaCodec` and `MediaExtractor` to pull raw PCM samples from compressed formats (MP3/AAC) at 16,000Hz (Mono).
*   **Internal Mic Simulation**: 
    *   **Writer**: Pipes PCM bytes into an `AudioTrack` output stream.
    *   **Listener**: Triggers the system `SpeechRecognizer` in parallel.
    *   *Result*: Effectively "tricks" the OS speech engine into processing local files as live voice input, bypassing server-side transcription costs.
*   **Low-Level Binary Framing**: Manually constructs 44-byte WAV headers using `ByteBuffer` with `ByteOrder.LITTLE_ENDIAN` for compatibility with legacy Android media APIs.

### 5. O(1) Content Discovery (`ContentTypeDetector`)
*   **Pre-Compiled Regex Lexicon**: Uses a static pool of pre-compiled `Regex` patterns for Idea/Task/Code detection.
*   **MIME Hash-Map Lookup**: Reduces `detectTypeFromMime` calls to actual O(1) average time by avoiding long `if-else` chains in favor of a `HashMap<String, NoteType>`.

### 6. Animation Physics Math (`AnimationMath`)
*   **C2-Continuous Transitions**: Uses `smootherStep` logic (`t^3 * (t * (t * 6 - 15) + 10)`) instead of standard `smoothStep`, resulting in zero acceleration at endpoints (Zero Derivative) for a more premium, "liquid" feel.
*   **Shortest-Arc Interpolation**: Specialized `lerp` logic for rotations that ensures transformations never take the "long way" around the circle (360-degree wrapping).

### 7. Request Batching Engine (`RequestBatcher`)
*   **DataLoader Pattern**: Aggregates multiple requests within a batch window (50ms) into single operations.
*   **Concurrent Request Handling**: Uses `CompletableDeferred` for async result delivery.
*   **Deduplication**: Reuses pending requests for identical keys.
*   **Statistics**: Tracks batching efficiency (0 = no batching, 1 = perfect batching).

### 8. Audio Engine Internals (`AudioPlayerService`)
*   **3-Band Frequency Analysis**: Uses `Visualizer` to capture FFT data and bucketize into Bass (20-250Hz), Mid (250-2000Hz), and Treble (2000Hz+) for precise visualization.
*   **Psychoacoustic Boosting**: Applies frequency-dependent gain (Bass * 1.5, Treble * 1.2) to match human loudness perception curves.
*   **Foreground/Background Resource Throttling**: Dynamically switches UI update intervals from **100ms** (foreground) to **1000ms** (background) to save battery while keeping notifications active.

### 9. Conversation Summarization (`ConversationSummarizer`)
*   **Privacy-Safe Summaries**: Never includes raw private content, only abstract descriptions.
*   **Trigger Conditions**: New session, 30+ min inactivity, or 15+ messages.
*   **Token Efficiency**: Max 200 chars per message in summary context, max 20 messages.
*   **AI-Generated**: Uses dedicated system prompt for concise, factual summaries.

### 10. Core Workflows & Logic

### 1. The "Agentic Loop" (AI Request Lifecycle)
*   **Trigger**: User input (Text, Voice, Image) or Scheduled Task.
*   **Contextualization**:
    *   **RAG Retrieval**: Fetches relevant notes via `match_documents_hybrid` (Vector + Full-Text).
    *   **System State**: Injects battery, time, location, and active screen context.
    *   **Short-Term Memory**: Retrieves recent conversation history (last 10 messages).
*   **Reasoning (Server-Side)**:
    *   **Validates** request against safety protocols (PII, Rate Limits).
    *   **Decides** on Tool Usage (e.g., `create_note` vs `web_search`).
*   **Execution**:
    *   **Server-Side Tools**: Directly modifies PostgreSQL (Notes, Calendar) -> Emits `StateSync` to device.
    *   **Device Commands**: Sends JSON command to Android -> App executes (e.g., "Take Screenshot").
*   **Response**: Streams text/results back via SSE.

### 2. Daily Briefing Intelligence (`DailyBriefingWorker`)
*   **Schedule**: Runs automatically at **7:30 AM** daily.
*   **Context Gathering (72h Window)**: Aggregates the last **3 days** of modified notes, upcoming 3 days of events, and recent chat history.
*   **AI Synthesis**: Generates a structured briefing including:
    *   **Priorities**: Top 3 tasks for the day.
    *   **Encouragement**: Personal encouragement based on stuck projects.
    *   **Memory Extraction**: Identifies and extracts new user preferences (`---MEMORY---`) for the long-term vector store.

### 3. Voice-to-Action Pipeline (Hybrid Execution)
*   **Wake Word**: "Friday" detected by **Vosk** (Offline, High-Sensitivity).
*   **Transcription**: Google SpeechRecognizer converts audio to text.
*   **Preprocessing**: Strips polite fillers ("please", "hey smarty") and normalized punctuation.
*   **Fast-Path Check (`LocalCommandProcessor`)**: 
    *   **0ms Latency**: Checks `LocalCommandProcessor` for exact matches (Flashlight, Volume, Time).
    *   **Hybrid Chaining**: Detects "Task Words" (e.g., "play music AND remind me") to optionally execute a local command AND pass the request to the LLM.
    *   *Match?* -> Execute locally.
    *   *No Match?* -> Send to Server Agent.

### 4. Smart Content Clipping (Reader Mode)
*   **Input**: URL shared from Chrome/Twitter to Smarty.
*   **Extraction**: `ContentExtractor` strips ads, nav bars, and scripts.
*   **Summarization**: Agent generates a 3-line summary.
*   **Storage**: Saves as a new Note with `LINK` type, auto-tagged with domain and topic.

### 5. Hybrid Sync Architecture
*   **Write Path**: Device -> Background Worker -> API -> Postgres (Source of Truth).
*   **Read Path**: Server Update -> SSE Event -> Device -> Room DB (Local Cache).
*   **Conflict Resolution**: High-Water Mark Logic (Last-Write-Wins based on Server Timestamp).

## &#x1F6E0;&#xFE0F; Technology Stack

### Android Client (App)
-   **Language**: Kotlin (JDK 17)
-   **UI Toolkit**: Jetpack Compose (Material 3)
-   **Architecture**: MVVM + Clean Architecture (Service Locator Pattern)
-   **Asynchronous**: Kotlin Coroutines & Flow
-   **Database**: Room (SQLite) with Paging 3
-   **Network**: OkHttp 4, Ktor Client (Engine: OkHttp), Server-Sent Events (SSE)
-   **Media**: Media3 ExoPlayer (Audio), Coil (Image Loading)
-   **AI/Speech**:
    -   **Vosk**: Offline Speech-to-Text
    -   **Android SpeechRecognizer**: Online fallback
    -   **TFLite**: On-device ML (optional)
-   **Background Work**: WorkManager (Scheduled Tasks), Foreground Services (Audio/File Transfer)
-   **Serialization**: Kotlinx Serialization, Gson
-   **Security**: EncryptedSharedPreferences (JetPack Security), Firebase Auth

### Server (Friday Server)
-   **Framework**: Ktor Server (Netty Engine)
-   **Language**: Kotlin (JDK 17)
-   **Agent Framework**: KOOG (Knowledge-Oriented Object Graph) - Custom Integration
-   **Database Access**: HikariCP (Connection Pooling), JDBC (Raw SQL for performance)
-   **Observability**: Micrometer (Prometheus Metrics), Logstash Logback (Structured Logging)
-   **PDF Processing**: Apache PDFBox 3.0
-   **Authentication**: Firebase Admin SDK

### Data & Infrastructure
-   **Database**: PostgreSQL 15+
-   **Extensions**:
    -   `vector` (pgvector): For high-dimensional embedding search
    -   `gin`: For accelerated Full-Text Search
-   **Containerization**: Docker (Fat JAR deployment)
-   **Hosting**: Compatible with any Docker-supported environment (HuggingFace Spaces, Railway, AWS ECS)

## &#x1F3E0; Comprehensive Engineering Specifications

### Architecture Pattern
-   **Core**: Clean Architecture (Presentation -> Domain -> Data) with Feature-First packaging.
-   **Dependency Injection**: Custom lightweight `ServiceLocator` (Singleton Object) enabling lazy initialization of feature managers without the overhead of Dagger/Hilt.
-   **Feature Managers**: Modular managers for each feature domain (Audio, Auth, Calendar, Chat, Search, Settings, System, Voice, etc.).
-   **State Management**:
    -   **SharedAppState**: A centralized singleton for cross-feature state (e.g., Navigation, Active Note ID).
    -   **Reactive Flows**: Extensive use of `StateFlow` and `SharedFlow` for UI updates.
-   **Concurrency**:
    -   **SupervisorJob**: Used in `ApplicationScope` to ensure critical background tasks (like backups) survive individual failures.
    -   **Dispatchers.IO/Default**: Strictly enforced for database and heavy computation.

### DevOps & Build Infrastructure
-   **Build System**: Gradle Kotlin DSL (`.kts`) with Version Catalog (`libs.versions.toml`) for centralized dependency management.
-   **Static Analysis**: `Detekt` configured with custom rules (`detekt.yml`) and baseline support (`baseline.xml`) to enforce code quality.
-   **Memory Leak Detection**: `LeakCanary` integrated in debug builds to catch Activity/Fragment leaks during development.
-   **Local Development**: `docker-compose.yml` orchestrates a local PostgreSQL instance with the `ankane/pgvector` image and auto-initializes schemas via `init-db.sql`.
-   **Tunneling**: `cloudflared` and `ngrok` executables included for exposing localhost to Android devices during testing.

### Testing Strategy
-   **Unit Testing**:
    -   **JUnit 5**: Standard test runner.
    -   **MockK**: For mocking repositories and services.
    -   **Turbine**: Specialized library for testing Kotlin Flows and ensuring correct state emissions.
-   **Instrumented Testing**:
    -   **Espresso**: For UI interactions.
    -   **Compose Test Rule**: For native Jetpack Compose UI testing.
    -   **Idling Resources**: To handle asynchronous background operations during UI tests.

### Database Schema Specifications
-   **Agent Context (`agent_context`)**:
    -   **Vector Storage**: Stores 1536-dimensional embeddings for RAG retrieval.
    -   **Indexing**: Uses `HNSW` (Hierarchical Navigable Small World) index with `vector_cosine_ops` for O(log N) similarity search.
    -   **Hybrid Search**: Uses `GIN` index on `to_tsvector('english', content)` for full-text search capability.
-   **Multi-Tenancy**:
    -   **Row-Level Isolation**: All tables (`chat_sessions`, `chat_messages`, `agent_context`, `file_uploads`) enforce strict `user_id` checking.
    -   **Indexes**: Dedicated B-Tree indexes on `user_id` for all major tables to prevent scan leakage.
-   **Caching Layer (`ai_cache`)**:
    -   **Hash-Based Retrieval**: Uses content hashing for O(1) cache lookups.
    -   **TTL Management**: `expires_at` column with indexed cleanup for automated cache eviction.

### Protocol & State Management
-   **Chat State Management (`ChatManager`)**:
    -   **Mutex-Locked Concurrency**: Uses `kotlinx.coroutines.sync.Mutex` to prevent race conditions during rapid message updates or session switching.
    -   **Preserved State**: Maintains `preservedChatMessages` and `preservedProcessingState` in memory during quick context switches (Note -> Chat -> Note) to prevent unnecessary re-fetching.
    -   **Error Observability**: Exposes a `lastError` StateFlow for UI error handling (e.g., failed message saves).
-   **Data Models**:
    -   **Analysis Results**: Structured, serializable data classes (`ContentAnalysisResult`, `DocumentAnalysisResult`) ensure type safety across the network boundary.
    -   **History Compression**: `HistoryCompressor` actively reduces token usage by keeping the last 3 exchanges verbatim and summarizing older ones, achieving 50-70% reduction.
