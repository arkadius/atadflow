---
phase: 02-job-execution-lifecycle
plan: 04
subsystem: code-generation
tags: [signal-handling, graceful-shutdown, spark-streaming, gap-closure]
requires:
  - SparkSubmissionService with ProcessHandle.destroy() for SIGTERM
  - write-stream node type with query variable generation
provides:
  - Python signal handlers for SIGTERM/SIGINT in generated code
  - Streaming query tracking and graceful shutdown
  - Generated code that stops Spark queries on cancellation
affects:
  - CodeGenerationService.generateCode()
  - FlowResourceTest.generateCode()
tech-stack:
  added: [signal-handling-pattern]
  patterns: [signal-handler-registration, streaming-query-tracking]
key-files:
  created: []
  modified:
    - backend/src/main/java/io/atadflow/service/CodeGenerationService.java
    - backend/src/test/java/io/atadflow/resource/FlowResourceTest.java
key-decisions:
  - decision: Add signal handlers to generated Python code instead of JobService-level orchestration
    rationale: Python process needs to stop queries before exit; external process can't access SparkSession
  - decision: Track write-stream queries using streaming_queries list
    rationale: Need references to call stop() during shutdown; write-stream nodes create StreamingQuery objects
  - decision: Register both SIGTERM and SIGINT handlers
    rationale: Handle both graceful termination (SIGTERM from cancel) and user interrupt (SIGINT from Ctrl+C)
metrics:
  duration: 2m 29s
  tasks_completed: 2/2
  tests_passing: 12/12
  commits: 2
completed: 2026-02-12
---

# Phase 02 Plan 04: Signal Handler Gap Closure Summary

**One-liner:** Generated Python code now includes signal handlers that track StreamingQuery references and call query.stop() on SIGTERM/SIGINT, enabling graceful cancellation.

## Performance

- All 12 backend tests pass
- 2 tasks completed in ~2.5 minutes
- 2 atomic commits

## Accomplishments

### Signal Handler Implementation
Modified `CodeGenerationService.generateCode()` to inject signal handling logic into generated Python code:

1. **Added imports and setup** after SparkSession creation:
   - `import signal` and `import sys`
   - `streaming_queries = []` list for tracking
   - `handle_shutdown(sig, frame)` function that stops all queries
   - Signal handler registration for SIGTERM and SIGINT

2. **Query tracking** in node generation loop:
   - Detects write-stream nodes using `node.nodeType().equals("write-stream")`
   - Adds `streaming_queries.append(query_{{varName}})` after template expansion
   - Captures StreamingQuery references for shutdown

3. **Interruptible termination**:
   - Wrapped `spark.streams.awaitAnyTermination()` in try/except
   - Handles KeyboardInterrupt by calling `handle_shutdown(None, None)`

### Generated Code Structure
```python
from pyspark.sql import SparkSession
from pyspark.sql.functions import *
import signal
import sys

spark = SparkSession.builder.remote("sc://localhost:15002").getOrCreate()

streaming_queries = []

def handle_shutdown(sig, frame):
    print(f"Received signal {sig}, stopping streaming queries...")
    for query in streaming_queries:
        try:
            query.stop()
        except Exception as e:
            print(f"Error stopping query: {e}")
    sys.exit(0)

signal.signal(signal.SIGTERM, handle_shutdown)
signal.signal(signal.SIGINT, handle_shutdown)

# Read Stream
df_read_stream_1 = spark.readStream.format("rate").load()

# Write Stream
query_df_write_stream_1 = df_read_stream_1.writeStream.format("console").outputMode("append").start()
streaming_queries.append(query_df_write_stream_1)

try:
    spark.streams.awaitAnyTermination()
except KeyboardInterrupt:
    handle_shutdown(None, None)
```

### Test Verification
Updated `FlowResourceTest.generateCode()` with assertions verifying:
- Signal handler imports (`import signal`, `import sys`)
- Signal handler registration (`signal.signal(signal.SIGTERM`)
- Query tracking list (`streaming_queries = []`)
- Query append calls (`streaming_queries.append(query_`)
- Query stop calls (`query.stop()`)
- Handler function definition (`def handle_shutdown`)

## Task Commits

| Task | Name                                              | Commit  | Files                            |
| ---- | ------------------------------------------------- | ------- | -------------------------------- |
| 1    | Add signal handler logic to generated Python code | af551cb | CodeGenerationService.java       |
| 2    | Add test verification for signal handler          | 5624ed2 | FlowResourceTest.java            |

## Files Created/Modified

**Created:** None

**Modified:**
- `backend/src/main/java/io/atadflow/service/CodeGenerationService.java` — Added signal handling logic to generateCode()
- `backend/src/test/java/io/atadflow/resource/FlowResourceTest.java` — Added signal handler assertions

## Decisions Made

| Decision                                         | Rationale                                                                                              |
| ------------------------------------------------ | ------------------------------------------------------------------------------------------------------ |
| Inject signal handlers in generated code         | Python process needs direct access to SparkSession and StreamingQuery objects for graceful shutdown   |
| Track only write-stream nodes                    | Only write-stream nodes create StreamingQuery objects that need stopping                              |
| Register both SIGTERM and SIGINT                 | Handle both cancel (SIGTERM) and user interrupt (SIGINT) scenarios                                    |
| Wrap awaitAnyTermination in try/except           | Allows graceful handling of KeyboardInterrupt without leaving queries running                          |
| Use list append instead of set                   | Preserves query creation order; no duplicate queries expected                                          |

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None - implementation was straightforward. The write-stream template already generates `query_{{varName}}` variables, making it easy to append to the tracking list.

## Gap Closure Verification

This plan closes the gap identified in phase 02 UAT where cancelling a job killed the Python process but left streaming queries running on the Spark Connect server.

**Before this plan:**
- JobService.cancel() called ProcessHandle.destroy() (sends SIGTERM)
- Python process terminated immediately without cleanup
- Streaming queries remained active on Spark Connect server
- Spark UI showed queries as still RUNNING after cancellation

**After this plan:**
- Generated Python code registers SIGTERM handler
- Handler calls query.stop() on all tracked StreamingQuery objects
- Python process exits after cleanup completes
- Spark Connect server properly terminates queries

**Verification steps** (to be performed during UAT):
1. Create flow with rate source -> console sink
2. Submit job and verify status becomes RUNNING
3. Click Cancel button
4. Verify: Job status -> CANCELLED, Python process exits within 2s, Spark UI shows query TERMINATED

## Next Phase Readiness

Phase 02 (Job Execution & Lifecycle) is now complete with all 4 plans finished:
- 02-01: Subprocess execution with lifecycle tracking
- 02-02: JobService integration
- 02-03: Frontend job cancellation UI
- 02-04: Signal handler gap closure (this plan)

**Ready for:** Phase 03 (User Acceptance Testing) or next milestone planning.

**No blockers.**

## Self-Check: PASSED

Verification completed:

**Files exist:**
```bash
$ [ -f "backend/src/main/java/io/atadflow/service/CodeGenerationService.java" ] && echo "FOUND: CodeGenerationService.java" || echo "MISSING"
FOUND: CodeGenerationService.java

$ [ -f "backend/src/test/java/io/atadflow/resource/FlowResourceTest.java" ] && echo "FOUND: FlowResourceTest.java" || echo "MISSING"
FOUND: FlowResourceTest.java
```

**Commits exist:**
```bash
$ git log --oneline --all --grep="02-04"
5624ed2 test(02-04): verify signal handler presence in generated code
af551cb feat(02-04): add signal handler logic to generated Python code
```

All files exist, all commits present, all tests passing.
