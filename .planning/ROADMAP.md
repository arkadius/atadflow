# Roadmap: Atadflow v1.0 — Minimal Spark Execution

**Created:** 2026-02-08
**Milestone:** v1.0 — Minimal Spark Execution
**Phases:** 2
**Requirements:** 8

## Phase 1: Spark Connect Infrastructure

**Goal:** Spark Connect server running in Docker, code generation produces valid Spark Connect PySpark, Python+PySpark availability verified on startup.

**Requirements:** INFRA-01, INFRA-02, INFRA-03

**Plans:** 2 plans

Plans:
- [ ] 01-01-PLAN.md — Spark Connect server in Docker + configuration
- [ ] 01-02-PLAN.md — Code generation update + Python health check

**Success Criteria:**
1. `docker compose up` starts Spark Connect server, port 15002 reachable from host
2. CodeGenerationService generates PySpark using `SparkSession.builder.remote("sc://...")` instead of local `.appName()`
3. Backend startup logs confirm Python and PySpark are available (or fails fast with clear error)
4. Manually running generated PySpark code via `python script.py` executes against Spark Connect server

**Key Changes:**
- `docker-compose.yml` — Add Spark Connect server service (apache/spark image, ports 15002 + 4040)
- `CodeGenerationService.java` — Change SparkSession builder from `.appName(name).getOrCreate()` to `.builder.remote("sc://...").getOrCreate()`
- New: `PythonHealthCheck.java` — Quarkus startup health check verifying `python --version` and `pyspark --version`
- `application.properties` — Add `spark.connect.url` config property

## Phase 2: Job Execution & Lifecycle

**Goal:** User submits a flow, backend executes PySpark as subprocess, status transitions visible in UI, cancellation works.

**Requirements:** SUB-01, SUB-02, LIFE-01, LIFE-02, LIFE-03

**Success Criteria:**
1. User clicks "Run" on a rate→console flow and sees job status change from PENDING to RUNNING
2. Streaming job runs until user clicks "Cancel", which transitions to CANCELLED
3. If Python process exits with error, job transitions to FAILED
4. Frontend automatically refreshes job status every 5 seconds for active jobs
5. Temp .py files are cleaned up after job starts (or on failure)

**Key Changes:**
- `SparkSubmissionService.java` — Replace stub: write code to temp file, launch `python script.py` via ProcessBuilder, capture PID, monitor process
- `JobService.java` — Make submitJob() async via ManagedExecutor, add status polling on getJob()
- `Job.java` entity — Add `processPid` (Long) field for process tracking
- New: Flyway migration `V2__add_process_pid.sql` — `ALTER TABLE job ADD COLUMN process_pid BIGINT`
- `useJobs.ts` hook — Add polling interval (5s) when any job is PENDING/SUBMITTED/RUNNING
- `application.properties` — Add `spark.connect.url`, `spark.python.executable` config

## Requirement Coverage

| Requirement | Phase | Description |
|-------------|-------|-------------|
| INFRA-01 | Phase 1 | Docker Compose Spark Connect server |
| INFRA-02 | Phase 1 | CodeGeneration with remote SparkSession |
| INFRA-03 | Phase 1 | Python availability check |
| SUB-01 | Phase 2 | Async Python subprocess execution |
| SUB-02 | Phase 2 | Temp file write + cleanup |
| LIFE-01 | Phase 2 | Status transitions (PENDING → RUNNING → COMPLETED/FAILED) |
| LIFE-02 | Phase 2 | Cancel via process kill |     
| LIFE-03 | Phase 2 | Frontend polls every 5s |

**Coverage:** 8/8 requirements mapped ✓

## Dependencies

```
Phase 1 (Infrastructure)
    └── Phase 2 (Execution) — requires Spark Connect running + correct code generation
```

---
*Roadmap created: 2026-02-08*
*Last updated: 2026-02-08 after Phase 1 planning*
