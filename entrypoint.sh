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
echo "[1/3] Verifying OpenCode CLI installation..."
if ! command -v opencode &> /dev/null; then
    echo "ERROR: opencode CLI not found. Install with: npm install -g opencode-ai"
    exit 1
fi

OPENCODE_VERSION=$(opencode --version 2>&1 || echo "unknown")
echo "  OpenCode version: $OPENCODE_VERSION"
echo ""

# -----------------------------------------------------------------------------
# Step 2: Start OpenCode daemon in background
# -----------------------------------------------------------------------------
echo "[2/3] Starting OpenCode daemon on port $DAEMON_PORT..."

# Ensure opencode.json is in the working directory
if [ ! -f "./opencode.json" ]; then
    echo "WARNING: opencode.json not found in $(pwd), using defaults"
fi

# Launch daemon — redirect output to log file
opencode serve --port $DAEMON_PORT --hostname $DAEMON_HOST > /tmp/opencode-daemon.log 2>&1 &
DAEMON_PID=$!
echo "  Daemon PID: $DAEMON_PID"
echo ""

# -----------------------------------------------------------------------------
# Step 3: Health check loop — wait for daemon to respond
# -----------------------------------------------------------------------------
echo "[3/3] Waiting for OpenCode daemon to be ready..."

for i in $(seq 1 $MAX_RETRIES); do
    if wget -q --spider --timeout=2 "$DAEMON_URL/global/health" 2>/dev/null; then
        echo "  Daemon is healthy after $((i * RETRY_INTERVAL)) seconds!"
        echo ""
        break
    fi

    if [ $i -eq $MAX_RETRIES ]; then
        echo "  WARNING: Daemon did not respond after $((MAX_RETRIES * RETRY_INTERVAL)) seconds."
        echo "  Continuing anyway — Ktor will use one-shot CLI mode."
        echo ""
        break
    fi

    sleep $RETRY_INTERVAL
done

# -----------------------------------------------------------------------------
# Step 4: Launch Ktor server
# -----------------------------------------------------------------------------
echo "============================================"
echo "  Launching Ktor server on port ${SERVER_PORT:-7860}"
echo "============================================"
echo ""

# JVM optimizations for 384MB heap on HF Spaces free tier
JAVA_OPTS="-Xmx384m -XX:+UseG1GC -XX:MaxRAMPercentage=80.0 -XX:+ExitOnOutOfMemoryError"

exec java $JAVA_OPTS -jar app.jar
