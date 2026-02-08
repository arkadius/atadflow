# Architecture Research: Spark Connect Integration

**Domain:** Spark Connect integration for streaming flow submission and monitoring
**Researched:** 2026-02-08
**Confidence:** HIGH

## Executive Summary

Spark Connect integration for Atadflow requires transitioning from stub submission to real Spark job execution using **spark-submit with PySpark scripts**, not the Spark Connect client library. The backend will write generated Python code to temporary files and submit them via ProcessBuilder executing spark-submit commands. Job status tracking will use the Spark REST API (not Spark Connect gRPC) for polling application state. Submission must be asynchronous using Quarkus ManagedExecutor to avoid blocking HTTP threads. No Python process or Spark Connect Java client is needed — communication is entirely through subprocess execution (spark-submit) and HTTP polling (REST API).

## System Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Frontend (React)                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ FlowCanvas   │  │ JobMonitor   │  │ JobStatus    │      │
│  │ (design)     │  │ (polling)    │  │ (display)    │      │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘      │
│         │                  │                  │              │
├─────────┴──────────────────┴──────────────────┴──────────────┤
│                   Quarkus REST API                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ FlowResource │  │ JobResource  │  │ NodeType     │      │
│  │              │  │              │  │ Resource     │      │
│  └──────┬───────┘  └──────┬───────┘  └──────────────┘      │
│         │                  │                                 │
├─────────┴──────────────────┴─────────────────────────────────┤
│                     Service Layer                            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ FlowService  │  │ JobService   │  │ CodeGen      │      │
│  │              │  │              │  │ Service      │      │
│  └──────────────┘  └──────┬───────┘  └──────┬───────┘      │
│                            │                  │              │
│         NEW ────> ┌────────┴──────────────────┴───────┐     │
│                   │   SparkSubmissionService           │     │
│                   │ ┌────────────┐ ┌────────────────┐ │     │
│                   │ │ Submission │ │ StatusPolling  │ │     │
│                   │ │  (async)   │ │   (REST API)   │ │     │
│                   │ └─────┬──────┘ └────────┬───────┘ │     │
│                   └───────┼──────────────────┼─────────┘     │
├───────────────────────────┼──────────────────┼───────────────┤
│                 Infrastructure                │               │
│  ┌──────────────┐  ┌─────┴──────┐  ┌─────────┴────────┐    │
│  │ PostgreSQL   │  │ Process    │  │ HTTP Client      │    │
│  │ (metadata)   │  │ Builder    │  │ (REST polling)   │    │
│  └──────────────┘  └─────┬──────┘  └──────────────────┘    │
│                           │                                  │
├───────────────────────────┼──────────────────────────────────┤
│              External: Spark Environment                     │
│  ┌────────────────────────┴───────────────────────────┐     │
│  │ Spark (Docker)                                      │     │
│  │ ┌─────────────────┐  ┌──────────────────────────┐  │     │
│  │ │ spark-submit    │  │ REST API :4040 or :18080 │  │     │
│  │ │ (entry point)   │  │ (status monitoring)      │  │     │
│  │ └────────┬────────┘  └──────────────────────────┘  │     │
│  │          │                                          │     │
│  │  ┌───────┴─────────┐                               │     │
│  │  │ Spark Master    │                               │     │
│  │  │ + Workers       │                               │     │
│  │  └─────────────────┘                               │     │
│  └─────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────┘
```

## Component Responsibilities

| Component | Responsibility | Implementation |
|-----------|----------------|----------------|
| **JobResource** | HTTP endpoints for job operations | Already exists — no changes needed |
| **JobService** | Job lifecycle orchestration | Modify: make submitJob async, add status refresh endpoint |
| **SparkSubmissionService** | Spark job submission and monitoring | Replace stub: implement spark-submit + REST polling |
| **CodeGenerationService** | Generate PySpark code from flow | Already exists — no changes needed |
| **Job entity** | Job metadata persistence | Extend: add lastPolledAt, sparkRestUrl fields |
| **ManagedExecutor** | Async task execution | NEW: inject for async submission |
| **ProcessBuilder** | External process execution | NEW: execute spark-submit commands |
| **HTTP client** | REST API polling | NEW: query Spark REST API for status |
| **Spark (Docker)** | Job execution environment | NEW: docker-compose with spark-submit + REST API |

## Integration Architecture

### Existing vs Modified vs New

#### Existing Components (No Changes)
- **FlowResource, FlowService, FlowMapper**: Flow management is stable
- **CodeGenerationService**: Already generates valid PySpark code
- **NodeTypeRegistry, BuiltInNodeTypeProvider**: Node type system is stable
- **JobResource endpoints**: `/api/jobs` GET/POST/cancel are sufficient
- **Job entity core fields**: id, flow, status, timestamps are sufficient

#### Modified Components (Enhanced)
1. **JobService.submitJob()**
   - **Before:** Synchronous submission, waits for stub
   - **After:** Async submission via ManagedExecutor, returns immediately with PENDING status
   - **Change:** Add `@Inject ManagedExecutor executor` and wrap submission in `executor.runAsync()`

2. **SparkSubmissionService** (complete rewrite)
   - **Before:** Stub setting status to SUBMITTED with fake sparkAppId
   - **After:**
     - Write code to temp file
     - Execute spark-submit via ProcessBuilder
     - Parse application ID from output
     - Return async (don't wait for completion)
   - **New methods:**
     - `submit(Job, String code)` — async spark-submit execution
     - `pollStatus(Job)` — query Spark REST API for current status
     - `cancel(Job)` — kill application via REST API or spark-submit --kill

3. **Job entity**
   - **Add fields:**
     - `lastPolledAt` (LocalDateTime) — track when status was last updated
     - `sparkRestUrl` (String) — store REST API endpoint for this job
   - **Rationale:** Enable efficient polling and direct status queries

4. **JobService** (new method)
   - **Add:** `refreshJobStatus(UUID jobId)`
   - **Purpose:** Trigger status poll on-demand (called by frontend polling)
   - **Returns:** Updated JobDto

#### New Components

1. **Quarkus dependencies**
   - `io.quarkus:quarkus-rest-client-reactive` — for REST API polling
   - No Spark Connect client needed

2. **SparkRestClient** (REST interface)
   ```java
   @RegisterRestClient(configKey = "spark-rest")
   public interface SparkRestClient {
       @GET
       @Path("/api/v1/applications/{appId}")
       ApplicationInfo getApplication(@PathParam("appId") String appId);

       @GET
       @Path("/api/v1/applications/{appId}/jobs")
       List<JobInfo> getJobs(@PathParam("appId") String appId);
   }
   ```

3. **Temp file management**
   - Use `Files.createTempFile("atadflow-", ".py")` for script storage
   - Clean up after submission (or on failure)

4. **Docker Compose spark service**
   ```yaml
   spark:
     image: apache/spark:3.5.7
     ports:
       - "4040:4040"  # Web UI / REST API
       - "7077:7077"  # Master
     command: /opt/spark/bin/spark-class org.apache.spark.deploy.master.Master
   ```

## Data Flow

### Job Submission Flow

```
[POST /api/jobs]
    |
    v
JobResource.submit()
    |
    v
JobService.submitJob()
    |
    +---> CodeGenerationService.generateCode(flow)
    |     returns: String pythonCode
    |
    +---> Job.persist() with status=PENDING
    |
    +---> ManagedExecutor.runAsync(() -> {
              SparkSubmissionService.submit(job, code)
          })
          |
          v
    [Return JobDto immediately with PENDING status]


--- ASYNC BOUNDARY ---


SparkSubmissionService.submit(job, code)
    |
    +---> Write code to temp file: /tmp/atadflow-<uuid>.py
    |
    +---> ProcessBuilder.command(
            "spark-submit",
            "--master", "spark://localhost:7077",
            "--deploy-mode", "client",
            "/tmp/atadflow-<uuid>.py"
          )
    |
    +---> process.start()
    |
    +---> Read process output stream to extract:
    |     "Submitted application application_1234567890_0001"
    |
    +---> Update Job:
    |     - sparkAppId = "application_1234567890_0001"
    |     - status = SUBMITTED
    |     - sparkRestUrl = "http://localhost:4040"
    |     - startedAt = now()
    |
    +---> Delete temp file
    |
    v
[Spark job running independently]
```

### Status Polling Flow

```
[GET /api/jobs/{id}]
    |
    v
JobResource.get(id)
    |
    v
JobService.getJob(id)
    |
    +---> if (job.status in [SUBMITTED, RUNNING]) {
    |         SparkSubmissionService.pollStatus(job)
    |     }
    |
    v
SparkSubmissionService.pollStatus(job)
    |
    +---> GET {sparkRestUrl}/api/v1/applications/{sparkAppId}
    |     |
    |     +---> 200 OK → application still exists
    |     |     Read response:
    |     |     { "id": "...", "name": "...", "attempts": [...] }
    |     |     attempts[0].completed = false → RUNNING
    |     |     attempts[0].completed = true → check jobs API
    |     |
    |     +---> 404 Not Found → check history server
    |           GET http://localhost:18080/api/v1/applications/{sparkAppId}
    |           |
    |           +---> 200 OK → completed, check attempts[0].completed
    |           |     attempt.completionTime exists → SUCCEEDED or FAILED
    |           |     Check jobs API for failures
    |           |
    |           +---> 404 → status = FAILED (app not found anywhere)
    |
    +---> GET {sparkRestUrl}/api/v1/applications/{sparkAppId}/jobs
    |     Check if any job has status = "FAILED"
    |     If yes → job.status = FAILED, errorMessage = first failure
    |     If all succeeded → job.status = SUCCEEDED
    |
    +---> Update Job entity:
    |     - status = [new status]
    |     - lastPolledAt = now()
    |     - finishedAt = (if terminal state)
    |     - errorMessage = (if failed)
    |
    v
[Return updated JobDto]
```

### Frontend Polling Pattern

```
JobMonitor component (React)
    |
    +---> useEffect(() => {
            const interval = setInterval(() => {
                if (job.status in ['PENDING', 'SUBMITTED', 'RUNNING']) {
                    fetch(`/api/jobs/${job.id}`)
                        .then(updateJobState)
                }
            }, 5000)  // Poll every 5 seconds

            return () => clearInterval(interval)
          }, [job.status])
```

## Architectural Patterns

### Pattern 1: Async Fire-and-Forget Submission

**What:** Submit Spark job asynchronously without blocking HTTP request
**When to use:** Long-running job submission (spark-submit can take 5-30 seconds to start)
**Trade-offs:**
- PRO: HTTP requests return immediately, no timeout issues
- PRO: Server can handle multiple concurrent submissions
- CON: Requires polling for status updates
- CON: Error handling is delayed (not immediate in response)

**Example:**
```java
@ApplicationScoped
public class JobService {
    @Inject ManagedExecutor executor;
    @Inject SparkSubmissionService sparkSubmission;

    @Transactional
    public JobDto submitJob(SubmitJobRequest request) {
        // Generate code and create Job entity synchronously
        String code = codeGenerationService.generateCode(flowDto);
        Job job = new Job();
        job.status = JobStatus.PENDING;
        job.persist();

        // Submit to Spark asynchronously
        executor.runAsync(() -> {
            try {
                sparkSubmission.submit(job, code);
            } catch (Exception e) {
                updateJobFailure(job, e);
            }
        });

        return mapper.toDto(job);  // Return immediately with PENDING
    }
}
```

### Pattern 2: REST API Polling with Fallback

**What:** Poll active Spark REST API first, fall back to history server if 404
**When to use:** Jobs transition from running (4040) to completed (18080)
**Trade-offs:**
- PRO: Always finds job status regardless of completion state
- PRO: No need to know completion state in advance
- CON: Two HTTP calls when job completes (first 404, then history server)
- CON: Relies on history server being configured

**Example:**
```java
public void pollStatus(Job job) {
    try {
        // Try active application REST API first
        ApplicationInfo app = restClient.getApplication(job.sparkAppId);
        updateStatusFromActiveApp(job, app);
    } catch (WebApplicationException e) {
        if (e.getResponse().getStatus() == 404) {
            // App finished, check history server
            try {
                ApplicationInfo app = historyClient.getApplication(job.sparkAppId);
                updateStatusFromCompletedApp(job, app);
            } catch (WebApplicationException e2) {
                // Not found anywhere
                job.status = JobStatus.FAILED;
                job.errorMessage = "Application not found in history";
            }
        }
    }
}
```

### Pattern 3: ProcessBuilder with Output Parsing

**What:** Execute spark-submit as subprocess and parse stdout for application ID
**When to use:** No Spark Connect client available, need spark-submit compatibility
**Trade-offs:**
- PRO: Works with any Spark deployment (Standalone, YARN, K8s)
- PRO: No additional dependencies beyond Spark installation
- PRO: Matches standard spark-submit workflow
- CON: Requires parsing text output (fragile if format changes)
- CON: Must handle process lifecycle (cleanup, timeout)

**Example:**
```java
public void submit(Job job, String code) throws IOException {
    // Write code to temp file
    Path scriptPath = Files.createTempFile("atadflow-", ".py");
    Files.writeString(scriptPath, code);

    // Build spark-submit command
    ProcessBuilder pb = new ProcessBuilder(
        "spark-submit",
        "--master", sparkMasterUrl,
        "--deploy-mode", "client",
        "--name", "Atadflow-" + job.id,
        scriptPath.toString()
    );
    pb.redirectErrorStream(true);

    // Start process and capture output
    Process process = pb.start();
    BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream())
    );

    // Parse application ID from output
    String line;
    String appId = null;
    while ((line = reader.readLine()) != null) {
        if (line.contains("Submitted application")) {
            appId = extractAppId(line);  // Parse "application_xxx"
            break;
        }
    }

    // Update job with app ID
    job.sparkAppId = appId;
    job.status = JobStatus.SUBMITTED;
    job.persist();

    // Clean up
    Files.deleteIfExists(scriptPath);
}
```

### Pattern 4: Transactional Status Updates

**What:** Update job status within transactions to ensure consistency
**When to use:** Status polling and async callbacks that modify Job entities
**Trade-offs:**
- PRO: Prevents lost updates from concurrent polling
- PRO: Database reflects accurate state
- CON: Requires careful transaction boundaries in async code

**Example:**
```java
@Transactional
public void updateJobStatusFromPoll(UUID jobId) {
    Job job = Job.findById(jobId);
    if (job == null) return;

    // Poll external state
    pollStatus(job);

    // Persist within same transaction
    job.lastPolledAt = LocalDateTime.now();
    job.persist();
}
```

## Anti-Patterns

### Anti-Pattern 1: Synchronous spark-submit in HTTP Request

**What people do:** Call ProcessBuilder.start().waitFor() directly in JobService.submitJob()
**Why it's wrong:**
- Blocks HTTP thread for 10-60 seconds during Spark startup
- Can cause request timeouts
- Prevents concurrent job submissions
**Do this instead:** Use ManagedExecutor.runAsync() to submit in background thread

### Anti-Pattern 2: Using Spark Connect gRPC Client

**What people do:** Try to use spark-connect-client-jvm to submit PySpark code
**Why it's wrong:**
- Spark Connect is for DataFrame API in client languages (Python, Scala)
- No Java API for submitting arbitrary PySpark scripts
- Designed for interactive sessions, not job submission
**Do this instead:** Use spark-submit via ProcessBuilder (standard submission mechanism)

### Anti-Pattern 3: Polling Every Request

**What people do:** Call pollStatus() on every GET /api/jobs/{id} regardless of status
**Why it's wrong:**
- Wastes HTTP calls to Spark REST API for completed jobs
- Adds 50-200ms latency to every status check
- Can overwhelm Spark REST API with requests
**Do this instead:** Only poll when status is SUBMITTED or RUNNING, cache terminal states

### Anti-Pattern 4: No Python Environment

**What people do:** Assume backend needs embedded Python interpreter or py4j
**Why it's wrong:**
- spark-submit handles Python execution internally
- Backend only generates Python code as strings
- Adding Python dependencies complicates deployment
**Do this instead:** Generate Python code, write to file, exec spark-submit — Spark handles Python

### Anti-Pattern 5: Blocking on Application Completion

**What people do:** Wait for Spark application to finish before returning from submit()
**Why it's wrong:**
- Streaming jobs run indefinitely (spark.streams.awaitAnyTermination())
- Would block forever
- Defeats purpose of async submission
**Do this instead:** Return immediately after getting sparkAppId, poll status separately

## Scaling Considerations

| Scale | Architecture Adjustments |
|-------|--------------------------|
| 1-10 concurrent jobs | Current approach: single Spark cluster, PostgreSQL, sync polling per request |
| 10-100 concurrent jobs | Add: Background status poller (scheduled task updates all RUNNING jobs every 10s), reduce per-request polling |
| 100-1000 concurrent jobs | Add: Separate Spark clusters (by tenant or workload), job queue with priority, Redis cache for status |

### Scaling Priorities

1. **First bottleneck: Frontend polling overhead**
   - **Symptom:** Many browser tabs polling /api/jobs/{id} every 5 seconds
   - **Fix:** Server-side polling — scheduled task updates all running jobs, frontend queries cached state
   - **Implementation:** Quarkus @Scheduled method polls all RUNNING/SUBMITTED jobs every 10s

2. **Second bottleneck: Spark cluster saturation**
   - **Symptom:** Jobs queued, submissions slow, resource contention
   - **Fix:** Resource limits (max concurrent streams), job queuing, multiple Spark clusters
   - **Implementation:** Add `max_concurrent_jobs` config, return 429 Too Many Requests when limit reached

3. **Third bottleneck: PostgreSQL status updates**
   - **Symptom:** Lock contention on Job table during polling spikes
   - **Fix:** Cache job status in Redis, batch status updates
   - **Implementation:** Redis cache with TTL, only write to PostgreSQL on state transitions

## Integration Points

### External Services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| **Spark (spark-submit)** | ProcessBuilder subprocess execution | Master URL from config (spark://localhost:7077 for local) |
| **Spark REST API** | Quarkus REST Client (Reactive) | Dynamic URL per job (4040 for running, 18080 for history) |
| **Spark History Server** | Quarkus REST Client (Reactive) | Fallback when active API returns 404 |
| **Docker Compose** | External orchestration | Spark master/workers, backend connects via localhost ports |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| **JobService → SparkSubmissionService** | Direct method call (sync), wrapped in ManagedExecutor (async) | JobService orchestrates, SparkSubmissionService executes |
| **SparkSubmissionService → ProcessBuilder** | JDK subprocess API | Error handling: capture stderr, parse exit codes |
| **SparkSubmissionService → SparkRestClient** | HTTP REST (reactive) | Inject @RestClient interface, configure base URL |
| **Frontend → JobResource** | HTTP polling (GET /api/jobs/{id}) | 5 second interval for active jobs, stop when terminal |

## Component Implementation Details

### SparkSubmissionService Implementation Strategy

**Submit Method:**
1. Write Python code to temp file
2. Build ProcessBuilder with spark-submit command
3. Start process and read stdout in separate thread (ManagedExecutor)
4. Parse "Submitted application app_xxx" from output
5. Update Job entity with sparkAppId, status=SUBMITTED
6. Return (don't wait for completion)

**PollStatus Method:**
1. Check if status is terminal (SUCCEEDED, FAILED, CANCELLED) — skip if yes
2. Call Spark REST API GET /api/v1/applications/{sparkAppId}
3. If 404: try history server GET /api/v1/applications/{sparkAppId}
4. Parse application state: attempts[0].completed, attempts[0].completionTime
5. If completed: call GET /api/v1/applications/{sparkAppId}/jobs to check for failures
6. Update Job status, finishedAt, errorMessage based on results
7. Set lastPolledAt = now()

**Cancel Method:**
1. Option A: REST API DELETE (if available in Spark version)
2. Option B: spark-submit --kill {sparkAppId} --master {masterUrl}
3. Update Job status = CANCELLED, finishedAt = now()

### Job Entity Schema Changes

```sql
ALTER TABLE job ADD COLUMN last_polled_at TIMESTAMP;
ALTER TABLE job ADD COLUMN spark_rest_url VARCHAR(255);

-- Index for efficient polling queries
CREATE INDEX idx_job_status_active ON job(status)
WHERE status IN ('SUBMITTED', 'RUNNING');
```

### Configuration Properties

```properties
# application.properties
spark.master.url=spark://localhost:7077
spark.rest.url=http://localhost:4040
spark.history.url=http://localhost:18080
spark.submit.path=/opt/spark/bin/spark-submit

# Max concurrent running jobs (optional scaling feature)
spark.max.concurrent.jobs=50
```

### Docker Compose Configuration

```yaml
services:
  postgres:
    image: postgres:16
    # ... existing config

  backend:
    # ... existing config
    depends_on:
      - postgres
      - spark-master
    environment:
      SPARK_MASTER_URL: spark://spark-master:7077
      SPARK_REST_URL: http://spark-master:4040

  spark-master:
    image: apache/spark:3.5.7
    ports:
      - "4040:4040"  # Web UI / REST API
      - "7077:7077"  # Master
      - "18080:18080"  # History server
    command: >
      bash -c "
      /opt/spark/bin/spark-class org.apache.spark.deploy.master.Master &
      /opt/spark/sbin/start-history-server.sh &
      wait
      "
    volumes:
      - spark-logs:/opt/spark/logs

  spark-worker:
    image: apache/spark:3.5.7
    depends_on:
      - spark-master
    command: /opt/spark/bin/spark-class org.apache.spark.deploy.worker.Worker spark://spark-master:7077

volumes:
  spark-logs:
```

## Build Order Recommendations

Based on dependencies and integration complexity:

### Phase 1: Foundation (Spark Environment)
1. **Docker Compose setup** — Spark master, worker, history server
   - No code changes, pure infrastructure
   - Validates Spark installation and networking
   - Test: `docker-compose up`, verify Web UI at localhost:4040

2. **Configuration properties** — Add spark.* config to application.properties
   - Simple property additions
   - Test: config values injectable via @ConfigProperty

### Phase 2: Submission (Core Integration)
3. **Job entity enhancements** — Add lastPolledAt, sparkRestUrl fields
   - Database schema migration (Liquibase/Flyway)
   - Simple entity changes
   - Test: Unit test entity persistence with new fields

4. **ProcessBuilder spike** — Proof of concept: write temp file, exec spark-submit, parse output
   - Standalone test (not integrated)
   - Validates spark-submit available, output parseable
   - Test: Execute spark-submit with sample PySpark script, extract appId

5. **SparkSubmissionService.submit()** — Implement real submission logic
   - Replace stub with ProcessBuilder implementation
   - Synchronous first (async added next)
   - Test: Integration test submits job, verifies sparkAppId populated

6. **ManagedExecutor async wrapper** — Make JobService.submitJob() async
   - Inject ManagedExecutor, wrap submit() call
   - Test: Verify HTTP request returns before Spark job starts

### Phase 3: Monitoring (Status Tracking)
7. **SparkRestClient interface** — Define REST client for Spark API
   - Quarkus @RegisterRestClient interface
   - ApplicationInfo, JobInfo DTOs
   - Test: Mock REST client, verify DTO parsing

8. **SparkSubmissionService.pollStatus()** — Implement REST API polling
   - Call Spark REST API, parse response
   - Update Job entity based on state
   - Test: Integration test with mock REST responses

9. **JobService.refreshJobStatus()** — Add manual refresh endpoint
   - Called by JobResource.get() for active jobs
   - Test: Verify status updated when called

10. **Frontend polling** — Add useEffect interval polling in React
    - Poll every 5s for PENDING/SUBMITTED/RUNNING jobs
    - Test: Manual verification in browser

### Phase 4: Lifecycle (Cancel, Failure Handling)
11. **SparkSubmissionService.cancel()** — Implement job cancellation
    - Use spark-submit --kill or REST DELETE
    - Test: Submit job, cancel, verify CANCELLED status

12. **Error handling** — Add try/catch in async submit, update job.errorMessage
    - Handle ProcessBuilder failures, parse errors
    - Test: Submit invalid code, verify FAILED status with message

### Phase 5: Optimization (Optional)
13. **Scheduled background poller** — Reduce per-request polling load
    - @Scheduled task updates all RUNNING jobs
    - Test: Submit multiple jobs, verify status updates without frontend polling

14. **History server fallback** — Query history server on 404
    - Handles completed jobs after Spark UI shuts down
    - Test: Submit job, wait for completion, verify status still accurate

## Confidence Assessment

| Area | Confidence | Rationale |
|------|------------|-----------|
| **Submission via spark-submit** | HIGH | Official Spark submission mechanism, well-documented, ProcessBuilder is standard JDK |
| **REST API polling** | HIGH | Official Spark monitoring API (since 1.x), stable endpoint contract |
| **Async with ManagedExecutor** | HIGH | Quarkus best practice, MicroProfile standard, extensive documentation |
| **No Spark Connect client needed** | HIGH | Spark Connect is for interactive DataFrame API, not PySpark script submission (verified in official docs) |
| **Docker Compose config** | MEDIUM | Standard pattern from community examples, but port conflicts and networking can vary by environment |
| **Status state machine** | MEDIUM | REST API states documented, but edge cases (crashes, network failures) need testing |
| **History server fallback** | MEDIUM | Documented API, but configuration (spark.eventLog.enabled) must be correct |

## Open Questions for Phase-Specific Research

1. **Spark version compatibility:** Does spark-submit output format vary between Spark 3.5 and 4.0? (Test during Phase 2, task 4)
2. **Job cancellation reliability:** Does spark-submit --kill work immediately, or does it require graceful shutdown? (Test during Phase 4, task 11)
3. **History server delay:** How long after completion before application appears in history server? (Test during Phase 5, task 14)
4. **Resource limits:** What happens when Spark cluster is saturated? Does spark-submit queue or fail immediately? (Test during scaling, if needed)

## Sources

### Spark Connect Architecture
- [Spark Connect Overview - Spark 4.1.0 Documentation](https://spark.apache.org/docs/latest/spark-connect-overview.html) — HIGH confidence
- [Spark Connect | Apache Spark](https://spark.apache.org/spark-connect/) — HIGH confidence
- [Spark Connect Overview - Spark 3.5.0 Documentation](https://spark.apache.org/docs/3.5.0/spark-connect-overview.html) — HIGH confidence

### Spark REST API
- [Monitoring and Instrumentation - Spark 4.1.0 Documentation](https://spark.apache.org/docs/latest/monitoring.html) — HIGH confidence
- [How to Submit a Spark Job via Rest API? - Spark By Examples](https://sparkbyexamples.com/spark/submit-spark-job-via-rest-api/) — MEDIUM confidence

### Spark Submission
- [Submitting Applications - Spark 4.1.0 Documentation](https://spark.apache.org/docs/latest/submitting-applications.html) — HIGH confidence
- [How to Spark Submit Python | PySpark File (.py)? - Spark By Examples](https://sparkbyexamples.com/pyspark/spark-submit-python-file/) — MEDIUM confidence
- [SparkLauncher (Spark 4.1.0 JavaDoc)](https://spark.apache.org/docs/latest/api/java/org/apache/spark/launcher/SparkLauncher.html) — HIGH confidence

### Quarkus Async Execution
- [Mastering Background Tasks in Quarkus: From Simple Schedulers to Resilient Job Execution](https://www.the-main-thread.com/p/quarkus-background-tasks-scheduling-async-quartz) — MEDIUM confidence
- [Context Propagation in Quarkus - Quarkus](https://quarkus.io/guides/context-propagation) — HIGH confidence
- [relative how to trigger a background/side job without waiting it · quarkusio/quarkus · Discussion #31022](https://github.com/quarkusio/quarkus/discussions/31022) — MEDIUM confidence

### Java Process Execution
- [How to Call Python From Java | Baeldung](https://www.baeldung.com/java-working-with-python) — MEDIUM confidence
- [Java ProcessBuilder examples - Mkyong.com](https://mkyong.com/java/java-processbuilder-examples/) — MEDIUM confidence

### Docker Configuration
- [bitnami/spark - Docker Image](https://hub.docker.com/r/bitnami/spark/) — MEDIUM confidence
- [Setting up Spark using Docker. Quick tutorial on how to create… | by Dimitris Kalouris | Medium](https://medium.com/@dkalouris/setting-up-spark-using-docker-59db2d073487) — MEDIUM confidence

### Maven Dependencies
- [Maven Central: org.apache.spark:spark-connect-client-jvm_2.13](https://central.sonatype.com/artifact/org.apache.spark/spark-connect-client-jvm_2.13) — HIGH confidence
- [org.apache.spark:spark-connect-client-jvm_2.12 - Maven Central](https://central.sonatype.com/artifact/org.apache.spark/spark-connect-client-jvm_2.12) — HIGH confidence

---
*Architecture research for: Spark Connect Integration (Atadflow)*
*Researched: 2026-02-08*
