#!/bin/sh

# Start the OpenCode local serve daemon in the background
echo "Starting OpenCode Local Serve Daemon..."
opencode serve --port 4096 --hostname 127.0.0.1 > opencode_serve.log 2>&1 &

# Wait for daemon to be ready with health check loop
echo "Waiting for OpenCode Daemon to bind to port 4096..."
MAX_RETRIES=15
RETRY_INTERVAL=2
i=0
while [ $i -lt $MAX_RETRIES ]; do
    if wget --no-verbose --tries=1 --spider http://127.0.0.1:4096/global/health 2>/dev/null; then
        echo "OpenCode Daemon is ready on port 4096"
        break
    fi
    i=$((i + 1))
    if [ $i -eq $MAX_RETRIES ]; then
        echo "WARNING: OpenCode Daemon did not respond within $((MAX_RETRIES * RETRY_INTERVAL))s. Continuing anyway..."
    else
        sleep $RETRY_INTERVAL
    fi
done

# Boot up the Ktor shadow JAR
echo "Booting Friday Ktor Server on port 7860..."
exec java -Xmx384m -XX:+UseG1GC -XX:MaxRAMPercentage=80.0 -XX:+UseStringDeduplication -jar app.jar
