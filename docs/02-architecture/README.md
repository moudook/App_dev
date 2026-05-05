---
title: Architecture
category: architecture
--

# Architecture

This section contains comprehensive documentation about the Smarty system architecture, design patterns, and technical implementation details.

## System Overview

Smarty follows a thin-client architecture with a sophisticated multi-agent backend system. The architecture is designed for scalability, maintainability, and performance.

## Architecture Diagram

```
┌─────────────────────────┐
│ Smarty Android Client   │
│ │                       │
│ │  Jetpack Compose UI   │
│ │  Room Database        │
│ │  Media3 ExoPlayer     │
└───────────┬─────────────┘
            │ HTTPS / SSE
            ▼
┌─────────────────────────┐
│ Smarty Server           │
│ │                       │
│ │  Ktor Server          │
│ │  Multi-Agent System   │
│ │  Tool Orchestration   │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│ External Services       │
│ │                       │
│ │  LLM Providers        │
│ │  Tavily Search API    │
│ │  PostgreSQL + pgvector│
└─────────────────────────┘
```

## Core Components

### Android Client
- **Jetpack Compose**: Modern declarative UI framework
- **Room Database**: Local persistence with FTS5 full-text search
- **Media3 ExoPlayer**: Audio/video playback capabilities
- **Ktor Client**: HTTP client for server communication
- **Server-Sent Events**: Real-time streaming responses

### Server Backend
- **Ktor Framework**: Asynchronous server for high performance
- **Multi-Agent System**: Coordinated specialized AI agents
- **Tool Orchestration**: Dynamic tool selection and execution
- **PostgreSQL**: Primary database with pgvector for embeddings
- **HikariCP**: Connection pooling for optimal performance

### External Services
- **LLM Providers**: Gemini, OpenAI with intelligent routing
- **Tavily Search**: Web search and content extraction
- **Krea AI**: Image generation with Flux.1 Dev model
- **Firebase**: Authentication and cloud messaging

## Design Patterns

### MVVM Architecture
- **Model**: Room entities, data classes
- **View**: Jetpack Compose UI components
- **ViewModel**: State management, business logic

### Repository Pattern
- Abstracts data sources
- Provides clean API for data access
- Handles caching and synchronization

### Multi-Agent Pattern
- Specialized agents for different tasks
- Dynamic agent switching
- Coordinated execution workflows

### Circuit Breaker Pattern
- Prevents cascading failures
- Automatic failover to alternative providers
- Graceful degradation

## Data Flow

### User Interaction Flow
1. User interacts with Compose UI
2. ViewModel processes input
3. Ktor client sends request to server
4. Server routes to appropriate agent
5. Agent executes tools and searches
6. Results streamed back via SSE
7. ViewModel updates UI state

### Research Workflow
1. User initiates research query
2. Research agent analyzes requirements
3. Concurrent web searches executed
4. Sources collected and scored
5. Content analyzed and synthesized
6. Structured notes created
7. Citations generated and linked

### Data Persistence Flow
1. Data written to Room database
2. Changes tracked for synchronization
3. Periodic sync with server
4. Server updates PostgreSQL
5. Vector embeddings updated
6. Search index refreshed

## Database Schema

### Core Tables
- **Users**: User accounts and preferences
- **Chat Sessions**: Conversation history
- **Messages**: Individual chat messages
- **Notes**: Structured note cards
- **Categories**: Note organization
- **Research Sessions**: Research state and progress
- **Calendar Events**: Scheduled items
- **Tasks**: Task management
- **Tags**: Tag system with colors
- **Notifications**: User notifications
- **Vault**: Zero-knowledge encrypted storage
- **Images**: Generated image metadata

### Vector Embeddings
- Document embeddings for semantic search
- User preference embeddings
- Content similarity indexing

## API Architecture

### REST Endpoints
- Resource-oriented design
- JSON payloads
- Standard HTTP methods
- Comprehensive error handling

### Streaming Endpoints
- Server-Sent Events for real-time updates
- Progressive response streaming
- Research progress updates

### WebSocket Connections
- Bidirectional communication
- Real-time collaboration features
- Live updates and notifications

## Security Architecture

### Authentication
- Firebase Authentication
- Multi-tenant isolation
- JWT token management

### Data Protection
- Client-side encryption
- Zero-knowledge vault
- Encrypted credential storage
- HTTPS everywhere

### Access Control
- IP allowlisting
- Firewall protection
- Row-level security
- Input validation

## Scalability Considerations

### Horizontal Scaling
- Stateless server design
- Load balancer ready
- Database connection pooling
- Cache layer ready

### Performance Optimization
- Lazy loading
- Pagination
- Query optimization
- Connection pooling
- Caching strategies

### Resource Management
- Memory-efficient data structures
- Background processing
- Throttling and rate limiting
- Circuit breakers

## Monitoring and Observability

### Metrics
- Request latency
- Error rates
- Database performance
- Agent execution times

### Logging
- Structured logging
- Request tracing
- Error tracking
- Performance monitoring

### Health Checks
- Service availability
- Database connectivity
- External service status
- Resource utilization

## Extensibility

### Plugin Architecture
- Tool system for extensibility
- Agent framework for customization
- Provider abstraction for LLMs
- Modular design patterns

### Integration Points
- Webhook support
- API webhooks
- Event system
- Custom tool development

## Best Practices

### Code Quality
- Comprehensive testing
- Type safety
- Code reviews
- Documentation standards

### Security
- Regular audits
- Dependency updates
- Security headers
- Input sanitization

### Performance
- Profiling and optimization
- Load testing
- Caching strategies
- Database indexing

## Future Enhancements

- Microservices architecture
- Event-driven design
- Advanced caching layers
- Multi-region deployment
- Edge computing integration

## Documentation Index

- **[System Overview](./SYSTEM_OVERVIEW.md)** - Comprehensive system analysis
- **[AI Agent Architecture](./AI_AGENT_ARCHITECTURE.md)** - Agent system design
- **[AI Agent Processing Flow](./AI_AGENT_PROCESSING_FLOW.md)** - Agent execution workflows
- **[Agent Architecture Changes](./AGENT_ARCHITECTURE_CHANGES.md)** - Evolution and changes
- **[Architecture Diagrams](./ARCHITECTURE_DIAGRAMS.md)** - Visual representations
- **[NoteCard Processing Flow](./NOTECARD_PROCESSING_FLOW.md)** - Note management system
- **[UI Architecture](./UI_ARCHITECTURE.md)** - Client-side architecture

---

**Version 6.0.0** | **Last Updated:** 2026-05-03