#!/bin/bash
# =============================================================================
# Entrypoint for Hugging Face Spaces (Alpine Linux)
# 1. Start OpenCode CLI daemon in background
# 2. Verify OpenCode CLI works (models + run)
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
# Step 2: List available free models (VERIFICATION)
# -----------------------------------------------------------------------------
echo "[2/5] Discovering free models via 'opencode models'..."
echo "  Running: opencode models"
echo "  --- BEGIN opencode models output ---"
MODELS_OUTPUT=$(opencode models 2>&1 || echo "ERROR: opencode models failed")
echo "$MODELS_OUTPUT"
echo "  --- END opencode models output ---"

# Count free models
FREE_COUNT=$(echo "$MODELS_OUTPUT" | grep -i "free" | grep -c "opencode/" || echo "0")
echo ""
echo "  Found $FREE_COUNT free model(s) containing 'free' in the name:"
echo "$MODELS_OUTPUT" | grep -i "free" | grep "opencode/" | while read -r line; do
    echo "    -> $line"
done
echo ""

if [ "$FREE_COUNT" -eq 0 ]; then
    echo "  WARNING: No free models found! Chat will not work."
else
    echo "  SUCCESS: $FREE_COUNT free model(s) available for inference."
fi
echo ""

# -----------------------------------------------------------------------------
# Step 3: Launch Ktor server FIRST so MCP routes are available
# -----------------------------------------------------------------------------
echo "[3/5] Launching Ktor server on port ${SERVER_PORT:-7860}..."
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
    if wget -q --spider --timeout=2 "http://127.0.0.1:${KTOR_PORT}/health" 2>/dev/null; then
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

# -----------------------------------------------------------------------------
# Step 4: Start OpenCode daemon (Ktor is already running — MCP routes are live)
# -----------------------------------------------------------------------------
echo "[4/5] Starting OpenCode daemon on port $DAEMON_PORT..."

if [ ! -f "./opencode.json" ]; then
    echo "WARNING: opencode.json not found in $(pwd), using defaults"
else
    echo "  opencode.json: FOUND"
fi

echo "  Launching: opencode serve --port $DAEMON_PORT --hostname $DAEMON_HOST"
opencode serve --port $DAEMON_PORT --hostname $DAEMON_HOST > /tmp/opencode-daemon.log 2>&1 &
DAEMON_PID=$!
echo "  Daemon PID: $DAEMON_PID"
echo "  Daemon log: /tmp/opencode-daemon.log"

sleep 1
if kill -0 $DAEMON_PID 2>/dev/null; then
    echo "  Daemon process: RUNNING"
else
    echo "  Daemon process: EXITED (check /tmp/opencode-daemon.log)"
    tail -5 /tmp/opencode-daemon.log 2>/dev/null || echo "  (no log output)"
fi
echo ""

# -----------------------------------------------------------------------------
# Step 5: Health check loop — wait for daemon to respond
# -----------------------------------------------------------------------------
echo "[5/5] Waiting for OpenCode daemon to be ready..."
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
echo "============================================"
echo "  Startup complete — Ktor + daemon running"
echo "============================================"
echo "  Ktor PID: $KTOR_PID"
echo "  Daemon PID: $DAEMON_PID"
echo "  Free models: $FREE_COUNT"
echo ""

# Wait for either process to exit
wait
