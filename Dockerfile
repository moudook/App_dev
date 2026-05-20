# =============================================================================
# OPTIMIZED Dockerfile for Hugging Face Spaces
# Uses server-only Gradle config (no Android SDK overhead) for 5-10x faster builds
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Download dependencies (cached layer - rarely changes)
# -----------------------------------------------------------------------------
FROM gradle:8.12.1-jdk17-alpine AS deps

WORKDIR /build

# Clear any cached Java home settings
ENV GRADLE_OPTS=""

# Use server-only Gradle configs (no Android SDK detection, no version catalog)
COPY build.server.gradle.kts build.gradle.kts
COPY settings.server.gradle.kts settings.gradle.kts
COPY gradle.properties ./
COPY server/build.server.gradle.kts server/build.gradle.kts
COPY common/build.server.gradle.kts common/build.gradle.kts
COPY gradle/ gradle/
COPY gradlew ./

# Download all dependencies - this layer is heavily cached
RUN chmod +x gradlew && \
    ./gradlew :server:dependencies --no-daemon --parallel \
    -Dorg.gradle.jvmargs="-Xmx1g -XX:MaxMetaspaceSize=256m" || true

# -----------------------------------------------------------------------------
# Stage 2: Build the server JAR
# -----------------------------------------------------------------------------
FROM deps AS builder

WORKDIR /build

# Copy source code (changes frequently - separate layer for fast rebuilds)
COPY common/src/commonMain/kotlin/ common/src/commonMain/kotlin/
COPY server/src/ server/src/

# Build shadow JAR with optimizations
RUN ./gradlew :server:shadowJar --no-daemon --parallel -x test \
    -Dorg.gradle.jvmargs="-Xmx1g -XX:MaxMetaspaceSize=256m" \
    -Porg.gradle.java.home=/opt/java/openjdk \
    --max-workers=2

# -----------------------------------------------------------------------------
# Stage 3: Minimal JRE runtime with Node.js and OpenCode CLI
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine

# Install Node.js, NPM, and bash for orchestration
RUN apk add --no-cache nodejs npm bash

# Install OpenCode CLI globally
RUN npm install -g @opencode-ai/cli

# Security: non-root user (HF Spaces uses UID 1000)
RUN addgroup -S appgroup && adduser -S -G appgroup -u 1000 user

WORKDIR /app

# Copy fat JAR and configurations
COPY --from=builder --chown=user:appgroup /build/server/build/libs/server-1.0.0-all.jar app.jar
COPY --chown=user:appgroup entrypoint.sh /app/entrypoint.sh
COPY --chown=user:appgroup opencode.json /app/opencode.json

# Pre-create temp directories and set permissions
RUN mkdir -p /app/_temp && \
    chmod +x /app/entrypoint.sh && \
    chown -R user:appgroup /app

USER user

ENV SERVER_PORT=7860
EXPOSE 7860

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:7860/health || exit 1

# Orchestrate daemon and server launch via entrypoint script
CMD ["/bin/bash", "/app/entrypoint.sh"]