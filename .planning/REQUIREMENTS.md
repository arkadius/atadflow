# Requirements: Atadflow

**Defined:** 2026-02-08
**Core Value:** Users can visually design a streaming pipeline and execute it on Spark without writing code

## v1 Requirements

Requirements for milestone v1.0 — Minimal Spark Execution. Each maps to roadmap phases.

### Infrastructure

- [ ] **INFRA-01**: Spark Connect server runs in Docker Compose with correct port exposure (15002, 4040)
- [ ] **INFRA-02**: CodeGenerationService generates PySpark code using `SparkSession.builder.remote("sc://...")` for Spark Connect
- [ ] **INFRA-03**: Backend verifies Python and PySpark availability on startup and reports clear error if missing

### Submission

- [ ] **SUB-01**: User can submit a flow and backend executes generated PySpark code as async Python subprocess
- [ ] **SUB-02**: Backend writes generated PySpark code to temp file and cleans up after execution completes or fails

### Lifecycle

- [ ] **LIFE-01**: User can see job status transition from PENDING to RUNNING to COMPLETED or FAILED
- [ ] **LIFE-02**: User can cancel a running job which kills the Python subprocess
- [ ] **LIFE-03**: Frontend polls job status every 5 seconds for active jobs and stops polling when terminal

## Future Requirements

Deferred to future milestones. Tracked but not in current roadmap.

### Observability

- **OBS-01**: User can see stderr/error messages from failed jobs
- **OBS-02**: User can view Spark application metrics (input rate, processing rate)

### Production Hardening

- **PROD-01**: Backend+frontend Docker image with Python bundled
- **PROD-02**: Graceful streaming job shutdown (finish current batch before stopping)
- **PROD-03**: Checkpoint configuration for streaming job recovery
- **PROD-04**: Orphaned Python process cleanup on backend restart

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Log streaming to UI | High complexity (log aggregation, WebSocket), use Spark UI at port 4040 instead |
| Kafka sources/sinks | Rate source + console sufficient for minimal proof |
| Spark REST API polling | Process-based monitoring sufficient for MVP |
| Custom Spark config per job | Spark Connect server has fixed config, defer to future |
| Multi-user / authentication | Single-user tool for now |
| RDD-based operations | Spark Connect only supports DataFrame API |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| INFRA-01 | Phase 1 | Pending |
| INFRA-02 | Phase 1 | Pending |
| INFRA-03 | Phase 1 | Pending |
| SUB-01 | Phase 2 | Pending |
| SUB-02 | Phase 2 | Pending |
| LIFE-01 | Phase 2 | Pending |
| LIFE-02 | Phase 2 | Pending |
| LIFE-03 | Phase 2 | Pending |

**Coverage:**
- v1 requirements: 8 total
- Mapped to phases: 8
- Unmapped: 0 ✓

---
*Requirements defined: 2026-02-08*
*Last updated: 2026-02-08 after roadmap creation*
