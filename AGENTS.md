# AGENTS.md — Smarty / K1tt3n OpenCode Streaming Investigation

## TL;DR — The Mission

- **Goal:** Make OpenCode CLI's chat completions actually **stream incrementally** to the
  Ktor server, then through to the Android client. Right now the stream path is broken
  or looks static — we are proving it with timing logs and fixing it.
- **Stack:** Android app → Ktor server (HF Space public) → OpenCode CLI v1.16.0 daemon
  (port 4096, internal) → free model.
- **Working window:** 24 hours. We are inside a `commit → push → test → fix → repeat`
  loop. Do not stop until the user explicitly says "stop" or the streaming is proven
  incremental end-to-end.

## Repo Layout & Key Files

| Path | What it does |
|---|---|
| `server/src/main/kotlin/com/example/smarty/server/Application.kt` | Ktor `Application.module()` — wires routes, CORS, plugins. |
| `server/.../plugins/Security.kt` | `bearer("firebase")` no-op stub. `ADMIN_EMAIL` constant. |
| `server/.../routes/AuthRoutes.kt` | `POST /auth/verify` always 200 with anonymous principal. |
| `server/.../routes/ChatRoutes.kt` | `POST /chat/query` (real flow, heavy) + `POST /debug/llm/stream` + `GET /debug/daemon/event` (lightweight test endpoints). |
| `server/.../llm/OpencodeLlmProvider.kt` | The streaming consumer — POSTs to daemon `/session/{id}/message`, reads SSE. Logs `[OpenCode.StreamDiag]` timing. |
| `server/.../llm/LlmProviderFactory.kt` | Returns `OpencodeLlmProvider` with 30-min read timeout. |
| `server/.../agent/ApplicationAttributes.kt` | Tool-permission enforcer singleton. |
| `server/.../routes/TimelineBridgeRoutes.kt` | `/opencode/events` POST — receives events from the JS plugin. |
| `.opencode/plugins/smarty-bridge.js` | OpenCode 1.16+ plugin (`event` hook) — POSTs every `Bus` event to `http://127.0.0.1:7860/opencode/events`. |
| `entrypoint.sh` | Bash — exports env, copies plugin to 4 paths, launches Ktor FIRST (so MCP SSE is up), waits for `/health`, then launches `opencode serve --port 4096 --hostname 127.0.0.1 --log-level DEBUG`. |
| `Dockerfile` | 3-stage — Gradle build → JRE + Node 20 + `opencode-ai@1.16.0` → runtime with HF user 1000. |
| `scripts/test-space.sh` | Local helper — sources `.env` and curls Space endpoints. |
| `AGENTS.md` | **This file** — agent working memory. |

## Git Workflow (HARD RULES)

**Local `feat/cli` → GitHub `origin/feat/cli` AND HF Space `space main`** — every commit
goes to BOTH, no exceptions.

```powershell
git add <files>
git commit -m "<message>"
git push origin feat/cli    # GitHub
git push space HEAD:main    # HF Space (Docker build triggered, ~5 min boot)
```

- Branch is single source of truth — do not commit to `main` or other branches.
- Push to `space` triggers a Docker rebuild. **Wait ~5 minutes** before testing.
- **Token in `.env` MUST stay there** — never paste into any tracked file (pre-push hook scans for `hf_…` tokens and rejects the commit). Use `<HF_TOKEN_REDACTED>` placeholder in docs.
- AGENTS.md is a working memory file. It is intentionally committed (so it survives
  session restarts). Keep it useful.

## The Recursion Test Loop (PRIMARY WORKFLOW)

You are inside an autonomous test-fix-push loop. The protocol:

1. **Read AGENTS.md first** (this file) — what's the current state, what was the last
   known good / bad commit.
2. **Read `git log --oneline -15` and `git status`** — see what's uncommitted / pushed.
3. **Make a focused change** (≤ ~50 lines if possible).
4. **Build & verify** locally:
   ```powershell
   # 5-min max timeout. Use 600 for tracing builds.
   $p = Start-Process .\gradlew.bat -ArgumentList ":server:compileKotlin" -PassThru -NoNewWindow
   $p | Wait-Process -Timeout 300 -ErrorAction SilentlyContinue
   if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force; throw "Timeout" }
   ```
5. **Commit with WHY** — message describes the symptom fixed and the diagnosis.
6. **Push to BOTH remotes.**
7. **Wait 5 minutes for Space to rebuild.** Check `https://K1tt3n-Friday-server.hf.space/health` (HTTP 200, `uptime` should be small).
8. **Test the endpoint:**
   ```powershell
   # 60-90s max — first LLM call is slow because the daemon model cache is cold.
   cmd.exe /c 'curl.exe -sN --max-time 60 -X POST -H "Content-Type: application/json" -H "Authorization: Bearer dummy" -d "{\"message\":\"Say hi in one sentence.\"}" "https://K1tt3n-Friday-server.hf.space/debug/llm/stream" > C:\Users\gbust\Smarty\debug-out.txt 2>&1'
   Get-Content C:\Users\gbust\Smarty\debug-out.txt
   ```
9. **Read the output** — look at the `+ms` field (time since previous chunk). Real
   streaming shows non-zero `+ms` values, sometimes hundreds of ms. Static/batched
   responses show `+ms` ≈ 0 for every chunk or arrive as a single blob.
10. **Inspect the response shape** — every chunk now has `content`, `sseEvent`,
    `rawJson`. If `content` is empty but `rawJson` is non-empty, the parser is
    dropping text. If both are empty, the daemon returned no data.
11. **Fix and repeat.** Do not stop after a single fix.

## Endpoints Cheat Sheet

| Method | Path | Auth | Purpose | Expected |
|---|---|---|---|---|
| GET | `/health` | none | Liveness check | `{"status":"ok",...}` |
| POST | `/chat/query` | bearer (no-op) | Real Android flow | Streams events. Heavy. |
| POST | `/chat/query/stream` | none | LLM-only SSE stream for Android | `data: {chunk,+ms,content,reasoning,toolCall,rawJson}\n\n` + `event: done`. Body: `{query|message, model?, tools?, history?, sessionId?}`. |
| POST | `/auth/verify` | none | Always 200 stub | `{"userId":"anonymous",...}` |
| POST | `/debug/llm/stream` | none | Direct OpenCode stream test | SSE with `{"chunk":N,"+ms":...,"content":"...","rawJson":"..."}` |
| GET | `/debug/daemon/event` | none | Daemon `/event` SSE passthrough | SSE with `{"daemonChunk":N,"line":"..."}` |
| GET | `/chat/events/test` | none | Test event JSON | Returns array of stub events |
| POST | `/opencode/events` | none | Plugin → Ktor event bridge | Internal, 200 OK |

**Always include** `-H "Authorization: Bearer dummy"` even on no-auth routes — keeps
the no-op provider in the principal context.

## Streaming Verification (THE KEY DIAGNOSTIC)

The OpenCode daemon returns SSE in two shapes:
- **Old (pre-1.16):** `event: message.part.delta\ndata: {...}` on each line
- **New (1.16+):** `data: {"type":"message.part.updated","properties":{"part":{...},"delta":"..."}}`

Both must be handled. The provider's `processSseEvent` unwraps `{type, properties}`.

**Proving streaming (must do after every test):**
- Look at `+ms` field in `/debug/llm/stream` output — should be NON-ZERO across chunks
  (a few ms to several hundred ms). All-zero means the chunks were batched.
- Look at `fromStart` field — first chunk should arrive 1-10s after request, not 0ms
  (cold daemon) and not >60s (timeout).
- Look at `chunks` and `accumulated` in the `done` event — `accumulated` should be the
  full text.

**If you see:** `[OpenCode.Request]` then long pause then a single `[OpenCode.Response]`
with all content → **NOT streaming**, buffered. Fix the consumer.

**If you see:** `[OpenCode.Request]` then NO `[OpenCode.Response]` at all → daemon call
hung. Check daemon logs / restart.

## Failure Modes We Have Hit

| Symptom | Likely cause | Fix |
|---|---|---|
| HF returns HTML 500 page | Ktor threw before writing headers, or upstream call hit HF 5-min gateway cap | Check Ktor logs via `/tmp/ktor-server.log` (need container access) or add try-catch in route |
| `/debug/llm/stream` returns "An internal error occurred" | Bug in the route code (e.g. `runBlocking` issue, wrong import, Ktor version mismatch) | Add try-catch in route, log error to a file the agent can read |
| `OpenCode.Response` logged with Content-Type=application/json | Daemon returned the whole response as JSON, not SSE — it's NOT streaming | Add the "static JSON" branch to handle gracefully (already added); investigate why SSE didn't form |
| All chunks have `content=""` but `rawJson` non-empty | `parseCanonicalResponse` not matching the daemon's event shape | Look at rawJson, add a new branch in `parseCanonicalResponse` for the shape |
| `uptime` is 0:00:00 right after a push | Space just rebooted, MCP not registered yet | Wait 1-2 minutes more |
| Push rejected with "Hugging Face secrets" | AGENTS.md or other file contains `hf_…` token | Replace with `<HF_TOKEN_REDACTED>` or use env var ref |
| Empty `+ms` for all chunks | Ktor buffered the stream — `respondTextWriter` may be flushing in big batches | Add explicit `flush()` after each `write()` (done) |

## Auth State (STRIPPED)

- HF Space is public. All Firebase + ADMIN_EMAIL gating is a no-op.
- `bearer("firebase")` always returns `FirebaseUserPrincipal(userId="anonymous", email=ADMIN_EMAIL)`.
- Don't re-add auth without explicit user instruction.
- See `server/.../plugins/Security.kt` and `server/.../routes/AuthRoutes.kt` for the no-op implementations.

## OpenCode Daemon Configuration

```
opencode serve --port 4096 --hostname 127.0.0.1 --log-level DEBUG
```

- NO `--bridge-url` — plugin auto-detects bridge at `http://127.0.0.1:7860/opencode/events`.
- Plugin copied to 4 locations by `entrypoint.sh` (XDG_CONFIG and HOME, both `plugin/` and `plugins/`).
- MCP tools at `http://127.0.0.1:7860/mcp/sse` — daemon calls this for tool execution.
- Ktor launches FIRST (so MCP SSE is up), daemon SECOND.

## Local Testing Setup (Windows)

- PowerShell 5.1 — no `head`/`tail`; use `Get-Content -Head N` / `Select-Object -Last N`.
- HF token in `C:\Users\gbust\Smarty\.env` as `HUGGINGFACE_ACCESS_TOKEN=hf_xxx`.
  - The token is also embedded in the `space` git remote URL (with push access).
  - For log-streaming specifically, the user's read-only token is fine.
- `scripts/test-space.sh {logs|health|chat|events|all}` — sources `.env` and curls.
- All commands MUST have a timeout (5 min default, 10 min for tracing).

**The HF log streaming endpoint (`/logs/run`) currently returns
`{"error":"Invalid username or password."}` with the read-only token** — known issue.
The container's actual logs are inside the container at `/tmp/ktor-server.log`. We
have no direct read access from outside. Workaround: trigger behavior via
`/debug/llm/stream` and read the diagnostic info it returns.

## Hard Rules (Do Not Violate)

1. **No stubbing or TODOs** — all code must be production-ready.
2. **No bypassing the commit-push-test cadence** — even if a push fails, tag and pivot.
3. **Read the actual error** before guessing the cause.
4. **Test in vacuum first** for new code paths (small scratch file → integrate).
5. **Don't ask the user for clarification** — make a calculated assumption or search
   the web. Absolute autonomy.
6. **Never paste HF token into a tracked file.**
7. **Don't re-add auth** without explicit user instruction.
8. **Work the full 24 hours** — never declare success unless user confirms.
9. **Goal persists across compaction** — if you lose context, come back to this file
   and the `git log` to recover.

## Decision Log (Last 15 Commits)

```
a2a3a8ee  debug: include rawJson + sseEvent in /debug/llm/stream chunks
a742160f  feat: add debug endpoints /debug/llm/stream and /debug/daemon/event
1fbb378b  test-script: support typo'd HUGGINGFACE_ACESS_TOKEN for compat
a4cdb3a2  feat: strip all auth + add streaming timing logs
5ba39b98  fix(bridge): migrate to OpenCode 1.16+ new event-hook plugin API
810036be  issue is ,,,,......'''({    })
541b2dd0  might test these two also
322f528b  Its getting tiring
69e37b92  fix: declare plugin in opencode.json and avoid daemon stdout buffering
```

## Current Investigation State (as of last test)

- **Last good:** Multi-step tool calls proven end-to-end on Space.
- Commit graph (latest at bottom):
  ```
  a2a3a8ee → ... → 2f455033 → ... → 1eec8526 → f9b34e88 → aba428d6
  → 84060322 → fd2b20cd → 388220aa → c0bd2095 → fd08dc43
  → 0be960fd → d5c45d22 → 04fb2845 → 2b69ff2a → c8adc4be
  → e3dfa666 (Documentacy Part 3)
  ```
- **Wire format PROVEN**: deepseek and m3 both successfully execute
  3-iter chains (fibonacci → add → final) via /chat/query/stream with
  the `messages` field carrying `tool_calls` and `tool_call_id`.
- **Bugs fixed in this session**:
  - m3 splits atomic tool_call across 2 SSE frames; parser now merges via `activeToolCall`.
  - history parser was dropping `tool_call_id` (TOOL) and `tool_calls` (ASSISTANT) — now extracted.
  - server injected default "What is 2+2?" user msg when `query=""` — now only when no `history`/`messages` present.
  - server now accepts `messages` field (not just `history`) for full conversation passing.
- **Test artifacts** at `C:\Users\gbust\Smarty\`:
  - `test-multistep-ds2.txt` — deepseek 3-iter SUCCESS
  - `test-multistep-m3.txt` — m3 3-iter SUCCESS
  - `test-multistep-iter{1,2,3}.txt` — raw SSE per iteration
  - `multistep_test.py` — harness (gitignored, lives locally)
- **Open hypothesis:** ServerAgent's system prompt doesn't force tool use; with a stronger system prompt the production loop will drive tool calls naturally.
- **Next step to try:** add `system` parameter to `/chat/query/agent/stream` that injects into ServerAgent's system message — should make the LLM call ServerAgent's real tools (memory, schedule, etc.) instead of narrating.

## Appendix: Useful One-Liners

```powershell
# Health check
cmd.exe /c 'curl.exe -s --max-time 10 "https://K1tt3n-Friday-server.hf.space/health"'

# Quick stream test (60s)
cmd.exe /c 'curl.exe -sN --max-time 60 -X POST -H "Content-Type: application/json" -H "Authorization: Bearer dummy" -d "{\"message\":\"hi\"}" "https://K1tt3n-Friday-server.hf.space/debug/llm/stream"'

# Tail the last 100 lines of a log file
Get-Content C:\Users\gbust\Smarty\debug-out.txt -Tail 100

# Kill any stuck curl
Get-Process curl -ErrorAction SilentlyContinue | Stop-Process -Force

# Build server (5-min max)
$p = Start-Process .\gradlew.bat -ArgumentList ":server:compileKotlin" -PassThru -NoNewWindow
$p | Wait-Process -Timeout 300 -ErrorAction SilentlyContinue
if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force; throw "Timeout" }

# Push to both remotes
git push origin feat/cli 2>&1 | Select-Object -Last 3
git push space HEAD:main 2>&1 | Select-Object -Last 3
```
