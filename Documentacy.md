# Documentacy — Zen Free Model Stream & Tool-Call Reference

> Living reference for **how each free Zen model emits SSE chunks** when
> accessed via the opencode/K1tt3n stack. Built from real captures on
> `https://opencode.ai/zen/v1/chat/completions`. Use this when writing a
> parser plugin for a new model or debugging "the model returned X but my
> parser only saw Y".

**All free models in this document accept `Authorization: Bearer public`** —
the Zen free tier requires no key.

---

## 0. The 5 Distinct Free-Model "Personalities"

| Model ID (as Zen sees it) | Provider | Underlying model | Reasoning style | Tool-call style |
|---|---|---|---|---|
| `deepseek-v4-flash-free` | DeepSeek | deepseek-v4-flash | Dedicated `delta.reasoning_content` field (streamed in parallel with `delta.content`) | Streamed character-by-character JSON in `delta.tool_calls[].function.arguments` |
| `mimo-v2.5-free` | Xiaomi | xiaomi/mimo-v2.5-20260422 | Dedicated `delta.reasoning` field + a `delta.reasoning_details[]` array with `type/text/format/index` | Streamed character-by-character, same shape as deepseek but with extra `native_finish_reason` and `service_tier` |
| `minimax-m3-free` | MiniMax | MiniMax-M3 | Inline in `delta.content` wrapped in `<think>...</think>` tags (no separate field) | Sent as a **single complete chunk** with full `id`, `type`, `function.name`, `function.arguments` already filled |
| `qwen3.6-plus-free` | — | — | ❌ **NO LONGER FREE** — returns `{"type":"error","error":{"type":"ModelError","message":"Free promotion has ended for Qwen3.6 Plus Free. You can continue using the model by subscribing to OpenCode Go - https://opencode.ai/go"}}` | n/a |
| `nemotron-3-ultra-free` | Nvidia | nemotron-3-ultra | (not tested — known to be heavily rate-limited) | (not tested) |
| `nemotron-3-super-free` | Nvidia | nemotron-3-super | (not tested) | (not tested) |

**The discovery list** at runtime is whatever `opencode models` CLI returns
filtered by the "free" pattern. The current snapshot is in
`/debug/model/info` on the Space.

---

## 1. Common SSE Wire Format

Every free model speaks the OpenAI chat-completions streaming protocol:

```
data: {"id":"...","object":"chat.completion.chunk","created":...,"model":"...","choices":[...],"usage":{...}}\n\n
data: {"id":"...","object":"chat.completion.chunk","created":...,"model":"...","choices":[...],"usage":{...}}\n\n
...
data: [DONE]\n\n
```

**MUST** handle:
- Each event is exactly one `data: <json>\n\n` line.
- `[DONE]` sentinel (literal string in `data:`).
- The final `data: {"choices":[],"cost":"0"}` **summary chunk** emitted by
  deepseek/mimo AFTER `[DONE]` — do not treat as a new payload.
- A **first frame** that sets `delta.role: "assistant"` and may have
  `delta.content: null` and `delta.reasoning_content: ""` (deepseek) or
  `delta.content: "<think>\n..."` (minimax-m3) or `delta.content: ""` (mimo).
- A **final frame** that has `finish_reason: "stop" | "tool_calls" | "length"`.

---

## 2. `delta` Field Reference (per model)

### 2.1 `deepseek-v4-flash-free`

```jsonc
// First chunk (start of turn)
{
  "choices": [{
    "index": 0,
    "delta": {
      "role": "assistant",
      "content": null,
      "reasoning_content": ""        // opens reasoning stream
    },
    "finish_reason": null
  }],
  "usage": null
}

// Reasoning chunk (many)
{
  "choices": [{
    "index": 0,
    "delta": {
      "content": null,                // text not yet produced
      "reasoning_content": "First"    // append to caller's reasoning buffer
    },
    "finish_reason": null
  }]
}

// Content chunk (eventually)
{
  "choices": [{
    "index": 0,
    "delta": {
      "content": "Quantum ",          // append to caller's content buffer
      "reasoning_content": null       // reasoning done
    },
    "finish_reason": null
  }]
}

// First tool_call chunk (when tools are used)
{
  "choices": [{
    "index": 0,
    "delta": {
      "tool_calls": [{
        "index": 0,
        "id": "call_00_pyhtnUNKMDTBgKymx4ex7742",
        "type": "function",
        "function": { "name": "add", "arguments": "" }
      }]
    }
  }]
}

// Subsequent tool_call chunks (streamed args)
{
  "choices": [{
    "index": 0,
    "delta": {
      "tool_calls": [{ "index": 0, "function": { "arguments": "{" } }]
    }
  }]
}
// ... { "arguments": "\"" } ... { "arguments": "a" } ... { "arguments": "}" }

// Final chunk
{
  "choices": [{
    "index": 0,
    "delta": { "content": "", "reasoning_content": null },
    "finish_reason": "tool_calls"     // or "stop" or "length"
  }],
  "usage": {
    "prompt_tokens": 370,
    "completion_tokens": 87,
    "total_tokens": 457,
    "prompt_tokens_details": { "cached_tokens": 0 },
    "completion_tokens_details": { "reasoning_tokens": 28 },
    "prompt_cache_hit_tokens": 0,
    "prompt_cache_miss_tokens": 370
  }
}
```

**Gotchas**:
- `tool_choice: "required"` → API error: `"Error from provider (DeepSeek): Thinking mode does not support this tool_choice"`. **Do not** set `tool_choice="required"` with deepseek.
- Reasoning can run to **thousands of tokens** before content starts. Set a long HTTP read timeout (30+ min) and keepalive flushes.

### 2.2 `mimo-v2.5-free`

```jsonc
// First chunk
{
  "id": "gen-1780707271-l2ficpgEhlHxb3FXbdbm",
  "model": "xiaomi/mimo-v2.5-20260422",
  "provider": "Xiaomi",           // <-- extra top-level field
  "choices": [{
    "index": 0,
    "delta": {
      "content": "",
      "role": "assistant",
      "reasoning": "First",        // <-- single field, not array
      "reasoning_details": [{      // <-- structured details
        "type": "reasoning.text",
        "text": "First",
        "format": "unknown",
        "index": 0
      }]
    },
    "finish_reason": null,
    "native_finish_reason": null   // <-- extra
  }]
}

// Tool call (mimo streams the same as deepseek)
{
  "choices": [{
    "delta": {
      "content": null,
      "role": "assistant",
      "tool_calls": [{
        "index": 0,
        "id": "call_0ab7211fbe5e4a148c8b0701",
        "type": "function",
        "function": { "name": "get_weather", "arguments": "" }
      }]
    }
  }]
}
// ...args stream char by char...

// Final usage chunk
{
  "service_tier": null,           // <-- mimo-specific
  "choices": [{ "delta": { "content": "", "role": "assistant" }, "finish_reason": "tool_calls", "native_finish_reason": "tool_calls" }],
  "usage": {
    "prompt_tokens": 484,
    "completion_tokens": 39,
    "total_tokens": 523,
    "cost": 0,                     // <-- mimo has cost
    "is_byok": true,               // <-- mimo has BYOK flag
    "prompt_tokens_details": {
      "cached_tokens": 192,
      "cache_write_tokens": 0,
      "audio_tokens": 0,
      "video_tokens": 0
    },
    "cost_details": {              // <-- mimo has cost_details
      "upstream_inference_cost": 0.0000523376,
      "upstream_inference_prompt_cost": 0.0000414176,
      "upstream_inference_completions_cost": 0.00001092
    },
    "completion_tokens_details": {
      "reasoning_tokens": 16,
      "image_tokens": 0,
      "audio_tokens": 0
    }
  }
}
```

**Gotchas**:
- `provider: "Xiaomi"` is set on the first chunk but is constant — use it to switch parsers if you want to be clever.
- `native_finish_reason` mirrors `finish_reason` but is non-null for some
  OpenRouter-style stop reasons; prefer `finish_reason`.

### 2.3 `minimax-m3-free` (MiniMax)

**MOST DIFFERENT FORMAT** — reasoning is embedded as `<think>...</think>` tags in `delta.content`, NOT a separate field.

```jsonc
// First chunk (start of reasoning)
{
  "id": "0672a2dfccb26daf3aad65130f1ccad9",
  "model": "MiniMax-M3",
  "choices": [{
    "index": 0,
    "delta": {
      "content": "<think>\nThe user is asking a",   // <-- reasoning starts IMMEDIATELY
      "role": "assistant",
      "name": "MiniMax AI",                          // <-- model name in delta
      "audio_content": ""                            // <-- multimodal placeholder
    }
  }],
  "usage": null,
  "input_sensitive": false,
  "output_sensitive": false,
  "input_sensitive_type": 0,
  "output_sensitive_type": 0,
  "output_sensitive_int": 0
}

// Final reasoning + answer chunk (one big chunk often)
{
  "choices": [{
    "finish_reason": "stop",
    "index": 0,
    "delta": {
      "content": " simple math question and wants a one-word answer.\n</think>\nFour",
      "role": "assistant",
      "name": "MiniMax AI",
      "audio_content": ""
    }
  }]
}

// Tool call — SENT IN ONE CHUNK, not streamed
{
  "choices": [{
    "finish_reason": "tool_calls",
    "index": 0,
    "delta": {
      "role": "assistant",
      "name": "MiniMax AI",
      "tool_calls": [{
        "id": "call_function_f4aw3vvlxat9_1",
        "type": "function",
        "function": {
          "name": "add",
          "arguments": "{\"a\": 2, \"b\": 3}"        // <-- FULL ARGS, not streamed
        },
        "index": 0
      }],
      "audio_content": ""
    }
  }]
}

// Usage chunk (always last, even for content-only responses)
{
  "choices": [],
  "usage": {
    "total_tokens": 204,
    "total_characters": 0,
    "prompt_tokens": 186,
    "completion_tokens": 18,
    "prompt_tokens_details": { "cached_tokens": 114 }
  },
  "base_resp": { "status_code": 0, "status_msg": "" }   // <-- MiniMax-specific
}
```

**Gotchas**:
- Parser MUST split `delta.content` on `<think>...\n</think>` to get
  reasoning vs answer. Do NOT trust a separate reasoning field.
- Tool calls arrive **atomically** — no need to buffer `arguments` per
  chunk. Just JSON.parse the `arguments` string when `finish_reason == "tool_calls"`.
- `name: "MiniMax AI"` is repeated in every delta — ignore.
- The post-response usage chunk has `choices: []` and `base_resp` (different shape from OpenAI).
- `input_sensitive` / `output_sensitive` flags = content moderation markers.

---

## 3. How the Ktor Parser Handles Each Shape

In `OpencodeLlmProvider.kt` → `streamDirectZen()`:

| Field to extract | deepseek | mimo | m3 |
|---|---|---|---|
| Reasoning text | `delta.reasoning_content` | `delta.reasoning` (also see `delta.reasoning_details[].text`) | Regex-split `delta.content` on `<think>\n(.*?)\n</think>` |
| Answer text | `delta.content` | `delta.content` | `delta.content` minus the `<think>` block |
| Tool-call name | first chunk with `delta.tool_calls[0].function.name` | same | single chunk `delta.tool_calls[0].function.name` |
| Tool-call args (streamed) | buffer `delta.tool_calls[0].function.arguments` per chunk | same | take `delta.tool_calls[0].function.arguments` once |
| Tool-call id | `delta.tool_calls[0].id` on first chunk | same | same |
| Finish reason | `choices[0].finish_reason` | `choices[0].finish_reason` (ignore `native_finish_reason`) | `choices[0].finish_reason` |
| Usage | last chunk `usage` | last chunk `usage` (note: `cost` field) | last chunk `usage` (note: `base_resp`) |

**Current state**: The Ktor parser in `streamDirectZen` (in
`server/.../llm/OpencodeLlmProvider.kt`) handles `content` and
`reasoning_content` for deepseek. It treats both as `content` in
`LlmChunk.content` and doesn't currently surface `reasoning_details`
or `tool_calls` separately — those go into `LlmChunk.rawJson` only.

**Mimo and m3 reasoning is currently being treated as `content` text** in
the parser, which means the m3 `<think>...</think>` tags will leak into
the user-visible stream unless the Android side strips them. **This is
the #1 thing to fix next.**

---

## 4. End-to-End Proof of Work — Space Deployed

**Environment**: `https://K1tt3n-Friday-server.hf.space/debug/llm/stream`
(POST `{message, model}`). Bypasses the opencode CLI daemon, calls
`https://opencode.ai/zen/v1/chat/completions` directly with
`Authorization: Bearer public` (gated on `OPENCODE_USE_DIRECT_ZEN=true`).

### Test 1 — `big-pickle` → 822 chunks, 8408ms
Prompt: `"Explain quantum computing in exactly 3 short paragraphs, with
one real-world example in each paragraph. Use simple language."`

```
chunks: 822
firstChunkMs: 530
totalMs: 8408
accumulated: [full 3-paragraph essay about qubits, entanglement, optimization]
```

Per-chunk timing varied 0–138ms (real streaming, not buffered).

### Test 2 — `minimax-m3-free` → 5 chunks, 2440ms
Prompt: `"List 3 colors of the rainbow."`

```
chunks: 5
firstChunkMs: 972
totalMs: 2440
accumulated: "<think>\nThe user is asking a simple question about rainbow colors. I'll list 3 colors.\n</think>\nThree colors of the rainbow are:\n\n1. **Red**\n2. **Blue**\n3. **Green**\n\n(For reference, the full rainbow spectrum in order is: red, orange, yellow, green, blue, indigo, and violet.)"
```

### Test 3 — `mimo-v2.5-free` → 30 chunks, 2538ms
Prompt: `"List 3 colors of the rainbow."`

```
chunks: 30
firstChunkMs: 1401
totalMs: 2538
accumulated: "Sure! Here are three colors of the rainbow:\n\n1. **Red**\n2. **Yellow**\n3. **Blue** dYO^\n\nOf course, the full rainbow has seven colors �? red, orange, yellow, green, blue, indigo, and violet �? but these three are among the most commonly recognized!"
```

### Test 4 — `deepseek-v4-flash-free` → 933 chunks, 8940ms
Prompt: `"List 3 colors of the rainbow."`

```
chunks: 933
firstChunkMs: 406
totalMs: 8940
accumulated: "Thinking.1. **Analyze the Request:**\n * Target: List3 colors of the rainbow.\n * Constraint: Length of argumentative text (not specified, but must understand the prompt's straightforward nature).\n * ...\n\nRed, yellow, and blue."
```

### Tool-call proof — `minimax-m3-free` (the only free model that does this reliably)

```
Prompt: "You MUST call the add tool with a=2 and b=3."
Tools: [{"type":"function","function":{"name":"add","description":"Add two numbers","parameters":{"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},"required":["a","b"]}}}]
tool_choice: "required"

Chunk 1: delta.content = "<think>\nThe user is asking me to call the add tool with specific parameters..."
Chunk 2: delta.content = " add tool with specific parameters. I need to use the add tool with a=2 and b=3.\n</think>\n"
Chunk 3: delta.tool_calls = [{
  "id": "call_function_f4aw3vvlxat9_1",
  "type": "function",
  "function": { "name": "add", "arguments": "{\"a\": 2, \"b\": 3}" },
  "index": 0
}], finish_reason: "tool_calls"
Chunk 4: usage = {"total_tokens": 489, "prompt_tokens": 425, "completion_tokens": 64, "prompt_tokens_details": {"cached_tokens": 114}}
```

### Tool-call proof — `deepseek-v4-flash-free` (streams args char by char)

```
Chunk 1: delta.tool_calls[0] = {id: "call_00_pyhtnUNKMDTBgKymx4ex7742", type: "function", function: {name: "add", arguments: ""}}
Chunk 2: delta.tool_calls[0] = {index: 0, function: {arguments: "{"}}
Chunk 3: delta.tool_calls[0] = {index: 0, function: {arguments: "\""}}
... (many more char-by-char chunks)
Chunk N: delta.tool_calls[0] = {index: 0, function: {arguments: "}"}}
Final: finish_reason: "tool_calls", usage: {prompt_tokens: 370, completion_tokens: 87, total_tokens: 457, ...}
```

### Tool-call proof — `mimo-v2.5-free` (also streams args char by char, with extra metadata)

```
Chunk 1: delta.tool_calls[0] = {index: 0, id: "call_0ab7211fbe5e4a148c8b0701", type: "function", function: {name: "get_weather", arguments: ""}}
... (streamed char by char) ...
Chunk N: finish_reason: "tool_calls", native_finish_reason: "tool_calls"
Final: usage = {prompt_tokens: 484, completion_tokens: 39, total_tokens: 523, cost: 0, is_byok: true, ...}
```

### Failure mode — `qwen3.6-plus-free` (NO LONGER FREE)

```json
{
  "type": "error",
  "error": {
    "type": "ModelError",
    "message": "Free promotion has ended for Qwen3.6 Plus Free. You can continue using the model by subscribing to OpenCode Go - https://opencode.ai/go"
  }
}
```

→ **Discovery must filter by `id contains "free"` AND verify at runtime.**
The current dynamic registry trusts the CLI's list — the parser should
also handle `ModelError` gracefully and remove the model from the live
list.

### Failure mode — `tool_choice: "required"` with deepseek

```json
{
  "error": {
    "message": "Error from provider (DeepSeek): Thinking mode does not support this tool_choice",
    "type": "invalid_request_error",
    "code": "invalid_request_error"
  }
}
```

→ **Do NOT** pass `tool_choice: "required"` to deepseek. Drop it or use
`"auto"`.

---

## 5. How to Add a New Model Parser (Plugin Recipe)

When you discover a new model that the default parser doesn't handle:

1. **Capture the raw SSE**: `curl -N https://opencode.ai/zen/v1/chat/completions -d '{...}'` with the
   smallest prompt that triggers the model's distinctive behaviour (use
   `tools` for tool-call format, use a multi-step prompt for reasoning).

2. **Diff against the 3 personalities above**:
   - Is `delta.reasoning_content` or `delta.reasoning` present? → deepseek/mimo family
   - Is reasoning in `<think>` tags inside `delta.content`? → m3 family
   - Does the first chunk have extra top-level fields (`provider`, `service_tier`, `base_resp`)? → note them but don't depend on them.

3. **Add a branch in `streamDirectZen()` in `OpencodeLlmProvider.kt`**:
   ```kotlin
   val reasoning = delta["reasoning"]?.jsonPrimitive?.contentOrNull
                   ?: delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
   val content = delta["content"]?.jsonPrimitive?.contentOrNull
   // For m3-style: strip <think>...</think> from content
   ```

4. **Surface tool calls**: extend `LlmChunk` if needed (currently has
   `toolCall: LlmToolCall?`). Set it when you see `delta.tool_calls[0]`.

5. **Test on the Space** with the actual model via `/debug/llm/stream`,
   and add the captured chunk stream to this file as a new "Test N"
   entry.

---

## 6. Rate Limit & Operational Reality

- **Shared HF egress IP** → Zen free tier rate-limits aggressively.
- Symptom: every request returns `{"type":"error","error":{"type":"FreeUsageLimitError","message":"Rate limit exceeded. Please try again later."},"metadata":{}}` within ~100ms.
- Cooldown appears to be **5–20 minutes** of inactivity.
- The `big-pickle` alias for `deepseek-v4-flash` is currently still being
  served on the free tier even though the model isn't in the "free"
  list — treat as opportunistic, not guaranteed.
- Different free models share the same rate-limit pool (per-IP), so
  switching models doesn't help once you're throttled.

---

## 7. Quick-Reference: Discovery → Test Loop

```bash
# 1) See what's currently free
curl -s https://K1tt3n-Friday-server.hf.space/debug/model/info

# 2) Hit a specific free model directly
curl -sN --max-time 90 -X POST \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dummy" \
  -d '{"message":"hi","model":"opencode/mimo-v2.5-free"}' \
  https://K1tt3n-Friday-server.hf.space/debug/llm/stream

# 3) Hit Zen directly (bypasses Ktor) to see the raw SSE
curl -sN --max-time 60 -X POST \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer public" \
  -d '{"model":"deepseek-v4-flash-free","messages":[{"role":"user","content":"hi"}],"stream":true}' \
  https://opencode.ai/zen/v1/chat/completions
```

---

_Last updated: end of /debug/llm/stream sweep on commit `e8ff5aac`._
