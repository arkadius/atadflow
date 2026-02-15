# Milestones: Atadflow

## v1.1 Self-Contained Docker Distribution (Shipped: 2026-02-13)

**Delivered:** Single Docker image packaging backend + frontend + Python/PySpark runtime, with health monitoring and integration tests proving end-to-end deployment.

**Phases completed:** None (implemented in single commit outside GSD phase tracking)

**Key accomplishments:**

- Multi-stage Dockerfile: Node 22 → Gradle/JDK 25 → JRE 25 + Python 3.12 + PySpark (670MB)
- Frontend served by Quarkus (SPA routing via Vert.x filters, static assets in META-INF/resources/)
- Python dependencies bundled: pyspark[connect]==4.0.2, pandas, pyarrow, numpy
- Health checks: PythonLivenessCheck, /q/health/live, /q/health/ready
- Docker Compose: postgres → spark-connect → atadflow with health-based ordering
- DockerComposeIntegrationTest: frontend serving, health checks, API, job execution
- CORS scoped to %dev profile only

**Stats:**

- 12 files created/modified (897 insertions)
- 1 commit (implemented outside GSD)

**Git range:** `feat: implement v1.1 Self-Contained Docker Distribution`

**Last phase number:** 2

---

## v1.0 Minimal Spark Execution (Shipped: 2026-02-12)

**Delivered:** End-to-end streaming pipeline execution — users design flows visually and run them on Spark Connect with full job lifecycle management.

**Phases completed:** 1-2 (6 plans total)

**Key accomplishments:**

- Spark Connect server running in Docker Compose (apache/spark:4.0.2, port 15002)
- PySpark code generation using SparkSession.builder.remote() for Spark Connect
- Real subprocess execution via ProcessBuilder with PID tracking and async lifecycle
- Job status transitions (PENDING -> RUNNING -> SUCCEEDED/FAILED/CANCELLED)
- Frontend polls every 5s for active jobs with automatic cleanup
- Graceful cancellation via signal handlers calling spark.stop() on SIGTERM

**Stats:**

- 9 files created/modified (182 insertions)
- 1,556 lines Java + 1,051 lines TypeScript
- 2 phases, 6 plans, 15 commits
- 5 days from 2026-02-08 to 2026-02-12

**Git range:** `feat(01-01)` -> `fix(02-04)`

**Last phase number:** 2

---

## v0.1 — Initial Scaffold (pre-GSD)

**Shipped:** 2026-02-08 (single init commit)

**What shipped:**
- Flow CRUD API + PostgreSQL persistence
- Visual DAG editor (React Flow)
- 6 built-in node types with SPI
- PySpark code generation
- Job submission lifecycle (stub)
- Node configuration forms + custom code editor

**Phases:** 0 (pre-GSD, no phase tracking)
**Last phase number:** 0
