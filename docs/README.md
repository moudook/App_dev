---
title: Documentation
category: docs
--

# Smarty Documentation

Welcome to the comprehensive documentation for Smarty, an advanced AI research agent platform built for Android with deep research capabilities, persistent reasoning, and multi-agent architecture.

##  Documentation Structure

This documentation is organized into the following sections:

### [00-Project Overview](./00-project-overview/)
High-level project information, features, and architecture overview.
- Main project README
- Feature list
- Technology stack
- Quick start guide

### [01-Getting Started](./01-getting-started/)
Installation and setup guides for running Smarty.
- Installation guide
- Setup procedures
- Local development setup
- Configuration instructions

### [02-Architecture](./02-architecture/)
System architecture and design documentation.
- System overview
- AI agent architecture
- Processing flows
- Architecture diagrams
- UI architecture

### [03-Development](./03-development/)
Implementation plans and development guides.
- Implementation roadmap
- Development log
- Agent integration guides
- API development
- Testing strategies

### [04-Issue Tracking](./04-issue-tracking/)
Bug reports, crash reports, and technical deep dives.
- Bug reports
- Crash analysis
- Performance issues
- Technical investigations

### [05-UI/UX Design](./05-ui-ux-design/)
Design system and user experience documentation.
- Design system
- UX master plan
- UI refinement plans
- Component showcase
- Platform-specific designs

### [06-Progress Tracking](./06-progress-tracking/)
Daily progress and task tracking.
- Daily progress reports
- Task completion tracking
- Reasoning mode progress
- Remaining issues
- Feature planning

### [07-Optimization](./07-optimization/)
Performance optimization reports and analysis.
- Executive summary
- Batch optimization reports
- Cross-analysis
- Implementation guides
- Verification results

### [08-Reference](./08-reference/)
Technical references and guides.
- AI memory sync
- Database vs API
- PDF processing
- Security guides
- API documentation

### [09-Prompts](./09-prompts/)
System prompts and model configurations.
- System prompts
- Claude model configurations
- File format prompts
- Prompt engineering guidelines

### [10-Integration](./10-integration/)
Integration reports and status documentation.
- Integration summary
- Integration solution
- Integration status
- Migration plans
- Security setup

### [11-App Specific](./11-app-specific/)
Android app-specific guides and implementations.
- UI properties guides
- Inline calendar implementation
- Android implementation details

##  Quick Start

### For New Users
1. Start with **[Project Overview](./00-project-overview/README.md)** to understand Smarty
2. Follow **[Getting Started](./01-getting-started/README.md)** to set up your environment
3. Review **[Architecture](./02-architecture/README.md)** to understand the system design

### For Developers
1. Read **[Development Guide](./03-development/README.md)** for implementation details
2. Check **[API Documentation](./README.md#api-endpoints)** for integration points
3. Review **[Agent Integration Guide](./03-development/AGENT_INTEGRATION_GUIDE.md)**

### For Designers
1. Explore **[Design System](./05-ui-ux-design/DESIGN_SYSTEM.md)** for UI components
2. Review **[UX Master Plan](./05-ui-ux-design/UX_MASTER_PLAN.md)** for design strategy
3. Check **[Component Showcase](./05-ui-ux-design/COMPONENT_SHOWCASE.md)** for examples

### For Project Managers
1. Track progress in **[Progress Tracking](./06-progress-tracking/README.md)**
2. Review **[Integration Status](./10-integration/INTEGRATION_STATUS.md)**
3. Check **[Remaining Issues](./06-progress-tracking/REMAINING_ISSUES.md)**

##  Key Features

### AI Capabilities
- **Deep Research Agent**: Structured multi-step research with professional methodologies
- **Multi-Agent Architecture**: Specialized agents (Normal, Research, Advanced Research, Medical)
- **Image Generation**: AI-powered creation using Krea AI (Flux.1 Dev)
- **Vision & Document Processing**: OCR, analysis, PDF processing

### Technical Features
- **Persistent Reasoning**: Transparent reasoning with storage and recovery
- **Multi-Agent System**: Dynamic agent switching with context preservation
- **Offline-First**: Full functionality without network connectivity
- **Smart Synchronization**: CRDT-based conflict resolution
- **Privacy-First**: End-to-end encryption, zero-knowledge architecture

### User Experience
- **Wellness Features**: Guided breathing, mental health support
- **Entertainment**: Built-in games (Coin Toss, Tic Tac Toe)
- **Audio Features**: Full music playback with discovery
- **Daily/Weekly Digest**: Automated AI-powered activity synthesis
- **Device Control**: App launching, media control, settings toggle

##  Documentation Conventions

### Navigation
- **[Link Text](./path/to/file.md)**: Internal documentation links
- **[External Link](https://example.com)**: External resources
- `Code`: Inline code snippets
- ```Code Block```: Multi-line code examples

### Status Indicators
-  **Complete**: Feature or task is complete
-  **In Progress**: Currently being worked on
-  **Planned**: Scheduled for future implementation
- ️ **Warning**: Important note or caveat
-  **Critical**: Urgent issue or blocker

### Version Information
- **Version 6.0.0**: Current release
- **Last Updated**: 2026-05-03
- **License**: MIT

##  Technical Stack

### Android Client
- Kotlin, Jetpack Compose, Room, Media3 ExoPlayer
- Ktor Client, Server-Sent Events, Firebase

### Server
- Ktor, PostgreSQL (pgvector), HikariCP
- Apache PDFBox, Tavily Search API

### AI/ML
- Gemini, OpenAI, Krea AI
- Vector embeddings, semantic search

##  API Overview

### Core Endpoints
- **Research**: `/api/v1/research/*` - Research session management
- **Chat**: `/chat/*` - Chat and streaming responses
- **Image**: `/api/v1/image/*` - Image generation and processing
- **Data**: `/api/v1/notes`, `/api/v1/calendar`, `/api/v1/tasks` - Data management
- **Sync**: `/api/v1/sync/*` - Data synchronization

##  Learning Resources

### For AI/ML Developers
- [AI Agent Architecture](./02-architecture/AI_AGENT_ARCHITECTURE.md)
- [Agent Integration Guide](./03-development/AGENT_INTEGRATION_GUIDE.md)
- [Prompts Documentation](./09-prompts/README.md)

### For Mobile Developers
- [Android Implementation](./11-app-specific/README.md)
- [UI Architecture](./02-architecture/UI_ARCHITECTURE.md)
- [Design System](./05-ui-ux-design/DESIGN_SYSTEM.md)

### For Backend Developers
- [System Overview](./02-architecture/SYSTEM_OVERVIEW.md)
- [API Documentation](./README.md#api-endpoints)
- [Database Schema](./README.md#database)

##  Contributing

We welcome contributions! Please see our [Development Guide](./03-development/README.md) for:
- Development setup instructions
- Code standards and best practices
- Testing guidelines
- Pull request process

##  Support

- **GitHub Issues**: Report bugs and feature requests
- **Documentation**: Check this repository for guides
- **Community**: Join discussions for help and feedback

##  License

MIT License - See LICENSE file for details

---

**Version 6.0.0** | **Last Updated:** 2026-05-03

**Quick Links:**
- [Project Overview](./00-project-overview/README.md)
- [Getting Started](./01-getting-started/README.md)
- [Architecture](./02-architecture/README.md)
- [Development](./03-development/README.md)
- [API Reference](./README.md#api-endpoints)
- [Integration Status](./10-integration/INTEGRATION_STATUS.md)