# Milestones: Atadflow

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
