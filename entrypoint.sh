#!/bin/bash
# =============================================================================
# Entrypoint for Hugging Face Spaces (glibc-based JRE)
# 1. Verify OpenCode CLI is installed
# 2. Start OpenCode daemon (Ktor's DaemonManager finds it healthy)
# 3. Health check loop — wait for daemon to be ready
# 4. Launch Ktor server
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
echo "[0/4] Configuring OpenCode storage paths (avoid NFS SQLite corruption)..."
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
# Step 1: Verify OpenCode CLI is installed
# -----------------------------------------------------------------------------
echo "[1/4] Verifying OpenCode CLI installation..."
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
# Step 2: Start OpenCode daemon FIRST so Ktor's DaemonManager finds it healthy
# -----------------------------------------------------------------------------
echo "[2/4] Starting OpenCode daemon on port $DAEMON_PORT..."
if [ ! -f "./opencode.json" ]; then
    echo "WARNING: opencode.json not found in $(pwd), using defaults"
else
    echo "  opencode.json: FOUND"
fi

echo "  Launching: opencode serve --port $DAEMON_PORT --hostname $DAEMON_HOST"
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
# Step 3: Health check loop — wait for daemon to respond
# -----------------------------------------------------------------------------
echo "[3/4] Waiting for OpenCode daemon to be ready..."
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

echo ""

# -----------------------------------------------------------------------------
# Step 4: Launch Ktor server (daemon is already running - MCP routes available)
# -----------------------------------------------------------------------------
echo "[4/4] Launching Ktor server on port ${SERVER_PORT:-7860}..."
echo "  JVM heap: -Xmx384m"
echo "  GC: G1GC"
echo "  Max RAM: 80%"
echo "  OOM behavior: ExitOnOutOfMemoryError"

JAVA_OPTS="-Xmx384m -XX:+UseG1GC -XX:MaxRAMPercentage=80.0 -XX:+ExitOnOutOfMemoryError"
java $JAVA_OPTS -jar app.jar 2>&1 | tee /tmp/ktor-server.log &
KTOR_PID=$!
echo "  Ktor PID: $KTOR_PID"
echo "  Ktor log: /tmp/ktor-server.log"

# Wait for Ktor to be healthy
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
    echo "  WARNING: Ktor did not become healthy after 60 seconds."
    echo "  Ktor log (last 10 lines):"
    tail -10 /tmp/ktor-server.log 2>/dev/null || echo "  (no log output)"
fi

echo ""
echo "============================================"
echo "  Startup complete — daemon + Ktor running"
echo "============================================"
echo "  Ktor PID: $KTOR_PID"
echo "  Daemon PID: $DAEMON_PID"
echo "  Model discovery: handled by Ktor's OpencodeModelRegistry"
echo ""

# Wait for either process to exit
wait
