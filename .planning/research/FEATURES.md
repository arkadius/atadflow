# Feature Research

**Domain:** Self-Contained Docker Distribution for Java+JS+Python Applications
**Researched:** 2026-02-12
**Confidence:** MEDIUM

## Feature Landscape

### Table Stakes (Users Expect These)

Features users assume exist. Missing these = product feels incomplete.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Single Docker image for deployment | Modern apps ship as containers — standard deployment unit in 2026 | MEDIUM | Multi-stage build: frontend build stage → backend build stage → runtime stage. Frontend bundled into backend JAR. |
| Frontend served by backend | Production apps don't run separate dev servers — unified service | LOW | Quarkus serves static resources from `META-INF/resources/`. Vite builds to `dist/`, copy to `src/main/resources/META-INF/resources/`. |
| Python runtime in Docker image | App executes PySpark scripts via subprocess — Python must exist in container | MEDIUM | Install Python + pip + PySpark in Docker. Either layer Python on JVM image OR use multi-runtime base (community images exist but uncommon). |
| Application health check | Container orchestrators (Docker Compose, K8s) need liveness/readiness probes | LOW | HEALTHCHECK instruction in Dockerfile. Quarkus provides `/q/health/live` and `/q/health/ready` via smallrye-health. Check every 30s, timeout 3s, start period 60s. |
| Container starts and serves requests | Basic functionality — image must be runnable | LOW | EXPOSE port, CMD/ENTRYPOINT to start Quarkus. Port defaults to 8080. Frontend accessible at root `/`, API at `/api/*`. |
| Environment-based configuration | Production configs differ from dev — standard pattern | LOW | Quarkus profiles (`%prod` vs `%dev`). Override via ENV vars in docker-compose.yml or K8s manifests. Database URL, Spark Connect URL configurable. |

### Differentiators (Competitive Advantage)

Features that set the product apart. Not required, but valuable.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Integration test proving end-to-end flow | High confidence in releases — tests image itself, not dev environment | MEDIUM | Testcontainers: spin up app image + Spark Connect, create flow via REST API, submit job, poll status, verify SUCCESS. Validates full stack (frontend bundling, Python execution, Spark Connect). ~100-200 lines test code. |
| Optimized image size (layering) | Faster deployments, less storage — matters for CI/CD pipelines | MEDIUM | Separate layers: OS/runtime (rarely changes) → dependencies (changes occasionally) → app code (changes frequently). Quarkus fast-jar mode creates `quarkus-app/` with lib/ and app/ separation. Avoid uber-jar for better caching. |
| Docker Compose file for local usage | Easy local testing of production image — developers can validate before deploy | LOW | Include `docker-compose.yml` with app + Spark Connect + PostgreSQL. Single `docker compose up` starts entire stack. Use health checks for startup ordering. |
| Distroless base image option | Security-focused deployments want minimal attack surface | MEDIUM | quay.io/quarkus/quarkus-distroless-image for JVM mode (9MB base). Requires Quarkus app to be fully self-contained. Alternative: UBI-minimal (Red Hat Universal Base Image). Trade-off: smaller image vs debugging tools (no shell in distroless). |
| Build-time frontend bundling via Quinoa | Zero-configuration frontend integration — no manual build scripts | MEDIUM | Quarkus Quinoa extension auto-detects package.json, runs `npm install` + `npm run build`, copies output to META-INF/resources. Works with Gradle. Dev mode proxies to Vite dev server. Prod mode serves static files. Adds ~30-60s to build time. |
| Smoke test in CI | Catch broken images before release — fast feedback loop | LOW | Simple test: `docker run -d <image>`, `sleep 30`, `curl http://localhost:8080/q/health`, verify 200 OK, `docker stop`. Runs in <1 min. Add to GitHub Actions or equivalent. |

### Anti-Features (Commonly Requested, Often Problematic)

Features that seem good but create problems.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Native Quarkus build (GraalVM) | "Fastest startup, smallest image" | GraalVM requires extensive configuration for reflection/proxies, incompatible with some libraries. Build times 5-10min vs 30s for JVM. PostgreSQL, Hibernate, JSON work but PySpark subprocess might have issues. Not worth complexity for this use case. | Stick with JVM mode. Startup time <5s is acceptable. Image size ~200-300MB vs ~50MB native (but need Python anyway, so savings minimal). |
| Embedding Spark Connect in app image | "Simplify deployment — one container" | Spark Connect requires significant memory (~2GB+), different lifecycle than app. Mixing concerns. Users likely have existing Spark infrastructure. Forces users into specific Spark version. | Keep Spark Connect as separate service. Standard pattern: app + Spark cluster. Docker Compose orchestrates both. |
| Hot-reload in production image | "Debug in production" | Security risk, bloated image (need dev tools), not standard practice. Production images should be immutable. | Use proper logging + monitoring. Remote debugging via JDWP if absolutely necessary (disable in production). Dev mode separate concern. |
| Multiple Python versions | "Support Python 3.9, 3.10, 3.11..." | Image bloat, version conflicts, testing complexity. PySpark 4.x requires Python 3.9+. Most users on 3.11 in 2026. | Pick Python 3.11 (current stable, PySpark compatible). Document requirement. Users needing different version can rebuild Dockerfile. |
| Windows container support | "Some users on Windows" | Different base images, different filesystem paths, testing burden. Linux containers work on Windows via Docker Desktop (WSL2). No compelling reason for native Windows containers. | Linux containers only. Works on Windows, Mac, Linux via Docker. Standard approach in 2026. |

## Feature Dependencies

```
Single Docker Image
    └──requires──> Frontend Build (Vite) → Static Files
                       └──bundled into──> Backend JAR (Quarkus)
    └──requires──> Python Runtime + PySpark Installation
    └──requires──> Multi-Stage Dockerfile
                       ├──Stage 1: Node.js (frontend build)
                       ├──Stage 2: Gradle (backend build)
                       └──Stage 3: JDK + Python (runtime)

Frontend Served by Backend
    └──requires──> Frontend Build (npm run build → dist/)
    └──requires──> Copy dist/ to src/main/resources/META-INF/resources/
    └──optional──> Quinoa Extension (automates above)

Integration Test
    └──requires──> Docker Image Built
    └──requires──> Testcontainers Dependency
    └──requires──> Spark Connect Image Available
    └──requires──> REST API Endpoints (existing)
    └──validates──> Frontend Bundling + Python Execution + Spark Integration

Health Check
    └──requires──> smallrye-health Extension (existing)
    └──requires──> HEALTHCHECK Instruction in Dockerfile

Docker Compose
    └──requires──> Docker Image Built
    └──requires──> Spark Connect Image (apache/spark:4.0.2)
    └──requires──> PostgreSQL Image (postgres:16)

Optimized Layering
    └──requires──> Quarkus Fast-Jar Mode (default)
    └──optional──> .dockerignore to Exclude Build Artifacts
```

### Dependency Notes

- **Frontend bundling is prerequisite:** Docker image can't be built without frontend compiled to static files. Build order: frontend → backend → Docker.
- **Python + JDK in same image:** Requires either layering Python on JDK base OR using multi-runtime base image. Community images exist (rappdw/docker-java-python) but uncommon. Typically: start with JDK image, install Python via apt/yum.
- **Integration test depends on built image:** Can't test until image exists. Test runs in CI after `docker build` completes. Uses Testcontainers to manage lifecycle.
- **Quinoa simplifies but isn't required:** Manual approach works (Gradle task copies files). Quinoa adds convenience (auto-detection, dev mode proxy) at cost of build dependency.

## MVP Definition

### Launch With (v1.1)

Minimum viable product — what's needed to prove Docker distribution works.

- [ ] **Vite production build integrated** — `npm run build` produces `dist/` folder with bundled React app. Build command in `frontend/package.json`. Output: `dist/index.html` + `dist/assets/*.js` + `dist/assets/*.css`.
- [ ] **Static files served by Quarkus** — Copy Vite output to `backend/src/main/resources/META-INF/resources/`. Quarkus serves at root `/`. Verify with `curl http://localhost:8080/` returning HTML.
- [ ] **Multi-stage Dockerfile** — Stage 1: `node:22-alpine` for frontend build. Stage 2: `gradle:8-jdk25` for backend build. Stage 3: `eclipse-temurin:25-jre` + Python 3.11 for runtime. Copy artifacts from previous stages.
- [ ] **Python + PySpark in Docker image** — Install Python 3.11, pip, PySpark 4.x in runtime stage. Validate with `/opt/python/bin/python --version` and `pip list | grep pyspark`.
- [ ] **Health check endpoint** — Quarkus smallrye-health provides `/q/health/live` and `/q/health/ready`. HEALTHCHECK in Dockerfile uses `/q/health/live` every 30s.
- [ ] **Docker Compose for full stack** — `docker-compose.yml` with atadflow, spark-connect, postgres services. Health checks enforce startup order. `docker compose up` starts entire stack.
- [ ] **Integration test: flow submission** — Testcontainers spins up app + Spark Connect. Test creates flow via REST API, submits job, polls status, verifies SUCCEEDED. Proves end-to-end: frontend bundled, Python executes, Spark connects.

### Add After Validation (v1.x)

Features to add once core distribution is validated.

- [ ] **Optimized Docker layering** — Separate Gradle dependencies from app code. Use `COPY backend/build/quarkus-app/lib /deployments/lib` then `COPY backend/build/quarkus-app/*.jar /deployments/`. Docker caches dependencies layer. Trigger: Build times slow in CI.
- [ ] **Distroless base image** — Switch from eclipse-temurin to quay.io/quarkus/quarkus-distroless-image:2.0. Reduces attack surface. Trigger: Security audit or compliance requirement.
- [ ] **Quinoa auto-bundling** — Replace manual Gradle copy task with Quinoa extension. `./gradlew addExtension --extensions="io.quarkiverse.quinoa:quarkus-quinoa"`. Config in `application.properties`. Trigger: Adding more frontend frameworks or frequent frontend changes.
- [ ] **Smoke test in CI** — GitHub Actions step: build image, `docker run -d`, wait 30s, `curl /q/health`, verify 200, `docker stop`. Catch broken images before merge. Trigger: First broken image ships to staging.
- [ ] **Environment config examples** — Document overriding DB URL, Spark Connect URL via ENV vars. Example `docker-compose.override.yml` for different environments. Trigger: User question about configuration.
- [ ] **Integration test: cancellation flow** — Extend test to submit job, wait for RUNNING, cancel, verify CANCELLED. Proves cancellation works in Docker. Trigger: Cancellation feature used more.

### Future Consideration (v2+)

Features to defer until more complex deployment scenarios emerge.

- [ ] **Kubernetes manifests** — Deployment YAML for K8s with ConfigMaps, Secrets, resource limits. Why defer: No K8s requirement yet. Docker Compose sufficient for target users.
- [ ] **Multi-architecture builds** — Support arm64 (Apple Silicon, AWS Graviton) in addition to amd64. Why defer: Build complexity (QEMU, multi-platform), unclear demand. Most users on amd64.
- [ ] **Image scanning in CI** — Trivy or Snyk to detect vulnerabilities. Why defer: Security important but not blocking launch. Add when compliance matters.
- [ ] **Graceful shutdown handling** — SIGTERM handling to drain connections before exit. Why defer: Quarkus handles this reasonably. Enhance when long-running requests become issue.

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Vite build integration | HIGH | LOW | P1 |
| Static files via Quarkus | HIGH | LOW | P1 |
| Multi-stage Dockerfile | HIGH | MEDIUM | P1 |
| Python + PySpark in image | HIGH | MEDIUM | P1 |
| Health check endpoint | MEDIUM | LOW | P1 |
| Docker Compose for stack | HIGH | LOW | P1 |
| Integration test: submission | HIGH | MEDIUM | P1 |
| Optimized layering | MEDIUM | LOW | P2 |
| Distroless base | LOW | MEDIUM | P2 |
| Quinoa auto-bundling | MEDIUM | MEDIUM | P2 |
| Smoke test in CI | MEDIUM | LOW | P2 |
| Environment config docs | MEDIUM | LOW | P2 |
| Integration test: cancellation | MEDIUM | LOW | P2 |
| Kubernetes manifests | LOW | HIGH | P3 |
| Multi-architecture builds | LOW | HIGH | P3 |
| Image scanning | MEDIUM | MEDIUM | P3 |
| Graceful shutdown | LOW | MEDIUM | P3 |

**Priority key:**
- P1: Must have for launch — image builds, runs, integration test passes
- P2: Should have, add when core stable — optimization and convenience
- P3: Nice to have, future consideration — advanced deployment scenarios

## Implementation Details

### Multi-Stage Dockerfile Structure

Based on research, the recommended structure for Java+JS+Python:

```dockerfile
# Stage 1: Frontend Build
FROM node:22-alpine AS frontend-build
WORKDIR /build
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build
# Output: /build/dist/

# Stage 2: Backend Build
FROM gradle:8-jdk25 AS backend-build
WORKDIR /build
COPY backend/gradle/ gradle/
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/src/ src/
# Copy frontend build output to resources
COPY --from=frontend-build /build/dist/ src/main/resources/META-INF/resources/
RUN ./gradlew build -x test --no-daemon
# Output: /build/build/quarkus-app/

# Stage 3: Runtime
FROM eclipse-temurin:25-jre
# Install Python
RUN apt-get update && \
    apt-get install -y python3.11 python3-pip && \
    rm -rf /var/lib/apt/lists/*
# Install PySpark
RUN pip3 install --no-cache-dir pyspark==4.0.2
# Copy Quarkus app
WORKDIR /deployments
COPY --from=backend-build /build/build/quarkus-app/ ./
# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/q/health/live || exit 1
EXPOSE 8080
CMD ["java", "-jar", "quarkus-run.jar"]
```

**Key points:**

- **Stage 1 uses Alpine:** Minimal Node.js image. `npm ci` for reproducible builds.
- **Stage 2 uses Gradle image:** Caches Gradle dependencies. `COPY --from=frontend-build` merges frontend into backend.
- **Stage 3 uses JRE not JDK:** Smaller runtime. Install Python via apt (Debian-based). Quarkus fast-jar layout: `quarkus-app/quarkus-run.jar` + `quarkus-app/lib/` + `quarkus-app/app/`.
- **Health check uses curl:** Available in eclipse-temurin. Check `/q/health/live` (liveness, not readiness). 60s start period accommodates slow startup.

**Size estimate:** ~400-500MB (JRE ~200MB, Python ~150MB, Quarkus app ~50MB, dependencies ~100MB).

**Build time estimate:** ~2-4 minutes (frontend 30s, backend 90s, Docker layers 60s).

### Vite Build Configuration

Ensure correct base path for serving from backend:

```typescript
// frontend/vite.config.ts
export default defineConfig({
  base: '/', // Served from root, not /assets/
  build: {
    outDir: 'dist',
    assetsDir: 'assets', // JS/CSS in /assets/ subfolder
    emptyOutDir: true
  }
})
```

**Backend serves:**

- `/` → `index.html` (SPA entry point)
- `/assets/*` → JS/CSS bundles
- `/api/*` → REST endpoints (Quarkus)

**Routing:** React Router uses browser history. Backend must return `index.html` for unknown paths (SPA fallback). Quarkus doesn't do this by default — add filter or configure.

### Integration Test Example

Using Testcontainers with custom Docker image:

```java
@QuarkusIntegrationTest
public class DockerIntegrationTest {

    @Container
    static GenericContainer<?> sparkConnect = new GenericContainer<>("apache/spark:4.0.2")
        .withCommand("./sbin/start-connect-server.sh")
        .withExposedPorts(15002)
        .waitingFor(Wait.forLogMessage(".*Spark Connect server started.*", 1));

    @Test
    void testFlowSubmissionInDocker() {
        // App image runs via @QuarkusIntegrationTest (uses built Docker image)
        // Spark Connect runs via Testcontainers

        // Create flow via REST API
        String flowJson = """
            {
              "name": "Test Flow",
              "nodes": [...],
              "edges": [...]
            }
        """;

        int flowId = given()
            .contentType("application/json")
            .body(flowJson)
            .when().post("/api/flows")
            .then().statusCode(201)
            .extract().path("id");

        // Submit job
        int jobId = given()
            .when().post("/api/flows/" + flowId + "/submit")
            .then().statusCode(200)
            .extract().path("id");

        // Poll status (max 60s)
        await().atMost(60, SECONDS).until(() -> {
            String status = given()
                .when().get("/api/jobs/" + jobId)
                .then().statusCode(200)
                .extract().path("status");
            return "SUCCEEDED".equals(status);
        });

        // Verify final state
        given()
            .when().get("/api/jobs/" + jobId)
            .then()
            .statusCode(200)
            .body("status", equalTo("SUCCEEDED"))
            .body("sparkAppId", notNullValue());
    }
}
```

**What this validates:**

- ✓ Frontend bundled (UI accessible at `/`)
- ✓ Backend API works (`/api/*` endpoints respond)
- ✓ Python execution (job transitions to RUNNING)
- ✓ Spark Connect integration (job completes, returns sparkAppId)
- ✓ Database persistence (status transitions recorded)

**Runtime:** ~30-60 seconds (Docker startup dominant).

### Docker Compose Configuration

Full stack for local development/testing:

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: atadflow
      POSTGRES_USER: atadflow
      POSTGRES_PASSWORD: atadflow
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U atadflow"]
      interval: 10s
      timeout: 5s
      retries: 5

  spark-connect:
    image: apache/spark:4.0.2
    command: ./sbin/start-connect-server.sh
    ports:
      - "15002:15002"  # Spark Connect
      - "4040:4040"    # Spark UI
    environment:
      SPARK_NO_DAEMONIZE: "true"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:4040"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  atadflow:
    image: atadflow:latest
    build:
      context: .
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    environment:
      QUARKUS_DATASOURCE_JDBC_URL: jdbc:postgresql://postgres:5432/atadflow
      ATADFLOW_SPARK_CONNECT_URL: sc://spark-connect:15002
    depends_on:
      postgres:
        condition: service_healthy
      spark-connect:
        condition: service_healthy
```

**Usage:**

```bash
docker compose build           # Build atadflow image
docker compose up -d           # Start all services
docker compose logs -f atadflow  # Watch logs
docker compose down            # Stop all services
```

**Startup order:** postgres → spark-connect → atadflow (enforced by health checks).

### Quinoa Alternative (Optional)

If choosing Quinoa over manual integration:

**1. Add extension:**

```bash
cd backend
./gradlew addExtension --extensions="io.quarkiverse.quinoa:quarkus-quinoa"
```

**2. Configure in `application.properties`:**

```properties
# Quinoa auto-detects package.json in ../frontend
quarkus.quinoa.ui-dir=../frontend
quarkus.quinoa.build-dir=dist

# Dev mode: proxy to Vite
quarkus.quinoa.dev-server.port=5173

# Prod mode: bundle into JAR
quarkus.quinoa.package-manager-install=true
quarkus.quinoa.package-manager-command.install=npm ci
quarkus.quinoa.package-manager-command.build=npm run build
```

**3. Dockerfile simplifies (no manual copy):**

```dockerfile
# Stage 2: Backend Build (Quinoa handles frontend)
FROM gradle:8-jdk25 AS backend-build
WORKDIR /build
# Copy BOTH backend and frontend (Quinoa needs frontend/)
COPY backend/ backend/
COPY frontend/ frontend/
WORKDIR /build/backend
RUN ./gradlew build -x test --no-daemon
# Quinoa auto-bundles frontend into quarkus-app/
```

**Trade-offs:**

- **Pro:** Zero manual copying, dev mode proxy works, auto-detects React/Vite
- **Con:** Adds build dependency, slower builds (~30-60s overhead), Gradle-specific config

**Recommendation:** Start without Quinoa (manual copy simple). Add later if managing multiple frontend frameworks or frequent changes.

## Complexity Analysis

### LOW Complexity Features

- **Frontend served by Quarkus:** Copy files to `META-INF/resources/`. Quarkus serves automatically.
- **Health check endpoint:** smallrye-health already included. Add HEALTHCHECK to Dockerfile.
- **Docker Compose:** Template available. 3 services, health checks, env vars. ~50 lines YAML.
- **Environment-based config:** Quarkus supports ENV var overrides out-of-box.
- **Smoke test in CI:** Single curl command after `docker run`. ~10 lines script.

### MEDIUM Complexity Features

- **Multi-stage Dockerfile:** 3 stages, copy artifacts between stages, install Python. ~50 lines. Standard pattern.
- **Python + PySpark in image:** Install via apt + pip. Version pinning. ~10 lines in Dockerfile.
- **Integration test:** Testcontainers setup, REST API calls, polling logic. ~100 lines Java. Standard QuarkusIntegrationTest.
- **Optimized layering:** Separate lib/ from app/ in COPY commands. Understand Quarkus fast-jar layout. Marginal improvement.
- **Quinoa integration:** Add extension, configure properties, adjust Dockerfile. ~30 min setup. Build time impact.
- **Distroless base:** Switch base image, ensure app self-contained (no shell dependencies). Test thoroughly.

### HIGH Complexity Features

- None in P1/P2 scope. All P3 features deferred (K8s, multi-arch, image scanning).

## Known Limitations

Based on research:

1. **Python version fixed in Dockerfile:** Changing Python version requires rebuilding image. Not runtime-configurable. Users needing different Python must fork Dockerfile.

2. **Frontend routing requires SPA fallback:** Quarkus doesn't provide SPA fallback by default. Need custom filter to return `index.html` for non-API paths. Common pattern but not automatic.

3. **Image size ~400-500MB:** JRE + Python + dependencies. Optimizations possible (distroless, Alpine) but diminishing returns. Smaller than separate images (frontend + backend + Python = ~600MB).

4. **PySpark subprocess security:** Container runs subprocess as same user (root by default). Consider non-root user in production. Quarkus supports `USER 1001` in Dockerfile.

5. **Testcontainers requires Docker socket:** Integration tests need Docker access. CI environment must provide `/var/run/docker.sock` or Docker-in-Docker. GitHub Actions supports natively.

6. **Spark Connect external dependency:** App image doesn't include Spark Connect. Separate service required (Docker Compose or external cluster). Intentional design.

## Sources

**Quarkus Frontend Integration:**
- [Quarkus for the Web - Quarkus](https://quarkus.io/guides/web)
- [Quarkus Quinoa - Getting Started :: Quarkiverse Documentation](https://docs.quarkiverse.io/quarkus-quinoa/dev/index.html)
- [Quarkus Quinoa - Web Frameworks :: Quarkiverse Documentation](https://docs.quarkiverse.io/quarkus-quinoa/dev/web-frameworks.html)
- [React + Quarkus integration using Maven - Marc Nuri](https://blog.marcnuri.com/react-quarkus-integration-using-maven)
- [Build, run and deploy React app with Quarkus | by Dmytro Chaban | Quarkify | Medium](https://medium.com/quarkify/build-run-and-deploy-react-app-with-quarkus-6cc4f6074d6)

**Docker Multi-Stage Builds:**
- [Multi-stage builds | Docker Docs](https://docs.docker.com/get-started/docker-concepts/building-images/multi-stage-builds/)
- [Multi-Stage Docker Builds: Smaller Images, Faster Deployments, and Safer Runtime Containers](https://www.cleanstart.com/guide/multi-stage-build)
- [Multi-stage | Docker Docs](https://docs.docker.com/build/building/multi-stage/)

**Quarkus Docker Best Practices:**
- [Build Quarkus Microservices with Docker - Production Guide | Codez Up](https://codezup.com/quarkus-docker-microservice-production/)
- [From Source to Container: Building Efficient Quarkus Docker Images | My (Work)Space)](https://blog.popescul.com/posts/2023/05/05/from-source-to-container-building-efficient-quarkus-docker-images/)
- [Building a Native Executable - Quarkus](https://quarkus.io/guides/building-native-image)

**Vite Build Integration:**
- [Backend Integration | Vite](https://vite.dev/guide/backend-integration)
- [Building for Production | Vite](https://vite.dev/guide/build)
- [Build Options | Vite](https://vite.dev/config/build-options)

**Testcontainers:**
- [Testcontainers | Docker Docs](https://docs.docker.com/testcontainers/)
- [Getting Started - Testcontainers](https://testcontainers.com/getting-started/)
- [Creating images on-the-fly - Testcontainers for Java](https://java.testcontainers.org/features/creating_images/)
- [Testcontainers Best Practices | Docker](https://www.docker.com/blog/testcontainers-best-practices/)

**Docker Health Checks:**
- [How to Implement Docker Health Check Best Practices (2026)](https://oneuptime.com/blog/post/2026-01-30-docker-health-check-best-practices/view)
- [How to Use Docker Health Checks Effectively (2026)](https://oneuptime.com/blog/post/2026-01-23-docker-health-checks-effectively/view)
- [Docker Health Check: A Practical Guide - Lumigo](https://lumigo.io/container-monitoring/docker-health-check-a-practical-guide/)

**Mixed Java/Python Runtime:**
- [rappdw/docker-java-python - Docker Image](https://hub.docker.com/r/rappdw/docker-java-python)
- [GitHub - chrsep/alpine-java-python: Docker image containing java and python](https://github.com/chrsep/alpine-java-python)
- [Docker and Containers 2026: Python Containerization and the Cloud-Native Tipping Point | Programming Helper Tech](https://www.programming-helper.com/tech/docker-containers-2026-python-containerization-cloud-native)

**PySpark in Docker:**
- [Tutorial: Running PySpark inside Docker containers | Spot.io](https://spot.io/blog/tutorial-running-pyspark-inside-docker-containers/)
- [How to Run Apache Spark in Docker (2026)](https://oneuptime.com/blog/post/2026-02-08-how-to-run-apache-spark-in-docker/view)

**Docker Image Optimization:**
- [Using Alpine, Distroless, and Multi-Stage Builds for Smaller Docker Images (2026)](https://oneuptime.com/blog/post/2026-01-16-docker-reduce-image-size/view)
- [Reducing Docker Image Sizes: From 1.2GB to 150MB (or less) | Better Stack Community](https://betterstack.com/community/guides/scaling-docker/reducing-docker-image-size/)

**Confidence Assessment:**
- HIGH confidence: Multi-stage Docker patterns, Quarkus static resource serving, Vite build output, Testcontainers usage, health check patterns
- MEDIUM confidence: Quinoa with Gradle (less documented than Maven), optimal Python installation method (apt vs multi-runtime base), integration test complexity estimates, SPA routing fallback specifics
- LOW confidence: Exact image size predictions (depends on dependencies), build time estimates (hardware-dependent), distroless compatibility with subprocess patterns

---
*Feature research for: Self-contained Docker distribution (Java + JS + Python)*
*Researched: 2026-02-12*
