const bridgeUrl = 'http://127.0.0.1:7860/opencode/events';
const seenParts = new Map<string, {textLen: number, reasoningLen: number}>();

async function forward(kind: string, payload: any) {
    try {
        await fetch(bridgeUrl, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ type: kind, ...payload })
        });
    } catch(e) {
        // Ignore fetch errors to avoid crashing daemon
    }
}

export const SmartyBridge = async ({client}: {client: any}) => {
    return {
        "message.part.updated": async (payload: any) => {
            const p = payload.part;
            if (!p || !p.id) return;
            
            const state = seenParts.get(p.id) || {textLen: 0, reasoningLen: 0};
            
            const textLen = p.text?.length || p.content?.length || 0;
            const rLen = p.reasoning?.length || p.reasoning_content?.length || 0;

            // Deduplicate: don't forward if we haven't gained any new text or reasoning
            if (textLen === state.textLen && rLen === state.reasoningLen) {
                return;
            }
            seenParts.set(p.id, {textLen, reasoningLen: rLen});

            await forward("message.part.updated", {
                ...payload,
                sessionID: p.sessionID || payload.sessionID,
                messageID: p.messageID || payload.messageID
            });
        },
        "tool.execute.before": async (i: any, o: any) => {
            await forward("tool.execute.before", { ...i, args: o?.args, sessionID: i.sessionID });
        },
        "tool.execute.after": async (i: any, o: any) => {
            await forward("tool.execute.after", { ...i, result: o?.result, error: o?.error, sessionID: i.sessionID });
        },
        "session.created": async (payload: any) => {
            await forward("session.created", payload);
        },
        "session.aborted": async (payload: any) => {
            await forward("session.aborted", payload);
        },
        dispose: async () => {
            await forward("plugin.dispose", {});
            seenParts.clear();
        }
    };
};

export default SmartyBridge;
