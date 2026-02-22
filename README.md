---
title: Smarty Server
emoji: 🤖
colorFrom: blue
colorTo: purple
sdk: docker
pinned: false
---

# Smarty

> An intelligent agentic companion for Android with a privacy-first architecture.

Smarty is a next-generation "Thin Client" AI agent focused on **advanced agentic frameworks**, **tool calling**, and **intelligent automation**. It features sophisticated agent loop optimization, contact management, and prevents AI overthinking through intelligent tool selection.

![Platform](https://img.shields.io/badge/Platform-Android-blue)
![Architecture](https://img.shields.io/badge/Architecture-Thin_Client-orange)
![License](https://img.shields.io/badge/License-MIT-green)

---

## Focus Areas

Smarty is evolving beyond a simple chatbot to become a research platform for agentic AI systems:

- **Advanced Tool Calling**: Optimized tool selection and execution to prevent unnecessary API calls
- **Agent Loop Optimization**: Intelligent loop detection and prevention of overthinking
- **Contact Management**: AI-driven management of contacts and relationships
- **Multi-Provider Routing**: Seamless switching between LLM providers (OpenAI, Gemini, Anthropic)
- **Privacy-First**: Your data stays yours - BYO-Key architecture

---

## Architecture

```
┌─────────────────┐      ┌─────────────────────────┐
│  Android App    │──────│  Smarty Server          │
│  (Thin Client)  │      │  (Hugging Face Spaces)  │
└─────────────────┘      └─────────────────────────┘
                                │
                                ▼
                         ┌─────────────┐
                         │   LLM +     │
                         │   Vector DB │
                         └─────────────┘
```

- **Smarty Android App**: A lightweight UI built with Jetpack Compose. Handles input (Voice, Text), renders UI, and executes local device commands.
- **Smarty Server**: Hosted on Hugging Face Spaces. Manages the agentic loop, connects to LLM providers, maintains vector memory via PostgreSQL + pgvector.

---

## Quick Start (Hugging Face Deployment)

### Step 1: Deploy Server to Hugging Face Spaces

1. Go to [Hugging Face](https://huggingface.co/spaces) -> **New Space**
2. Select **Docker** as the SDK
3. Choose "Blank" template
4. Connect this repository

### Step 2: Configure Secrets

In your Space settings, add the following **Secrets**:

| Secret | Description |
| :--- | :--- |
| `DB_URL` | JDBC connection to PostgreSQL (Supabase/Neon) |
| `DB_USER` | Database username |
| `DB_PASSWORD` | Database password |
| `ACTIVE_PROVIDER` | `GEMINI` or `OPENAI` |
| `GEMINI_API_KEY` | Your Gemini API key |
| `OPENAI_API_KEY` | Your OpenAI API key |
| `TAVILY_API_KEY` | Your Tavily API key for web search |

### Step 3: Setup Database

Hugging Face Spaces does not host persistent databases. Use an external provider:

1. Create a PostgreSQL database with **Supabase** or **Neon**
2. Enable pgvector extension:
   ```sql
   CREATE EXTENSION vector;
   ```
3. Run `init-db.sql` to create the schema

### Step 4: Connect Android App

The app is pre-configured to connect to a default Hugging Face Space. To connect to your own deployment:

**Option 1: In-App Settings**
1. Open the Smarty app
2. Go to Settings -> Server Configuration
3. Enter your Hugging Face Space URL: `https://your-username-smarty.hf.space`

**Option 2: Modify Source Code**
1. Open `app/src/main/java/com/example/smarty/data/local/SecurePreferences.kt`
2. Change the default URL at line 101:
   ```kotlin
   private const val DEFAULT_SERVER_URL = "https://your-username-smarty.hf.space"
   ```
3. Rebuild the app

Verify the server is running:
```bash
curl https://your-space.hf.space/health
```

---

## Local Development (Optional)

For local development without Hugging Face:

### Prerequisites
- Java JDK 17
- Docker Desktop
- Android Studio Ladybug

### Database
```bash
docker-compose up -d db
```

### Server
```bash
export DB_URL=jdbc:postgresql://localhost:5432/smarty_db
export DB_USER=smarty_user
export DB_PASSWORD=smarty_pass
export GEMINI_API_KEY=your_key
export TAVILY_API_KEY=your_key

./gradlew :server:run
```

---

## Tech Stack

### Android Client
- Kotlin + Jetpack Compose
- MVVM Architecture
- Room Database with FTS5
- Media3 ExoPlayer
- OkHttp 4, Ktor Client, SSE

### Server
- Kotlin + Ktor Server
- PostgreSQL + pgvector
- HikariCP Connection Pooling
- Apache PDFBox 3.0

---

## Environment Variables Reference

| Variable | Required | Description |
|----------|----------|-------------|
| `DB_URL` | Yes | PostgreSQL JDBC URL |
| `DB_USER` | Yes | Database username |
| `DB_PASSWORD` | Yes | Database password |
| `ACTIVE_PROVIDER` | No | `OPENAI` or `GEMINI` (default: `GEMINI`) |
| `GEMINI_API_KEY` | Conditional | Google Gemini API key |
| `OPENAI_API_KEY` | Conditional | OpenAI API key |
| `TAVILY_API_KEY` | No | Tavily API key for web search |
| `SERVER_PORT` | No | Server port (default: `7860`) |

---

## License

This project is licensed under the [MIT License](LICENSE).
