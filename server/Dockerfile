# Stage 1: Build
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app
COPY . .
# Build the fat jar (using installDist then manually creating jar, or just assemble if shadowJar is configured)
# Since we don't have shadowJar, we use the distribution from installDist or similar.
# But server/build.gradle.kts has a 'fatJar' task registered.
RUN ./gradlew :server:fatJar --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine

# Install Node.js, npm, and curl (for health-check probing)
RUN apk add --no-cache nodejs npm curl

WORKDIR /app
COPY --from=builder /app/server/build/libs/server-1.0.0-all.jar app.jar
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# Hugging Face default user/permissions
# HF Spaces run as user 1000 by default
RUN adduser -D -u 1000 user
USER user
ENV HOME=/home/user \
    PATH=/home/user/.local/bin:$PATH \
    SERVER_PORT=7860

# HF uses port 7860 by default
EXPOSE 7860

ENTRYPOINT ["/entrypoint.sh"]
