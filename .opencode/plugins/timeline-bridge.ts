/**
 * Timeline Bridge Plugin v3 — v1.15.6 Production-Grade
 *
 * Goals (Friday agent, 100% live transparency):
 *   - Forward EVERYTHING OpenCode does to the Ktor bridge — no gaps
 *   - Sub-agent hierarchy: track parent/child sessions for inline sub-agent card
 *   - Bounded async queue with retry/backoff — survive Ktor hiccups + delta storms
 *   - AbortController on dispose — clean teardown
 *   - keep-alive HTTP — lower latency on small payloads
 *   - Tool safety net: deny bash/read/write/grep/glob (defense in depth —
 *     opencode.json permissions is primary, this is the belt)
 *   - Surface web search/fetch with full prompt + result + site domains
 *     so the Android UI can show the sliding-icon card
 *
 * Event kinds emitted (Ktor reads `kind` to route to AgentEvent):
 *   session.created | session.idle | session.compacted | session.compaction.start |
 *   session.error | session.aborted | session.status | subagent.created | subagent.idle |
 *   message.updated | message.completed |
 *   message.part.delta | part.updated |
 *   tool.before | tool.after | tool.denied |
 *   user.input.required | user.input.resolved |
 *   permission.ask | permission.reply |
 *   websearch.query | websearch.result |
 *   webfetch.url | webfetch.result |
 *   compaction.start | compaction.complete |
 *   command.execute | shell.env |
 *   file.edited | lsp.diagnostics |
 *   plugin.dispose
 */

import type { Plugin } from "@opencode-ai/plugin"

const BRIDGE_URL = "http://127.0.0.1:7860/opencode/events"

// ── Bounded queue + retry config ────────────────────────────────────────────
const MAX_QUEUE_SIZE = 5000
const MAX_RETRIES = 5
const RETRY_BASE_MS = 100
const KEEP_ALIVE_HEADER = { "Connection": "keep-alive" }

// ── Tool classification ─────────────────────────────────────────────────────
const MCP_TOOLS = new Set([
  "ask_user", "ask", "askuser", "confirm", "question", "clarify", "input",
  "memory", "memory_save", "memory_find", "memory_update", "memory_delete", "memory_remember",
  "schedule", "schedule_add", "schedule_list", "schedule_remove",
  "remind", "remind_set", "remind_list", "remind_cancel",
  "device", "device_open", "device_media", "device_toggle", "device_status", "device_capture",
  "navigate", "navigate_screen", "navigate_share",
  "generate_image",
  "search_history", "get_note_by_id",
  "guided_breathing",
  "save_progress", "read_progress",
])

const INTERACTIVE_TOOLS = new Set([
  "ask_user", "ask", "askuser", "confirm", "question", "clarify", "input",
])

// DENIED tools — safety net. opencode.json permission: "deny" is primary.
const DENIED_TOOLS = new Set([
  "bash", "read", "write", "edit", "grep", "glob", "list", "patch",
  "multiedit", "webfetch_unsafe",
])

// Tools whose results include website metadata
const WEB_TOOLS = new Set(["websearch", "webfetch", "web_search", "web_fetch"])

// Thinking tag detection
const THINK_OPEN_TAGS = ["<think>", "[think]", "<thought>", "[thought]", "<reasoning>", "<|DSML|"]
const THINK_CLOSE_TAGS = ["</think>", "[/think]", "</thought>", "[/thought]", "</reasoning>", "|>"]

// ── Bounded async queue with retry/backoff (DR1 §Race Conditions) ──────────
class EventQueue {
  private queue: string[] = []
  private inflight: Set<number> = new Set()
  private abortController = new AbortController()
  private totalDropped = 0
  private totalFailed = 0
  private totalSent = 0
  private lastErrorLog = 0

  enqueue(json: string): boolean {
    if (this.queue.length >= MAX_QUEUE_SIZE) {
      this.totalDropped++
      this.queue.shift() // drop oldest, keep newest
    }
    this.queue.push(json)
    this.kick()
    return true
  }

  private kick(): void {
    if (this.queue.length === 0) return
    const json = this.queue.shift()!
    const id = Date.now() + Math.random()
    this.inflight.add(id)
    this.send(json, id, 0)
  }

  private async send(json: string, id: number, attempt: number): Promise<void> {
    if (this.abortController.signal.aborted) {
      this.inflight.delete(id)
      return
    }
    try {
      const res = await fetch(BRIDGE_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json", ...KEEP_ALIVE_HEADER },
        body: json,
        signal: this.abortController.signal,
        // @ts-ignore — keepalive is supported in Bun + Node 18+
        keepalive: true,
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      this.totalSent++
      this.inflight.delete(id)
      if (this.queue.length > 0) this.kick()
    } catch (e: any) {
      if (e?.name === "AbortError") {
        this.inflight.delete(id)
        return
      }
      if (attempt < MAX_RETRIES) {
        const backoff = RETRY_BASE_MS * Math.pow(2, attempt) + Math.random() * 50
        setTimeout(() => {
          if (!this.abortController.signal.aborted) {
            this.send(json, id, attempt + 1)
          } else {
            this.inflight.delete(id)
          }
        }, backoff)
      } else {
        this.totalFailed++
        this.inflight.delete(id)
        const now = Date.now()
        if (now - this.lastErrorLog > 10_000) {
          this.lastErrorLog = now
          console.error(`[TimelineBridge] Event dropped after ${MAX_RETRIES} retries (totalFailed=${this.totalFailed})`)
        }
        if (this.queue.length > 0) this.kick()
      }
    }
  }

  stats() {
    return { queued: this.queue.length, inflight: this.inflight.size, dropped: this.totalDropped, failed: this.totalFailed, sent: this.totalSent }
  }

  dispose(): void {
    this.abortController.abort()
    this.queue.length = 0
    this.inflight.clear()
  }
}

// ── Coalescing buffer for message.part.delta events (Phase 3.1) ───────────────
const COALESCE_WINDOW_MS = 50
const coalescingBuffers = new Map<string, { text: string[], reasoning: string[], timer: ReturnType<typeof setTimeout> }>()
let coalesceSeq = 0

function flushCoalesced(key: string, buf: { text: string[], reasoning: string[], timer: ReturnType<typeof setTimeout> }): void {
  const text = buf.text.join("")
  const reasoning = buf.reasoning.join("")
  if (text || reasoning) {
    queue.enqueue(JSON.stringify({
      ts: Date.now(),
      kind: "message.part.delta",
      sessionID: key.split(":")[0],
      messageID: key.split(":")[1],
      partID: key.split(":")[2],
      coalesceSeq: ++coalesceSeq,
      text,
      reasoning,
    }))
  }
  coalescingBuffers.delete(key)
}

function enqueueCoalesced(sessionID: string, messageID: string, partID: string, text: string, reasoning: string): void {
  const key = `${sessionID}:${messageID}:${partID}`
  const buf = coalescingBuffers.get(key) ?? { text: [], reasoning: [], timer: null }
  if (text) buf.text.push(text)
  if (reasoning) buf.reasoning.push(reasoning)
  if (!buf.timer) {
    buf.timer = setTimeout(() => {
      flushCoalesced(key, buf)
    }, COALESCE_WINDOW_MS)
  }
  coalescingBuffers.set(key, buf)
}

// ── Extract website domains from websearch/webfetch result ──────────────────
function extractDomains(text: string): string[] {
  if (!text || typeof text !== "string") return []
  const domains = new Set<string>()
  const urlRe = /https?:\/\/([^\/\s?"<>)]+)/g
  let m: RegExpExecArray | null
  while ((m = urlRe.exec(text)) !== null) {
    try {
      const host = new URL(m[0]).hostname.replace(/^www\./, "")
      if (host) domains.add(host)
    } catch { /* skip */ }
  }
  return Array.from(domains)
}

// ── Plugin entry ────────────────────────────────────────────────────────────
export const TimelineBridgePlugin: Plugin = async ({ client, project }: any) => {
  const queue = new EventQueue()

  // sessionID → { parentSessionID, startedAt, toolCallCount, isThinking, partTextLength, compactionCount, isSubAgent }
  const sessions = new Map<string, {
    startedAt: number
    parentSessionID?: string
    toolCallCount: number
    isThinking: boolean
    partTextLength: Map<string, number>
    compactionCount: number
    isSubAgent: boolean
    subAgentTitle?: string
  }>()

  // Map callID → subAgentSessionID for tool calls in sub-agents
  const subAgentByCallID = new Map<string, string>()

  function getSession(sessionID: string) {
    if (!sessions.has(sessionID)) {
      sessions.set(sessionID, {
        startedAt: Date.now(),
        toolCallCount: 0,
        isThinking: false,
        partTextLength: new Map(),
        compactionCount: 0,
        isSubAgent: false,
      })
    }
    return sessions.get(sessionID)!
  }

  function emit(payload: Record<string, unknown>): void {
    queue.enqueue(JSON.stringify({ ts: Date.now(), ...payload }))
  }

  // Periodic cleanup of idle sessions
  const cleanupTimer = setInterval(() => {
    const cutoff = Date.now() - 3 * 60 * 60 * 1000
    for (const [id, s] of sessions) {
      if (s.startedAt < cutoff) sessions.delete(id)
    }
  }, 5 * 60 * 1000)

  // Stats reporter for observability
  const statsTimer = setInterval(() => {
    const s = queue.stats()
    if (s.queued > 100 || s.dropped > 0 || s.failed > 0) {
      console.log(`[TimelineBridge] stats=${JSON.stringify(s)}`)
    }
  }, 60_000)

  // Log plugin load
  try {
    await client.app.log({
      level: "info",
      message: `TimelineBridgePlugin v3.0.0 loaded (project: ${project?.id ?? "unknown"})`,
    })
  } catch { /* best-effort */ }

  return {
    // ── Catch-all for session lifecycle + unknown event types ──────────────
    event: async ({ event }: { event: any }) => {
      const t = event.type
      const props = event.properties ?? {}

      if (t === "session.created") {
        const sessionID: string = props.sessionID
        const parentID: string | undefined = props.parentID ?? props.parentSessionID
        const title: string | undefined = props.title
        const s = getSession(sessionID)
        if (parentID) {
          s.parentSessionID = parentID
          s.isSubAgent = true
          s.subAgentTitle = title
        }
        emit({
          kind: parentID ? "subagent.created" : "session.created",
          sessionID,
          parentSessionID: parentID,
          title,
          ts: Date.now(),
        })
      }

      else if (t === "session.idle") {
        const sessionID: string = props.sessionID
        const s = sessions.get(sessionID)
        emit({
          kind: s?.isSubAgent ? "subagent.idle" : "session.idle",
          sessionID,
          parentSessionID: s?.parentSessionID,
          durationMs: s ? Date.now() - s.startedAt : null,
          totalToolCalls: s?.toolCallCount ?? null,
          ts: Date.now(),
        })
        sessions.delete(sessionID)
      }

      else if (t === "session.compacted") {
        emit({ kind: "session.compacted", sessionID: props.sessionID, ts: Date.now() })
      }

      else if (t === "session.error") {
        emit({ kind: "session.error", sessionID: props.sessionID, error: props.error, ts: Date.now() })
        sessions.delete(props.sessionID)
      }

      else if (t === "session.status") {
        const status = props.status
        if (status?.type === "retry" && (status?.attempt ?? 0) > 5) {
          try { await client.session.abort({ path: { id: props.sessionID } }) } catch { /* swallow */ }
          emit({ kind: "session.aborted", reason: "retry_limit_exceeded", sessionID: props.sessionID, ts: Date.now() })
        } else {
          emit({ kind: "session.status", sessionID: props.sessionID, status, ts: Date.now() })
        }
      }

      else if (t === "message.updated") {
        const payload: Record<string, unknown> = {
          kind: "message.updated",
          sessionID: props.sessionID,
          messageID: props.messageID,
          info: props.info,
          message: (props as any).message,
          ts: Date.now(),
        }
        const rawParts =
          (props as any).parts ??
          (props.info as any)?.parts ??
          (props.message as any)?.parts
        if (rawParts) {
          payload.parts = Array.isArray(rawParts) ? rawParts : Object.values(rawParts)
        }
        emit(payload)
      }

      else if (t === "message.completed") {
        emit({ kind: "message.completed", sessionID: props.sessionID, messageID: props.messageID, ts: Date.now() })
      }
    },

    // ── Message parts — text, reasoning, tool, sub-agent, steps ────────────
    "message.part.updated": async ({ part }: { part: any }) => {
      const s = getSession(part.sessionID)

      if (part.type === "text") {
        const delta = part.delta
        const text: string = delta?.text ?? part.content ?? part.text ?? ""
        const reasoning: string = delta?.reasoning ?? part.reasoning ?? ""
        const hasOpen = THINK_OPEN_TAGS.some((tag) => text.includes(tag))
        const hasClose = THINK_CLOSE_TAGS.some((tag) => text.includes(tag))
        if (hasOpen && !hasClose) s.isThinking = true
        if (hasClose) s.isThinking = false
        if (reasoning.length > 0) s.isThinking = true
        emit({
          kind: "part.updated",
          phase: "streaming",
          sessionID: part.sessionID,
          messageID: part.messageID,
          partID: part.id,
          partType: "text",
          text,
          reasoning,
          delta,
          isThinkingHint: s.isThinking,
          isSubAgent: s.isSubAgent,
          parentSessionID: s.parentSessionID,
          ts: Date.now(),
        })
      }

      else if (part.type === "reasoning") {
        emit({
          kind: "part.updated",
          phase: "snapshot",
          sessionID: part.sessionID,
          messageID: part.messageID,
          partID: part.id,
          partType: "reasoning",
          reasoning: part.reasoning ?? "",
          thinkingDurationMs:
            part.time?.end != null && part.time?.start != null
              ? part.time.end - part.time.start
              : null,
          isSubAgent: s.isSubAgent,
          parentSessionID: s.parentSessionID,
          ts: Date.now(),
        })
      }

      else if (part.type === "tool") {
        if (part.state?.status === "running") s.toolCallCount++
        const toolName = (part.tool ?? "").toLowerCase()
        const isMcp = MCP_TOOLS.has(toolName)
        emit({
          kind: "part.updated",
          phase: "streaming",
          sessionID: part.sessionID,
          messageID: part.messageID,
          partID: part.id,
          partType: "tool",
          tool: part.tool,
          toolCallID: part.toolCallID,
          state: part.state?.status,
          input: "input" in (part.state ?? {}) ? part.state.input : undefined,
          output: "output" in (part.state ?? {}) ? part.state.output : undefined,
          error: "error" in (part.state ?? {}) ? part.state.error : undefined,
          raw: "raw" in (part.state ?? {}) ? part.state.raw : undefined,
          isMcpTool: isMcp,
          isSubAgent: s.isSubAgent,
          parentSessionID: s.parentSessionID,
          ts: Date.now(),
        })
      }

      else if (part.type === "subtask") {
        emit({
          kind: "part.updated",
          phase: "streaming",
          sessionID: part.sessionID,
          messageID: part.messageID,
          partID: part.id,
          partType: "subtask",
          agent: part.agent,
          description: part.description,
          state: part.state,
          ts: Date.now(),
        })
      }

      else if (part.type === "step-start") {
        emit({
          kind: "part.updated",
          phase: "streaming",
          sessionID: part.sessionID,
          messageID: part.messageID,
          partID: part.id ?? `step-start-${part.step}`,
          partType: "step-start",
          step: part.step,
          isSubAgent: s.isSubAgent,
          parentSessionID: s.parentSessionID,
          ts: Date.now(),
        })
      }

      else if (part.type === "step-finish") {
        emit({
          kind: "part.updated",
          phase: "streaming",
          sessionID: part.sessionID,
          messageID: part.messageID,
          partID: part.id ?? `step-finish-${part.step}`,
          partType: "step-finish",
          step: part.step,
          cost: part.cost,
          tokens: part.tokens,
          isSubAgent: s.isSubAgent,
          parentSessionID: s.parentSessionID,
          ts: Date.now(),
        })
      }

      else if (part.type === "compaction") {
        emit({
          kind: "part.updated",
          phase: "streaming",
          sessionID: part.sessionID,
          messageID: part.messageID,
          partID: part.id,
          partType: "compaction",
          ts: Date.now(),
        })
      }

      else if (part.type === "file") {
        emit({
          kind: "part.updated",
          phase: "streaming",
          sessionID: part.sessionID,
          messageID: part.messageID,
          partID: part.id,
          partType: "file",
          filename: part.filename,
          mediaType: part.mediaType,
          source: part.source,
          ts: Date.now(),
        })
      }
    },

    // ── Word-by-word streaming ─────────────────────────────────────────────
    "message.part.delta": async ({ part, delta }: { part: any; delta: any }) => {
      const s = sessions.get(part.sessionID)
      const text = delta?.text ?? delta?.content ?? ""
      const reasoning = delta?.reasoning ?? delta?.reasoning_content ?? ""
      if (!text && !reasoning) return
      enqueueCoalesced(
        part.sessionID,
        part.messageID,
        part.id,
        text,
        reasoning,
      )
    },

    // ── Tool execution — safety net for denied tools + web tool metadata ──
    "tool.execute.before": async (input: any, output: any) => {
      const toolName: string = input.tool ?? ""
      const toolLower = toolName.toLowerCase()
      const callID = input.callID ?? input.toolCallID
      const args = output?.args ?? {}

      // Defense in depth: deny if on the denylist (should be blocked by opencode.json too)
      if (DENIED_TOOLS.has(toolLower)) {
        emit({
          kind: "tool.denied",
          sessionID: input.sessionID,
          callID,
          tool: toolName,
          args,
          reason: `Tool '${toolName}' is disabled for safety. Use MCP tools instead.`,
          ts: Date.now(),
        })
        throw new Error(`Tool '${toolName}' is disabled. Use MCP tools (ask_user, memory, device, schedule) instead.`)
      }

      const isMcp = MCP_TOOLS.has(toolLower)
      const isInteractive = INTERACTIVE_TOOLS.has(toolLower)
      const isWeb = WEB_TOOLS.has(toolLower)

      emit({
        kind: "tool.before",
        sessionID: input.sessionID,
        messageID: input.messageID,
        callID,
        tool: toolName,
        args,
        isMcpTool: isMcp,
        isInteractive,
        isWebTool: isWeb,
        ts: Date.now(),
      })

      // Web search: surface the query separately so the UI can build the search card
      if (isWeb && toolLower.includes("search")) {
        const query = args.query ?? args.q ?? args.search_query ?? ""
        emit({
          kind: "websearch.query",
          sessionID: input.sessionID,
          callID,
          query,
          numResults: args.numResults ?? args.num ?? 5,
          ts: Date.now(),
        })
      }

      // Web fetch: surface the URL separately
      if (isWeb && toolLower.includes("fetch")) {
        const url = args.url ?? args.uri ?? ""
        let domain = ""
        try { domain = new URL(url).hostname.replace(/^www\./, "") } catch { /* */ }
        emit({
          kind: "webfetch.url",
          sessionID: input.sessionID,
          callID,
          url,
          domain,
          ts: Date.now(),
        })
      }

      if (isInteractive) {
        const question: string = args.question ?? args.prompt ?? ""
        const options: string[] = args.options ?? args.choices ?? []
        emit({
          kind: "user.input.required",
          sessionID: input.sessionID,
          messageID: input.messageID,
          callID,
          tool: toolName,
          question,
          options,
          inputMode: options.length >= 2 ? "choice" : "text",
          ts: Date.now(),
        })
      }
    },

    "tool.execute.after": async (input: any, output: any) => {
      const toolName: string = input.tool ?? ""
      const toolLower = toolName.toLowerCase()
      const callID = input.callID ?? input.toolCallID
      // v1.15.6 tool.execute.after output shape: { title, output: string, metadata }
      const outputString: string = output?.output ?? output?.value ?? output?.result ?? ""
      const error = output?.error
      const metadata = output?.metadata
      const isMcp = MCP_TOOLS.has(toolLower)
      const isInteractive = INTERACTIVE_TOOLS.has(toolLower)
      const isWeb = WEB_TOOLS.has(toolLower)

      emit({
        kind: "tool.after",
        sessionID: input.sessionID,
        messageID: input.messageID,
        callID,
        tool: toolName,
        result: outputString,
        error,
        metadata,
        isMcpTool: isMcp,
        isInteractive,
        isWebTool: isWeb,
        ts: Date.now(),
      })

      // Web search: extract domains from the result string for the icon track
      if (isWeb && toolLower.includes("search")) {
        const domains = extractDomains(outputString)
        emit({
          kind: "websearch.result",
          sessionID: input.sessionID,
          callID,
          domains,
          resultLength: outputString.length,
          ts: Date.now(),
        })
      }

      // Web fetch: extract domain from URL (already in before; just confirm)
      if (isWeb && toolLower.includes("fetch")) {
        const args = input.args ?? {}
        const url = args.url ?? args.uri ?? ""
        const domains = extractDomains(outputString).concat(extractDomains(url))
        emit({
          kind: "webfetch.result",
          sessionID: input.sessionID,
          callID,
          url,
          domains: Array.from(new Set(domains)),
          resultLength: outputString.length,
          ts: Date.now(),
        })
      }

      if (isInteractive) {
        const wasDeclined =
          typeof outputString === "string" &&
          (outputString.startsWith("User declined") || outputString.startsWith("Request timed out"))
        emit({
          kind: "user.input.resolved",
          sessionID: input.sessionID,
          callID,
          response: outputString,
          declined: wasDeclined,
          ts: Date.now(),
        })
      }
    },

    // ── Native permission gate (OpenCode v1.15.6) — NOT used by us, but log it
    "permission.ask": async (input: any, _output: any) => {
      emit({
        kind: "permission.ask",
        sessionID: input.sessionID,
        tool: input.tool,
        ts: Date.now(),
      })
    },

    // ── Compaction events (informational only) ─────────────────────────────
    "experimental.session.compacting": async (input: any, _output: any) => {
      const s = getSession(input.sessionID)
      s.compactionCount = (s.compactionCount ?? 0) + 1
      emit({
        kind: "compaction.start",
        sessionID: input.sessionID,
        compactionCount: s.compactionCount,
        ts: Date.now(),
      })
    },

    "experimental.compaction.autocontinue": async (_input: any, _output: any) => {
      emit({ kind: "compaction.complete", ts: Date.now() })
    },

    // ── CLI command tracking ───────────────────────────────────────────────
    "command.execute.before": async (input: any, _output: any) => {
      emit({
        kind: "command.execute",
        sessionID: input.sessionID,
        command: input.command,
        arguments: input.arguments,
        ts: Date.now(),
      })
    },

    // ── File edits (informational) ─────────────────────────────────────────
    "file.edited": async (input: any) => {
      emit({ kind: "file.edited", sessionID: input.sessionID, path: input.path, ts: Date.now() })
    },

    // ── LSP diagnostics (informational) ────────────────────────────────────
    "lsp.client.diagnostics": async (input: any) => {
      emit({ kind: "lsp.diagnostics", sessionID: input.sessionID, path: input.path, diagnostics: input.diagnostics, ts: Date.now() })
    },

    // ── TUI hooks (informational) ─────────────────────────────────────────
    "tui.toast.show": async (input: any) => {
      emit({ kind: "tui.toast", sessionID: input.sessionID, message: input.message, level: input.level, ts: Date.now() })
    },

    "tui.command.execute": async (input: any) => {
      emit({ kind: "tui.command", sessionID: input.sessionID, command: input.command, ts: Date.now() })
    },

    // ── Clean teardown ─────────────────────────────────────────────────────
    dispose: async () => {
      clearInterval(cleanupTimer)
      clearInterval(statsTimer)
      const stats = queue.stats()
      queue.dispose()
      sessions.clear()
      subAgentByCallID.clear()
      try {
        await client.app.log({
          level: "info",
          message: `TimelineBridgePlugin v3.0.0 disposed (sent=${stats.sent}, dropped=${stats.dropped}, failed=${stats.failed})`,
        })
      } catch { /* swallow */ }
      emit({ kind: "plugin.dispose", ts: Date.now() })
    },
  }
}
