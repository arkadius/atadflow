# Project Research Summary

**Project:** Atadflow - Streaming Flow Designer with Spark Execution
**Domain:** Visual streaming data flow designer with Apache Spark backend execution
**Researched:** 2026-02-08
**Confidence:** MEDIUM

## Executive Summary

Atadflow integrates Spark streaming execution into an existing Java/Quarkus visual flow designer. The recommended approach is **Python subprocess execution with Spark Connect server**: the Java backend generates PySpark code, writes it to a temporary file, and executes it via ProcessBuilder (`python script.py`). The PySpark script itself connects to a Spark Connect server running in Docker via `SparkSession.builder.remote("sc://host:15002")`. This hybrid approach avoids both the complexity of Spark REST submission API (which requires spark-submit and cluster deployment) and the limitations of Java Spark Connect clients (which don't exist for PySpark code generation scenarios). Status tracking monitors the Python process state plus optional polling of Spark's REST API for detailed metrics. The minimal viable flow is rate source → console sink with basic lifecycle management (PENDING → RUNNING → COMPLETED/FAILED).

The primary risk is managing the Python subprocess lifecycle correctly: graceful shutdown, checkpoint recovery, and orphaned process cleanup require careful design. The backend must track process PIDs, implement signal handling, and ensure temporary files are cleaned up. Quarkus async patterns (ManagedExecutor) prevent blocking I/O threads during subprocess execution. Docker networking between backend, Python environment, and Spark Connect server needs explicit configuration. Research identified 11 critical pitfalls, most addressable in Phase 1 if approached systematically.

Version compatibility is straightforward: use Spark 4.0.2 (latest stable), ensure PySpark version on backend host matches Spark Connect server version, and pin all versions explicitly. The existing code generation service already produces valid PySpark DataFrame API code, so no major refactoring is required — only integration glue and status tracking infrastructure.

## Key Findings

### Recommended Stack

The chosen architecture uses **Python subprocess execution** rather than the Spark REST Submission API recommended by one researcher or the Spark Connect Java client approach. The Java backend does not need Spark client libraries — it only needs Python + PySpark installed on the host (initially; later milestone will Dockerize this).

**Core technologies:**
- **Apache Spark Connect Server (Docker)**: Spark 4.0.2 running in standalone mode, exposing port 15002 for Spark Connect and 4040 for REST API monitoring — provides the execution environment for PySpark scripts
- **Python 3.x + PySpark 4.0.2 (backend host)**: Installed on the same host as the Quarkus backend — enables subprocess execution of generated PySpark scripts
- **ProcessBuilder (JDK)**: Executes `python script.py` as subprocess — provides process control (start, monitor, kill)
- **Quarkus ManagedExecutor**: Async subprocess execution without blocking I/O threads — prevents backend slowdowns during job submission
- **Spark REST API (optional)**: Port 4040 for detailed metrics polling — enhances status tracking beyond process exit codes

**Infrastructure:**
- **Docker Compose**: Spark Connect server + master + worker(s) — dev/test environment
- **Quarkus REST Client (optional)**: For Spark REST API polling if detailed metrics needed — not required for MVP

**What NOT to use:**
- Spark REST Submission API (port 6066): Requires spark-submit, not chosen
- Spark Connect Java client: Doesn't support submitting arbitrary PySpark code
- Apache Livy: Doesn't support Spark 4.0 yet
- spark-submit via ProcessBuilder: Adds unnecessary indirection; Python subprocess is simpler

### Expected Features

**Must have (table stakes):**
- Submit generated PySpark code to Spark Connect — core capability, already have code generation
- Real-time status tracking (PENDING/RUNNING/COMPLETED/FAILED) — users need to see job state
- Job cancellation — kill the Python process, signal Spark to stop gracefully
- Display basic job metadata (ID, timestamps, flow reference) — minimum debugging visibility

**Should have (competitive advantage):**
- Basic error detection — capture stderr from Python process, surface errors in UI
- Graceful shutdown with timeout — configure Spark's stopGracefullyOnShutdown, prevent checkpoint corruption
- Job lifecycle audit trail — log state transitions for debugging and compliance

**Defer (v2+):**
- Progress metrics tracking (input rate, processing rate, batch latency) — requires polling Spark REST API `/streaming/statistics` endpoint, adds complexity
- Streaming Query Listener integration — push-based events instead of polling, requires event infrastructure
- Automatic retry on transient failures — complex failure classification logic
- Log streaming to UI — massive complexity, users can access Spark UI directly at port 4040

**Anti-features (commonly requested, problematic):**
- Synchronous job execution — streaming jobs run indefinitely, would block forever
- Custom Spark configuration per job — Spark Connect server has fixed config, not per-job tunable in client mode
- RDD-based operations — Spark Connect only supports DataFrame API

### Architecture Approach

The integration uses **async subprocess execution with process lifecycle management**. The backend generates PySpark code (already implemented), writes it to `/tmp/atadflow-{uuid}.py`, launches `python script.py` via ProcessBuilder on a ManagedExecutor worker thread, and immediately returns to the client with PENDING status. The Python script connects to Spark Connect server via `remote("sc://localhost:15002")` and starts the streaming query. The backend tracks the process PID and periodically polls process state (running/exited) to update job status. Cancellation sends SIGTERM to the Python process. Spark Connect server runs in Docker with exposed ports for gRPC (15002) and Web UI (4040).

**Major components:**
1. **JobService** — orchestrates async submission via ManagedExecutor, delegates to SparkSubmissionService
2. **SparkSubmissionService** — writes PySpark code to temp file, launches Python subprocess, captures PID, monitors process lifecycle
3. **Job entity** — extended with `processPid`, `lastPolledAt`, `errorOutput` fields for process tracking
4. **ProcessBuilder + Process API** — executes and monitors Python subprocess
5. **Spark Connect Server (Docker)** — executes PySpark code remotely, manages streaming queries
6. **Optional: SparkRestClient** — polls REST API for detailed metrics (deferred to Phase 2)

**Data flow:**
```
POST /api/jobs
  → JobService.submitJob()
  → CodeGenerationService.generateCode() (existing)
  → Job.persist() with status=PENDING
  → ManagedExecutor.runAsync(() => {
      SparkSubmissionService.submit(job, code)
        → Write code to /tmp/atadflow-{uuid}.py
        → ProcessBuilder.start("python", tmpFile)
        → Capture process.pid()
        → Update Job: status=RUNNING, processPid=pid
        → Delete temp file
    })
  → Return JobDto (PENDING)

GET /api/jobs/{id} (polling)
  → JobService.getJob(id)
  → if (status RUNNING) {
      SparkSubmissionService.pollStatus(job)
        → ProcessHandle.of(pid).isAlive() ?
        → if exited: read exitCode, update status
    }
  → Return JobDto with current status
```

**Patterns:**
- **Async fire-and-forget submission**: Return immediately, avoid blocking HTTP threads
- **Process lifecycle tracking**: Store PID, poll via ProcessHandle API
- **Transactional status updates**: Wrap polling in @Transactional to prevent lost updates
- **Temp file cleanup**: Delete after subprocess starts (Python holds file handle)

### Critical Pitfalls

1. **Python Dependency Mismatch (Client vs Server)** — PySpark version on backend host must match Spark Connect server version (4.0.2). If mismatch, connection fails with protocol errors. Prevention: Pin versions in both Dockerfile and backend host installation, document in README.

2. **Streaming Job Lifecycle - No Process Handle = No Control** — Must capture and store `process.pid()` immediately after ProcessBuilder.start(). Without PID, cannot monitor or kill the job. Prevention: Store PID in Job entity, implement ProcessHandle-based polling.

3. **Graceful Shutdown Complexity** — Sending SIGKILL instead of SIGTERM causes checkpoint corruption. Python process needs time to call `query.stop()` before exit. Prevention: Use `process.destroy()` (sends SIGTERM), configure `spark.sql.streaming.stopTimeout`, allow 30s+ before forceful kill.

4. **Checkpoint Directory Management** — Each flow execution needs unique checkpoint path: `/checkpoints/{flow_id}/{execution_id}`. Shared checkpoints cause state corruption. Prevention: Generate unique paths in code generation, use Docker volume for persistence.

5. **Quarkus I/O Thread Blocking** — ProcessBuilder calls block. Must annotate endpoints with `@Blocking` or wrap in ManagedExecutor. Prevention: Use ManagedExecutor.runAsync() for all subprocess operations, verify no "blocking on IO thread" warnings.

6. **Docker Networking** — Backend (host) must reach Spark Connect (Docker container) on port 15002. Python subprocess (host) must resolve connection string `sc://localhost:15002`. Prevention: Use `ports:` mapping in docker-compose, test connectivity before implementation.

7. **Orphaned Processes** — If backend crashes, Python processes continue running. On restart, no PID tracking. Prevention: Implement startup cleanup (query Spark REST API for active jobs, correlate with DB, kill orphans).

8. **Process Output Buffering** — If stderr is not consumed, buffer fills and Python process hangs. Prevention: Capture stderr in background thread, store in Job.errorOutput for later display.

## Implications for Roadmap

Based on research, suggested phase structure:

### Phase 1: Basic Spark Execution
**Rationale:** Establish foundational integration with minimal scope — prove the Python subprocess + Spark Connect approach works end-to-end before adding complexity.

**Delivers:**
- Docker Compose with Spark Connect server (port 15002, 4040)
- Python + PySpark installed on backend host (documented setup)
- SparkSubmissionService with subprocess execution
- Process lifecycle tracking (PID storage, isAlive polling)
- Status transitions: PENDING → RUNNING → COMPLETED/FAILED
- Minimal flow: rate source → console sink
- Job cancellation (SIGTERM to process)

**Addresses features:**
- Submit PySpark code to Spark Connect
- Real-time status tracking (basic)
- Job cancellation
- Basic error detection (exit code + stderr)

**Avoids pitfalls:**
- Python dependency mismatch — pin versions upfront
- No process handle — capture PID immediately
- Quarkus I/O blocking — use ManagedExecutor from start
- Docker networking — validate connectivity early
- Checkpoint isolation — implement unique paths from beginning

**Research needs:** None — standard patterns, well-documented APIs (ProcessBuilder, ProcessHandle, Spark Connect connection).

### Phase 2: Enhanced Status and Lifecycle
**Rationale:** Once basic execution works, add visibility and robustness without changing core architecture.

**Delivers:**
- Graceful shutdown with timeout (SIGTERM → wait → SIGKILL)
- Checkpoint recovery validation (stop → restart → verify state)
- Process output capture (stderr streaming to Job.errorOutput)
- Orphaned process cleanup on backend startup
- Job lifecycle audit log (state transition history)

**Addresses features:**
- Graceful shutdown with timeout
- Job lifecycle audit trail

**Avoids pitfalls:**
- Graceful shutdown complexity — configure stopGracefullyOnShutdown, test recovery
- Checkpoint corruption — validate restart-from-checkpoint scenarios
- Orphaned processes — startup cleanup routine
- Process output buffering — background thread consumes stderr

**Research needs:** None — Spark graceful shutdown and checkpoint recovery are documented patterns.

### Phase 3: Advanced Monitoring (Optional)
**Rationale:** Deferred until basic execution is stable and user feedback indicates need. Adds operational complexity.

**Delivers:**
- Spark REST API integration for detailed metrics
- Progress metrics tracking (input rate, processing rate, batch latency)
- Streaming query listener (push-based events)
- Performance optimization (connection pooling, caching)

**Addresses features:**
- Progress metrics tracking (differentiator)

**Implements:**
- SparkRestClient (Quarkus REST Client)
- Polling optimization (cache status with TTL)

**Avoids pitfalls:**
- Inefficient status polling — reduce frequency, add caching
- Resource cleanup — monitor memory usage if caching enabled

**Research needs:** Phase-level research recommended for Spark REST API details — endpoint schema, polling patterns, metric interpretation.

### Phase Ordering Rationale

- **Phase 1 first**: Validates the core architecture (Python subprocess + Spark Connect) with minimal surface area. If this approach fails, pivot early without wasted effort.
- **Phase 2 second**: Adds production-readiness (graceful shutdown, recovery) once happy path is proven. These features require the basic execution to exist.
- **Phase 3 deferred**: Optional enhancements that add complexity without changing fundamentals. Can be skipped if users don't need detailed metrics.
- **Dependency chain**: Phase 1 delivers the subprocess execution foundation that Phases 2-3 enhance. No circular dependencies.
- **Risk mitigation**: Front-loads the highest-risk pitfalls (process management, networking, checkpoints) into Phase 1 where they're easiest to address.

### Research Flags

**Phases likely needing deeper research during planning:**
- **Phase 3 (Advanced Monitoring)**: Spark REST API endpoint schema varies by version, metric interpretation requires domain knowledge, polling patterns need optimization guidance.

**Phases with standard patterns (skip research-phase):**
- **Phase 1 (Basic Spark Execution)**: ProcessBuilder, ProcessHandle, Docker Compose, Spark Connect connection string — all well-documented with official APIs.
- **Phase 2 (Enhanced Status and Lifecycle)**: Graceful shutdown patterns, checkpoint recovery, process cleanup — documented in Spark Structured Streaming guides.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | MEDIUM | Python subprocess approach is non-standard (researchers recommended different approaches), but technically sound. Lack of official examples for this pattern. |
| Features | HIGH | Spark Structured Streaming API well-documented, feature set clear from official docs and community examples. |
| Architecture | MEDIUM | ProcessBuilder + Spark Connect is straightforward, but lifecycle management (PID tracking, orphan cleanup) requires custom implementation. |
| Pitfalls | MEDIUM | 11 pitfalls identified with prevention strategies. Process management pitfalls are well-known (Unix signals, exit codes), Spark-specific ones documented. |

**Overall confidence:** MEDIUM

Research resolved conflicting recommendations by selecting the Python subprocess approach based on project constraints (no spark-submit available initially, avoid Java Spark client limitations). This approach is less common than REST submission or spark-submit, increasing implementation risk, but better aligned with current project state (Python installed, code generation complete, minimal dependencies).

### Gaps to Address

**During Phase 1 planning:**
- **Python installation method**: Document exact installation (pyenv, venv, system Python?) and PySpark version pinning
- **Temp file location**: Confirm `/tmp` is appropriate or use configurable directory (cleanup policies, disk space)
- **ProcessHandle polling frequency**: Determine optimal interval (5s? 10s?) based on responsiveness vs overhead
- **Error output size limits**: Define max stderr capture size to prevent memory issues from verbose Spark logs
- **Checkpoint storage**: Decide between Docker volume (dev) vs S3/cloud (prod), document migration path

**During Phase 2 planning:**
- **Graceful shutdown timeout**: Research optimal timeout based on typical batch intervals (10s × batch interval as starting point?)
- **Orphaned process detection**: Define heuristic for "this process belongs to a deleted job" (timestamp correlation?)

**During implementation:**
- **Spark Connect server resource limits**: Test with multiple concurrent jobs to determine when scaling is needed
- **Network reliability**: Validate Spark Connect connection resilience to transient network issues

## Sources

### Primary (HIGH confidence)
- [Spark Connect Overview - Spark 4.1.0 Documentation](https://spark.apache.org/docs/latest/spark-connect-overview.html) — confirmed Spark Connect protocol, DataFrame-only API, PySpark client support
- [Monitoring and Instrumentation - Spark 4.1.0 Documentation](https://spark.apache.org/docs/latest/monitoring.html) — REST API schema, status polling patterns
- [Submitting Applications - Spark 4.1.0 Documentation](https://spark.apache.org/docs/latest/submitting-applications.html) — spark-submit behavior, deployment modes
- [PySpark Quickstart: Spark Connect](https://spark.apache.org/docs/latest/api/python/getting_started/quickstart_connect.html) — connection string format, session builder API
- Java ProcessBuilder/ProcessHandle API (JDK 11+) — official Java docs

### Secondary (MEDIUM confidence)
- [Apache Spark News](https://spark.apache.org/news/) — Spark 4.0.2 release announcement (Feb 5, 2026)
- [apache/spark Docker Hub](https://hub.docker.com/r/apache/spark/) — official Docker images
- [Graceful shutdown for Spark Structured Streaming - Medium](https://medium.com/@grigor60/graceful-shutdown-for-spark-structured-streaming-why-is-it-complicated-and-how-can-it-be-done-bef2674b731c) — graceful shutdown patterns
- [The Ultimate Guide to Checkpoint Location - RisingWave](https://risingwave.com/blog/the-ultimate-guide-to-setting-checkpoint-location-in-spark-streaming/) — checkpoint isolation patterns
- [Quarkus Blog: RESTEasy Reactive - To block or not to block](https://quarkus.io/blog/resteasy-reactive-smart-dispatch/) — @Blocking vs async patterns

### Tertiary (LOW confidence, needs validation)
- Community examples of Spark Connect Docker Compose configurations — port mappings vary
- Medium articles on ProcessBuilder patterns — error handling approaches not tested in Quarkus context

### Conflicts Resolved

**Researcher disagreements:**
1. **STACK.md recommended**: Spark REST Submission API (port 6066) with spark-submit
2. **ARCHITECTURE.md recommended**: spark-submit via ProcessBuilder
3. **USER DECISION**: Python subprocess (no spark-submit) + Spark Connect server

**Resolution rationale:**
- Spark REST Submission API requires spark-submit binary in backend environment (not available initially)
- spark-submit via ProcessBuilder adds indirection (Quarkus → spark-submit → Python → Spark Connect)
- Direct Python subprocess execution is simpler: Quarkus → Python → Spark Connect
- Aligns with milestone constraint: "Python+PySpark installed on backend host"
- Spark Connect server handles all Spark cluster communication, backend just launches Python client

---
*Research completed: 2026-02-08*
*Ready for roadmap: yes*
