---
title: Getting Started
category: getting-started
--

# Getting Started

This section provides all the necessary guides to get Smarty up and running.

## Quick Start

### 1. Deploy the Server

1. Open Hugging Face Spaces
2. Create a new Space

**Configuration:**
- Space Name: `smarty-server`
- SDK: Docker
- Template: Blank

Connect this repository to the Space.

### 2. Configure Secrets

Add the following repository secrets:

| Secret | Required | Description |
|--------|----------|-------------|
| DB_URL | Yes | PostgreSQL JDBC URL |
| DB_USER | Yes | Database username |
| DB_PASSWORD | Yes | Database password |
| TAVILY_API_KEY | Yes | Tavily search API key |
| KREA_API_KEY | Yes | Krea AI image generation API key |
| ACTIVE_PROVIDER | Optional | GEMINI or OPENAI |
| GEMINI_API_KEY | Conditional | Required if Gemini is used |
| OPENAI_API_KEY | Conditional | Required if OpenAI is used |

**Important:** After adding or modifying secrets, you **must restart** the Hugging Face Space for changes to take effect.

### 3. Configure Database

Example setup using PostgreSQL:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
```

Run the schema file: `COMPLETE_SCHEMA_v3.0_RESEARCH.sql`

### 4. Connect the Android Application

**In-App Configuration:**
1. Open the application
2. Navigate to Settings
3. Select Server Configuration
4. Enter your Space URL

**Source Configuration:**
Modify `app/src/main/java/.../SecurePreferences.kt`

Update: `private const val DEFAULT_SERVER_URL = "https://your-username-smarty.hf.space"`

### 5. Verify Deployment

```bash
curl https://your-space.hf.space/health
```

Expected response:
```json
{"status":"ok","module":"smarty-server"}
```

## Local Development

### Requirements

- Java JDK 17
- Docker Desktop
- Android Studio
- Git

### Database Setup

```bash
docker-compose up -d db
```

Load schema:
```bash
docker exec -i smarty-db psql -U smarty_user -d smarty_db < COMPLETE_SCHEMA_v3.0_RESEARCH.sql
```

### Server Setup

```bash
export DB_URL=jdbc:postgresql://localhost:5432/smarty_db
export DB_USER=smarty_user
export DB_PASSWORD=smarty_pass
export TAVILY_API_KEY=your_key
export GEMINI_API_KEY=your_key

./gradlew :server:run
```

### Android Setup

1. Open the project in Android Studio
2. Synchronize Gradle
3. Run the application on an emulator or device

## Documentation

- **[Installation Guide](./INSTALLATION.md)** - Environment setup and configuration
- **[Setup Guide](./SETUP_GUIDE.md)** - Complete system setup procedures
- **[Architecture Overview](./../02-architecture/SYSTEM_OVERVIEW.md)** - System design and architecture
- **[API Documentation](./../README.md#api-endpoints)** - Complete API reference

## Troubleshooting

### Common Issues

**Server not responding:**
- Check if the Space is running
- Verify secrets are configured correctly
- Check database connection

**Android app can't connect:**
- Verify server URL in SecurePreferences.kt
- Check network connectivity
- Ensure server is accessible from device

**Database errors:**
- Verify PostgreSQL is running
- Check database credentials
- Ensure schema is loaded

## Next Steps

- Review the [Architecture Documentation](./../02-architecture/README.md)
- Explore [Development Guides](./../03-development/README.md)
- Check [Integration Status](./../10-integration/README.md)
- Read [API Endpoints](./../README.md#api-endpoints)

---

**Last Updated:** 2026-05-03