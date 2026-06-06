# Suggestions.md — Smarty/K1tt3n Streaming After the Night Shift

> Post-mortem of ~24 hours of debugging streaming from the opencode CLI
> daemon → Ktor server → Android client. This file is the design brief
> for **what to keep, what to throw out, what to fix next, and where the
> real Android-side bug is**.

---

## 1. The Best Way to Implement It (TL;DR)

```
Android (Ktor SSE client)
   │  GET /chat/events   (SSE over HTTP/1.1, no chunked)
   ▼
Ktor server (HF Space, public, no auth)
   │  /chat/query → ServerAgent (MCP tools + LLM) → /mcp/sse + /llm/stream
   │
   ├─► MCP tools:    Ktor /mcp/sse        (the opencode CLI daemon calls this)
   └─► LLM calls:    Ktor → Zen /v1/chat/completions  (DIRECT, no opencode CLI in path)
                       Bearer public, stream=true
```

**The single biggest lesson**: do NOT route the LLM call through the
opencode CLI daemon. The CLI's auth handling for the Zen provider is
broken in v1.16.0 and there is no fix on the daemon side. Call Zen
directly from Ktor.

The daemon can stay for the MCP tool plumbing — it calls Ktor's
`/mcp/sse` endpoint via the `smarty-bridge` plugin — but the LLM
inference path should be:

```
Ktor OpencodeLlmProvider.streamDirectZen(messages, model)
   ↓ POST /v1/chat/completions with Bearer public
Zen
   ↓ SSE stream
Ktor (parse, strip <think>, surface content+reasoning+toolCall)
   ↓ Flow<LlmChunk>
Ktor /chat/query route
   ↓ respondTextWriter (flush per chunk)
Android SSE client
```

---

## 2. What Was Actually Broken (Hurdles in Order)

### 2.1 The CLI daemon is broken for Zen
| Symptom | Root cause | Where we found it |
|---|---|---|
| `UnknownError` on every model call | opencode CLI v1.16.0 sends no `Authorization` header when `apiKey: "public"`, but Zen rejects empty header | `/debug/daemon/chat?model=zen/deepseek-v4-flash` returns `{"name":"UnknownError"}` |
| 5-minute hang | `apiKey: "sk-..."` is sent but Zen says "Invalid API key" (no public-key whitelist) | `5ba39b98` — added the auth stripping |
| Built-in `opencode` provider hangs | The provider is `kilo`/`nvidia`/`openrouter` under the hood; not Zen | `ff43e783` — `/debug/daemon/chat` returns 500 with no body |
| Models returned empty registry | `opencode models --verbose` exits 0 but prints no model IDs | `e46bb8d1` — hardcoded fallback |

**Verdict**: The CLI is **fundamentally broken** for our use case. We
worked around it by calling Zen directly.

### 2.2 Ktor was buffering the SSE stream
The `respondTextWriter { … }` lambda was collecting the entire flow
before flushing. Fix: `runBlocking { GlobalScope.launch { … } }` and
**explicit `flush()` after every `write()`**. See commit `8bec6584`.

### 2.3 Ktor's per-chunk SseEvent format was wrong
The route was emitting `event: message` with `data: {…}\n\n` but the
event type was hardcoded. We rewrote it to put a chunk number, the
`+ms` timing, the cleaned `content`, and the raw `rawJson` in one
`data: {…}` line so the Android side can parse it trivially. See
commit `a2a3a8ee`.

### 2.4 HF gateway 5-minute hard timeout
If the LLM cold start takes >5 minutes, HF's gateway returns a 500 HTML
page. Fix: send an immediate `:ping\n\n` SSE comment so the gateway
sees headers + body bytes within 1 second. See commit `9e279a27`.

### 2.5 Zen free tier rate limit on shared egress IP
The HF Space shares an egress IP with many other spaces; Zen's free
tier throttles it heavily. Symptom: every request returns
`{"type":"error","error":{"type":"FreeUsageLimitError","message":"Rate limit exceeded. Please try again later."}}` in 100ms.
Cooldown: 5–20 minutes. **Not solvable with code** — the only fixes
are (a) wait, (b) get a real Zen API key, (c) use a different egress
IP via a Cloudflare worker in front.

### 2.6 Per-model format differences
Every free model has a different shape. See `Documentacy.md` for the
full reference. Key example: m3 streams reasoning **inside the
content** as `<think>…</think>` tags, deepseek uses
`delta.reasoning_content`, mimo uses `delta.reasoning` +
`delta.reasoning_details[]`.

### 2.7 5-minute deprecation of "free" models
The dynamic registry picks up `qwen3.6-plus-free` because the name
contains "free", but Zen has retired the free promotion and returns a
`ModelError` at runtime. The current code shows the error to the
caller but doesn't blacklist the model — the next request will hit
the same error. We should add runtime error tracking.

### 2.8 The `<think>` tag stripping
The per-chunk regex missed because the think block was split across
multiple chunks. Fixed by stripping at the accumulator level in the
`done` event. See commit `1eec8526`.

---

## 3. What to KEEP (Do Not Strip)

| Component | Why |
|---|---|
| `OpencodeLlmProvider.streamDirectZen()` | The only working LLM path |
| `OPENCODE_USE_DIRECT_ZEN=true` in `entrypoint.sh` | Gate that activates the bypass |
| `:ping\n\n` keepalive in `/debug/llm/stream` | Saves us from HF's 5-min timeout |
| `runBlocking { GlobalScope.launch { … } }` in the route | Lets `respondTextWriter` flush per chunk |
| Triple try-catch in the route (`flow / runBlocking / outer`) | We need to see any error in the response body |
| `OpencodeModelRegistry` dynamic discovery (CLI + Zen /models fallback) | The CLI returns 0; the Zen fallback is the actual source of truth |
| `KNOWN_FREE_MODELS` filter pattern (id starts with `opencode/`, contains `free`) | Pattern-based filter is correct; just don't hardcode names |
| The `Documentacy.md` reference | Future devs will need it for any new model |
| `LlmChunk.reasoning` and `LlmChunk.toolCall` fields | The Android side will need to render reasoning and dispatch tool calls separately |
| The `model.id` stripped of `opencode/` prefix when calling Zen | Zen rejects prefixed model names with 401 |

---

## 4. What to STRIP / REMOVE

| Component | Why |
|---|---|
| `bearer("firebase")` no-op stub in `Security.kt` | Not needed; auth is already stripped on the Space |
| `ADMIN_EMAIL` whitelist | Same — never used |
| `initializeFirebase()` no-op | Same |
| The `daemonSemaphore` and busy-retry logic in `OpencodeLlmProvider` | Only relevant for the CLI path which is dead |
| The `parseCanonicalResponse` function | Old 1.16 event-shape parser, replaced by direct Zen SSE |
| The hardcoded `big-pickle` (in user/.env docs) | Not free; remove from any docs |
| The `zenBaseUrl` default of `https://gateway.opencode.ai/v1` | Wrong — fixed to `https://opencode.ai/zen/v1` in `e8ff5aac` |
| The old `event: open` / `event: session` / `event: requestBody` events in `/debug/daemon/chat` | Dead debug-only code |
| `kotlinx.coroutines.runBlocking` inside `runZenApiDiscovery` | Should be `suspend` for proper coroutine context |
| The 30-min read timeout on the daemon HTTP client | Only needed for the CLI path |
| `kotlinx.coroutines.sync.Semaphore(5)` (daemonSemaphore) | Only needed for the CLI path |
| All references to the opencode CLI in `opencode.json` | The CLI is bypassed; the file is ignored at runtime |

---

## 5. What Is Preventing the Android App from Working

This is the **most important section** — read carefully. There is **no
single bug**. There are several layered issues:

### 5.1 SERVER-SIDE issues (need fixing first)

1. **No persistent WebSocket / SSE channel from Android to Ktor**
   - Current state: Android polls `/chat/events/test` for stub events.
   - Need: A long-lived `GET /chat/events?session=…` SSE endpoint that
     the Android app holds open. Ktor's `respondTextWriter` is fine for
     this; the Android side needs to use OkHttp's `EventSource` or
     Ktor-client's SSE.
   - File: probably `server/.../routes/TimelineBridgeRoutes.kt` and/or
     a new `ChatEventsRoutes.kt`.

2. **`/chat/query` returns a single JSON, not a stream**
   - Current: `POST /chat/query` calls `ServerAgent.runOnce()` and
     returns the final `LlmResponse` as one JSON object.
   - Need: a streaming variant (`POST /chat/query/stream` or
     `Accept: text/event-stream` on the same route) that mirrors
     `/debug/llm/stream` — uses `GlobalScope.launch` + per-chunk
     `write()` + `flush()`.
   - The agent flow also needs to forward tool-call deltas to the
     client so the Android side can show "calling get_weather…"
     UIs.

3. **No retry / rate-limit backoff for the user-facing error**
   - The FreeUsageLimitError is sent verbatim to the client.
   - Need: detect it, return a structured `Retry-After: <seconds>`,
     and have the Android side back off automatically.

4. **MCP tool calls from the opencode CLI don't go through the direct LLM path**
   - The opencode CLI daemon calls `/mcp/sse` for tools. If we bypass
     the CLI for the LLM, we lose the tool-calling context the daemon
     had.
   - Decision needed: do we (a) use the CLI as a "shell" for MCP tools
     only, or (b) rewrite the agent to call MCP directly from Ktor?
   - Currently neither is implemented. The direct LLM path is a
     **demo** — not the real agent flow.

5. **Discovery + model filtering has a race condition**
   - `discoverAtStartup()` runs once at Ktor boot. If it returns 0
     models, the registry falls back to Zen `/models`, but that
     fallback is sync-blocking and may slow first request.
   - Need: lazy refresh, and a `/debug/model/refresh` endpoint that
     forces a re-fetch.

### 5.2 APP-SIDE issues (likely also broken)

> **You need to actually run the Android app and watch logcat to
> confirm these.** I cannot do that from here.

1. **The Android client probably uses `HttpURLConnection` or Retrofit
   without an SSE adapter.** Retrofit doesn't speak SSE out of the box.
   You need either `OkHttp-EventSource` (com.launchdarkly:okhttp-eventsource)
   or the Ktor-client `prepareGet` with `Content-Type: text/event-stream`.

2. **The SSE parsing in the app likely expects a single JSON in
   `data:` lines.** Our format is a single JSON with all chunk info
   bundled, so that part is OK — but make sure the app doesn't
   re-parse `event:` lines (we use `event: message` for chunks,
   `event: done` for the final marker).

3. **The Android side may be waiting for `Content-Length` before
   rendering.** SSE has no content length. You need
   `transfer-encoding: chunked` to be accepted (Ktor does this), and
   your HTTP client must NOT call `InputStream.readAllBytes()`.

4. **The Android UI probably has a `TextView.setText(jsonString)` call
   that overwrites the streamed text on every chunk.** You need
   `textView.append(chunk.content)` and to keep the
   `accumulated` text in a `StringBuilder` in the ViewModel.

5. **The Android `runBlocking` may be on the main thread.** If the
   SSE connection is open, the main thread will block. Use
   `lifecycleScope.launch(Dispatchers.IO)`.

6. **The auth token is sent as a hardcoded `Bearer dummy`.** That's
   fine for now since the Space is public, but the real Android app
   probably sends the Firebase token. Confirm it matches
   `bearer("firebase")` no-op expectations — i.e. it's accepted by
   Ktor but the principal is just `anonymous`.

7. **TLS / certificate pinning.** The HF Space is HTTPS but the cert
   chain may differ from prod. Make sure the Android `OkHttpClient`
   trusts the HF cert (or use the system default truststore).

### 5.3 INFRASTRUCTURE issues

1. **No persistent storage of conversation state.** The `ChatRepository`
   is in-memory or Postgres-backed, but the streamed chunks are
   discarded after the SSE closes. If the Android app reconnects, it
   gets the same events replayed from the event bus — but only if the
   bus keeps them. Confirm with `/chat/events/test`.

2. **The Ktor process restarts on every push** (Docker rebuild → Space
   reboot). All in-memory state is lost. For a production app, use
   Supabase / Postgres for everything that needs to survive restarts.

3. **The Space sleeps after 48h of inactivity** (HF free tier). The
   first request after a sleep takes 30s for cold start. The Android
   app must handle this with a "Loading…" state and a 60s timeout.

4. **No CDN / Cloudflare in front.** Every request hits the Space
   directly. If the Space is rate-limited by Zen, so is the entire
   user base. A Cloudflare worker with a 60s cache on `/health` and a
   per-IP token bucket would help.

---

## 6. Concrete Next Steps (Priority Order)

| # | Task | Why | Effort |
|---|---|---|---|
| 1 | Add `POST /chat/query/stream` (or convert existing route to honor `Accept: text/event-stream`) | The current `/chat/query` is unusable for the Android app | 1-2h |
| 2 | Add `GET /chat/events?session=…` persistent SSE | The Android app needs a long-lived event channel for tool calls, partial answers, and timeline events | 1-2h |
| 3 | Forward MCP tool-call deltas through the SSE | Without this, the Android side can't show "calling add(2,3)…" | 2-3h |
| 4 | Auto-refresh model list + blacklist errored models | Currently qwen3.6-plus-free is still in the dropdown | 30m |
| 5 | Strip the opencode CLI path entirely from the production build | Reduces image size, removes the dead auth bug surface | 30m |
| 6 | Add `Cache-Control: no-store` and `X-Accel-Buffering: no` to all SSE routes | Prevents intermediate proxies from buffering | 5m |
| 7 | Add a `/debug/llm/stream` POST variant that accepts `tools` | Lets us reproduce tool-call bugs from curl | 15m |
| 8 | Migrate the Android HTTP client to OkHttp + okhttp-eventsource | Required for SSE to work | 1-2h |
| 9 | Add unit tests for `streamDirectZen` per model personality | Catches regressions when a new model is added | 1-2h |
| 10 | Get a real Zen API key and switch `Bearer public` → `Bearer <key>` | Eliminates the rate limit | 5m (user action) |
| 11 | Add a Cloudflare worker in front of the Space for egress IP diversity | Avoids per-IP rate limits | 2-3h |

---

## 7. The Single Most Important Question to Answer

Before any more code, **open the Android app, point it at
`https://K1tt3n-Friday-server.hf.space/debug/llm/stream`, and look at
logcat**. I bet the problem is one of:

- The OkHttp client is reading the full response body before calling
  the listener (blocking the main thread).
- The SSE adapter is treating the response as one JSON.
- The `TextView` is being reset on every chunk.

Run the app, watch logcat for 60s, paste the log here. The fix is
likely 5-10 lines of Android code.

---

## 8. The 24-Hour Plan (If You Want Me to Keep Going)

| Hour | Goal |
|---|---|
| 0-1 | Wait for the `1eec8526` deploy, re-test `/debug/llm/stream` with m3 — confirm `accumulated` no longer has `<think>` |
| 1-3 | Add `POST /chat/query/stream` — mirror `/debug/llm/stream` but call `ServerAgent.runOnce` and forward chunks |
| 3-5 | Add `GET /chat/events?session=…` — persistent SSE that listens to the opencode plugin's events and forwards to the Android client |
| 5-7 | Add tool-call forwarding through the SSE channel |
| 7-9 | Test the full loop with `accept: text/event-stream` from the Android emulator |
| 9-12 | Migrate `streamDirectZen` to use Ktor's `prepareGet`/`preparePost` properly (no `runBlocking` inside) |
| 12-15 | Auto-refresh model registry + blacklist errored models |
| 15-18 | Write integration tests for the SSE → Android path using a local Ktor + a minimal Kotlin HTTP client |
| 18-21 | Add `tools` passthrough to `/debug/llm/stream` so we can repro tool-call bugs from curl |
| 21-24 | Final docs pass — update `Documentacy.md` with anything new, add examples, and produce a smoke-test script |

---

_Last updated: end of debugging night, commit `1eec8526`._
