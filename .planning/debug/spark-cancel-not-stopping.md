---
status: diagnosed
trigger: "Investigate why cancelling a running Spark job does not actually stop the Spark processing."
created: 2026-02-12T00:00:00Z
updated: 2026-02-12T00:00:00Z
symptoms_prefilled: true
goal: find_root_cause_only
---

## Current Focus

hypothesis: Killing Python gRPC client does not cancel Spark job on remote Spark Connect server
test: analyze code architecture and Spark Connect behavior
expecting: confirmation that server-side job cancellation is missing
next_action: gather evidence about Spark Connect job lifecycle

## Symptoms

expected: Clicking Cancel stops the Spark job processing
actual: Job status changes to CANCELLED in DB, but Python/Spark process continues running
errors: None - process continues silently
reproduction: Submit streaming job, click Cancel button
started: Always broken (architectural issue)

## Eliminated

## Evidence

- timestamp: 2026-02-12T00:01:00Z
  checked: SparkSubmissionService.cancel() method
  found: Sends SIGTERM to Python process PID, waits 5s, then SIGKILL if needed
  implication: Only kills the client process, not the Spark Connect server job

- timestamp: 2026-02-12T00:02:00Z
  checked: CodeGenerationService.generateCode()
  found: Generated Python code connects to remote Spark Connect server and ends with spark.streams.awaitAnyTermination()
  implication: Blocking call keeps Python process alive, but killing it doesn't affect server-side job

- timestamp: 2026-02-12T00:03:00Z
  checked: docker-compose.yml
  found: Spark Connect server runs independently in Docker container on port 15002
  implication: Server is long-lived, isolated from Python client lifecycle

- timestamp: 2026-02-12T00:04:00Z
  checked: Spark Connect cancellation mechanisms (web research)
  found: Spark Connect Go has known issues where context cancellation doesn't stop long-running operations
  implication: This is a known architectural challenge in Spark Connect - client disconnect != job cancellation

- timestamp: 2026-02-12T00:05:00Z
  checked: PySpark Structured Streaming APIs
  found: StreamingQuery.stop() method exists to stop queries; spark.streams.active lists all active queries; queries can be named via queryName()
  implication: Need to track query references and call stop() explicitly, not just kill process

- timestamp: 2026-02-12T00:06:00Z
  checked: Python signal handling patterns
  found: SIGTERM can be caught with signal.signal() to trigger graceful shutdown (flag-based approach)
  implication: Python script needs signal handler to intercept SIGTERM and stop streaming queries before exit

- timestamp: 2026-02-12T00:07:00Z
  checked: Job tagging mechanisms
  found: Queries can have names (queryName()), SparkContext has setJobGroup() and cancelJobGroup()
  implication: Could tag jobs with job UUID for identification and targeted cancellation

## Resolution

root_cause: |
  Killing the Python client process does not cancel Spark jobs running on the remote Spark Connect server.

  Architecture breakdown:
  1. Python client connects to Spark Connect server via gRPC (sc://localhost:15002)
  2. Client submits streaming queries that execute on the server
  3. Client calls spark.streams.awaitAnyTermination() to block indefinitely
  4. When cancel() is called, SIGTERM kills the Python process
  5. Server-side streaming queries continue running - the server doesn't know the client disconnected or that cancellation was intended

  The Spark Connect server is stateful and maintains running queries independently of client lifecycle. Simply disconnecting the client (by killing it) does NOT stop the server-side execution.

fix: |
  Multi-layered solution required:

  **Layer 1: Python Script Signal Handler (REQUIRED)**
  - Add signal handler for SIGTERM/SIGINT in generated Python code
  - Handler must call .stop() on all active streaming queries
  - Only then exit gracefully

  **Layer 2: Query Identification (REQUIRED)**
  - Tag each streaming query with the job UUID using .queryName(f"job-{uuid}")
  - Allows server-side identification of which queries belong to which job

  **Layer 3: Backend Tracking (OPTIONAL but recommended)**
  - Store query IDs/names in Job entity
  - Enables future admin cleanup of orphaned queries

  **Layer 4: Timeout/Fallback (RECOMMENDED)**
  - If signal handler doesn't work, could use Spark REST API to list/stop queries
  - Or SparkContext.cancelJobGroup() if job groups are used

verification: |
  1. Submit a streaming job that writes to console output
  2. Observe output appearing in logs
  3. Click Cancel button
  4. Verify:
     - Python process exits (expected: 0-2 seconds)
     - Spark Connect server UI (localhost:4040) shows query as TERMINATED
     - Console output stops appearing
     - No orphaned queries in spark.streams.active

files_changed:
  - backend/src/main/java/io/atadflow/service/CodeGenerationService.java
  - backend/src/main/java/io/atadflow/entity/Job.java (optional: add queryIds field)
  - backend/src/main/java/io/atadflow/service/SparkSubmissionService.java (optional: REST API cleanup)
