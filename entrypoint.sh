#!/bin/sh

# Start the OpenCode local serve daemon in the background
echo "Starting OpenCode Local Serve Daemon..."
opencode serve --port 4096 --hostname 127.0.0.1 > opencode_serve.log 2>&1 &

# Sleep to let the daemon bind to port 4096
echo "Waiting for OpenCode Daemon to bind to port 4096..."
sleep 3

# Boot up the Ktor shadow JAR
echo "Booting Friday Ktor Server on port 7860..."
exec java -Xmx384m -XX:+UseG1GC -XX:MaxRAMPercentage=80.0 -XX:+UseStringDeduplication -jar app.jar
