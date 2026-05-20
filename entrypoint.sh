#!/bin/bash
# =============================================================================
# Entrypoint for Hugging Face Spaces (Alpine Linux)
# 1. Start OpenCode CLI daemon in background
# 2. Health check loop — wait for daemon to be ready
# 3. Launch Ktor server
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
# Step 2: Start OpenCode daemon in background
# -----------------------------------------------------------------------------
echo "[2/4] Starting OpenCode daemon on port $DAEMON_PORT..."

# Ensure opencode.json is in the working directory
if [ ! -f "./opencode.json" ]; then
    echo "WARNING: opencode.json not found in $(pwd), using defaults"
else
    echo "  opencode.json: FOUND"
    echo "  Config: $(cat ./opencode.json | head -1)"
fi

# Launch daemon — redirect output to log file
# 'opencode serve' is headless by default
echo "  Launching: opencode serve --port $DAEMON_PORT --hostname $DAEMON_HOST"
opencode serve --port $DAEMON_PORT --hostname $DAEMON_HOST > /tmp/opencode-daemon.log 2>&1 &
DAEMON_PID=$!
echo "  Daemon PID: $DAEMON_PID"
echo "  Daemon log: /tmp/opencode-daemon.log"

# Give daemon a moment to start
sleep 1
if kill -0 $DAEMON_PID 2>/dev/null; then
    echo "  Daemon process: RUNNING"
else
    echo "  Daemon process: EXITED (check /tmp/opencode-daemon.log)"
    echo "  Last 5 lines of daemon log:"
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
        echo "  Continuing anyway — Ktor will use one-shot CLI mode."
        echo ""
        break
    fi

    # Log progress every 5 retries
    if [ $((i % 5)) -eq 0 ]; then
        echo "  Still waiting... ($((i * RETRY_INTERVAL))s elapsed)"
    fi

    sleep $RETRY_INTERVAL
done

# -----------------------------------------------------------------------------
# Step 3b: Verify daemon is actually serving
# -----------------------------------------------------------------------------
if [ "$DAEMON_READY" = true ]; then
    echo "[3b/4] Verifying daemon responds to requests..."
    HEALTH_RESPONSE=$(wget -q -O - --timeout=2 "$DAEMON_URL/global/health" 2>/dev/null || echo "")
    if [ -n "$HEALTH_RESPONSE" ]; then
        echo "  Daemon health response: $HEALTH_RESPONSE"
    else
        echo "  WARNING: Daemon accepted connection but returned empty response"
    fi
    echo ""
fi

# -----------------------------------------------------------------------------
# Step 4: Launch Ktor server
# -----------------------------------------------------------------------------
echo "[4/4] Launching Ktor server on port ${SERVER_PORT:-7860}"
echo "============================================"
echo ""
echo "  JVM heap: -Xmx384m"
echo "  GC: G1GC"
echo "  Max RAM: 80%"
echo "  OOM behavior: ExitOnOutOfMemoryError"
echo "  Daemon status: $([ "$DAEMON_READY" = true ] && echo 'RUNNING' || echo 'NOT RUNNING')"
echo "  Daemon PID: $([ -n "$DAEMON_PID" ] && echo $DAEMON_PID || echo 'N/A')"
echo ""
echo "============================================"
echo "  All checks complete — starting Ktor server"
echo "============================================"
echo ""

# JVM optimizations for 384MB heap on HF Spaces free tier
JAVA_OPTS="-Xmx384m -XX:+UseG1GC -XX:MaxRAMPercentage=80.0 -XX:+ExitOnOutOfMemoryError"

exec java $JAVA_OPTS -jar app.jar
