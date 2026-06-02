# AGENTS.md — Critical Project Info

## CRITICAL: Git Branch Deployment Mapping

**Local `feat/cli` → GitHub `origin/feat/cli` AND HF Space `space main`**

Every commit MUST be pushed to BOTH remotes:
```bash
git push origin feat/cli
git push space HEAD:main
```

- `origin` = `git@github.com-personal:moudook/App_dev.git`
- `space` = `huggingface.co/spaces/K1tt3n/Friday-server`
- The local `feat/cli` branch is the single source of truth
- GitHub `feat/cli` = HF Space `main` — they must always be identical

## Server Architecture

- Server runs on HuggingFace Spaces at `https://K1tt3n-Friday-server.hf.space`
- OpenCode daemon v1.15.13 runs on port 4096, Ktor on port 7860
- Daemon started via `entrypoint.sh` with `opencode serve --port 4096 --hostname 127.0.0.1` (NO `--bridge-url`)
- Despite no `--bridge-url`, daemon auto-detects plugin bridge at `localhost:7860/opencode/events`
- Daemon sends `message.updated` SNAPSHOT events (full accumulated text), NOT `message.part.delta` deltas
- MCP tool calls are handled by the server's `/mcp/sse` endpoint (custom rules in `opencode.json`)

## Event Pipeline

1. Daemon emits `message.updated` snapshots to `/opencode/events`
2. `TimelineBridgeRoutes.translatePluginEvent()` handles `message.updated`, extracts text/reasoning from `parts` array, computes deltas via `sessionContentStates` tracker, emits `FinalAnswerDelta`/`ReasoningDelta`
3. Events flow via `AgentRunManager.emitEvent()` → `sessionEventFlows` → WebSocket → Android
4. Android `ChatFeatureManager` processes events in `processRemoteQuery()` with `hasReceivedPluginEvents` guard for deduplication

## Testing Checklist

- [ ] Server builds: `.\gradlew.bat :server:compileKotlin`
- [ ] App builds: `.\gradlew.bat :app:compileDebugKotlin`
- [ ] Push to both remotes after every commit
