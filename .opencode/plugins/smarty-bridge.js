class SmartyBridge {
  constructor() {
    this.bridgeUrl = 'http://127.0.0.1:7860/opencode/events';
    this.seenParts = new Map();
  }

  async setup(opencode) {
    opencode.on('message.updated', async (event) => {
      if (!event) return;
      try {
        await fetch(this.bridgeUrl, {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: this.safeStringify({ type: 'message.updated', ...event })
        });
      } catch (e) {
        console.error('[SmartyBridge] message.updated error:', e);
      }
    });

    // Forward tool lifecycle and session events directly
    const directEvents = [
      'tool.execute.before', 'tool.execute.after', 
      'session.created', 'session.aborted',
      'subagent.created', 'subagent.idle',
      'websearch.query', 'websearch.result',
      'webfetch.url', 'webfetch.result',
      'compaction.start', 'compaction.complete',
      'file.edited', 'command.execute'
    ];
    
    for (const kind of directEvents) {
      opencode.on(kind, async (e) => this.forward(kind, e));
    }
  }

  async forward(kind, event) {
    try {
      await fetch(this.bridgeUrl, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: this.safeStringify({ type: kind, ...event })
      });
    } catch(e) {
      console.error(`[SmartyBridge] ${kind} error:`, e);
    }
  }
  safeStringify(obj) {
    const cache = new Set();
    return JSON.stringify(obj, (key, value) => {
      if (typeof value === 'object' && value !== null) {
        if (cache.has(value)) return undefined;
        cache.add(value);
      }
      return value;
    });
  }
}

module.exports = async function(ctx) {
  const bridge = new SmartyBridge();
  await bridge.setup(ctx);
};
