# Smarty (Formerly Friday)

> **The Intelligent Agentic Companion for Android**

Smarty is a next-generation "Thin Client" AI agent designed to **declutter your mind** and **supercharge ideation**. Unlike simple chatbots, Smarty is a true agent that proactively manages your life—handling calendar events, meetings, deadlines, and complex thought management through a secure, privacy-first architecture.

![Smarty Platform](https://img.shields.io/badge/platform-Android-blue?style=flat-square)
![Smarty Architecture](https://img.shields.io/badge/Architecture-Thin_Client-orange?style=flat-square)
![Smarty License](https://img.shields.io/badge/License-MIT-green?style=flat-square)

---

## Architecture

Smarty follows a **Thin Client** architecture to maximize battery life and security while delivering powerful AI capabilities.

1.  **Smarty Android App**: A lightweight, reactive UI built with **Jetpack Compose**. It handles input (Voice, Text, Gestures), renders UI, and executes local device commands (screenshots, app launches). It does *not* run heavy LLMs locally.
2.  **Smarty Server**: The "Remote Brain". A Kotlin/Ktor service that:
    *   Manages the Agentic Loop (Reasoning -> Tool Call -> Result).
    *   Connects to LLM Providers (OpenAI, Anthropic).
    *   Maintains Vector Memory (PostgreSQL + pgvector).
    *   Orchestrates persistent tasks like Calendar Sync.

---

## Features

### Real Agentics
Smarty is powered by a sophisticated backend that allows it to perform real-world actions:
*   **Proactive Management**: Automatically schedules events, sets reminders, and manages deadlines.
*   **Tool Usage**: Access to Calendar, Device Control (WiFi, Bluetooth, etc.), Web Search, and Long-term Memory.

### Privacy-First Design
*   **Shake-to-Private**: A unique physical gesture. Shake your phone to instantly toggle "Privacy Mode", cutting off AI access to your screen and context.
*   **BYO-Key**: You can host the server yourself and use your own API keys, ensuring complete data sovereignty.

### Advanced Ideation
*   **Thinking Mode**: Visualizes the AI's step-by-step reasoning process for complex queries.
*   **RAG Memory**: Uses Vector Search to recall past conversations and notes, creating a personalized experience that improves over time.

---

## Developer Setup Guide

Follow these instructions to set up the Smarty ecosystem (App + Server) on your local machine.

### Prerequisites
*   **Java JDK 17** (Required for both Server and Android)
*   **Docker Desktop** (For running the database and local server)
*   **Android Studio Ladybug** (or newer)
*   **Git**

---

### Step 1: Database Setup (PostgreSQL + pgvector)

The server requires a PostgreSQL database with the `pgvector` extension enabled. The easiest way to run this is via Docker.

1.  **Start the Database**:
    Run the provided Docker Compose file in the root directory:
    ```bash
    docker-compose up -d db
    ```
    This will start a PostgreSQL container on port `5432` and automatically initialize the schema using `init-db.sql`.

2.  **Verify Database**:
    Ensure the `agent_context` and `chat_messages` tables are created. You can connect using any SQL client (e.g., DBeaver) with credentials defined in `docker-compose.yml` (default: `user`/`password`).

---

### Step 2: Server Setup (Brain)

The server is a Kotlin Ktor application.

1.  **Configuration**:
    The server looks for environment variables for configuration. You can set these in your IDE run configuration or a `.env` file if you implemented one.
    
    **Required Environment Variables**:
    *   `DB_URL`: `jdbc:postgresql://localhost:5432/smarty_db` (or your Docker IP)
    *   `DB_USER`: `smarty_user` (as defined in docker-compose)
    *   `DB_PASSWORD`: `smarty_pass`
    *   `OPENAI_API_KEY`: Your OpenAI API Key (for the LLM)
    *   `TAVILY_API_KEY`: Your Tavily API Key (for Web Search tools)

2.  **Run the Server**:
    Navigate to the root directory and run:
    ```bash
    ./gradlew :server:run
    ```
    The server starts on `http://0.0.0.0:7860` (configurable via `SERVER_PORT`).

    Verify with:
    ```bash
    curl http://localhost:7860/health
    ```

---

### Step 3: Android App Setup (Client)

1.  **Open Project**:
    Launch Android Studio and open the root directory of this repository.

2.  **Sync Gradle**:
    Allow Android Studio to download dependencies and sync the project.

3.  **Configure Server URL**:
    *   Emulator: `http://10.0.2.2:7860`
    *   Physical Device: `http://192.168.1.x:7860`

4.  **Build & Run**:
    Select your device/emulator and click **Run**.

---

## Deployment: Hugging Face Spaces

You can deploy the Smarty Server directly to Hugging Face Spaces to have a specialized, always-on "Remote Brain".

1.  **Create a Space**:
    *   Go to Hugging Face -> New Space.
    *   Space SDK: **Docker**.
    *   Choose a generic "Blank" template.

2.  **Connect Repository**:
    *   Connect this GitHub repository to your Space.

3.  **Configure Environment Secrets**:
    In your Space settings, navigate to **Settings -> Variables and secrets**. Add the following **Secrets** (not Variables, for security):

    | Secret | Description |
    | :--- | :--- |
    | `DB_URL` | JDBC Connection string to your hosted PostgreSQL (e.g., Supabase, Neon) |
    | `DB_USER` | Database username |
    | `DB_PASSWORD` | Database password |
    | `OPENAI_API_KEY` | Your LLM Provider Key |
    | `TAVILY_API_KEY` | For Web Search capabilities |

4.  **Database Note for Cloud**:
    Hugging Face Spaces generally do *not* host persistent databases. You must use an external PostgreSQL provider like **Supabase**, **Neon**, or **AWS RDS**.
    *   Ensure your external database has `pgvector` enabled (`CREATE EXTENSION vector;`).
    *   Run the contents of `init-db.sql` on your external database to create the schema.

5.  **Build**:
    Hugging Face will automatically detect the `Dockerfile` in the root, build the Fat JAR, and deploy it. The server listens on port `7860` by default in this environment.

---

## License

This project is licensed under the [MIT License](LICENSE).