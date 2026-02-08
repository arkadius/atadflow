# Feature Research

**Domain:** Spark Connect Job Submission for Streaming Applications
**Researched:** 2026-02-08
**Confidence:** MEDIUM

## Feature Landscape

### Table Stakes (Users Expect These)

Features users assume exist. Missing these = product feels incomplete.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Submit PySpark code to Spark Connect | Core capability — without this, no jobs run | LOW | Use `SparkSession.builder.remote("sc://host:port")` connection string. Already have PySpark code generation in place. |
| Real-time status tracking | Users need to know if job is running/succeeded/failed | MEDIUM | Use `StreamingQuery.isActive` property and `status` dictionary. Poll programmatically or use REST API. |
| Job cancellation | Users need ability to stop runaway/incorrect jobs | LOW | Call `StreamingQuery.stop()` method. Supported in Spark Connect streaming API. |
| Display Spark Application ID | Users need this for debugging in Spark UI | LOW | Available in job metadata after submission. Track in `sparkAppId` field. |
| Basic failure detection | System must detect when job crashes/errors | MEDIUM | Check `StreamingQuery.exception` property. Contains `StreamingQueryException` if terminated abnormally. |

### Differentiators (Competitive Advantage)

Features that set the product apart. Not required, but valuable.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Progress metrics tracking | Show batch processing metrics (input rate, processing rate, latency) | MEDIUM | Use `StreamingQuery.lastProgress` property. Returns dict with `inputRowsPerSecond`, `processedRowsPerSecond`, `batchId`, etc. Requires periodic polling. |
| Automatic job lifecycle logging | Audit trail of all state transitions with timestamps | LOW | Use existing `submittedAt`, `finishedAt` fields. Add transitions to audit log table. |
| Graceful shutdown with timeout | Prevent indefinite hangs on stop | LOW | Configure `spark.sql.streaming.stopTimeout` or use timeout in `awaitTermination(timeout)`. |
| Failed job retry mechanism | Automatically retry transient failures | HIGH | Complex — requires failure classification, retry policy, state management. Defer to v2+. |
| Streaming query listener integration | Real-time events for query start/progress/termination | MEDIUM | Use `StreamingQueryListener` API (Python/Scala). More advanced than polling. Requires event infrastructure. |

### Anti-Features (Commonly Requested, Often Problematic)

Features that seem good but create problems.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Log streaming to UI | "Users want to see Spark logs in real-time" | Massive complexity: log aggregation across cluster, WebSocket infrastructure, parsing Spark's diverse log formats. Scope creep. | Direct users to Spark UI (port 4040) for logs. Store Application ID for easy access. Milestone explicitly excludes log viewing. |
| RDD-based job submission | "Support for legacy Spark code" | Spark Connect does NOT support RDDs or SparkContext. Only DataFrame API. Not feasible. | Use DataFrame API only. Document this limitation. All generated code uses DataFrames already. |
| Synchronous job execution | "Wait for job completion in submit API" | Streaming jobs run indefinitely until stopped. Can't return "complete" synchronously. Blocks backend threads. | Return immediately with SUBMITTED status. Client polls status endpoint for updates. |
| Custom Spark configuration per job | "Let users tune memory, cores, etc." | Spark Connect server has fixed configuration. Per-job tuning requires cluster-mode deployment, not client-mode. | Use server-side Spark configuration. Document as limitation. Consider v2+ if needed. |

## Feature Dependencies

```
Submit PySpark Code
    └──requires──> Spark Connect Server Running (Docker)
                       └──requires──> Docker Compose Configuration

Real-time Status Tracking
    └──requires──> Submit PySpark Code (to have query handle)
    └──requires──> Polling Mechanism or Listener Registration

Job Cancellation
    └──requires──> Status Tracking (to get query handle)

Progress Metrics Tracking
    └──enhances──> Real-time Status Tracking
    └──requires──> Status Tracking (uses same query handle)

Streaming Query Listener
    └──conflicts──> Polling-based Status Tracking (choose one approach)
```

### Dependency Notes

- **Submit PySpark Code requires Spark Connect Server:** Need server running before client can connect. Docker setup is prerequisite.
- **Status Tracking requires Submission:** Need `StreamingQuery` object handle returned from `writeStream.start()` to check status.
- **Cancellation requires Status Tracking:** Need same query handle to call `stop()`.
- **Progress Metrics enhances Status Tracking:** Uses same `StreamingQuery` handle, just accesses `lastProgress` property.
- **Streaming Query Listener conflicts with Polling:** Two different architectural approaches. Polling is simpler for MVP. Listener is more sophisticated but requires event infrastructure.

## MVP Definition

### Launch With (v1)

Minimum viable product — what's needed to validate the concept.

- [x] **Submit PySpark code to Spark Connect** — Core capability. Use remote SparkSession builder.
- [x] **Docker Compose for Spark Connect server** — Infrastructure prerequisite. Include in repo.
- [x] **Track job status transitions** — PENDING → SUBMITTED → RUNNING → COMPLETED/FAILED/CANCELLED. Use `isActive` and `exception` properties.
- [x] **Display Spark Application ID** — Needed for debugging. Store in database.
- [x] **Job cancellation** — Call `StreamingQuery.stop()`. Essential safety mechanism.
- [ ] **Basic error detection** — Check `exception` property periodically. Surface errors in UI.

### Add After Validation (v1.x)

Features to add once core is working.

- [ ] **Progress metrics tracking** — Add `lastProgress` polling. Shows inputRate, processingRate, batchId. Trigger: Users request visibility into job performance.
- [ ] **Graceful shutdown with timeout** — Configure `stopTimeout` to prevent hangs. Trigger: First production issue with stuck jobs.
- [ ] **Job lifecycle audit log** — Separate table for state transitions. Trigger: Compliance or debugging requirements.
- [ ] **Enhanced failure messages** — Parse `StreamingQueryException` details, show root cause. Trigger: Users struggle with cryptic errors.

### Future Consideration (v2+)

Features to defer until product-market fit is established.

- [ ] **Streaming Query Listener integration** — Real-time events instead of polling. Why defer: Requires event bus infrastructure. Polling sufficient for MVP.
- [ ] **Automatic retry on transient failures** — Retry failed jobs with backoff. Why defer: Complex failure classification logic. Manual retry sufficient initially.
- [ ] **Multiple Spark Connect servers** — Load balancing across cluster. Why defer: Single server sufficient for initial scale.
- [ ] **Job priority/queueing** — Prioritize certain jobs over others. Why defer: Premature optimization. Add when resource contention is real problem.

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Submit PySpark code | HIGH | LOW | P1 |
| Docker Compose setup | HIGH | LOW | P1 |
| Status tracking (basic) | HIGH | MEDIUM | P1 |
| Job cancellation | HIGH | LOW | P1 |
| Display Application ID | MEDIUM | LOW | P1 |
| Basic error detection | HIGH | MEDIUM | P1 |
| Progress metrics tracking | MEDIUM | MEDIUM | P2 |
| Graceful shutdown timeout | MEDIUM | LOW | P2 |
| Job lifecycle audit log | LOW | LOW | P2 |
| Enhanced failure messages | MEDIUM | MEDIUM | P2 |
| Streaming Query Listener | MEDIUM | HIGH | P3 |
| Automatic retry | MEDIUM | HIGH | P3 |
| Multiple servers | LOW | HIGH | P3 |
| Job priority/queueing | LOW | HIGH | P3 |

**Priority key:**
- P1: Must have for launch — job runs and user sees status
- P2: Should have, add when possible — better visibility/debugging
- P3: Nice to have, future consideration — advanced infrastructure

## Implementation Details

### Status Transition Flow

Based on Spark Structured Streaming lifecycle:

```
1. PENDING (initial state in DB, before submission)
       ↓
2. SUBMITTED (after calling writeStream.start(), have query handle)
       ↓
3. RUNNING (first time query.isActive returns True)
       ↓
   ┌───────────────┐
   ↓               ↓
4a. COMPLETED   4b. FAILED        4c. CANCELLED
   (stopped        (exception         (user called
    normally)       occurred)          stop())
```

**Detection logic:**

- **PENDING → SUBMITTED:** After successful `writeStream.start()` call returns `StreamingQuery` object
- **SUBMITTED → RUNNING:** First poll where `query.isActive == True`
- **RUNNING → COMPLETED:** `query.isActive == False` AND `query.exception == None`
- **RUNNING → FAILED:** `query.exception != None` (contains `StreamingQueryException`)
- **RUNNING → CANCELLED:** Explicit `stop()` call triggered by user action

**Polling frequency:** Every 5-10 seconds sufficient for UI updates.

### Minimal Streaming Job Example

Based on research, the minimal PySpark streaming job (rate source → console sink):

```python
from pyspark.sql import SparkSession

# Connect to Spark Connect server
spark = SparkSession.builder.remote("sc://localhost:15002").getOrCreate()

# Read from rate source (generates test data)
df = spark.readStream.format("rate").option("rowsPerSecond", 1).load()

# Write to console sink
query = df.writeStream \
    .format("console") \
    .outputMode("append") \
    .start()

# Query handle available for status tracking
# query.isActive -> bool
# query.status -> dict
# query.exception -> StreamingQueryException or None
# query.lastProgress -> dict or None

# To stop:
# query.stop()
```

**Key properties for tracking:**

- `query.id`: Unique query ID (UUID string)
- `query.runId`: Unique run ID (UUID string)
- `query.name`: Optional query name (can set with `.queryName()`)
- `query.isActive`: Boolean, True while running
- `query.status`: Dict with `message`, `isDataAvailable`, `isTriggerActive`
- `query.exception`: `StreamingQueryException` if failed, else None
- `query.lastProgress`: Dict with batch metrics or None

### Docker Setup Notes

From research on Spark Connect Docker configurations:

**Required services in docker-compose.yml:**

1. **spark-master**: Port 8080 (UI), 7077 (cluster)
2. **spark-connect**: Port 15002 (Spark Connect), 4040 (Spark UI)

**Connection string:** `sc://localhost:15002` (default Spark Connect port)

**Spark version:** Use 3.5+ (Spark Connect introduced in 3.4, stabilized in 3.5)

## Complexity Analysis

### LOW Complexity Features

- Submit PySpark code (connection string + remote session builder)
- Job cancellation (single method call: `query.stop()`)
- Display Application ID (already in metadata)
- Docker Compose configuration (template available)

### MEDIUM Complexity Features

- Status tracking (polling loop, state machine logic)
- Basic error detection (exception handling, error message extraction)
- Progress metrics tracking (parse lastProgress dict, update DB)
- Graceful shutdown timeout (configuration + timeout handling)

### HIGH Complexity Features

- Streaming Query Listener integration (event infrastructure, callback registration)
- Automatic retry (failure classification, exponential backoff, state persistence)
- Multiple servers (load balancing, health checks, failover)

## Known Limitations

From Spark Connect research:

1. **No RDD support:** Spark Connect only supports DataFrame API. RDDs and SparkContext not available.
2. **No direct JVM access:** Cannot access `._jdf` or `._jcol` private fields.
3. **Server-side configuration:** Client cannot override Spark configuration per job.
4. **Streaming API mostly supported:** DataStreamReader, DataStreamWriter, StreamingQuery, StreamingQueryListener all supported.

## Sources

**Spark Connect Documentation:**
- [Quickstart: Spark Connect — PySpark 4.1.0 documentation](https://spark.apache.org/docs/latest/api/python/getting_started/quickstart_connect.html)
- [Spark Connect Overview - Spark 4.1.0 Documentation](https://spark.apache.org/docs/latest/spark-connect-overview.html)
- [pyspark.sql.SparkSession.builder.remote](https://spark.apache.org/docs/latest/api/python/reference/pyspark.sql/api/pyspark.sql.SparkSession.builder.remote.html)

**Monitoring & Status Tracking:**
- [Monitoring and Instrumentation - Spark 4.1.0 Documentation](https://spark.apache.org/docs/latest/monitoring.html)
- [pyspark.sql.streaming.StreamingQuery.isActive](https://spark.apache.org/docs/latest/api/python/reference/pyspark.ss/api/pyspark.sql.streaming.StreamingQuery.isActive.html)
- [pyspark.sql.streaming.StreamingQuery.lastProgress](https://spark.apache.org/docs/latest/api/python/reference/pyspark.ss/api/pyspark.sql.streaming.StreamingQuery.lastProgress.html)

**Streaming Examples:**
- [Perform Spark streaming using a Rate Source and Console Sink](https://www.projectpro.io/recipes/perform-spark-streaming-using-rate-source-and-console-sink)
- [Output Sinks in PySpark: A Comprehensive Guide](https://www.sparkcodehub.com/pyspark/streaming/output-sinks)
- [Structured Streaming Overview in PySpark: A Comprehensive Guide](https://www.sparkcodehub.com/pyspark/streaming/structured-streaming-overview)

**Job Cancellation:**
- [pyspark.sql.streaming.StreamingQuery.awaitTermination](https://spark.apache.org/docs/latest/api/python/reference/pyspark.ss/api/pyspark.sql.streaming.StreamingQuery.awaitTermination.html)
- [Spark Kill Running Application or Job? - Spark By {Examples}](https://sparkbyexamples.com/spark/spark-how-to-kill-running-application/)
- [pyspark.SparkContext.cancelAllJobs](https://spark.apache.org/docs/latest/api/python/reference/api/pyspark.SparkContext.cancelAllJobs.html)

**Docker Setup:**
- [GitHub - franciscoabsampaio/spark-connect-server](https://github.com/franciscoabsampaio/spark-connect-server)
- [apache/spark - Docker Image](https://hub.docker.com/r/apache/spark/)
- [Spark Connect: Launch Spark Applications Anywhere with the Client-Server Architecture](https://medium.com/@yssmelo/spark-connect-launch-spark-applications-anywhere-with-the-client-server-architecture-dbt-f99399c566fe)

**Confidence Assessment:**
- HIGH confidence: Spark Connect connection, basic streaming API, query properties
- MEDIUM confidence: Status transition flow (inferred from API), Docker setup specifics
- LOW confidence: Production-grade error handling patterns, optimal polling frequencies

---
*Feature research for: Spark Connect job submission for streaming applications*
*Researched: 2026-02-08*
