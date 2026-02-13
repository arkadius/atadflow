# Stage 1: Frontend Build
FROM node:22-alpine AS frontend-build
WORKDIR /build
COPY frontend/package*.json ./
RUN npm ci --prefer-offline --no-audit
COPY frontend/ ./
RUN npm run build
# Output: /build/dist/

# Stage 2: Backend Build
FROM gradle:9.3.1-jdk25-corretto AS backend-build
WORKDIR /build

# Copy Gradle wrapper and build files
COPY backend/gradle/ gradle/
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts backend/gradle.properties ./

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY backend/src/ src/

# Copy frontend build output to static resources
COPY --from=frontend-build /build/dist/ src/main/resources/META-INF/resources/

# Build backend (skip tests for faster Docker builds)
RUN ./gradlew build -x test --no-daemon
# Output: /build/build/quarkus-app/

# Stage 3: Runtime
FROM eclipse-temurin:25-jre

# Install Python 3.12 and pip
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        python3.12 \
        python3-pip \
        curl && \
    rm -rf /var/lib/apt/lists/*

# Install PySpark with Spark Connect support and common dependencies
RUN pip3 install --no-cache-dir \
    'pyspark[connect]==4.0.2' \
    --break-system-packages && \
    pip3 install --no-cache-dir \
    pandas>=2.0.0 \
    pyarrow>=10.0.0 \
    numpy>=1.21.0 \
    --break-system-packages

# Create python symlink for consistency
RUN ln -s /usr/bin/python3.12 /usr/bin/python

# Copy Quarkus application
WORKDIR /deployments
COPY --from=backend-build /build/build/quarkus-app/ ./

# Create non-root user for security
RUN useradd -r -u 1001 -g root quarkus && \
    chown -R 1001:0 /deployments && \
    chmod -R g=u /deployments

USER 1001

# Health check (liveness probe)
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/q/health/live || exit 1

EXPOSE 8080

CMD ["java", "-jar", "quarkus-run.jar"]
