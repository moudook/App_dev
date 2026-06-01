/**
 * Timeline Bridge Plugin — Phase 1: Verification
 *
 * Captures every in-flight event from the OpenCode daemon's internal bus
 * and forwards them to the Ktor companion server for timeline reconstruction.
 *
 * Architecture:
 *   OpenCode Bus -> Plugin Hooks -> HTTP POST -> Ktor /opencode/events
 *
 * Runtime logs use a prefix constant for easy identification in container logs.
 * Source code contains no emoji literals — only the runtime EMOJI constant.
 */

// ── Configuration ──────────────────────────────────────────────────────────────

const BRIDGE_URL = "http://127.0.0.1:7860/opencode/events"
const EMOJI = "\u{1F426}\u200D\u{1F525}"

// ── Helpers ────────────────────────────────────────────────────────────────────

function log(msg: string): void {
  console.log(`${EMOJI} ${msg}`)
}

/**
 * Fire-and-forget POST to the Ktor server.
 * Never blocks the event loop. Failures are silently swallowed
 * because Ktor may not be ready when the plugin first loads.
 */
async function emit(payload: unknown): Promise<void> {
  try {
    await fetch(BRIDGE_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    })
  } catch {
    // Ktor not ready yet — expected during startup race
  }
}

// ── Plugin Entry Point ─────────────────────────────────────────────────────────

export const TimelineBridgePlugin = async (ctx: any) => {
  log(`${EMOJI} PLUGIN LOADED — Timeline Bridge Phase 1 ${EMOJI}`)
  log(`Bridge target: ${BRIDGE_URL}`)
  log(`Project: ${ctx.project?.name ?? "unknown"}`)
  log(`Worktree: ${ctx.worktree ?? "unknown"}`)
  log(`Directory: ${ctx.directory ?? "unknown"}`)

  // Per-session state — keyed by sessionID (ULID)
  const sessions = new Map<string, {
    startedAt: number
    toolCallCount: number
    eventCount: number
  }>()

  // ── HOOK: event — generic catch-all for session lifecycle ────────────────

  const onEvent = async ({ event }: { event: any }) => {
    const kind: string = event.type ?? "unknown"
    const props: Record<string, unknown> = event.properties ?? {}
    const sid = (props.sessionID as string) ?? "no-session"
    const ts = Date.now()

    // Log every event type for hook discovery
    const propsStr = JSON.stringify(props).substring(0, 200)
    log(`[EVENT] kind=${kind} session=${sid.substring(0, 8)} props=${propsStr}`)

    // Session lifecycle
    if (kind === "session.created") {
      sessions.set(sid, { startedAt: ts, toolCallCount: 0, eventCount: 0 })
      log(`${EMOJI} SESSION CREATED: ${sid} ${EMOJI}`)
      await emit({ kind: "session.created", sessionID: sid, ts })
    }

    if (kind === "session.idle") {
      const s = sessions.get(sid)
      const duration = s ? ts - s.startedAt : null
      log(`${EMOJI} SESSION IDLE: ${sid} duration=${duration}ms tools=${s?.toolCallCount ?? "?"} events=${s?.eventCount ?? "?"} ${EMOJI}`)
      await emit({ kind: "session.idle", sessionID: sid, durationMs: duration, totalToolCalls: s?.toolCallCount ?? null, ts })
      sessions.delete(sid)
    }

    if (kind === "session.compacted") {
      log(`${EMOJI} SESSION COMPACTED: ${sid} ${EMOJI}`)
      await emit({ kind: "session.compacted", sessionID: sid, ts })
    }

    if (kind === "session.error") {
      const errStr = JSON.stringify(props.error)?.substring(0, 200)
      log(`${EMOJI} SESSION ERROR: ${sid} error=${errStr} ${EMOJI}`)
      await emit({ kind: "session.error", sessionID: sid, error: props.error, ts })
    }

    if (kind === "session.status") {
      const statusStr = JSON.stringify(props.status)?.substring(0, 200)
      log(`[STATUS] session=${sid.substring(0, 8)} status=${statusStr}`)
      await emit({ kind: "session.status", sessionID: sid, status: props.status, ts })
    }

    // Forward unrecognized event types
    if (!kind.startsWith("session.") && kind !== "message.part.updated") {
      await emit({ kind, sessionID: sid, properties: props, ts })
    }

    // Count
    const s = sessions.get(sid)
    if (s) s.eventCount++
  }

  // ── HOOK: message.part.updated — word-by-word text/reasoning/tool tracing ─

  const onPartUpdated = async ({ part }: { part: any }) => {
    const sid = (part.sessionID as string) ?? "no-session"
    const mid = (part.messageID as string) ?? "no-message"
    const pid = (part.id as string) ?? "no-id"
    const ptype = (part.type as string) ?? "unknown"
    const ts = Date.now()

    switch (ptype) {

      case "text": {
        const content = (part.content ?? "").substring(0, 300)
        log(`[TEXT] session=${sid.substring(0, 8)} msg=${mid.substring(0, 8)} part=${pid.substring(0, 8)} content="${content}"`)
        await emit({
          kind: "part.updated", partType: "text",
          sessionID: sid, messageID: mid, partID: pid,
          text: part.content,
          synthetic: part.synthetic ?? false,
          ignored: part.ignored ?? false,
          ts,
        })
        break
      }

      case "reasoning": {
        const thinking = (part.reasoning ?? "").substring(0, 300)
        log(`${EMOJI} [REASONING] session=${sid.substring(0, 8)} part=${pid.substring(0, 8)} thinking="${thinking}"`)
        await emit({
          kind: "part.updated", partType: "reasoning",
          sessionID: sid, messageID: mid, partID: pid,
          reasoning: part.reasoning,
          thinkingDurationMs: (part.time?.end != null && part.time?.start != null)
            ? part.time.end - part.time.start : null,
          ts,
        })
        break
      }

      case "tool": {
        const state = (part.state?.status as string) ?? "unknown"
        const tool = (part.tool as string) ?? "unknown"
        const toolCallID = (part.toolCallID as string) ?? "unknown"

        if (state === "running") {
          const s = sessions.get(sid)
          if (s) s.toolCallCount++
          const inputStr = JSON.stringify(part.state?.input)?.substring(0, 300)
          log(`${EMOJI} [TOOL START] session=${sid.substring(0, 8)} tool=${tool} callID=${toolCallID.substring(0, 8)} input=${inputStr} ${EMOJI}`)
        } else if (state === "complete") {
          const outStr = JSON.stringify(part.state?.output)?.substring(0, 300)
          log(`${EMOJI} [TOOL DONE] session=${sid.substring(0, 8)} tool=${tool} callID=${toolCallID.substring(0, 8)} output=${outStr} ${EMOJI}`)
        } else if (state === "error") {
          const errStr = JSON.stringify(part.state?.error)?.substring(0, 300)
          log(`${EMOJI} [TOOL ERROR] session=${sid.substring(0, 8)} tool=${tool} callID=${toolCallID.substring(0, 8)} error=${errStr} ${EMOJI}`)
        } else {
          log(`[TOOL ${state.toUpperCase()}] session=${sid.substring(0, 8)} tool=${tool} callID=${toolCallID.substring(0, 8)}`)
        }

        await emit({
          kind: "part.updated", partType: "tool",
          sessionID: sid, messageID: mid, partID: pid,
          tool, toolCallID, state,
          input: "input" in (part.state ?? {}) ? part.state.input : undefined,
          output: "output" in (part.state ?? {}) ? part.state.output : undefined,
          error: "error" in (part.state ?? {}) ? part.state.error : undefined,
          raw: "raw" in (part.state ?? {}) ? part.state.raw : undefined,
          ts,
        })
        break
      }

      case "subtask": {
        const desc = (part.description ?? "").substring(0, 100)
        log(`${EMOJI} [SUBTASK] session=${sid.substring(0, 8)} agent=${part.agent} state=${part.state} desc="${desc}"`)
        await emit({
          kind: "part.updated", partType: "subtask",
          sessionID: sid, messageID: mid, partID: pid,
          agent: part.agent, description: part.description, state: part.state,
          ts,
        })
        break
      }

      case "step-start": {
        log(`[STEP START] session=${sid.substring(0, 8)} step=${part.step}`)
        await emit({ kind: "part.updated", partType: "step-start", sessionID: sid, messageID: mid, partID: pid, step: part.step, ts })
        break
      }

      case "step-finish": {
        const cost = (part.cost ?? 0).toFixed(6)
        log(`[STEP FINISH] session=${sid.substring(0, 8)} step=${part.step} cost=$${cost} tokens=${JSON.stringify(part.tokens)}`)
        await emit({
          kind: "part.updated", partType: "step-finish",
          sessionID: sid, messageID: mid, partID: pid,
          step: part.step, cost: part.cost, tokens: part.tokens,
          ts,
        })
        break
      }

      case "compaction": {
        log(`${EMOJI} [COMPACTION MARKER] session=${sid.substring(0, 8)} ${EMOJI}`)
        await emit({ kind: "part.updated", partType: "compaction", sessionID: sid, messageID: mid, partID: pid, ts })
        break
      }

      case "file": {
        log(`[FILE] session=${sid.substring(0, 8)} file=${part.filename} media=${part.mediaType}`)
        await emit({
          kind: "part.updated", partType: "file",
          sessionID: sid, messageID: mid, partID: pid,
          filename: part.filename, mediaType: part.mediaType, source: part.source,
          ts,
        })
        break
      }

      default: {
        const raw = JSON.stringify(part).substring(0, 200)
        log(`[PART UNKNOWN] session=${sid.substring(0, 8)} type=${ptype} raw=${raw}`)
        await emit({
          kind: "part.updated", partType: ptype,
          sessionID: sid, messageID: mid, partID: pid,
          raw: JSON.stringify(part).substring(0, 2000),
          ts,
        })
        break
      }
    }
  }

  // ── HOOK: tool.execute.before — full parsed args ─────────────────────────

  const onToolBefore = async (input: any, output: any) => {
    const argsStr = JSON.stringify(output.args)?.substring(0, 300)
    log(`${EMOJI} [TOOL.BEFORE] session=${(input.sessionID ?? "").substring(0, 8)} tool=${input.tool} args=${argsStr}`)
    await emit({
      kind: "tool.before",
      sessionID: input.sessionID, messageID: input.messageID,
      tool: input.tool, callID: input.callID,
      args: output.args, ts: Date.now(),
    })
  }

  // ── HOOK: tool.execute.after — full result/error ────────────────────────

  const onToolAfter = async (input: any, output: any) => {
    const resultStr = JSON.stringify(output.value)?.substring(0, 300) ?? "null"
    const errorStr = JSON.stringify(output.error)?.substring(0, 300) ?? "null"
    log(`${EMOJI} [TOOL.AFTER] session=${(input.sessionID ?? "").substring(0, 8)} tool=${input.tool} result=${resultStr} error=${errorStr}`)
    await emit({
      kind: "tool.after",
      sessionID: input.sessionID, messageID: input.messageID,
      tool: input.tool, callID: input.callID,
      result: output.value, error: output.error,
      ts: Date.now(),
    })
  }

  // ── HOOK: permission.asked / permission.replied ──────────────────────────

  const onPermissionAsked = async ({ permission }: { permission: any }) => {
    const argsStr = JSON.stringify(permission.args)?.substring(0, 200)
    log(`${EMOJI} [PERMISSION.ASKED] session=${(permission.sessionID ?? "").substring(0, 8)} tool=${permission.tool} args=${argsStr}`)
    await emit({
      kind: "permission.asked",
      sessionID: permission.sessionID,
      tool: permission.tool, args: permission.args,
      ts: Date.now(),
    })
  }

  const onPermissionReplied = async ({ permission }: { permission: any }) => {
    log(`${EMOJI} [PERMISSION.REPLIED] session=${(permission.sessionID ?? "").substring(0, 8)} tool=${permission.tool} granted=${permission.granted}`)
    await emit({
      kind: "permission.replied",
      sessionID: permission.sessionID,
      tool: permission.tool, granted: permission.granted,
      ts: Date.now(),
    })
  }

  // ── HOOK: experimental.session.compacting — fires BEFORE compaction LLM ──

  const onCompacting = async (input: any, output: any) => {
    log(`${EMOJI} [COMPACTION.START] session=${input.sessionID} usage=${input.usage ?? "?"}% ${EMOJI}`)
    await emit({
      kind: "compaction.start",
      sessionID: input.sessionID,
      contextUsagePercent: input.usage,
      ts: Date.now(),
    })
  }

  // ── Done ────────────────────────────────────────────────────────────────

  log(`${EMOJI} PLUGIN HOOKS REGISTERED — waiting for events... ${EMOJI}`)

  return {
    event: onEvent,
    "message.part.updated": onPartUpdated,
    "tool.execute.before": onToolBefore,
    "tool.execute.after": onToolAfter,
    "permission.asked": onPermissionAsked,
    "permission.replied": onPermissionReplied,
    "experimental.session.compacting": onCompacting,
  }
}
