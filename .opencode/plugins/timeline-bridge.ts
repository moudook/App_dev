/**
 * Timeline Bridge Plugin v2 — Skeleton-Stream Pattern
 *
 * Architecture:
 *   DURING STREAMING: part.updated events carry phase:"streaming" — server uses them
 *     ONLY for skeleton/spinner state, NEVER for final content.
 *   AT COMPLETION: message.updated snapshot carries cleanly separated parts —
 *     server extracts reasoning+text from parts array and emits content blocks.
 *
 * Key changes from v1:
 *   - escapeThinkTags() REMOVED — streaming text is never rendered as final content
 *   - All events carry phase:"streaming" | "snapshot"
 *   - Text events carry isThinkingHint boolean for skeleton detection
 *   - Tool events carry isMcpTool boolean for MCP badge
 *   - user.input.required emitted from tool.execute.before immediately
 *   - user.input.resolved emitted from tool.execute.after
 *   - Content-based dedup REMOVED (it suppressed valid updates)
 */

const BRIDGE_URL = "http://127.0.0.1:7860/opencode/events"

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

function emit(payload: object): void {
  fetch(BRIDGE_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  }).catch(() => {})
}

export const TimelineBridgePlugin = async ({ client }: any) => {
  const sessions = new Map<string, {
    startedAt: number
    toolCallCount: number
    isThinking: boolean
    partTextLength: Map<string, number>
  }>()

  function getSession(sessionID: string) {
    if (!sessions.has(sessionID)) {
      sessions.set(sessionID, {
        startedAt: Date.now(),
        toolCallCount: 0,
        isThinking: false,
        partTextLength: new Map(),
      })
    }
    return sessions.get(sessionID)!
  }

  setInterval(() => {
    const cutoff = Date.now() - 3_600_000
    for (const [id, s] of sessions) {
      if (s.startedAt < cutoff) sessions.delete(id)
    }
  }, 300_000)

  return {
    event: async ({ event }: { event: any }) => {
      if (event.type === "session.created") {
        getSession(event.properties.sessionID)
        emit({ kind: "session.created", sessionID: event.properties.sessionID, ts: Date.now() })
      }

      if (event.type === "session.idle") {
        const s = sessions.get(event.properties.sessionID)
        emit({
          kind: "session.idle",
          sessionID: event.properties.sessionID,
          durationMs: s ? Date.now() - s.startedAt : null,
          ts: Date.now(),
        })
        sessions.delete(event.properties.sessionID)
      }

      if (event.type === "session.compacted") {
        emit({ kind: "session.compacted", sessionID: event.properties.sessionID, ts: Date.now() })
      }

      if (event.type === "session.error") {
        emit({ kind: "session.error", sessionID: event.properties.sessionID, error: event.properties.error, ts: Date.now() })
        sessions.delete(event.properties.sessionID)
      }

      if (event.type === "session.status") {
        const status = event.properties?.status
        if (status?.type === "retry" && (status?.attempt ?? 0) > 5) {
          try { await client.session.abort({ path: { id: event.properties.sessionID } }) } catch {}
          emit({ kind: "session.aborted", reason: "retry_limit_exceeded", sessionID: event.properties.sessionID, ts: Date.now() })
        }
      }

      if (event.type === "message.updated") {
        const payload: Record<string, unknown> = {
          kind: "message.updated",
          sessionID: event.properties.sessionID,
          messageID: event.properties.messageID,
          info: event.properties.info,
          ts: Date.now(),
        }
        const rawParts = (event.properties as any).parts ?? (event.properties.info as any)?.parts ?? (event.properties.message as any)?.parts
        if (rawParts) payload.parts = rawParts
        emit(payload)
      }

      if (event.type === "message.completed") {
        emit({ kind: "message.completed", sessionID: event.properties.sessionID, messageID: event.properties.messageID, ts: Date.now() })
      }
    },

    "message.part.updated": async ({ part }: { part: any }) => {
      const s = getSession(part.sessionID)

      if (part.type === "text") {
        const text: string = (part as any).content ?? (part as any).text ?? ""

        const hasOpenThink = text.includes("<think>") || text.includes("[think]")
        const hasCloseThink = text.includes("</think>") || text.includes("[/think]")
        if (hasOpenThink && !hasCloseThink) s.isThinking = true
        if (hasCloseThink) s.isThinking = false

        emit({
          kind: "part.updated",
          phase: "streaming",
          sessionID: part.sessionID,
          messageID: part.messageID,
          partID: (part as any).id,
          partType: "text",
          text: text,
          isThinkingHint: s.isThinking,
          ts: Date.now(),
        })
      }

      else if (part.type === "reasoning") {
        const reasoning: string = (part as any).reasoning ?? ""
        emit({
          kind: "part.updated",
          phase: "snapshot",
          sessionID: part.sessionID,
          messageID: part.messageID,
          partID: (part as any).id,
          partType: "reasoning",
          reasoning: reasoning,
          thinkingDurationMs:
            (part as any).time?.end != null && (part as any).time?.start != null
              ? (part as any).time.end - (part as any).time.start
              : null,
          ts: Date.now(),
        })
      }

      else if (part.type === "tool") {
        const toolPart = part as any
        if (toolPart.state?.status === "running") s.toolCallCount++

        const isMcp = MCP_TOOLS.has((toolPart.tool ?? "").toLowerCase())
        emit({
          kind: "part.updated",
          phase: "streaming",
          sessionID: part.sessionID,
          messageID: part.messageID,
          partID: toolPart.id,
          partType: "tool",
          tool: toolPart.tool,
          toolCallID: toolPart.toolCallID,
          state: toolPart.state?.status,
          input: "input" in (toolPart.state ?? {}) ? toolPart.state.input : undefined,
          output: "output" in (toolPart.state ?? {}) ? toolPart.state.output : undefined,
          error: "error" in (toolPart.state ?? {}) ? toolPart.state.error : undefined,
          isMcpTool: isMcp,
          ts: Date.now(),
        })
      }

      else if (part.type === "subtask") {
        const sp = part as any
        emit({
          kind: "part.updated", phase: "streaming",
          sessionID: part.sessionID, messageID: part.messageID, partID: sp.id,
          partType: "subtask", agent: sp.agent, description: sp.description, state: sp.state,
          ts: Date.now(),
        })
      }

      else if (part.type === "step-start") {
        emit({
          kind: "part.updated", phase: "streaming",
          sessionID: part.sessionID, messageID: part.messageID,
          partID: (part as any).id ?? `step-start-${(part as any).step}`,
          partType: "step-start", step: (part as any).step,
          ts: Date.now(),
        })
      }

      else if (part.type === "step-finish") {
        const sfp = part as any
        emit({
          kind: "part.updated", phase: "streaming",
          sessionID: part.sessionID, messageID: part.messageID,
          partID: sfp.id ?? `step-finish-${sfp.step}`,
          partType: "step-finish", step: sfp.step, cost: sfp.cost, tokens: sfp.tokens,
          ts: Date.now(),
        })
      }

      else if (part.type === "compaction") {
        emit({
          kind: "part.updated", phase: "streaming",
          sessionID: part.sessionID, messageID: part.messageID,
          partID: (part as any).id, partType: "compaction",
          ts: Date.now(),
        })
      }
    },

    "tool.execute.before": async (input: any, output: any) => {
      const toolName: string = (input as any).tool ?? ""
      const isMcp = MCP_TOOLS.has(toolName.toLowerCase())
      const isInteractive = INTERACTIVE_TOOLS.has(toolName.toLowerCase())
      const args = (output as any).args ?? {}

      emit({
        kind: "tool.before",
        sessionID: (input as any).sessionID,
        messageID: (input as any).messageID,
        callID: (input as any).callID ?? (input as any).toolCallID,
        tool: toolName,
        args: args,
        isMcpTool: isMcp,
        isInteractive: isInteractive,
        ts: Date.now(),
      })

      if (isInteractive) {
        const question: string = args.question ?? args.prompt ?? ""
        const options: string[] = args.options ?? args.choices ?? []

        emit({
          kind: "user.input.required",
          sessionID: (input as any).sessionID,
          messageID: (input as any).messageID,
          callID: (input as any).callID ?? (input as any).toolCallID,
          tool: toolName,
          question: question,
          options: options,
          inputMode: options.length >= 2 ? "choice" : "text",
          ts: Date.now(),
        })
      }
    },

    "tool.execute.after": async (input: any, output: any) => {
      const toolName: string = (input as any).tool ?? ""
      const isMcp = MCP_TOOLS.has(toolName.toLowerCase())
      const isInteractive = INTERACTIVE_TOOLS.has(toolName.toLowerCase())
      const result = (output as any).value ?? (output as any).output
      const error = (output as any).error

      emit({
        kind: "tool.after",
        sessionID: (input as any).sessionID,
        messageID: (input as any).messageID,
        callID: (input as any).callID ?? (input as any).toolCallID,
        tool: toolName,
        result: result,
        error: error,
        isMcpTool: isMcp,
        isInteractive: isInteractive,
        userResponse: isInteractive ? result : undefined,
        ts: Date.now(),
      })

      if (isInteractive) {
        const wasDeclined = typeof result === "string" &&
          (result.startsWith("User declined") || result.startsWith("Request timed out"))
        emit({
          kind: "user.input.resolved",
          sessionID: (input as any).sessionID,
          callID: (input as any).callID ?? (input as any).toolCallID,
          response: result,
          declined: wasDeclined,
          ts: Date.now(),
        })
      }
    },

    "permission.asked": async ({ permission }: { permission: any }) => {
      emit({
        kind: "permission.asked",
        sessionID: (permission as any).sessionID,
        tool: (permission as any).tool,
        args: (permission as any).args,
        ts: Date.now(),
      })
    },

    "permission.replied": async ({ permission }: { permission: any }) => {
      emit({
        kind: "permission.replied",
        sessionID: (permission as any).sessionID,
        tool: (permission as any).tool,
        granted: (permission as any).granted,
        ts: Date.now(),
      })
    },

    dispose: () => {
      sessions.clear()
    },
  }
}
