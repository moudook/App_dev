#!/bin/bash
# =============================================================================
# Entrypoint for Hugging Face Spaces (glibc-based JRE)
# 0. Configure non-NFS storage paths
# 0b. Install Timeline Bridge plugin
# 1. Verify OpenCode CLI is installed
# 2. Start Ktor server FIRST so MCP SSE endpoint is available
# 3. Wait for Ktor to be healthy
# 4. Start OpenCode daemon (it can now connect to MCP tools at startup)
# 5. Verify MCP tools registered
# =============================================================================
set -e

DAEMON_PORT=4096
DAEMON_HOST="127.0.0.1"
DAEMON_URL="http://${DAEMON_HOST}:${DAEMON_PORT}"
MAX_RETRIES=30
RETRY_INTERVAL=2

echo "============================================"
echo "  Smarty Server — OpenCode CLI Integration"
echo "============================================"
echo ""

# -----------------------------------------------------------------------------
# Step 0: Configure non-NFS storage paths for OpenCode (HF Spaces uses NFS)
# Required to prevent SQLite "database disk image is malformed" corruption
# See: https://github.com/anomalyco/opencode/issues/14970
# -----------------------------------------------------------------------------
echo "[0/5] Configuring OpenCode storage paths (avoid NFS SQLite corruption)..."
export XDG_DATA_HOME="/tmp/opencode-data"
export XDG_CONFIG_HOME="/tmp/opencode-config"
export OPENCODE_DATA_DIR="/tmp/opencode"
mkdir -p "$XDG_DATA_HOME/opencode" "$XDG_CONFIG_HOME/opencode/plugins" "$OPENCODE_DATA_DIR"
echo "  XDG_DATA_HOME=$XDG_DATA_HOME"
echo "  XDG_CONFIG_HOME=$XDG_CONFIG_HOME"
echo "  OPENCODE_DATA_DIR=$OPENCODE_DATA_DIR"
echo "  Directories created OK"
echo ""

# -----------------------------------------------------------------------------
# Step 0b: Install Timeline Bridge plugin so daemon sends part.updated / message.part.delta events
# -----------------------------------------------------------------------------
echo "[0b/5] Installing Timeline Bridge plugin..."
PLUGIN_SRC=".opencode/plugins/timeline-bridge.ts"
PLUGIN_DEST="$XDG_CONFIG_HOME/opencode/plugins/timeline-bridge.ts"
if [ -f "$PLUGIN_SRC" ]; then
    cp "$PLUGIN_SRC" "$PLUGIN_DEST"
    echo "  Plugin installed to $PLUGIN_DEST"
else
    echo "  WARNING: Plugin source not found at $PLUGIN_SRC"
fi
echo ""

# -----------------------------------------------------------------------------
# Step 1: Verify OpenCode CLI is installed
# -----------------------------------------------------------------------------
echo "[1/5] Verifying OpenCode CLI installation..."
if ! command -v opencode &> /dev/null; then
    echo "ERROR: opencode CLI not found. Install with: npm install -g opencode-ai"
    exit 1
fi
OPENCODE_VERSION=$(opencode --version 2>&1 || echo "unknown")
echo "  OpenCode version: $OPENCODE_VERSION"
echo "  OpenCode path: $(which opencode)"
echo "  Working directory: $(pwd)"
echo "  opencode.json exists: $([ -f ./opencode.json ] && echo 'YES' || echo 'NO')"
echo ""

# -----------------------------------------------------------------------------
# Step 2: Start Ktor server FIRST so MCP SSE endpoint (localhost:7860/mcp/sse)
# is available BEFORE the OpenCode daemon starts. This is critical for MCP
# tool registration — if the daemon starts first, it cannot see any MCP tools.
# -----------------------------------------------------------------------------
echo "[2/5] Launching Ktor server on port ${SERVER_PORT:-7860}..."
echo "  JVM heap: -Xmx384m"
echo "  GC: G1GC"
echo "  Max RAM: 80%"
echo "  OOM behavior: ExitOnOutOfMemoryError"

JAVA_OPTS="-Xmx384m -XX:+UseG1GC -XX:MaxRAMPercentage=80.0 -XX:+ExitOnOutOfMemoryError"
java $JAVA_OPTS -jar app.jar 2>&1 | tee /tmp/ktor-server.log &
KTOR_PID=$!
echo "  Ktor PID: $KTOR_PID"
echo "  Ktor log: /tmp/ktor-server.log"

# Wait for Ktor to be healthy (so MCP SSE endpoint is reachable)
KTOR_PORT="${SERVER_PORT:-7860}"
echo "  Waiting for Ktor health endpoint at http://127.0.0.1:${KTOR_PORT}/health..."
KTOR_READY=false
for i in $(seq 1 30); do
    if wget -q -O /dev/null --timeout=2 "http://127.0.0.1:${KTOR_PORT}/health" 2>/dev/null; then
        echo "  Ktor is healthy after $((i * 2)) seconds!"
        KTOR_READY=true
        break
    fi
    if [ $((i % 5)) -eq 0 ]; then
        echo "  Still waiting for Ktor... ($((i * 2))s elapsed)"
    fi
    sleep 2
done
if [ "$KTOR_READY" = false ]; then
    echo "  ERROR: Ktor did not become healthy after 60 seconds."
    echo "  Ktor log (last 10 lines):"
    tail -10 /tmp/ktor-server.log 2>/dev/null || echo "  (no log output)"
    exit 1
fi

# Verify MCP SSE endpoint is actually responding (not just /health)
echo "  Verifying MCP SSE endpoint at http://127.0.0.1:${KTOR_PORT}/mcp/sse..."
if wget -q --spider --timeout=2 "http://127.0.0.1:${KTOR_PORT}/mcp/sse" 2>/dev/null; then
    echo "  MCP SSE endpoint: RESPONDING"
else
    echo "  WARNING: MCP SSE endpoint not responding yet (may need a moment)"
fi
echo ""

# -----------------------------------------------------------------------------
# Step 3: Start OpenCode daemon (Ktor is already up — MCP tools will register)
# -----------------------------------------------------------------------------
echo "[3/5] Starting OpenCode daemon on port $DAEMON_PORT..."
if [ ! -f "./opencode.json" ]; then
    echo "WARNING: opencode.json not found in $(pwd), using defaults"
else
    echo "  opencode.json: FOUND"
fi

echo " Launching: opencode serve --port $DAEMON_PORT --hostname $DAEMON_HOST"
opencode serve --port $DAEMON_PORT --hostname $DAEMON_HOST > /tmp/opencode-daemon.log 2>&1 &
DAEMON_PID=$!
echo "  Daemon PID: $DAEMON_PID"

sleep 1
if kill -0 $DAEMON_PID 2>/dev/null; then
    echo "  Daemon process: RUNNING"
else
    echo "  Daemon process: EXITED (check /tmp/opencode-daemon.log)"
    tail -5 /tmp/opencode-daemon.log 2>/dev/null || echo "  (no log output)"
fi
echo ""

# -----------------------------------------------------------------------------
# Step 4: Health check loop — wait for daemon to respond
# -----------------------------------------------------------------------------
echo "[4/5] Waiting for OpenCode daemon to be ready..."
echo "  Health endpoint: $DAEMON_URL/global/health"
echo "  Max retries: $MAX_RETRIES (every ${RETRY_INTERVAL}s)"

DAEMON_READY=false
for i in $(seq 1 $MAX_RETRIES); do
    if wget -q --spider --timeout=2 "$DAEMON_URL/global/health" 2>/dev/null; then
        echo "  Daemon is healthy after $((i * RETRY_INTERVAL)) seconds!"
        DAEMON_READY=true
        echo ""
        break
    fi
    if [ $i -eq $MAX_RETRIES ]; then
        echo "  WARNING: Daemon did not respond after $((MAX_RETRIES * RETRY_INTERVAL)) seconds."
        echo "  Daemon log (last 10 lines):"
        tail -10 /tmp/opencode-daemon.log 2>/dev/null || echo "  (no log output)"
        echo ""
        break
    fi
    if [ $((i % 5)) -eq 0 ]; then
        echo "  Still waiting... ($((i * RETRY_INTERVAL))s elapsed)"
    fi
    sleep $RETRY_INTERVAL
done

if [ "$DAEMON_READY" = true ]; then
    HEALTH_RESPONSE=$(wget -q -O - --timeout=2 "$DAEMON_URL/global/health" 2>/dev/null || echo "")
    if [ -n "$HEALTH_RESPONSE" ]; then
        echo "  Daemon health response: $HEALTH_RESPONSE"
    fi
fi

# -----------------------------------------------------------------------------
# Step 5: Verify MCP tools are registered in the daemon
# -----------------------------------------------------------------------------
echo ""
echo "[5/5] Verifying MCP tools are registered in OpenCode daemon..."
MCP_TOOLS_ENDPOINT="$DAEMON_URL/tools"
MCP_VERIFIED=false
for i in $(seq 1 12); do
    MCP_RESPONSE=$(wget -q -O - --timeout=3 "$MCP_TOOLS_ENDPOINT" 2>/dev/null || echo "")
    if echo "$MCP_RESPONSE" | grep -q "memory\|ask_user\|schedule\|device"; then
        echo "  MCP tools verified (found in response)"
        MCP_VERIFIED=true
        break
    fi
    if [ $((i % 4)) -eq 0 ]; then
        echo "  Still waiting for MCP tool registration... ($((i * 5))s elapsed)"
    fi
    sleep 5
done
if [ "$MCP_VERIFIED" = false ]; then
    echo "  WARNING: Could not verify MCP tools after 60 seconds."
    echo "  Daemon log (last 15 lines):"
    tail -15 /tmp/opencode-daemon.log 2>/dev/null || echo "  (no log output)"
fi
echo ""

echo ""
echo "============================================"
echo "  Startup complete — Ktor + daemon running"
echo "============================================"
echo "  Ktor PID: $KTOR_PID"
echo "  Daemon PID: $DAEMON_PID"
echo "  MCP SSE: http://127.0.0.1:${KTOR_PORT}/mcp/sse (started before daemon)"
echo "  Model discovery: handled by Ktor's OpencodeModelRegistry"
echo ""

# Wait for either process to exit
wait
