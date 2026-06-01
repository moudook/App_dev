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
# NOTE: Must use glibc-based image (NOT Alpine) because opencode-ai ships
# a prebuilt Go binary linked against glibc. Alpine's musl libc causes
# ENOENT on the bin/.opencode binary (postinstall.mjs fails silently).
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre

# Install Node.js 20.x (LTS), NPM, bash, and wget for orchestration
RUN apt-get update && apt-get install -y curl gnupg ca-certificates && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs bash wget && \
    rm -rf /var/lib/apt/lists/*

# Install OpenCode CLI globally and clean npm cache to reduce image size
# --unsafe-perm required because Docker build runs as root and npm >= 7
# skips postinstall scripts for root users. Without this, the native Go binary
# (bin/.opencode) is never downloaded by postinstall.mjs and the CLI is broken.
RUN npm install -g opencode-ai@1.14.41 --unsafe-perm && npm cache clean --force

# Security: non-root user (HF Spaces uses UID 1000)
# eclipse-temurin:17-jre base image has a pre-existing 'user' account.
# Reuse it (don't delete and recreate) and just set its primary group + shell.
RUN echo "=== Existing /etc/passwd entries for UID >= 999 ===" && \
    awk -F: '$3 >= 999 {print}' /etc/passwd && \
    echo "=== Setting up appgroup and ensuring user has shell + home ===" && \
    groupadd -f appgroup && \
    usermod -d /home/user -m -s /bin/bash -g appgroup user && \
    echo "Final user: uid=$(id -u user) gid=$(id -g user) home=$(getent passwd user | cut -d: -f6) shell=$(getent passwd user | cut -d: -f7)"

WORKDIR /app

# Copy fat JAR and configurations
COPY --from=builder --chown=user:appgroup /build/server/build/libs/server-1.0.0-all.jar app.jar
COPY --chown=user:appgroup entrypoint.sh /app/entrypoint.sh
COPY --chown=user:appgroup opencode.json /app/opencode.json
COPY --chown=user:appgroup .opencode/ /app/.opencode/

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