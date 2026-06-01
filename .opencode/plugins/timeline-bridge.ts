/**
 * Timeline Bridge Plugin — Full Rewrite
 *
 * Captures every in-flight event from the OpenCode daemon's internal bus
 * and forwards them to both:
 *   1. Ktor server at http://127.0.0.1:7860/opencode/events
 *   2. Browser proxy at http://127.0.0.1:8888/api/stream
 *
 * Architecture (from DR1-DR5):
 *   OpenCode Bus -> Plugin Hooks -> HTTP POST -> Ktor + Browser
 *
 * Hook reference (DR5, DR3):
 *   - event: catch-all for session lifecycle
 *   - message.part.updated: every Part state change (text, reasoning, tool, subtask, step, compaction, file)
 *   - tool.execute.before/after: tool args and results
 *   - permission.asked/replied: permission gates
 *   - experimental.session.compacting: before compaction LLM call
 */

// ── Configuration ──────────────────────────────────────────────────────────────

const BRIDGE_URL = "http://127.0.0.1:7860/opencode/events"
const STREAM_URL = "http://127.0.0.1:8888/api/stream"

// ── Helpers ────────────────────────────────────────────────────────────────────

function log(msg: string): void {
  console.log(`[timeline-bridge] ${msg}`)
}

/**
 * Fire-and-forget POST. Never blocks the event loop.
 * Failures silently swallowed — Ktor/proxy may not be ready at startup.
 */
async function emit(payload: unknown): Promise<void> {
  try {
    await fetch(BRIDGE_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    })
  } catch {}
}

async function streamEmit(payload: unknown): Promise<void> {
  try {
    await fetch(STREAM_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    })
  } catch {}
}

/**
 * Replace think tags with safe markers to prevent CLI double-parsing.
 * Handles case variations: <Think>, <THINK>, <think ...>, etc.
 */
function escapeThinkTags(text: string): string {
  if (!text) return text
  return text.replace(/<think\b[^>]*>/gi, "[think]").replace(/<\/think\b[^>]*>/gi, "[/think]")
}

/**
 * Truncate strings for safe transport. Handles non-string inputs gracefully.
 */
function truncateStr(s: unknown, maxLen = 10240): string {
  if (typeof s !== "string" || !s) return String(s ?? "")
  if (s.length <= maxLen) return s
  return s.substring(0, maxLen) + "... [truncated]"
}

// ── Plugin Entry Point ─────────────────────────────────────────────────────────

export const TimelineBridgePlugin = async (ctx: any) => {
  log(`PLUGIN LOADED — Timeline Bridge`)
  log(`Project: ${ctx.project?.name ?? "unknown"}`)
  log(`Worktree: ${ctx.worktree ?? "unknown"}`)
  log(`Directory: ${ctx.directory ?? "unknown"}`)

  // ── Per-session state (DR5: key by sessionID to prevent cross-session collision)
  const sessions = new Map<string, {
    startedAt: number
    toolCallCount: number
    eventCount: number
    seenToolCalls: Set<string>
  }>()

  // TTL cleanup — sweep stale entries older than 1 hour every 5 minutes
  const TTL_MS = 60 * 60 * 1000
  const ttlInterval = setInterval(() => {
    const now = Date.now()
    for (const [sid, s] of sessions) {
      if (now - s.startedAt > TTL_MS) {
        sessions.delete(sid)
      }
    }
  }, 5 * 60 * 1000)

  // Content-based dedup: skip events with identical content for the same partID
  const lastContentHash = new Map<string, string>()

  // Circuit breaker: detect infinite retry loops (DR4: session.processor retries indefinitely)
  const retryCount = new Map<string, number>()

  // ── HOOK: event — catch-all for session lifecycle ────────────────────────

  const onEvent = async ({ event }: { event: any }) => {
    const kind: string = event.type ?? "unknown"
    const props: Record<string, unknown> = event.properties ?? {}
    const sid = (props.sessionID as string) ?? "no-session"
    const ts = Date.now()

    // Session lifecycle
    if (kind === "session.created") {
      sessions.set(sid, { startedAt: ts, toolCallCount: 0, eventCount: 0, seenToolCalls: new Set() })
      await Promise.all([
        emit({ kind: "session.created", sessionID: sid, ts }),
        streamEmit({ kind: "session.created", sessionID: sid, ts }),
      ])
    }

    if (kind === "session.idle") {
      const s = sessions.get(sid)
      const duration = s ? ts - s.startedAt : null
      await Promise.all([
        emit({ kind: "session.idle", sessionID: sid, durationMs: duration, totalToolCalls: s?.toolCallCount ?? null, ts }),
        streamEmit({ kind: "session.idle", sessionID: sid, durationMs: duration, totalToolCalls: s?.toolCallCount ?? null, ts }),
      ])
      sessions.delete(sid)
      retryCount.delete(sid)
    }

    if (kind === "session.compacted") {
      await Promise.all([
        emit({ kind: "session.compacted", sessionID: sid, ts }),
        streamEmit({ kind: "session.compacted", sessionID: sid, ts }),
      ])
    }

    if (kind === "session.error") {
      await Promise.all([
        emit({ kind: "session.error", sessionID: sid, error: props.error, ts }),
        streamEmit({ kind: "session.error", sessionID: sid, error: props.error, ts }),
      ])
      sessions.delete(sid)
      retryCount.delete(sid)
    }

    if (kind === "session.status") {
      const status = props.status as Record<string, unknown> | undefined
      const statusType = status?.type as string

      // Circuit breaker (DR4 §5.3): abort if retry count exceeds 5
      if (statusType === "retry") {
        const count = (retryCount.get(sid) ?? 0) + 1
        retryCount.set(sid, count)
        if (count > 5) {
          log(`CIRCUIT BREAKER: aborting session ${sid.substring(0, 8)} after ${count} retries`)
          try {
            await (ctx.client as any).session?.abort?.({ path: { id: sid } })
          } catch {}
          retryCount.delete(sid)
        }
      } else {
        retryCount.delete(sid)
      }

      await Promise.all([
        emit({ kind: "session.status", sessionID: sid, status: props.status, ts }),
        streamEmit({ kind: "session.status", sessionID: sid, status: props.status, ts }),
      ])
    }

    // Forward streaming deltas (DR1: text-delta, reasoning-delta)
    if (kind === "message.part.delta") {
      const delta = escapeThinkTags(props.delta as string ?? "")
      if (delta) {
        await streamEmit({ kind, sessionID: sid, messageID: props.messageID, partID: props.partID, field: props.field, delta, ts })
      }
    }

    // Forward agent/model switches
    if (kind === "session.next.agent.switched") {
      await Promise.all([
        emit({ kind, sessionID: sid, agent: props.agent, ts }),
        streamEmit({ kind, sessionID: sid, agent: props.agent, ts }),
      ])
    }
    if (kind === "session.next.model.switched") {
      await Promise.all([
        emit({ kind, sessionID: sid, model: props.model, ts }),
        streamEmit({ kind, sessionID: sid, model: props.model, ts }),
      ])
    }

    // Forward message.updated (only once — not via catch-all)
    if (kind === "message.updated") {
      await Promise.all([
        emit({ kind, sessionID: sid, info: props.info, ts }),
        streamEmit({ kind, sessionID: sid, info: props.info, ts }),
      ])
    }

    // Count
    const s = sessions.get(sid)
    if (s) s.eventCount++
  }

  // ── HOOK: message.part.updated — the core event (DR5 §4.2) ──────────────
  // Fires on EVERY Part state change. Covers all part types:
  // text, reasoning, tool, subtask, step-start, step-finish, compaction, file

  const onPartUpdated = async ({ part }: { part: any }) => {
    const sid = (part.sessionID as string) ?? "no-session"
    const mid = (part.messageID as string) ?? "no-message"
    const pid = (part.id as string) ?? "no-id"
    const ptype = (part.type as string) ?? "unknown"
    const ts = Date.now()

    // Content-based dedup: skip if content is identical to last emission for this part
    const contentStr = part.content ?? part.reasoning ?? part.text ?? part.description ?? JSON.stringify(part.state) ?? ""
    const prev = lastContentHash.get(pid)
    if (prev === contentStr) return
    lastContentHash.set(pid, contentStr)
    // Prune stale hash entries periodically
    if (lastContentHash.size > 500) {
      const keys = [...lastContentHash.keys()]
      for (const k of keys.slice(0, 100)) lastContentHash.delete(k)
    }

    switch (ptype) {

      case "text": {
        const escaped = escapeThinkTags(part.content ?? "")
        await Promise.all([
          emit({
            kind: "part.updated", partType: "text",
            sessionID: sid, messageID: mid, partID: pid,
            text: truncateStr(part.content, 10240),
            synthetic: part.synthetic ?? false,
            ignored: part.ignored ?? false,
            ts,
          }),
          streamEmit({
            kind: "message.part.updated", sessionID: sid, messageID: mid, part: {
              type: "text", text: escaped, id: pid, messageID: mid, sessionID: sid,
            }, ts,
          }),
        ])
        break
      }

      case "reasoning": {
        const escaped = escapeThinkTags(part.reasoning ?? "")
        await Promise.all([
          emit({
            kind: "part.updated", partType: "reasoning",
            sessionID: sid, messageID: mid, partID: pid,
            reasoning: truncateStr(escaped, 10240),
            thinkingDurationMs: (part.time?.end != null && part.time?.start != null)
              ? part.time.end - part.time.start : null,
            ts,
          }),
          streamEmit({
            kind: "message.part.updated", sessionID: sid, messageID: mid, part: {
              type: "reasoning", reasoning: escaped, text: part.text, id: pid, messageID: mid, sessionID: sid,
            }, ts,
          }),
        ])
        break
      }

      case "tool": {
        const state = (part.state?.status as string) ?? "unknown"
        const tool = (part.tool as string) ?? "unknown"
        const toolCallID = (part.toolCallID as string) ?? "unknown"

        // Track unique tool calls per session
        if (state === "running") {
          const s = sessions.get(sid)
          if (s && !s.seenToolCalls.has(toolCallID)) {
            s.seenToolCalls.add(toolCallID)
            s.toolCallCount++
          }
        }

        // Build state-specific payload (DR5 ToolPart state machine)
        const base = {
          kind: "part.updated", partType: "tool",
          sessionID: sid, messageID: mid, partID: pid,
          tool, toolCallID, state,
        }
        const withState: Record<string, unknown> = { ...base }
        if (state === "running" || state === "pending") {
          withState.input = part.state?.input
          withState.raw = part.state?.raw
        } else if (state === "complete") {
          withState.input = part.state?.input
          withState.output = part.state?.output
          withState.raw = part.state?.raw
        } else if (state === "error") {
          withState.input = part.state?.input
          withState.error = part.state?.error
        }

        await Promise.all([
          emit({ ...withState, ts }),
          streamEmit({
            kind: "message.part.updated", sessionID: sid, messageID: mid, part: {
              type: "tool", tool, toolCallID, state,
              input: part.state?.input,
              output: part.state?.output,
              error: part.state?.error,
              raw: part.state?.raw,
            }, ts,
          }),
        ])
        break
      }

      case "subtask": {
        await Promise.all([
          emit({
            kind: "part.updated", partType: "subtask",
            sessionID: sid, messageID: mid, partID: pid,
            agent: part.agent, description: part.description, state: part.state,
            ts,
          }),
          streamEmit({
            kind: "message.part.updated", sessionID: sid, messageID: mid, part: {
              type: "subtask", agent: part.agent, description: part.description, state: part.state, id: pid, sessionID: sid,
            }, ts,
          }),
        ])
        break
      }

      case "step-start": {
        await Promise.all([
          emit({ kind: "part.updated", partType: "step-start", sessionID: sid, messageID: mid, partID: pid, step: part.step, ts }),
          streamEmit({ kind: "message.part.updated", sessionID: sid, messageID: mid, part: { type: "step-start", step: part.step }, ts }),
        ])
        break
      }

      case "step-finish": {
        const costVal = typeof part.cost === "number" ? part.cost : Number(part.cost) || 0
        const cost = costVal.toFixed(6)
        await Promise.all([
          emit({
            kind: "part.updated", partType: "step-finish",
            sessionID: sid, messageID: mid, partID: pid,
            step: part.step, cost: costVal, tokens: part.tokens,
            ts,
          }),
          streamEmit({
            kind: "message.part.updated", sessionID: sid, messageID: mid, part: {
              type: "step-finish", step: part.step, cost: costVal, tokens: part.tokens,
            }, ts,
          }),
        ])
        break
      }

      case "compaction": {
        await Promise.all([
          emit({ kind: "part.updated", partType: "compaction", sessionID: sid, messageID: mid, partID: pid, ts }),
          streamEmit({ kind: "message.part.updated", sessionID: sid, messageID: mid, part: { type: "compaction" }, ts }),
        ])
        break
      }

      case "file": {
        await Promise.all([
          emit({
            kind: "part.updated", partType: "file",
            sessionID: sid, messageID: mid, partID: pid,
            filename: part.filename, mediaType: part.mediaType, source: part.source,
            ts,
          }),
          streamEmit({
            kind: "message.part.updated", sessionID: sid, messageID: mid, part: {
              type: "file", filename: part.filename, mediaType: part.mediaType,
            }, ts,
          }),
        ])
        break
      }

      default: {
        // Forward unknown part types with raw JSON
        await Promise.all([
          emit({
            kind: "part.updated", partType: ptype,
            sessionID: sid, messageID: mid, partID: pid,
            raw: truncateStr(JSON.stringify(part), 2000),
            ts,
          }),
          streamEmit({
            kind: "message.part.updated", sessionID: sid, messageID: mid, part: {
              type: ptype, raw: truncateStr(JSON.stringify(part), 10240),
            }, ts,
          }),
        ])
        break
      }
    }
  }

  // ── HOOK: tool.execute.before — full parsed args (DR5 §5.1) ─────────────
  // Fires when state transitions to running. Input is the parsed argument object.

  // Tools in this set block waiting for user response.
  // The plugin emits user.input.required so the app can show an interactive prompt.
  // The MCP tool MUST be implemented to use ctx.client.session.permission.ask() to
  // request input — that way the existing permission infrastructure unblocks it.
  const INTERACTIVE_TOOLS = new Set([
    "ask", "ask_user", "askuser", "input", "confirm", "question", "clarify",
  ])

  const onToolBefore = async (input: any, output: any) => {
    log(`TOOL.BEFORE session=${(input.sessionID ?? "").substring(0, 8)} tool=${input.tool}`)
    const args = output.args ?? {}

    await Promise.all([
      emit({
        kind: "tool.before",
        sessionID: input.sessionID, messageID: input.messageID,
        tool: input.tool, callID: input.callID,
        args,
        ts: Date.now(),
      }),
      streamEmit({
        kind: "tool.execute.before",
        sessionID: input.sessionID, messageID: input.messageID,
        tool: input.tool, callID: input.callID,
        args,
        ts: Date.now(),
      }),
    ])

    // ── Interactive tool: ask the user for input ─────────────────────────
    // The CLI session is now BLOCKED waiting for this tool to return.
    // We emit a special event so the app can show a question card.
    // Delivery back to the tool: Ktor forwards the user's response to the
    // OpenCode permission system (if the tool called permission.ask) OR
    // to a file at /tmp/opencode-asks/<sessionID>/<callID>.response.txt
    // which the MCP server polls.
    if (INTERACTIVE_TOOLS.has((input.tool ?? "").toLowerCase())) {
      const question =
        (args.question as string) ??
        (args.prompt as string) ??
        (args.text as string) ??
        (args.message as string) ??
        JSON.stringify(args)
      const options = Array.isArray(args.options) ? args.options : null

      log(`USER.INPUT.REQUIRED tool=${input.tool} question=${question.substring(0, 80)}`)

      await Promise.all([
        emit({
          kind: "user.input.required",
          sessionID: input.sessionID,
          messageID: input.messageID,
          tool: input.tool, callID: input.callID,
          question, options,
          ts: Date.now(),
        }),
        streamEmit({
          kind: "user.input.required",
          sessionID: input.sessionID,
          messageID: input.messageID,
          tool: input.tool, callID: input.callID,
          question, options,
          ts: Date.now(),
        }),
      ])
    }
  }

  // ── HOOK: tool.execute.after — full result/error (DR5 §5.1) ─────────────
  // Fires when state transitions to complete or error.

  const onToolAfter = async (input: any, output: any) => {
    // DR5: output has { title, output, metadata } — also check output.value as fallback
    const result = output.output ?? output.value ?? output
    const errVal = typeof output.error === "string" ? output.error : JSON.stringify(output.error ?? null)
    log(`TOOL.AFTER session=${(input.sessionID ?? "").substring(0, 8)} tool=${input.tool}`)
    await Promise.all([
      emit({
        kind: "tool.after",
        sessionID: input.sessionID, messageID: input.messageID,
        tool: input.tool, callID: input.callID,
        result, error: output.error,
        ts: Date.now(),
      }),
      streamEmit({
        kind: "tool.execute.after",
        sessionID: input.sessionID, messageID: input.messageID,
        tool: input.tool, callID: input.callID,
        result, error: output.error,
        ts: Date.now(),
      }),
    ])
  }

  // ── HOOK: permission.asked / permission.replied (DR5 §5.1) ──────────────
  // Fires when agent requests permission to run a tool

  const onPermissionAsked = async ({ permission }: { permission: any }) => {
    log(`PERMISSION.ASKED tool=${permission.tool}`)
    await Promise.all([
      emit({
        kind: "permission.asked",
        sessionID: permission.sessionID,
        tool: permission.tool, args: permission.args,
        ts: Date.now(),
      }),
      streamEmit({
        kind: "permission.asked",
        sessionID: permission.sessionID,
        tool: permission.tool, args: permission.args,
        ts: Date.now(),
      }),
    ])
  }

  const onPermissionReplied = async ({ permission }: { permission: any }) => {
    log(`PERMISSION.REPLIED tool=${permission.tool} granted=${permission.granted}`)
    await Promise.all([
      emit({
        kind: "permission.replied",
        sessionID: permission.sessionID,
        tool: permission.tool, granted: permission.granted,
        ts: Date.now(),
      }),
      streamEmit({
        kind: "permission.replied",
        sessionID: permission.sessionID,
        tool: permission.tool, granted: permission.granted,
        ts: Date.now(),
      }),
    ])
  }

  // ── HOOK: experimental.session.compacting (DR5 §7, DR4 §5.1) ────────────
  // Fires BEFORE the LLM generates the compaction summary.
  // Can inject context via output.context.push().

  const onCompacting = async (input: any, output: any) => {
    log(`COMPACTION.START session=${input.sessionID}`)
    // Inject telemetry preservation marker into compaction context (DR4 §5.1)
    if (output?.context) {
      output.context.push(`## Telemetry Context Preserved\nTimeline bridge active for session ${input.sessionID}.`)
    }
    await Promise.all([
      emit({
        kind: "compaction.start",
        sessionID: input.sessionID,
        ts: Date.now(),
      }),
      streamEmit({
        kind: "compaction.start",
        sessionID: input.sessionID,
        ts: Date.now(),
      }),
    ])
  }

  // ── Done ────────────────────────────────────────────────────────────────

  log("PLUGIN HOOKS REGISTERED")

  return {
    event: onEvent,
    "message.part.updated": onPartUpdated,
    "tool.execute.before": onToolBefore,
    "tool.execute.after": onToolAfter,
    "permission.asked": onPermissionAsked,
    "permission.replied": onPermissionReplied,
    "experimental.session.compacting": onCompacting,
    dispose: () => {
      clearInterval(ttlInterval)
      sessions.clear()
      lastContentHash.clear()
      retryCount.clear()
    },
  }
}
