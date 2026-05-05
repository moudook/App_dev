---
title: Reference
category: reference
--

# Reference

This section contains technical references, guides, and documentation for various technologies, APIs, and systems used in the Smarty platform.

## Technical References

### AI and Machine Learning

#### [AI Memory Sync Explained](./AI_MEMORY_SYNC_EXPLAINED.md)
Comprehensive guide to AI memory synchronization mechanisms.

### Database and API

#### [Database vs API Explanation](./DATABASE_VS_API_EXPLANATION.md)
Comparison and guidance on when to use database storage vs API calls.

#### [PDF Processing Pipeline](./PDF_PROCESSING_PIPELINE.md)
Complete guide to PDF processing and text extraction.

### Development and Configuration

#### [Git Configuration Summary](./GIT_CONFIGURATION_SUMMARY.md)
Git configuration and best practices for the project.

#### [LM Studio Setup](./LM_STUDIO_SETUP.md)
Guide for setting up LM Studio for local LLM development.

#### [Migration Report](./MIGRATION_REPORT.md)
Database and system migration documentation.

#### [Security Guide](./SECURITY.md)
Security best practices and implementation guidelines.

#### [Shake Fix Instructions](./SHAKE_FIX_INSTRUCTIONS.md)
Guide for implementing shake gesture detection and actions.

#### [Simple Cost Explanation](./SIMPLE_COST_EXPLANATION.md)
Breakdown of operational costs and pricing models.

#### [Visualizer Improvement Task](./VISUALIZER_IMPROVEMENT_TASK.md)
Guide for improving visualization components.

#### [Gradient Effect How-To](./GRADIENT_EFFECT_HOW_TO.md)
Implementation guide for gradient effects in UI.

## API References

### Core APIs

#### Research API
- **POST** `/api/v1/research/start` - Start research session
- **POST** `/api/v1/research/{id}/answer` - Submit clarification answers
- **POST** `/api/v1/research/{id}/interrupt` - Interrupt research
- **GET** `/api/v1/research/{id}/timeout` - Check timeout status
- **GET** `/api/v1/research/{id}` - Retrieve research session

#### Chat API
- **SSE** `/chat/stream` - Streaming chat responses
- **POST** `/chat/query` - Chat with file attachments
- **POST** `/chat/events` - Receive client events
- **POST** `/briefing/generate` - Generate daily briefing
- **GET** `/api/v1/chat/sessions` - List chat sessions
- **POST** `/api/v1/chat/sessions` - Create chat session

#### Image & Vision API
- **POST** `/api/v1/image/direct` - Direct image generation
- **POST** `/process/image` - Image OCR/analysis
- **POST** `/process/pdf` - PDF text extraction
- **POST** `/upload` - File upload for processing

#### Content Analysis API
- **POST** `/analyze/content` - Analyze text content
- **POST** `/analyze/document` - Analyze documents

#### Data Management API
- **GET/POST** `/api/v1/notes` - Note management
- **GET/POST/DELETE** `/api/v1/calendar` - Calendar events
- **GET/POST/DELETE** `/api/v1/timers` - Timer management
- **GET/POST/DELETE** `/api/v1/vault` - Zero-knowledge vault
- **GET** `/api/v1/export/all` - Export all data
- **POST/DELETE** `/api/v1/calendar/events/{eventId}/notes/{noteId}` - Link notes to events

#### Tasks & Organization API (v6.0.0)
- **GET/POST/PATCH/DELETE** `/api/tasks` - Task management
- **GET/POST/DELETE** `/api/tags` - Tag management
- **GET/POST/PUT/DELETE** `/api/chat/folders` - Chat folder management
- **GET/POST/DELETE** `/api/notifications` - Notification management

#### Synchronization API
- **POST** `/api/v1/sync/pull` - Pull changes
- **POST** `/api/v1/sync/push` - Push changes

#### Session & Health API
- **POST** `/api/v1/session/init` - Session initialization
- **GET** `/health` - Health check
- **GET** `/metrics` - Metrics endpoint
- **POST** `/api/v1/fcm/register` - FCM token registration

## Technology Stack References

### Android Client

#### Core Technologies
- **Kotlin**: Primary programming language
- **Jetpack Compose**: Declarative UI framework
- **Room**: Local database with FTS5
- **Media3 ExoPlayer**: Audio/video playback
- **OkHttp**: HTTP client
- **Ktor Client**: Networking library
- **Server-Sent Events**: Real-time streaming
- **Firebase Cloud Messaging**: Push notifications
- **Google Drive API**: Cloud backup
- **Speech-to-Text API**: Voice input

#### Architecture Components
- **MVVM**: Architecture pattern
- **ViewModel**: UI state management
- **LiveData/Flow**: Reactive data streams
- **Coroutines**: Asynchronous programming
- **Room**: Data persistence
- **DataStore**: Preferences storage

### Server Technologies

#### Backend Framework
- **Ktor**: Asynchronous server framework
- **PostgreSQL**: Primary database
- **pgvector**: Vector embeddings
- **HikariCP**: Connection pooling
- **Apache PDFBox**: PDF processing

#### AI Integration
- **Tavily Search API**: Web search
- **Krea AI API**: Image generation
- **Gemini API**: LLM provider
- **OpenAI API**: LLM provider
- **Multi-provider routing**: Intelligent LLM selection

#### Infrastructure
- **Hugging Face Spaces**: Deployment platform
- **Docker**: Containerization
- **Firebase Authentication**: Auth system
- **FCM**: Push notifications

## Development References

### Code Standards

#### Kotlin Best Practices
```kotlin
// Use coroutines for async operations
viewModelScope.launch {
    val result = repository.fetchData()
    _uiState.value = result
}

// Use sealed classes for state management
sealed class UiState {
    object Loading : UiState()
    data class Success(val data: Data) : UiState()
    data class Error(val message: String) : UiState()
}

// Use extension functions for utility
fun String.isValidEmail(): Boolean {
    return Patterns.EMAIL_ADDRESS.matcher(this).matches()
}
```

#### Compose Best Practices
```kotlin
// Hoist state to parent
@Composable
fun ParentScreen() {
    var text by remember { mutableStateOf("") }
    ChildComponent(text = text, onTextChange = { text = it })
}

// Use remember for expensive calculations
val sortedList = remember(items) {
    items.sortedBy { it.name }
}

// Handle side effects properly
LaunchedEffect(key1 = viewModel.uiState) {
    // Handle state changes
}
```

### Testing Guidelines

#### Unit Testing
```kotlin
@Test
fun `repository returns data when successful`() = runTest {
    // Given
    coEvery { apiService.getData() } returns testData
    
    // When
    val result = repository.getData()
    
    // Then
    assertEquals(testData, result)
}
```

#### UI Testing
```kotlin
@Test
fun `button click shows message`() {
    composeTestRule.setContent {
        MyAppTheme {
            MyScreen()
        }
    }
    
    composeTestRule.onNodeWithText("Click me").performClick()
    composeTestRule.onNodeWithText("Message shown").assertIsDisplayed()
}
```

## Database References

### Schema Design

#### Core Tables
```sql
-- Users table
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Chat sessions
CREATE TABLE chat_sessions (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    title VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW()
);

-- Messages with reasoning
CREATE TABLE messages (
    id UUID PRIMARY KEY,
    session_id UUID REFERENCES chat_sessions(id),
    content TEXT,
    reasoning TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Notes with embeddings
CREATE TABLE notes (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    content TEXT,
    embedding vector(1536),
    created_at TIMESTAMP DEFAULT NOW()
);
```

### Query Optimization

#### Indexing Strategy
```sql
-- Vector search index
CREATE INDEX idx_notes_embedding ON notes USING ivfflat (embedding vector_cosine_ops);

-- Common query indexes
CREATE INDEX idx_messages_session ON messages(session_id);
CREATE INDEX idx_notes_user ON notes(user_id);
CREATE INDEX idx_sessions_user ON chat_sessions(user_id);
```

## Security References

### Authentication Flow
```
1. User initiates login
2. Firebase Authentication verifies credentials
3. JWT token generated
4. Token sent to client
5. Client includes token in API requests
6. Server validates token
7. Access granted/denied based on permissions
```

### Encryption Strategy
- **At Rest**: AES-256 encryption for database
- **In Transit**: TLS 1.3 for all communications
- **Client-Side**: User-controlled encryption keys
- **Zero-Knowledge**: Encrypted vault with user keys only

## API Documentation Standards

### Request Format
```json
{
  "timestamp": "2026-05-03T18:44:31Z",
  "requestId": "uuid-v4",
  "sessionId": "uuid-v4",
  "payload": {
    // Request-specific data
  }
}
```

### Response Format
```json
{
  "success": true,
  "timestamp": "2026-05-03T18:44:31Z",
  "requestId": "uuid-v4",
  "data": {
    // Response data
  },
  "metadata": {
    "processingTime": 123,
    "version": "6.0.0"
  }
}
```

### Error Format
```json
{
  "success": false,
  "timestamp": "2026-05-03T18:44:31Z",
  "requestId": "uuid-v4",
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable error message",
    "details": {
      // Additional error details
    }
  }
}
```

## Monitoring References

### Key Metrics

#### Application Metrics
- Request latency (p50, p95, p99)
- Error rates by endpoint
- Throughput (requests/second)
- Active users
- Session duration

#### System Metrics
- CPU utilization
- Memory usage
- Disk I/O
- Network throughput
- Database connections

#### Business Metrics
- User engagement
- Feature adoption
- Task completion rate
- User satisfaction

## Troubleshooting Guides

### Common Issues

#### Database Connection Issues
```bash
# Test connection
psql -h localhost -U smarty_user -d smarty_db -c "SELECT 1;"

# Check active connections
SELECT * FROM pg_stat_activity;

# Check table sizes
SELECT schemaname, tablename, pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size FROM pg_tables WHERE schemaname = 'public' ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
```

#### Performance Issues
```bash
# Check slow queries
SELECT query, mean_time, calls FROM pg_stat_statements ORDER BY mean_time DESC LIMIT 10;

# Check index usage
SELECT schemaname, tablename, indexname, idx_scan FROM pg_stat_user_indexes ORDER BY idx_scan;
```

## Additional Resources

### Documentation
- **[System Overview](./../02-architecture/SYSTEM_OVERVIEW.md)** - System analysis
- **[API Endpoints](./../README.md#api-endpoints)** - Complete API documentation
- **[Database Schema](./../README.md#database)** - Database design

### External Resources
- [Ktor Documentation](https://ktor.io/docs/)
- [Jetpack Compose Documentation](https://developer.android.com/jetpack/compose)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)
- [Firebase Documentation](https://firebase.google.com/docs)

---

**Version 6.0.0** | **Last Updated:** 2026-05-03