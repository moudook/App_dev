class SmartyBridge {
  private bridgeUrl = 'http://127.0.0.1:7860/opencode/events';

  // Part tracking deduplication: {partId: {textLen: 0, reasoningLen: 0}}
  private seenParts = new Map<string, {textLen: number, reasoningLen: number}>();

  async setup(opencode: any) {
    opencode.on('message.updated', async (event: any) => {
      if (!event) return;
      try {
        await fetch(this.bridgeUrl, {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({ type: 'message.updated', ...event })
        });
      } catch (e) {
        // Ignore fetch errors to avoid crashing daemon
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
      opencode.on(kind, async (e: any) => this.forward(kind, e));
    }
  }

  private async forward(kind: string, event: any) {
    try {
      await fetch(this.bridgeUrl, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({ type: kind, ...event })
      });
    } catch(e) {
      // Ignore
    }
  }
}

module.exports = new SmartyBridge();
