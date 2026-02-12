---
status: complete
phase: 02-job-execution-lifecycle
source: [02-01-SUMMARY.md, 02-02-SUMMARY.md, 02-03-SUMMARY.md]
started: 2026-02-12T19:15:00Z
updated: 2026-02-12T19:15:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Submit flow and see job go RUNNING
expected: Create a rate→console flow and click Run. Job status transitions from PENDING to RUNNING. The job appears in the UI with RUNNING status.
result: pass

### 2. Cancel a running job
expected: While a streaming job is RUNNING, click Cancel. Job status transitions to CANCELLED. The Python process is terminated.
result: issue
reported: "Job status is CANCELLED but python process is still there and job is still processing"
severity: major

### 3. Failed job shows FAILED status (not 500 error)
expected: If the Python process exits with an error (e.g., bad PySpark code or Python not found), the job transitions to FAILED with an error message visible — not a server error or blank screen.
result: skipped
reason: User can't check this for now

### 4. Frontend polls job status every 5 seconds
expected: While a job is RUNNING, the frontend automatically refreshes the job list every 5 seconds without manual page refresh. Status changes appear within ~5 seconds.
result: skipped

### 5. Polling stops on terminal state
expected: Once all jobs are in terminal states (SUCCEEDED, FAILED, CANCELLED), the frontend stops polling — no more automatic network requests to the jobs API.
result: skipped

## Summary

total: 5
passed: 1
issues: 1
pending: 0
skipped: 3

## Gaps

- truth: "Cancelling a running job kills the Python process"
  status: failed
  reason: "User reported: Job status is CANCELLED but python process is still there and job is still processing"
  severity: major
  test: 2
  root_cause: ""
  artifacts: []
  missing: []
  debug_session: ""
