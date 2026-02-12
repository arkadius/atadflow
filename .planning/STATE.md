# State: Atadflow

## Current Position

Phase: Phase 2 — Job Execution & Lifecycle (Complete)
Plan: 4/4 complete (02-01, 02-02, 02-03, 02-04)
Status: Phase complete with gap closure
Progress: ████████████ 100%
Last activity: 2026-02-12 — Completed 02-04-PLAN.md (Signal handler gap closure for graceful cancellation)

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-08)

**Core value:** Users can visually design a streaming pipeline and execute it on Spark without writing code.
**Current focus:** Milestone v1.0 — Minimal Spark Execution

## Accumulated Context

- Brownfield project — backend + frontend fully functional for flow design
- SparkSubmissionService rewritten with real ProcessBuilder subprocess execution
- 12 backend tests all passing
- CodeGenerationService produces valid PySpark using SparkSession.builder.remote()
- Architectural decision: Python subprocess + Spark Connect (not spark-submit, not REST API)
- Python+PySpark installed on host (Docker image with Python deferred to next milestone)
- Spark Connect server running in Docker (apache/spark:4.0.2, port 15002)
- PythonHealthCheck verifies Python+PySpark at startup, excluded from test profile
- python.executable configurable via application.properties (default: venv/bin/python3)
- useJobs hook now polls every 5s when active jobs exist (PENDING/SUBMITTED/RUNNING)
- Job entity has processPid field for process tracking (V2 migration)
- Process.onExit() + ManagedExecutor for async lifecycle tracking
- QuarkusTransaction.requiringNew() for transactions in async callbacks (CDI proxy bypass)
- Process cancellation: SIGTERM then SIGKILL after 5s via ProcessHandle
- JobService gracefully handles subprocess start failures (returns FAILED job, not 500 error)
- Tests environment-agnostic: work with or without Python/PySpark installed (anyOf RUNNING/FAILED)
- Generated Python code includes signal handlers for SIGTERM/SIGINT
- StreamingQuery references tracked in streaming_queries list for graceful shutdown
- Signal handlers call query.stop() on all queries before sys.exit(0)

## Decisions

| Phase | Decision | Rationale |
|-------|----------|-----------|
| 01-01 | apache/spark:4.0.2 image with start-connect-server.sh | Standard way to run Spark Connect server |
| 01-01 | spark.jars.ivy=/tmp/.ivy2 | Avoid permission issues in container |
| 01-02 | @UnlessBuildProfile("test") for health check | Testcontainers don't have Python |
| 01-02 | Configurable python.executable | Support venv and system Python |
| 01-02 | @Observes StartupEvent pattern | Allows @ConfigProperty injection |
| 02-01 | QuarkusTransaction.requiringNew() for async callbacks | Self-calls bypass CDI proxy interceptors |
| 02-01 | Process.onExit().thenAcceptAsync() with ManagedExecutor | Context-propagated async process monitoring |
| 02-01 | %test.python.executable=/usr/bin/python3 | Test environment lacks venv |
| 02-03 | useEffect cleanup pattern over useRef for intervals | Simpler lifecycle handling, automatic cleanup |
| 02-04 | Inject signal handlers in generated Python code | Python process needs direct SparkSession access for query.stop() |
| 02-04 | Track only write-stream nodes in streaming_queries | Only write-stream creates StreamingQuery objects needing shutdown |
| 02-04 | Register both SIGTERM and SIGINT handlers | Handle both cancel (SIGTERM) and user interrupt (SIGINT) scenarios |

## Session

Last session: 2026-02-12
Stopped at: Completed 02-04-PLAN.md (Signal handler gap closure)
Resume file: None
Remaining plans: None (Phase 02 complete with gap closure)
