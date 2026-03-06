---
title: Smarty - Advanced AI  Agent(AAA)
colorFrom: purple
colorTo: blue
sdk: docker
pinned: true
license: mit
---

# Smarty

Advanced AI Research Agent with deep research capabilities, persistent reasoning, and a multi-agent architecture designed for Android.

Smarty is an AI companion built with autonomous research agents, real-time reasoning visualization, comprehensive citation management, and a privacy-first architecture.

![Platform](https://img.shields.io/badge/Platform-Android-blue)
![Version](https://img.shields.io/badge/Version-3.2.0-purple)
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

The research agent is designed to conduct structured multi-step research sessions.

Capabilities include:

- Autonomous multi-source web research
- Automatic citation tracking
- Clarification questions before research begins
- Progress persistence during long research tasks
- User interruption and redirection during research
- Automated synthesis into structured note cards
- Built-in research time management with timeout controls

## Thinking Persistence

The system provides transparent reasoning visibility.

Features include:

- Real-time reasoning display
- Collapsible reasoning sections
- Persistent storage of reasoning content
- Recovery of reasoning data after application restart

## Multi-Agent Architecture

Smarty supports multiple specialized agents that can be switched dynamically.

| Agent | Purpose | Tool Access | Timeout |
|------|------|------|------|
| Normal Agent | General assistant tasks | Full tool access | None |
| Research Agent | Structured research | Web search and note creation | 15 minutes |

## User Experience

The Android application is designed with a modern responsive interface.

Key UI capabilities:

- Dynamic theme support
- Inline citation display
- Chat history and conversation management
- Selection mode for multi-note operations
- Contextual toolbars and action menus
- Searchable knowledge base

## Citation Management

All research operations include source tracking.

Features include:

- Automatic citation recording
- Inline citation references
- Expandable source cards
- Clickable links to original sources
- Full bibliography generation for research outputs

## Privacy and Security

Smarty is designed with a privacy-first architecture.

- Bring-Your-Own API keys
- Local encrypted credential storage
- Private conversation storage
- No conversation logging
- Secure communication using HTTPS

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

- Voice input support
- Text chat with streaming responses
- Persistent reasoning display
- Deep research agent
- Multi-agent switching
- Structured note creation
- File attachments (image, audio, video, documents)
- External sharing support
- Calendar integration
- Timer and alarm management
- Category and stack organization
- Chat history management
- Server configuration within the application

## Advanced Capabilities

- Long-running research sessions
- Context overflow handling
- User redirection during research
- Automated research synthesis
- Multi-select note operations
- Real-time server status monitoring
- Loading state animations
- Unread note indicators

## Research Workflow

Research sessions support:

- Clarification question phase
- Multi-step web search
- Source collection
- Citation generation
- Progress tracking
- Final synthesis into research notes

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

Technology Stack
Android Client

Kotlin

Jetpack Compose

MVVM Architecture

Room Database with FTS5

Media3 ExoPlayer

OkHttp

Ktor Client

Server-Sent Events streaming

Server

Ktor Server

PostgreSQL with pgvector

HikariCP

Apache PDFBox

Tavily Search API

Multi-provider LLM routing

Infrastructure

Hugging Face Spaces

Supabase / Neon PostgreSQL

Git-based CI/CD deployment

API Endpoints
Research
Endpoint	Description
POST /api/v1/research/start	Start research session
POST /api/v1/research/{id}/answer	Submit clarification answers
POST /api/v1/research/{id}/interrupt	Interrupt research
GET /api/v1/research/{id}	Retrieve research session
Chat
Endpoint	Description
POST /api/v1/chat/stream	Streaming chat responses
GET /api/v1/chat/sessions	List chat sessions
POST /api/v1/chat/sessions	Create chat session
Synchronization
Endpoint	Description
POST /api/v1/sync/pull	Pull changes
POST /api/v1/sync/push	Push changes
Database

Schema version: 3.0

Includes systems for:

Chat sessions

Messages with reasoning storage

Notes and categories

Research sessions

Citation tracking

Calendar events

Timers and alarms

Agent memory

Synchronization

Security

No conversation logging

Encrypted credential storage

Local database usage

Secure HTTPS communication

Optional database row-level security

License

MIT License

Support

GitHub Issues

GitHub Discussions

Repository documentation

Version 3.2.0