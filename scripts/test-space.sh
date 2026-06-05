#!/usr/bin/env bash
# ============================================================================
# scripts/test-space.sh — Test the Smarty HF Space from the local computer
# ============================================================================
# Sources .env (which must contain HUGGINGFACE_ACCESS_TOKEN=hf_xxx) and runs
# the standard diagnostic + LLM call sequence against the deployed Space.
#
# Usage:
#   bash scripts/test-space.sh logs        # stream live Space logs
#   bash scripts/test-space.sh health      # GET /health
#   bash scripts/test-space.sh chat "hi"   # POST /api/v1/chat/stream
#   bash scripts/test-space.sh events      # GET /event (daemon SSE)
#   bash scripts/test-space.sh all         # health + chat + logs
# ============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"

if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: .env file not found at $ENV_FILE"
    echo "Create it with: HUGGINGFACE_ACCESS_TOKEN=hf_xxxxxxxx"
    exit 1
fi

# Source .env
set -a
. "$ENV_FILE"
set +a

if [ -z "$HUGGINGFACE_ACCESS_TOKEN" ] || [ "$HUGGINGFACE_ACCESS_TOKEN" = "my_hf_token" ]; then
    # Fall back to the typo'd name HUGGINGFACE_ACESS_TOKEN (missing C) if present
    if [ -n "$HUGGINGFACE_ACESS_TOKEN" ] && [ "$HUGGINGFACE_ACESS_TOKEN" != "my_hf_token" ]; then
        HUGGINGFACE_ACCESS_TOKEN="$HUGGINGFACE_ACESS_TOKEN"
    else
        echo "ERROR: HUGGINGFACE_ACCESS_TOKEN is unset or still the placeholder in $ENV_FILE"
        echo "Paste your real HF token into the .env file and re-run."
        exit 1
    fi
fi

SPACE_URL="https://K1tt3n-Friday-server.hf.space"

cmd=${1:-help}
shift || true

case "$cmd" in
    logs)
        echo "Streaming live Space logs (Ctrl-C to stop)..."
        curl -N -H "Authorization: Bearer $HUGGINGFACE_ACCESS_TOKEN" \
            "https://huggingface.co/api/spaces/K1tt3n/Friday-server/logs/run"
        ;;
    health)
        echo "GET $SPACE_URL/health"
        curl -sS -o /dev/null -w "HTTP %{http_code} in %{time_total}s\n" "$SPACE_URL/health"
        ;;
    chat)
        msg=${1:-"Say hi in one sentence."}
        echo "POST $SPACE_URL/api/v1/chat/stream  body={\"message\":\"$msg\"}"
        curl -N -X POST -H "Content-Type: application/json" \
            -d "{\"message\":\"$msg\"}" \
            "$SPACE_URL/api/v1/chat/stream"
        ;;
    events)
        echo "GET http://127.0.0.1:4096/event  (requires running daemon locally)"
        curl -N --max-time 10 "http://127.0.0.1:4096/event" 2>&1 | head -30 || true
        ;;
    all)
        "$0" health
        echo ""
        "$0" chat "Hello, world! Please respond in one short sentence."
        ;;
    help|*)
        echo "Usage: $0 {logs|health|chat <msg>|events|all}"
        ;;
esac
