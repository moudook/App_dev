const SmartyPlugin = async function(ctx) {
  const bridgeUrl = 'http://127.0.0.1:7860/opencode/events';

  const forward = async (kind, ...args) => {
    try {
      const payload = { type: kind };
      if (args.length === 1 && typeof args[0] === 'object' && args[0] !== null) {
        Object.assign(payload, args[0]);
      } else if (args.length > 0) {
        payload.args = args;
      }
      
      // Ensure sessionID is present at the top level
      if (!payload.sessionID && !payload.sessionId) {
        for (const arg of args) {
          if (arg && typeof arg === 'object') {
            const sid = arg.sessionID || arg.sessionId || (arg.part && (arg.part.sessionID || arg.part.sessionId));
            if (sid) {
              payload.sessionID = sid;
              break;
            }
          }
        }
      }

      await fetch(bridgeUrl, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: safeStringify(payload)
      });
    } catch (e) {
      console.error(`[SmartyBridge] ${kind} forward error:`, e);
    }
  };

  function safeStringify(obj) {
    const cache = new Set();
    return JSON.stringify(obj, (key, value) => {
      if (typeof value === 'object' && value !== null) {
        if (cache.has(value)) return undefined;
        cache.add(value);
      }
      return value;
    });
  }

  // Support legacy event emitter pattern if ctx has 'on' method
  if (ctx && typeof ctx.on === 'function') {
    try {
      ctx.on('message.updated', async (event) => forward('message.updated', event));
      ctx.on('message.part.updated', async (event) => forward('message.part.updated', event));
      ctx.on('message.part.delta', async (event) => forward('message.part.delta', event));

      const legacyEvents = [
        'tool.execute.before', 'tool.execute.after', 
        'session.created', 'session.aborted',
        'subagent.created', 'subagent.idle',
        'websearch.query', 'websearch.result',
        'webfetch.url', 'webfetch.result',
        'compaction.start', 'compaction.complete',
        'file.edited', 'command.execute'
      ];
      for (const kind of legacyEvents) {
        ctx.on(kind, async (...args) => forward(kind, ...args));
      }
    } catch (err) {
      console.error('[SmartyBridge] legacy EventEmitter attachment failed:', err);
    }
  }

  // Return the hooks object for the new Plugin API
  return {
    "message.part.updated": async (event) => forward("message.part.updated", event),
    "message.part.delta": async (event) => forward("message.part.delta", event),
    "message.updated": async (event) => forward("message.updated", event),
    "session.created": async (event) => forward("session.created", event),
    "session.aborted": async (event) => forward("session.aborted", event),
    "subagent.created": async (event) => forward("subagent.created", event),
    "subagent.idle": async (event) => forward("subagent.idle", event),
    "websearch.query": async (event) => forward("websearch.query", event),
    "websearch.result": async (event) => forward("websearch.result", event),
    "webfetch.url": async (event) => forward("webfetch.url", event),
    "webfetch.result": async (event) => forward("webfetch.result", event),
    "compaction.start": async (event) => forward("compaction.start", event),
    "compaction.complete": async (event) => forward("compaction.complete", event),
    "file.edited": async (event) => forward("file.edited", event),
    "command.execute": async (event) => forward("command.execute", event),
    "tool.execute.before": async (...args) => forward("tool.execute.before", ...args),
    "tool.execute.after": async (...args) => forward("tool.execute.after", ...args),
    "dispose": async () => {
      console.log('[SmartyBridge] disposing plugin');
      await forward("plugin.dispose");
    }
  };
};

module.exports = SmartyPlugin;
module.exports.Plugin = SmartyPlugin;
module.exports.default = SmartyPlugin;

