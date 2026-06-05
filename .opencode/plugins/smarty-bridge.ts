class SmartyBridge {
  private bridgeUrl = 'http://127.0.0.1:7860/opencode/events';

  // Part tracking deduplication: {partId: {textLen: 0, reasoningLen: 0}}
  private seenParts = new Map<string, {textLen: number, reasoningLen: number}>();

  async setup(opencode: any) {
    opencode.on('message.part.updated', async (event: any) => {
      const p = event.part;
      if (!p || !p.id) return;
      
      const state = this.seenParts.get(p.id) || {textLen: 0, reasoningLen: 0};
      
      const textLen = p.text?.length || 0;
      const rLen = p.reasoning?.length || 0;

      // Deduplicate: don't forward if we haven't gained any new text or reasoning
      if (textLen === state.textLen && rLen === state.reasoningLen) {
         return;
      }
      this.seenParts.set(p.id, {textLen, reasoningLen: rLen});

      try {
        await fetch(this.bridgeUrl, {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify(event)
        });
      } catch (e) {
        // Ignore fetch errors to avoid crashing daemon
      }
    });

    // Forward tool lifecycle and session events directly
    opencode.on('tool.execute.before', async (e: any) => this.forward(e));
    opencode.on('tool.execute.after', async (e: any) => this.forward(e));
    opencode.on('session.created', async (e: any) => this.forward(e));
    opencode.on('session.aborted', async (e: any) => this.forward(e));
  }

  private async forward(event: any) {
    try {
      await fetch(this.bridgeUrl, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(event)
      });
    } catch(e) {
      // Ignore
    }
  }
}

module.exports = new SmartyBridge();
