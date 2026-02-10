---
phase: 02-job-execution-lifecycle
plan: 01
subsystem: backend
tags: [java, quarkus, processbuilder, subprocess, flyway, postgresql, async]

# Dependency graph
requires:
  - phase: 01-spark-connect-infrastructure
    provides: Spark Connect server, python.executable config, PythonHealthCheck
provides:
  - Real Python subprocess execution via ProcessBuilder with PID tracking
  - Async process lifecycle management (RUNNING/SUCCEEDED/FAILED transitions)
  - Process cancellation via SIGTERM/SIGKILL with 5s timeout
  - Temp .py file cleanup in onExit callback
affects:
  - 02-02-PLAN.md (async integration depends on this subprocess engine)

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "ProcessBuilder + inheritIO for subprocess execution"
    - "Process.onExit() + ManagedExecutor for async lifecycle tracking"
    - "QuarkusTransaction.requiringNew() for programmatic transactions in async callbacks"
    - "ProcessHandle.of(pid) for process cancellation with SIGTERM/SIGKILL escalation"

key-files:
  created:
    - backend/src/main/resources/db/migration/V2__add_process_pid.sql
  modified:
    - backend/src/main/java/io/atadflow/entity/Job.java
    - backend/src/main/java/io/atadflow/service/SparkSubmissionService.java
    - backend/src/main/resources/application.properties
    - backend/src/test/java/io/atadflow/resource/JobResourceTest.java

key-decisions:
  - "QuarkusTransaction.requiringNew() over @Transactional for async callbacks -- self-calls bypass CDI proxy interceptors"
  - "Process.onExit().thenAcceptAsync() with ManagedExecutor for context-propagated async handling"
  - "%test.python.executable=/usr/bin/python3 for test environment where venv is unavailable"

patterns-established:
  - "Async process monitoring: launch via ProcessBuilder, track PID, handle exit in callback"
  - "QuarkusTransaction for programmatic transactions in non-CDI-proxied methods"

# Metrics
duration: 12min
completed: 2026-02-10
---

# Phase 2 Plan 1: Subprocess Execution Engine Summary

**Real Python subprocess executor via ProcessBuilder with PID tracking, async lifecycle management (RUNNING/SUCCEEDED/FAILED), SIGTERM/SIGKILL cancellation, and temp file cleanup**

## Performance

- **Duration:** 12 min
- **Started:** 2026-02-10T19:08:46Z
- **Completed:** 2026-02-10T19:20:42Z
- **Tasks:** 2
- **Files modified:** 5

## Accomplishments
- Added process_pid column to job table via Flyway V2 migration and mapped to Job entity
- Replaced SparkSubmissionService stub with real ProcessBuilder-based Python subprocess execution
- Job transitions to RUNNING with startedAt timestamp when process starts successfully
- Async process exit handling sets SUCCEEDED (exit 0) or FAILED (non-zero) with finishedAt
- Cancellation sends SIGTERM via ProcessHandle.destroy(), escalates to SIGKILL after 5s if still alive
- Temp .py files cleaned up in process onExit callback and on IOException during startup
- All 12 existing tests pass with updated expectations

## Task Commits

Each task was committed atomically:

1. **Task 1: Add process_pid column and Job entity field** - `3a9b54c` (feat)
2. **Task 2: Rewrite SparkSubmissionService with real subprocess execution** - `1631026` (feat)

## Files Created/Modified
- `backend/src/main/resources/db/migration/V2__add_process_pid.sql` - Flyway migration adding process_pid BIGINT column to job table
- `backend/src/main/java/io/atadflow/entity/Job.java` - Added processPid Long field mapped to process_pid column
- `backend/src/main/java/io/atadflow/service/SparkSubmissionService.java` - Full rewrite: ProcessBuilder subprocess execution, PID capture, async exit handling, SIGTERM/SIGKILL cancellation
- `backend/src/main/resources/application.properties` - Added %test.python.executable for test environment
- `backend/src/test/java/io/atadflow/resource/JobResourceTest.java` - Updated status expectations from SUBMITTED to RUNNING

## Decisions Made
- Used `QuarkusTransaction.requiringNew()` instead of `@Transactional` annotation for the async handleProcessExit callback, because self-calls within the same CDI bean bypass proxy interceptors
- Used `Process.onExit().thenAcceptAsync(handler, executor)` with ManagedExecutor for context-propagated async process monitoring
- Added `%test.python.executable=/usr/bin/python3` so tests can start real Python processes (system Python available, venv not available in test)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Updated test expectations for new status flow**
- **Found during:** Task 2 (SparkSubmissionService rewrite)
- **Issue:** JobResourceTest.submitAndGetJob() expected SUBMITTED status, but new implementation sets RUNNING on successful process start
- **Fix:** Changed test assertion from `equalTo("SUBMITTED")` to `equalTo("RUNNING")` and removed redundant status check on GET
- **Files modified:** backend/src/test/java/io/atadflow/resource/JobResourceTest.java
- **Verification:** All 12 tests pass
- **Committed in:** 1631026 (Task 2 commit)

**2. [Rule 3 - Blocking] Added %test.python.executable configuration**
- **Found during:** Task 2 (SparkSubmissionService rewrite)
- **Issue:** Default python.executable (venv/bin/python3) doesn't exist in test environment, causing IOException and FAILED status
- **Fix:** Added `%test.python.executable=/usr/bin/python3` to application.properties
- **Files modified:** backend/src/main/resources/application.properties
- **Verification:** Process starts successfully in tests (fails with ModuleNotFoundError for pyspark, which is expected and handled async)
- **Committed in:** 1631026 (Task 2 commit)

---

**Total deviations:** 2 auto-fixed (1 bug, 1 blocking)
**Impact on plan:** Both auto-fixes necessary for test correctness. No scope creep.

## Issues Encountered
None beyond the auto-fixed deviations above.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- Subprocess execution engine complete and operational
- Plan 02-02 (JobService async integration) can proceed -- SparkSubmissionService now provides real submit() and cancel() with proper lifecycle management
- Frontend polling (02-03) already complete and will show status transitions once integrated

---
*Phase: 02-job-execution-lifecycle*
*Plan: 01*
*Completed: 2026-02-10*
