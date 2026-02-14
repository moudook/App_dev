# Friday Server (Ktor Backend)

This is the cloud-hosted backend for Smarty, designed as a "Thin Client" brain. It hosts the KOOG agent, handles RAG (Retrieval Augmented Generation) with Supabase, and orchestrates device commands.

## Prerequisites
- **Hugging Face Account**: For hosting the Dockerized Ktor service.
- **Supabase Account**: For PostgreSQL + `pgvector` storage.
- **Firebase Project**: For user authentication (JWT verification).

## Setup Instructions

### 1. Database (Supabase)
1. Go to your Supabase Project -> SQL Editor.
2. Open the [init-db.sql](../init-db.sql) file from the root directory.
3. Paste and run the SQL script to enable `pgvector` and create the required tables and hybrid search functions.

### 2. Environment Variables
You must set the following environment variables in your Hugging Face Space (or local environment):
- `DB_URL`: JDBC URL for Supabase (e.g., `jdbc:postgresql://db.xxxx.supabase.co:5432/postgres`)
- `DB_USER`: Supabase database user (usually `postgres`)
- `DB_PASSWORD`: Supabase database password
- `FIREBASE_PROJECT_ID`: Your Firebase project ID
- `FIREBASE_CREDENTIALS`: JSON content of your Firebase Admin SDK service account key

### 3. Deployment (Hugging Face Spaces)
1. Create a new "Space" on Hugging Face.
2. Select **Docker** as the SDK.
3. Template: **Blank**.
4. **Prepare your repository**:
   - Hugging Face expects the `Dockerfile` to be at the root of your repository.
   - Copy the `server/Dockerfile` to the project root: `cp server/Dockerfile ./Dockerfile`.
   - Ensure the root project (including `common/`, `gradle/`, `gradlew`, etc.) is pushed.
5. Push your code to the Space repository:
   ```bash
   git remote add hf https://huggingface.co/spaces/YOUR_USERNAME/YOUR_SPACE_NAME
   git push hf main --force
   ```
6. The `Dockerfile` is pre-configured for Hugging Face (Port 7860, UID 1000).

### 4. Configuration Tips
- **Firebase**: Copy the entire content of your Firebase Service Account JSON file into the `FIREBASE_CREDENTIALS` environment variable in HF Space settings.
- **Port**: HF uses port 7860. The server handles this automatically via the `SERVER_PORT` env var.

## Local Development
Run the server locally using Gradle:
```bash
./gradlew :server:run
```
Make sure to have the environment variables set in your shell or IDE.
