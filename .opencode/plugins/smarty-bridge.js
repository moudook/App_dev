// Smarty Bridge plugin for OpenCode CLI 1.16.0+
// Uses the new event hook API: { event: async ({ event }) => { ... } }
// where event is { type: string, properties: object }
const SmartyPlugin = async function(ctx) {
  const bridgeUrl = 'http://127.0.0.1:7860/opencode/events';

  const forward = async (event) => {
    try {
      // event has shape: { type: "message.part.updated", properties: { ... } }
      // Flatten properties to top-level for the Ktor bridge which expects both
      // the old format (top-level fields) and the new format (under properties).
      const payload = {
        type: event.type,
        ...(event.properties || {}),
      };
      // Ensure sessionID is at top level for Ktor bridge session lookup
      if (!payload.sessionID && event.properties) {
        const props = event.properties;
        const sid = props.sessionID
          || (props.part && (props.part.sessionID || props.part.sessionId))
          || (props.info && props.info.sessionID)
          || (props.message && props.message.sessionID);
        if (sid) payload.sessionID = sid;
      }
      await fetch(bridgeUrl, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: safeStringify(payload)
      });
    } catch (e) {
      console.error(`[SmartyBridge] ${event.type} forward error:`, e && e.message ? e.message : e);
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

  // New OpenCode 1.16.0+ plugin API:
  // A single `event` hook receives ALL events as { type, properties }
  return {
    event: async ({ event }) => {
      if (!event || !event.type) return;
      await forward(event);
    },
    dispose: async () => {
      console.log('[SmartyBridge] disposing plugin');
      try {
        await fetch(bridgeUrl, {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({ type: 'plugin.dispose' })
        });
      } catch (_) {}
    }
  };
};

module.exports = SmartyPlugin;
module.exports.Plugin = SmartyPlugin;
module.exports.default = SmartyPlugin;
