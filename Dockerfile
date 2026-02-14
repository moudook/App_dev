# =============================================================================
# Stage 1: Build the server fat JAR
# =============================================================================
FROM gradle:8.5-jdk17 AS builder

WORKDIR /project

# Copy only build configuration files first for dependency caching
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY gradlew gradlew.bat ./
COPY server/build.gradle.kts server/
COPY common/build.gradle.kts common/

# Pre-download dependencies (cached layer - only re-runs if build files change)
RUN chmod +x ./gradlew && \
    ./gradlew dependencies --no-daemon || true

# Now copy the actual source code
COPY common/src/ common/src/
COPY server/src/ server/src/

# Build the fat JAR (skip tests for faster builds)
# --no-daemon prevents Gradle daemon from consuming memory after build
RUN ./gradlew :server:fatJar --no-daemon -x test \
    -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=512m"

# =============================================================================
# Stage 2: Lightweight runtime image
# =============================================================================
FROM eclipse-temurin:17-jre-alpine

# Create non-root user (HF Spaces runs as UID 1000)
RUN addgroup -S appgroup && adduser -S -G appgroup -u 1000 user

WORKDIR /app

# Copy the built fat JAR from builder stage
COPY --from=builder --chown=user:appgroup /project/server/build/libs/server-1.0.0-all.jar app.jar

# Switch to non-root user
USER user

# HF Spaces expects port 7860
ENV SERVER_PORT=7860
EXPOSE 7860

# Health check for HF Spaces
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:7860/health || exit 1

# Run the server
CMD ["java", "-Xmx512m", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
