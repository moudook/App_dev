---
title: Smarty Server
emoji: 🧠
colorFrom: blue
colorTo: gray
sdk: docker
pinned: false
license: mit
---

# Smarty Server - AI Backend

This is the server component of the Smarty AI assistant application.

## Features

- 🧠 AI Chat with LLM integration (Gemini, OpenAI)
- 📅 Calendar management
- 📝 Note-taking with deduplication
- ⏰ Timer and reminder system
- 🔍 Web search via Tavily API
- 📱 Firebase push notifications
- 🔄 Real-time sync

## Deployment

This space uses Docker deployment. The server builds automatically on push.

## Environment Variables

Required environment variables (set in Hugging Face Space settings):

- `DB_URL` - PostgreSQL database URL
- `DB_USER` - Database username
- `DB_PASSWORD` - Database password
- `GEMINI_API_KEY` - Google Gemini API key
- `TAVILY_API_KEY` - Tavily search API key
- `FIREBASE_CREDENTIALS` - Firebase admin SDK credentials (JSON)

## Local Development

```bash
cd server
../gradlew :server:run
```

Server runs on port 7860 by default.

## API Endpoints

- `POST /api/chat` - Chat with AI assistant
- `GET /api/health` - Health check
- `POST /api/data/notes` - Create note
- `GET /api/data/calendar` - Get calendar events
- `POST /api/digest/generate` - Generate daily digest

## Documentation

See `SERVER_SDE_IMPROVEMENTS.md` in the root directory for architecture details.
