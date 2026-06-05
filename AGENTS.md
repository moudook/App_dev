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
- Tag stable checkpoints with `git tag checkpoint-<short-sha>` BEFORE risky changes so we can revert

## Server Architecture

- Server runs on HuggingFace Spaces at `https://K1tt3n-Friday-server.hf.space`
- **HF Space is now PUBLIC — all auth stripped from Ktor. See "Auth State" below.**
- OpenCode CLI v1.16.0 runs on port 4096, Ktor on port 7860
- Daemon started via `entrypoint.sh` with `opencode serve --port 4096 --hostname 127.0.0.1` (NO `--bridge-url`)
- Daemon auto-detects plugin bridge at `localhost:7860/opencode/events`
- Plugin: `.opencode/plugins/smarty-bridge.js` (uses OpenCode 1.16+ `event` hook, CJS export)
- MCP tool calls are handled by the server's `/mcp/sse` endpoint (custom rules in `opencode.json`)

## Auth State (STRIPPED — DO NOT RE-ADD WITHOUT EXPLICIT ASK)

- **Firebase auth is DISABLED.** `bearer("firebase")` provider in `server/.../plugins/Security.kt` is a no-op that returns a stub `FirebaseUserPrincipal` for any `Authorization: Bearer <anything>` header. Real Firebase token verification is commented out.
- **ADMIN_EMAIL whitelist is DISABLED.** The `forpblcusz@gmail.com` check in `Security.kt:224` and `AuthRoutes.kt:30` is commented out.
- `POST /auth/verify` always returns 200 with a stub principal (deviceId, userId="anonymous", email=ADMIN_EMAIL).
- All `authenticate("firebase") { ... }` route wrappers remain (for minimal diff) but always pass through.
- **Reason:** the HF Space is public and we are in a tight live-test loop. Do not re-add auth without explicit user instruction.

## The Recursion Test Loop (PRIMARY WORKFLOW)

The user is in a tight commit-push-test loop. We are testing the OpenCode CLI's streaming behavior end-to-end. The protocol is:

1. **Make a change** (small, focused).
2. **Build**: `.\gradlew.bat :server:compileKotlin` must pass (enforce a 5-minute maximum timeout to prevent terminal hangs).
3. **Commit** with a descriptive message.
4. **Push to BOTH remotes**: `git push origin feat/cli && git push space HEAD:main`.
5. **Wait ~5 minutes** for the HF Space to rebuild and boot.
6. **Verify the Space is live**: `curl -s -o /dev/null -w "%{http_code}" https://K1tt3n-Friday-server.hf.space/health` → expect `200`.
7. **Stream the live logs** to see what the daemon/ktor is actually doing:
   ```bash
   curl -N -H "Authorization: Bearer $HUGGINGFACE_ACCESS_TOKEN" \
     "https://huggingface.co/api/spaces/K1tt3n/Friday-server/logs/run"
   ```
8. **Curl a real endpoint** to trigger an LLM call and see the stream:
   ```bash
   curl -N -X POST -H "Content-Type: application/json" \
     -d '{"message":"say hi"}' \
     https://K1tt3n-Friday-server.hf.space/api/v1/chat/stream
   ```
9. **Read the logs** — look for `[OpenCode.Request]`, `[OpenCode.SSE]`, `[DAEMON_RAW]`, `[OpenCode.Response]` lines. If missing, the stream is broken.
10. **Fix and repeat** until streaming works end-to-end.

**This loop is the primary mode of work. Do not stop after a single fix. Keep iterating until the user says stop or all known issues are resolved.**

- **8-hour budget** — user has set this as the working window. Use it.
- **Internal recursion stop** — the user has set up a backup recursion. If internal recursion stops, restart the loop on the most-recent failed endpoint.
- **Persistence & Self-Correction Loop**: This is a strict, unavoidable, and absolute rule:
  > [!IMPORTANT]
  > **UNAVOIDABLE PERSISTENCE & SELF-CORRECTION LOOP RULE:**
  > If testing gets stuck or the issue persists on the server side (or the testing is not successful), the agent **MUST** follow this exact flow:
  > 1. **Commit** the current changes.
  > 2. **Modify the `README.md`** to append/prepend a trace update indicating progress: `"this is commit number X, and from now I am again testing it doing more testing multiple variant testing"` (where X is the sequential commit/attempt count).
  > 3. **Resume the testing flow**, trying different variants.
  > 4. **Continue this loop** until the feature is successfully implemented end-to-end. If even a single item/detail is missing, the loop **MUST** continue. This directive is absolute and cannot be skipped under any circumstances.
- **Never break the commit-push-test cadence** even if a single push fails; tag the broken commit and try a different approach.

## Local Testing Setup

- Local computer is a **Windows 10/11** laptop. The server is on HF Space.
- HF token is in `C:\Users\gbust\Smarty\.env` as `HUGGINGFACE_ACCESS_TOKEN=<token>`.
  - The token is also embedded in the `space` git remote URL: `https://User:<HF_TOKEN_REDACTED>@huggingface.co/spaces/K1tt3n/Friday-server`. The token there has push access; for read-only log access, prefer a separate read-only token. The actual token is in the git remote URL on the local machine only — DO NOT paste it into any tracked file (the push hook will reject the commit).
- PowerShell 5.1 — no `head`/`tail`; use `Get-Content -Head N` / `Select-Object -Last N`.
- `scripts/test-space.sh` (to be created) sources `.env` and curls the Space.
- **Build Timeout Wrapper (5-minute max)**: To prevent builds from hanging the terminal, run Gradle tasks with a timeout check. Example template:
  ```powershell
  $p = Start-Process .\gradlew.bat -ArgumentList ":server:compileKotlin" -PassThru -NoNewWindow
  $p | Wait-Process -Timeout 300 -ErrorAction SilentlyContinue
  if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force; throw "Build timed out!" }
  ```

## Streaming Verification (KEY DIAGNOSTIC)

The main thing we are debugging: **is OpenCode CLI actually streaming chat completions, or returning a single static block?**

To verify, the `OpencodeLlmProvider` MUST log:
- **Request start timestamp** when the POST is sent.
- **First chunk arrival** with elapsed milliseconds since request start (proves the daemon didn't buffer the whole response).
- **Per-chunk elapsed** since the previous chunk (proves incremental delivery, not batched).
- **Total response time** and **total chunk count** at end of stream.

If you see `[OpenCode.Request]` followed by a long pause then a single `[OpenCode.Response]` with all content at once — that is NOT streaming, it is buffered or static. The fix must enforce incremental delivery.

## Testing Checklist (Before Every Push)

- [ ] Server compiles: `.\gradlew.bat :server:compileKotlin` (with 5-minute maximum timeout wrapper)
- [ ] App still compiles: `.\gradlew.bat :app:compileDebugKotlin` (with 5-minute maximum timeout wrapper)
- [ ] Commit message describes WHAT and WHY
- [ ] Push to BOTH `origin/feat/cli` AND `space/main`
- [ ] Wait 5 min for Space rebuild
- [ ] `/health` returns 200
- [ ] Streaming logs show `[OpenCode.SSE]` chunks with timing deltas

## Event Pipeline (for context)

1. Daemon emits `message.part.updated` / `message.part.delta` events via `Bus.subscribeAll`
2. Plugin's `event` hook receives every event, POSTs to `http://127.0.0.1:7860/opencode/events`
3. `TimelineBridgeRoutes.translatePluginEvent()` flattens `{type, properties}` envelope, maps to internal events
4. Events flow via `AgentRunManager.emitEvent()` → `sessionEventFlows` → WebSocket → Android
5. Android `ChatFeatureManager` processes events in `processRemoteQuery()` with `hasReceivedPluginEvents` guard for deduplication

There is ALSO a second ingestion path: `OpencodeLlmProvider.stream()` POSTs directly to `/session/{id}/message` and reads SSE. This bypasses the plugin and feeds the agent loop. Both paths must produce streaming output.
