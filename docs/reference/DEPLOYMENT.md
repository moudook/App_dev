# Cloud Deployment Guide

This guide details how to deploy the Smarty "Remote Brain" server to Hugging Face Spaces.

## 1. Hugging Face Space Setup

1.  Create a new Space: [huggingface.co/new-space](https://huggingface.co/new-space)
    *   **SDK**: Docker
    *   **Template**: Blank
    *   **Hardware**: CPU Basic (2 vCPU, 16GB RAM) - *Free Tier*

2.  **Configure Dockerfile Path**:
    *   Go to **Settings**.
    *   Scroll to **Dockerfile Path**.
    *   Set it to: `server/Dockerfile`

## 2. Secrets Configuration

Go to **Settings** > **Variables and secrets**.

### Infrastructure Secrets
| Secret | Description |
| :--- | :--- |
| `TAVILY_API_KEY` | For web search capabilities. |
| `DB_URL` | `jdbc:postgresql://<host>:6543/postgres?sslmode=require` (Use Transaction Pooler port 6543) |
| `DB_USER` | Database user. |
| `DB_PASSWORD` | Database password. |

### AI Provider Secrets
Set `ACTIVE_PROVIDER` to one of: `OPENAI`, `GROQ`, `DEEPSEEK`, `GEMINI`, `CLAUDE`, `OPENROUTER`, `GITHUB`, `CEREBRAS`.

Then add the corresponding API key:
| Provider | Required Secret |
| :--- | :--- |
| `OPENAI` | `OPENAI_API_KEY` |
| `GROQ` | `GROQ_API_KEY` |
| `DEEPSEEK` | `DEEPSEEK_API_KEY` |
| `GEMINI` | `GEMINI_API_KEY` |
| `CLAUDE` | `ANTHROPIC_API_KEY` |
| `OPENROUTER` | `OPENROUTER_API_KEY` |
| `GITHUB` | `GITHUB_TOKEN` |
| `CEREBRAS` | `CEREBRAS_API_KEY` |

## 3. Deploying the Code

Run from the project root:

```bash
# 1. Add Remote
git remote add space https://huggingface.co/spaces/YOUR_USERNAME/YOUR_SPACE_NAME

# 2. Push Code
git push space main
```

## 4. Supabase Setup (One-time)

1.  Go to the Supabase SQL Editor.
2.  Run the contents of `server/src/main/resources/db/schema.sql`.
3.  Ensure `pgvector` extension is enabled.

## 5. Keep-Alive (Optional)

To prevent database pausing, ensure the `.github/workflows/keep_alive.yml` action is running in your GitHub repository and has the `DB_URL` secret configured.

## 6. Port Configuration

The server is configured to run on port **7860**.
- Local testing: `http://localhost:7860`
- Hugging Face: Mapped automatically, but the Dockerfile exposes 7860.
