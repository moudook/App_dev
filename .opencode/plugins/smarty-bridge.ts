import * as fs from "fs";
import * as http from "http";

const bridgeUrl = 'http://127.0.0.1:7860/opencode/events';
const logFile = "/tmp/smarty-plugin.log";

function log(msg: string) {
    try { fs.appendFileSync(logFile, new Date().toISOString() + " " + msg + "\n"); } catch (e) {}
}

log("smarty-bridge: Plugin script evaluated!");

const seenParts = new Map<string, {textLen: number, reasoningLen: number}>();

async function forward(kind: string, payload: any) {
    try {
        log(`forward() called for ${kind}`);
        const bodyStr = JSON.stringify({ type: kind, ...payload });
        
        // Use Node's native http to avoid fetch() compatibility issues
        const req = http.request(bridgeUrl, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(bodyStr)
            }
        }, (res: any) => {
            log(`forward() SUCCESS for ${kind}, status: ${res.statusCode}`);
        });

        req.on('error', (e: any) => {
            log(`forward() HTTP ERROR for ${kind}: ${e.message}`);
        });

        req.write(bodyStr);
        req.end();

    } catch(e: any) {
        log(`forward() CATCH ERROR for ${kind}: ${e?.message}`);
    }
}

const SmartyBridge = async ({client}: {client: any}) => {
    log("smarty-bridge: Plugin initialized by OpenCode!");
    return {
        "message.part.updated": async (payload: any) => {
            const p = payload.part;
            if (!p || !p.id) return;
            
            const state = seenParts.get(p.id) || {textLen: 0, reasoningLen: 0};
            
            const textLen = p.text?.length || p.content?.length || 0;
            const rLen = p.reasoning?.length || p.reasoning_content?.length || 0;

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
            log("smarty-bridge: dispose() called");
            await forward("plugin.dispose", {});
            seenParts.clear();
        }
    };
};

export default SmartyBridge;
