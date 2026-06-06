#!/bin/bash
set -e

echo "============================================"
echo "  Starting Smarty Server (Zen API Architecture)"
echo "============================================"
echo ""

echo "  JVM heap: -Xmx384m"
echo "  GC: G1GC"
echo "  Max RAM: 80%"
echo "  OOM behavior: ExitOnOutOfMemoryError"

JAVA_OPTS="-Xmx384m -XX:+UseG1GC -XX:MaxRAMPercentage=80.0 -XX:+ExitOnOutOfMemoryError"

echo "Launching Ktor server on port ${SERVER_PORT:-7860}..."
exec java $JAVA_OPTS -jar app.jar
