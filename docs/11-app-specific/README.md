---
title: App Specific
category: app-specific
--

# App Specific

This section contains documentation specific to the Smarty Android application implementation, including UI guides, component specifications, and platform-specific features.

## Android Implementation

### Core Components

#### UI Layer
- **Jetpack Compose**: Modern declarative UI framework
- **Material Design 3**: Google's design system
- **Compose Navigation**: In-app navigation
- **Compose Animations**: Smooth transitions and feedback

#### Architecture
- **MVVM Pattern**: Model-View-ViewModel architecture
- **ViewModel**: UI-related data holder
- **LiveData/Flow**: Observable data holders
- **Repository**: Data abstraction layer
- **Room**: Local database

#### Key Features

##### 1. [UI Properties Guide](./UI_PROPERTIES_GUIDES.md)
Comprehensive guide to UI properties, styling, and customization options.

##### 2. Inline Calendar Implementation
- **[Inline Calendar Implementation](./INLINE_CALENDAR_IMPLEMENTATION.md)** - Embedded calendar component

### Technical Architecture

```
┌─────────────────────────────────────────────┐
│              Android Application             │
│                                             │
│  ┌─────────────────┐    ┌────────────────┐  │
│  │    UI Layer     │    │  Domain Layer  │  │
│  │                 │    │                │  │
│  │  ┌───────────┐  │    │  ┌──────────┐  │  │
│  │  │  Screens  │  │    │  │  Use     │  │  │
│  │  │           │  │    │  │  Cases   │  │  │
│  │  └─────┬─────┘  │    │  └─────┬────┘  │  │
│  │        │         │    │        │       │  │
│  │  ┌─────┴─────┐  │    │  ┌─────┴────┐  │  │
│  │  │ ViewModel │◄─┼────┼──┤   Repo   │  │  │
│  │  └─────┬─────┘  │    │  └─────┬────┘  │  │
│  │        │         │    │        │       │  │
│  └────────┼─────────┘    └────────┼────────┘  │
│           │                       │          │
│  ┌────────┴─────────┐    ┌────────┴────────┐  │
│  │   Data Layer     │    │  Network Layer  │  │
│  │                 │    │                │  │
│  │  ┌───────────┐  │    │  ┌──────────┐  │  │
│  │  │   Room    │  │    │  │  Ktor    │  │  │
│  │  │ Database  │  │    │  │  Client  │  │  │
│  │  └───────────┘  │    │  └──────────┘  │  │
│  └──────────────────┘    └─────────────────┘  │
└─────────────────────────────────────────────┘
```

## UI Implementation Details

### Compose Components

#### Custom Components
- **NoteCard**: Displays individual notes with metadata
- **ChatBubble**: Message display with citations
- **ResearchProgress**: Visual research status indicator
- **BreathingAnimation**: Guided breathing exercise UI
- **CoinToss**: 3D coin flip animation

#### Theming
```kotlin
val ColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = Blue40,
    background = Gray10,
    surface = White,
    error = Red40
)
```

### Navigation
```kotlin
NavHost(navController, startDestination = "chat") {
    composable("chat") { ChatScreen() }
    composable("notes") { NotesScreen() }
    composable("research") { ResearchScreen() }
    composable("settings") { SettingsScreen() }
}
```

## Key Features Implementation

### 1. Deep Research Agent

#### Architecture
```kotlin
class ResearchAgent : BaseAgent() {
    override suspend fun execute(task: Task): ResearchResult {
        // 1. Analyze requirements
        val clarification = analyzeTask(task)
        
        // 2. Execute searches
        val sources = concurrentSearch(clarification)
        
        // 3. Process and synthesize
        val synthesis = synthesizeContent(sources)
        
        // 4. Generate citations
        val citations = generateCitations(sources)
        
        // 5. Create structured notes
        return createResearchNotes(synthesis, citations)
    }
}
```

#### Features
- Multi-step research workflows
- Concurrent web searches
- Source credibility scoring
- Automatic citation generation
- ACH matrix analysis
- Cognitive bias detection

### 2. Image Generation

#### Krea AI Integration
```kotlin
class ImageGenerator {
    suspend fun generate(prompt: String): ImageResult {
        val enhancedPrompt = enhancePrompt(prompt)
        return kreaApi.generate(
            prompt = enhancedPrompt,
            model = "flux.1-dev",
            aspectRatio = "16:9"
        )
    }
}
```

#### Features
- Art Director-style prompt enhancement
- Multiple aspect ratios
- Professional prompt crafting
- Real-time generation status
- Remix functionality

### 3. Vision & Document Processing

#### OCR Pipeline
```kotlin
class VisionProcessor {
    suspend fun processImage(image: Image): VisionResult {
        // Extract text
        val text = ocrEngine.extract(image)
        
        // Analyze content
        val analysis = mlModel.analyze(image)
        
        // Extract structure
        val structure = documentParser.parse(text)
        
        return VisionResult(text, analysis, structure)
    }
}
```

#### Features
- OCR text extraction
- Image analysis and description
- Table and form extraction
- Document structure detection
- PDF processing (50 pages)

### 4. Thinking Persistence

#### Implementation
```kotlin
class ThinkingPersistence {
    fun saveReasoning(sessionId: String, reasoning: Reasoning) {
        // Save to database
        database.reasoningDao().insert(reasoning)
        
        // Update UI state
        viewModel.updateReasoning(reasoning)
        
        // Stream to UI
        eventBus.emit(ReasoningUpdate(reasoning))
    }
}
```

#### Features
- Real-time reasoning display
- Collapsible sections
- Persistent storage
- Recovery after restart
- Progressive saving

### 5. Multi-Agent Architecture

#### Agent Switching
```kotlin
class AgentManager {
    fun switchAgent(type: AgentType): Agent {
        return when (type) {
            NORMAL -> NormalAgent()
            RESEARCH -> ResearchAgent()
            ADVANCED_RESEARCH -> AdvancedResearchAgent()
            MEDICAL -> MedicalAdvisor()
        }.also { currentAgent = it }
    }
}
```

#### Agent Types
- **Normal Agent**: General tasks, full tool access
- **Research Agent**: Structured research, web search
- **Advanced Research**: Professional intelligence, ACH matrix
- **Medical Advisor**: Health consultations, symptom analysis

### 6. Wellness Features

#### Breathing Exercises
```kotlin
class BreathingExercise {
    fun start478Technique() {
        // 4 second inhale
        animateBreath(Inhale, duration = 4000)
        
        // 7 second hold
        animateHold(duration = 7000)
        
        // 8 second exhale
        animateBreath(Exhale, duration = 8000)
        
        // Haptic feedback
        haptic.strong()
    }
}
```

#### Features
- 4-7-8 breathing technique
- Visual animations
- Haptic feedback
- Mental health assessments
- Symptom analysis

### 7. Audio Features

#### Media3 Integration
```kotlin
class AudioPlayer {
    private val exoPlayer = ExoPlayer.Builder(context).build()
    
    fun play(track: Track) {
        val mediaItem = MediaItem.fromUri(track.uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }
}
```

#### Features
- Full playback controls
- Next/previous navigation
- Seek functionality
- Playlist support
- Device audio discovery

### 8. Daily/Weekly Digest

#### Implementation
```kotlin
class DigestGenerator {
    suspend fun generateDailyDigest(): Digest {
        // Collect activities
        val activities = activityRepository.getDailyActivities()
        
        // Generate summary with AI
        val summary = aiService.summarize(activities)
        
        // Create notification
        return Digest(summary, activities)
    }
}
```

#### Features
- Configurable schedule
- Activity synthesis
- Goal progress tracking
- Priority identification
- Calendar integration

## Database Implementation

### Room Schema
```kotlin
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: String,
    val content: String,
    val createdAt: Long,
    val category: String,
    val tags: List<String>
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun getAll(): Flow<List<Note>>
    
    @Insert
    suspend fun insert(note: Note)
}
```

### Database Features
- FTS5 full-text search
- Type converters for complex types
- Foreign key constraints
- Transaction support
- Migration paths

## Network Implementation

### Ktor Client
```kotlin
val client = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        })
    }
    
    install(Logging)
    
    defaultRequest {
        url("https://api.smarty.com/")
        header("Authorization", "Bearer $token")
    }
}
```

### Features
- SSE streaming for real-time updates
- WebSocket for bidirectional communication
- Request/response interceptors
- Automatic retry with backoff
- Offline queue management

## Security Implementation

### Encryption
```kotlin
class EncryptionManager {
    fun encrypt(data: String): EncryptedData {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(data.toByteArray())
        return EncryptedData(encrypted, cipher.iv)
    }
}
```

### Security Features
- AES-256 encryption at rest
- TLS 1.3 for network traffic
- Certificate pinning
- Biometric authentication
- Secure credential storage

## Testing Strategy

### Unit Tests
```kotlin
@Test
fun `note creation saves to database`() = runTest {
    // Given
    val note = createTestNote()
    
    // When
    noteDao.insert(note)
    val result = noteDao.getAll().first()
    
    // Then
    assertEquals(1, result.size)
    assertEquals(note.id, result[0].id)
}
```

### UI Tests
```kotlin
@Test
fun `clicking note opens detail screen`() {
    composeTestRule.setContent {
        MyAppTheme { NoteListScreen() }
    }
    
    composeTestRule.onNodeWithText("Test Note").performClick()
    composeTestRule.onNodeWithText("Note Detail").assertIsDisplayed()
}
```

## Performance Optimization

### Memory Management
- Object pooling for frequently created objects
- Lazy initialization of heavy components
- Bitmap pooling for images
- Efficient list rendering with LazyColumn

### Battery Optimization
- WorkManager for background tasks
- Battery-efficient network polling
- Sensor usage optimization
- Doze mode compliance

### Network Optimization
- Request batching
- Response caching
- GZIP compression
- Connection pooling

## Build Configuration

### Gradle Setup
```gradle
android {
    compileSdk 34
    
    defaultConfig {
        minSdk 26
        targetSdk 34
        versionCode 600
        versionName "6.0.0"
    }
    
    buildFeatures {
        compose true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion '1.5.0'
    }
}
```

## CI/CD Pipeline

### GitHub Actions
```yaml
name: Android CI

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK
        uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Build
        run: ./gradlew assembleDebug
      - name: Test
        run: ./gradlew test
```

## Documentation

### Code Documentation
- KDoc for all public APIs
- Inline comments for complex logic
- README for each module
- Architecture decision records

### User Documentation
- In-app help system
- Tutorial screens
- FAQ section
- Troubleshooting guide

## Resources

### Implementation Guides
- **[UI Properties Guide](./UI_PROPERTIES_GUIDES.md)** - UI customization guide
- **[Inline Calendar Implementation](./INLINE_CALENDAR_IMPLEMENTATION.md)** - Calendar component guide

### Related Documentation
- **[System Overview](./../02-architecture/SYSTEM_OVERVIEW.md)** - Architecture details
- **[Development Guide](./../03-development/README.md)** - Implementation patterns
- **[API Documentation](./../README.md#api-endpoints)** - API specifications

---

**Version 6.0.0** | **Last Updated:** 2026-05-03