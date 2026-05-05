---
title: Project Overview
category: overview
--

# Project Overview

Welcome to the Smarty project documentation. This section provides a high-level overview of the Smarty AI research agent platform.

## What is Smarty?

Smarty is an advanced AI research agent built for Android with deep research capabilities, persistent reasoning, image generation, and a multi-agent architecture. It combines autonomous research workflows with transparent reasoning visualization and comprehensive citation management.

## Key Capabilities

- **Deep Research Agent**: Structured multi-step research with professional intelligence methodologies
- **Image Generation**: AI-powered image creation using Krea AI with Flux.1 Dev
- **Vision & Document Processing**: OCR, image analysis, table extraction, PDF processing
- **Thinking Persistence**: Transparent reasoning with persistent storage and recovery
- **Multi-Agent Architecture**: Specialized agents (Normal, Research, Advanced Research, Medical)
- **Wellness & Mental Health**: Guided breathing, psychiatric assessments, symptom analysis
- **Games & Entertainment**: Coin Toss, Tic Tac Toe with AI opponent
- **Audio Features**: Full audio playback with playlist support
- **Daily/Weekly Digest**: Automated AI-powered activity synthesis
- **Device Control**: App launching, media control, settings toggle
- **Backup & Restore**: Google Drive integration with complete data protection

## Architecture

```
┌─────────────────────────┐
│ Smarty Android Client   │
│  - Jetpack Compose UI   │
│  - Room Database        │
│  - Media3 ExoPlayer     │
└───────────┬─────────────┘
            │ HTTPS / SSE
            ▼
┌─────────────────────────┐
│ Smarty Server           │
│  - Ktor Backend         │
│  - Multi-Agent System   │
│  - Tool Orchestration   │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│ External Services       │
│  - LLM Providers        │
│  - Tavily Search API    │
│  - PostgreSQL + pgvector│
└─────────────────────────┘
```

## Documentation Structure

- **[00-Project Overview](./)** - High-level project information and README
- **[01-Getting Started](./../01-getting-started/)** - Installation and setup guides
- **[02-Architecture](./../02-architecture/)** - System architecture and design documents
- **[03-Development](./../03-development/)** - Implementation plans and development guides
- **[04-Issue Tracking](./../04-issue-tracking/)** - Bug reports and technical deep dives
- **[05-UI/UX Design](./../05-ui-ux-design/)** - Design systems and UX plans
- **[06-Progress Tracking](./../06-progress-tracking/)** - Daily progress and task tracking
- **[07-Optimization](./../07-optimization/)** - Performance optimization reports
- **[08-Reference](./../08-reference/)** - Technical references and guides
- **[09-Prompts](./../09-prompts/)** - System prompts and model configurations
- **[10-Integration](./../10-integration/)** - Integration reports and status
- **[11-App Specific](./../11-app-specific/)** - App-specific guides and implementations

## Quick Links

- [Main Project README](./README.md) - Complete project documentation
- [Features List](./README.md#complete-feature-list) - All features and capabilities
- [Technology Stack](./README.md#technology-stack) - Technologies used
- [API Endpoints](./README.md#api-endpoints) - Complete API documentation
- [Database Schema](./README.md#database) - Database design and structure

## License

MIT License - See LICENSE file for details

---

**Version 6.0.0**