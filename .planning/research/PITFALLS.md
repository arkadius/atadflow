# Pitfalls Research

**Domain:** Adding Spark Connect execution to Java/Quarkus streaming flow designer
**Researched:** 2026-02-08
**Confidence:** MEDIUM

## Critical Pitfalls

### Pitfall 1: Spark Connect API Limitations - No RDD/SparkContext Access

**What goes wrong:**
Application attempts to use RDD APIs, SparkContext, or JVM-dependent features (like `df.rdd.getNumPartitions()`) from Spark Connect client, resulting in `JVM_ATTRIBUTE_NOT_SUPPORTED` errors at runtime. Code that works with traditional Spark fails when migrated to Spark Connect.

**Why it happens:**
Developers assume Spark Connect provides full Spark API compatibility. The decoupled client-server architecture means the client doesn't run in the Spark JVM and cannot access JVM-dependent features. The Spark Connect protocol uses logical plans as the abstraction and does not support execution APIs like RDDs.

**How to avoid:**
- Use only DataFrame/Dataset APIs in generated PySpark code
- Avoid accessing `spark.sparkContext` or any RDD operations
- Review Spark Connect migration guide before implementation
- Test all node type code generation against Spark Connect constraints
- Document which Spark APIs are off-limits for node type implementations

**Warning signs:**
- Error messages containing "JVM_ATTRIBUTE_NOT_SUPPORTED"
- Code using `.rdd`, `sparkContext`, or broadcast variables
- Libraries that rely on RDD internals
- References to Spark private methods or internal classes

**Phase to address:**
Phase 1 (Basic Spark Connect Integration) - Establish code generation guardrails and validate DataFrame-only approach with rate→console test case.

---

### Pitfall 2: gRPC Channel Management - Resource Leaks and Connection Overhead

**What goes wrong:**
Creating a new gRPC channel for every Spark operation or failing to properly close channels leads to connection exhaustion, port exhaustion, memory leaks, and degraded performance. Long-running streaming jobs accumulate abandoned connections until the system becomes unstable.

**Why it happens:**
Developers unfamiliar with gRPC lifecycle management treat channels like HTTP requests (create, use, dispose). The Spark Connect Java client uses gRPC under the hood, but proper channel pooling and reuse isn't obvious. Quarkus's reactive model may conflict with blocking gRPC calls if not handled correctly.

**How to avoid:**
- Create a singleton gRPC channel or channel pool, reuse across operations
- Configure keepalive pings (`spark.connect.grpc.keepAlive=true`) for long-running connections
- Implement proper shutdown hooks to close channels gracefully
- Use connection pooling for high-load scenarios
- Enable gzip compression on gRPC channels
- In Quarkus, ensure gRPC calls run on worker threads (annotate with `@Blocking`), not I/O threads

**Warning signs:**
- Increasing number of `CLOSE_WAIT` or `TIME_WAIT` connections (check with `netstat`)
- Port exhaustion errors
- Memory growth in Java backend process
- Slow connection establishment times
- "Too many open files" errors
- Quarkus warning: "Blocking operation on a IO thread"

**Phase to address:**
Phase 1 (Basic Spark Connect Integration) - Implement channel management correctly from the start to avoid refactoring later. Add connection monitoring and shutdown hooks.

---

### Pitfall 3: Python Dependency Mismatch - Client vs. Server Environment Divergence

**What goes wrong:**
PySpark code executes successfully on Spark Connect server but fails with import errors, version conflicts, or unexpected behavior because the Python environment in the Spark Connect Docker container differs from what the backend assumes. Library versions mismatch between client and server.

**Why it happens:**
The backend (Java) doesn't execute Python - it sends code to Spark Connect server which executes in its own Python environment. Developers forget that the Docker Spark Connect image controls the Python environment, not the client. Session-based dependency management (added in Spark 3.5) is not configured, so different streaming jobs can't have isolated dependencies.

**How to avoid:**
- Use Spark Connect's session-based dependency management (PEX files or archives)
- Build custom Spark Connect Docker image with required Python packages pre-installed
- Document required Python packages in `requirements.txt` for Docker image
- Version-pin all Python dependencies (PySpark client version must match server version)
- Use `spark.addPyFile()` or `spark.addArchive()` for session-specific dependencies
- Test dependency resolution in Docker environment, not just local Python

**Warning signs:**
- `ImportError` or `ModuleNotFoundError` in streaming job execution
- Different behavior between local Spark and Docker Spark Connect
- Version mismatch warnings in Spark logs
- Jobs failing with "No module named X" after backend changes
- Different pandas/numpy/pyarrow versions causing serialization errors

**Phase to address:**
Phase 1 (Basic Spark Connect Integration) - Define Python dependency management strategy upfront. Phase 2 (Advanced Node Types) - Revisit when adding nodes requiring additional libraries.

---

### Pitfall 4: Streaming Job Lifecycle - No Query Handle = No Control

**What goes wrong:**
Spark Connect client submits streaming query and loses the query handle, making it impossible to monitor status, stop gracefully, or detect failures. The streaming job runs indefinitely with no programmatic control, requiring manual intervention to stop.

**Why it happens:**
Unlike batch jobs, streaming queries are long-running. Developers submit the query (via generated PySpark code) but don't capture the `StreamingQuery` object or implement a control mechanism. The Java backend loses visibility into the remote streaming query running on Spark Connect server.

**How to avoid:**
- Generate PySpark code that stores query handle: `query = df.writeStream.start(); query.awaitTermination()`
- Implement query ID tracking in backend database (map flow execution ID to Spark query ID)
- Use `StreamingQueryListener` to push events back to backend (onQueryStarted, onQueryProgress, onQueryTerminated)
- Expose Spark REST API or implement custom gRPC endpoint for query status polling
- Store query handles in shared state (e.g., Redis) for distributed backend scenarios
- Implement timeout mechanisms for queries that should have bounded runtime

**Warning signs:**
- No way to stop a streaming job except Docker restart
- Cannot determine if streaming job is running or failed
- Backend reports "submitted" but has no real-time status
- Orphaned streaming queries after backend restart
- No visibility into query progress or lag metrics

**Phase to address:**
Phase 1 (Basic Spark Connect Integration) - Implement basic query handle storage and status polling. Phase 3 (Production Hardening) - Add comprehensive monitoring and failure detection.

---

### Pitfall 5: Graceful Shutdown Complexity - SIGTERM vs. Batch Completion

**What goes wrong:**
Spark streaming job receives SIGTERM (from Kubernetes, Docker stop, or manual kill) and terminates immediately mid-batch, causing data loss, checkpoint corruption, or partial writes. On restart, the job cannot recover to a consistent state.

**Why it happens:**
By default, Spark stops immediately on SIGTERM without finishing the current batch. The async nature of `StreamingQueryListener.onProgress()` means that during shutdown, the next batch may start before cleanup completes. Structured Streaming requires completing the current micro-batch for checkpoint consistency.

**How to avoid:**
- Set `spark.streaming.stopGracefullyOnShutdown=true` in generated PySpark code
- Configure adequate `spark.streaming.gracefulStopTimeout` (default 10× batch interval may be insufficient)
- Implement external shutdown signal mechanism (HTTP endpoint, marker file, or database flag)
- Monitor shutdown timeout and alert if graceful stop fails
- Ensure checkpointing is configured before enabling graceful shutdown
- Test restart-from-checkpoint scenarios in Phase 1
- In Kubernetes/Docker, set `terminationGracePeriodSeconds` > graceful stop timeout

**Warning signs:**
- Checkpoint directory with inconsistent state after restarts
- "Unable to recover from checkpoint" errors
- Data duplication or loss across restarts
- Streaming queries that won't stop even after timeout
- Partial writes to sink (e.g., incomplete Parquet files)
- Long delay between stop request and actual termination

**Phase to address:**
Phase 1 (Basic Spark Connect Integration) - Configure graceful shutdown immediately. Phase 3 (Production Hardening) - Add monitoring, timeout alerts, and recovery testing.

---

### Pitfall 6: Checkpoint Directory Management - Per-Stream Isolation Required

**What goes wrong:**
Multiple streaming flows share the same checkpoint directory or checkpoint directory is not configured, leading to state corruption, inability to restart queries, or errors like "Checkpoint schema mismatch". Adding/removing stateful operators or sources breaks checkpoint compatibility.

**Why it happens:**
Developers either forget to set checkpoint location (uses temp directory) or use a single shared location for simplicity. Each streaming query requires its own isolated checkpoint directory for metadata and state recovery. Schema evolution (changing node configuration) invalidates existing checkpoints.

**How to avoid:**
- Generate unique checkpoint path per flow execution: `/checkpoints/{flow_id}/{execution_id}`
- Use reliable storage (HDFS, S3, persistent Docker volume - NOT ephemeral container storage)
- Set permissions correctly on checkpoint directory (Spark user must have write access)
- Document checkpoint compatibility rules: changing stateful operators = new checkpoint
- Implement checkpoint cleanup for deleted flows (avoid unbounded storage growth)
- Configure checkpoints on cloud storage (S3, GCS, Azure Blob) for production resilience
- Test checkpoint recovery: stop → restart → verify state continuity

**Warning signs:**
- "Checkpoint directory does not exist" errors
- "Incompatible checkpoint schema" errors
- Streaming query restarts from beginning instead of last checkpoint
- Permission denied errors when writing checkpoint
- Checkpoint directory grows unbounded (no cleanup)
- Different flows overwriting each other's checkpoints

**Phase to address:**
Phase 1 (Basic Spark Connect Integration) - Implement checkpoint path generation and storage strategy. Phase 3 (Production Hardening) - Add cleanup, monitoring, and cloud storage support.

---

### Pitfall 7: Docker Networking - Host Resolution and Dynamic Ports

**What goes wrong:**
Spark driver (in Docker) and client (Java backend) cannot communicate due to hostname resolution failures, port mapping issues, or VPN interference. Spark jobs hang during execution or fail with "Connection refused" errors. Executors cannot reach the driver.

**Why it happens:**
Spark uses dynamic port allocation for driver communication. Docker's internal DNS may not resolve hostnames correctly. When backend is on host and Spark Connect server is in Docker, or vice versa, network segmentation breaks communication. VPN configurations change host IP addresses unexpectedly.

**How to avoid:**
- Use `extra_hosts` in docker-compose to map host IP
- Expose Spark Connect port (default 15002) and UI port (4040) explicitly
- Use IP addresses instead of hostnames if DNS resolution is unreliable
- Configure `spark.driver.host` and `spark.driver.bindAddress` in server config
- Test connectivity from both directions (client→server, executor→driver)
- Document VPN requirements and IP address stability assumptions
- Use Docker bridge network for backend-to-Spark communication in dev, host network for simplicity

**Warning signs:**
- "Connection refused" in Spark logs
- Jobs stuck in "ACCEPTED" state without progress
- DNS resolution errors in driver logs
- Executors failing to register with driver
- Different behavior with/without VPN connected
- `netstat` showing port not listening on expected interface

**Phase to address:**
Phase 1 (Basic Spark Connect Integration) - Validate Docker networking with rate→console test. Document network configuration requirements.

---

### Pitfall 8: Spark Connect Client-Server Version Mismatch

**What goes wrong:**
Java backend uses Spark Connect client library version X, Spark Connect server runs version Y, leading to gRPC protocol incompatibilities, serialization errors, or features not working despite being "supported" by one side.

**Why it happens:**
Spark Connect's gRPC protocol is not fully backwards compatible across versions. Docker image version and Java dependency version diverge during updates (e.g., backend updated to Spark 3.5.1, Docker still on 3.5.0). PySpark version in Docker must also match.

**How to avoid:**
- Pin Spark Connect client version in Gradle dependencies: `org.apache.spark:spark-connect_2.13:3.5.x`
- Pin Spark Connect server version in Dockerfile: `FROM apache/spark:3.5.x-python3`
- Document version compatibility matrix in project README
- Add version check: backend validates server version via REST API before submission
- Use consistent version variables in build.gradle and docker-compose
- Test version upgrade path: client+server upgraded together

**Warning signs:**
- gRPC errors mentioning "unknown method" or "unimplemented"
- Protobuf deserialization errors
- Features working in client but failing on server
- Version mismatch warnings in Spark logs (if enabled)
- Unexpected "Method not found" errors

**Phase to address:**
Phase 1 (Basic Spark Connect Integration) - Establish version pinning strategy and validation. Add to CI/CD checks.

---

### Pitfall 9: Streaming Job Status Polling - Inefficient Monitoring Architecture

**What goes wrong:**
Java backend polls Spark REST API or database every second to check streaming job status, creating high CPU usage, network overhead, and delayed failure detection. Status updates lag reality by 10+ seconds, degrading user experience.

**Why it happens:**
No event-driven architecture for streaming job events. Backend uses naive polling loop instead of leveraging Spark's `StreamingQueryListener` push-based events. REST API polling has inherent latency. Executors send heartbeats every 10s by default (`spark.executor.heartbeatInterval`).

**How to avoid:**
- Implement `StreamingQueryListener` in PySpark code to push events via HTTP webhook or message queue
- Use Spark's streaming metrics (input rate, processing time, latency) for health checks
- Poll at reasonable intervals (5-10s for status, 1s only for active user-facing operations)
- Cache status responses with TTL to reduce backend load
- Consider WebSocket for real-time status updates to frontend
- Use Spark REST API `/applications/{appId}/streaming/statistics` for detailed metrics
- Configure faster metrics polling via `spark.executor.metrics.pollingInterval` (milliseconds) if needed

**Warning signs:**
- Backend CPU usage high due to polling loops
- Status updates feel laggy to users
- High network traffic to Spark server
- Spark REST API rate limiting or timeouts
- Status showing "running" for failed jobs (long detection delay)

**Phase to address:**
Phase 2 (Advanced Node Types) - Implement event-driven status updates. Phase 3 (Production Hardening) - Optimize polling intervals and add metrics monitoring.

---

### Pitfall 10: Resource Cleanup Failure - Memory Leaks in Spark Connect Caching

**What goes wrong:**
Cached DataFrames on Spark Connect server are never unpersisted, causing memory usage to grow unbounded over multiple streaming job executions until OutOfMemory errors occur. Standard `df.unpersist()` calls from client don't reliably free memory.

**Why it happens:**
Spark Connect's network communication creates new proxy objects that lose track of cache state. When operations create new DataFrame proxies, they communicate with the same server-side DataFrame but don't maintain the `is_cached` flag. The `unpersist()` call from the client may not propagate correctly to the server.

**How to avoid:**
- Use `spark.catalog.clearCache()` for reliable cleanup after streaming job completion
- Call `df.sparkSession.catalog.clearCache()` at end of each batch in streaming query
- Avoid caching in streaming jobs unless absolutely necessary (streaming context already manages state)
- Monitor Spark server memory usage via JMX or REST API `/metrics/json`
- Set `spark.memory.fraction` and `spark.memory.storageFraction` appropriately
- Implement periodic cache cleanup job (every N hours)
- Test memory usage under repeated execution scenarios

**Warning signs:**
- Spark Connect server memory usage grows monotonically
- OutOfMemoryError in Spark driver or executor logs
- Cached DataFrames listed in Spark UI Storage tab never disappear
- Server performance degrades over time
- Need to restart Spark Connect server to reclaim memory

**Phase to address:**
Phase 1 (Basic Spark Connect Integration) - Avoid caching initially. Phase 3 (Production Hardening) - Add memory monitoring and cleanup if caching becomes necessary.

---

### Pitfall 11: Java Backend Blocks Quarkus I/O Threads with Spark Connect Calls

**What goes wrong:**
Spark Connect gRPC calls (blocking I/O) are made from Quarkus REST endpoints running on I/O threads, triggering "Blocking operation on a IO thread" warnings and degrading server throughput. Under load, the backend becomes unresponsive.

**Why it happens:**
Quarkus RESTEasy Reactive defaults to I/O thread execution for performance. Spark Connect's Java client uses blocking gRPC calls (not async). Developers don't annotate endpoints with `@Blocking`, causing Quarkus to detect blocking operations on event loop threads.

**How to avoid:**
- Annotate all Spark-related REST endpoints with `@Blocking` to run on worker threads
- Use Quarkus Virtual Threads (if Java 21+): `@RunOnVirtualThread` for lightweight blocking
- Configure worker thread pool size: `quarkus.thread-pool.max-threads`
- Consider async gRPC stubs if Spark Connect client supports them (check API)
- Isolate Spark operations in separate service class running on worker threads
- Monitor thread pool exhaustion via Quarkus metrics

**Warning signs:**
- "Blocking operation on a IO thread" warnings in logs
- Quarkus REST endpoints timing out under moderate load
- Event loop thread pool showing 100% utilization
- Increasing response times for Spark-related endpoints
- Application freezes during Spark job submission

**Phase to address:**
Phase 1 (Basic Spark Connect Integration) - Properly configure thread model from the start. Add to endpoint implementation checklist.

---

## Technical Debt Patterns

Shortcuts that seem reasonable but create long-term problems.

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Skip checkpoint configuration, use temp directory | Faster initial development, no storage setup | Cannot recover from failures, state lost on restart, production unusable | Never - even dev/test needs checkpoints for realistic behavior |
| Use ProcessBuilder to call spark-submit instead of SparkLauncher API | Familiar shell-script approach, quick to implement | No process control, poor error handling, output parsing fragile, hard to debug | Never - SparkLauncher exists for this reason |
| Share single checkpoint directory across flows | Simpler directory structure | State corruption, cannot run flows concurrently, debugging nightmare | Never - checkpoint isolation is mandatory |
| Hardcode Spark Connect URL in code | No configuration complexity | Cannot switch environments, Docker changes break code, painful to test | Only for proof-of-concept demos |
| Poll Spark REST API every second for status | Immediate status updates, simple implementation | High CPU/network overhead, doesn't scale, Spark server load | Acceptable for MVP with <10 concurrent jobs, must refactor for production |
| Generate Python code with inline credentials | Quick testing without secrets management | Security vulnerability, credentials in logs/UI | Only for local dev with fake credentials, never commit |
| Use alpine-based Spark Docker image | Smaller image size | Missing glibc causes Python library failures (numpy, pandas), hard to debug | Never for PySpark - use Debian/Ubuntu base |
| Don't version-pin Spark dependencies | Always get latest features | Breaking changes, version mismatches, non-reproducible builds | Never - always pin versions |

## Integration Gotchas

Common mistakes when connecting to external services.

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| Spark Connect gRPC | Creating new channel per request, not closing channels | Create singleton channel with keepalive, reuse across requests, close on shutdown |
| Docker Networking | Using container hostname from Java backend on host | Use `host.docker.internal` (Docker Desktop) or explicit IP with `extra_hosts` |
| PostgreSQL from Spark | Including JDBC driver in backend JAR, not in Spark classpath | Add `spark.jars` config or mount driver JAR into Spark Connect container |
| Quarkus + Spark Connect | Calling Spark Connect from I/O thread | Annotate endpoint with `@Blocking` or `@RunOnVirtualThread` |
| Python Dependencies | Installing packages in backend, expecting them in Spark | Use Spark session-based deps (PEX, addPyFile) or build custom Spark Docker image |
| Checkpoint Storage | Using container-local filesystem | Use Docker volume, cloud storage (S3/GCS), or HDFS for persistence |
| Spark REST API | Polling root endpoint instead of streaming-specific endpoints | Use `/api/v1/applications/{appId}/streaming/statistics` for query-specific data |
| Graceful Shutdown | Sending SIGKILL instead of SIGTERM | Use SIGTERM with adequate timeout, configure `terminationGracePeriodSeconds` |

## Performance Traps

Patterns that work at small scale but fail as usage grows.

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| No gRPC connection pooling | Slow job submission, connection timeouts | Use channel pool (5-10 channels), configure max concurrent streams | >50 concurrent job submissions |
| Synchronous status polling in request path | Frontend waits 10s for status update | Use async job submission, WebSocket for status, cache recent status | >10 concurrent users checking status |
| Single Spark Connect server for all flows | Executor contention, resource starvation | Use separate Spark clusters per team/priority, resource quotas | >100 concurrent streaming jobs |
| No query result caching | Repeated Spark REST API calls for same data | Cache status/metrics with TTL (5-10s), invalidate on state change | >1000 status checks/minute |
| Large PySpark code in single string | Readability issues, hard to debug, exceeds gRPC message size | Use modular code generation, submit .py files via HDFS/S3 | Code >1MB or >100 node types |
| In-memory query handle storage | Lost on backend restart, not shared across instances | Use Redis/database for query handle persistence | Backend with >1 instance or restarts |
| No rate limiting on job submission | Resource exhaustion, Spark cluster overwhelmed | Implement queue with max concurrent submissions (e.g., 10) | >50 job submissions/minute |

## Security Mistakes

Domain-specific security issues beyond general web security.

| Mistake | Risk | Prevention |
|---------|------|------------|
| Exposing Spark Connect port publicly | Unauthorized code execution on Spark cluster | Use internal Docker network, VPN, or mTLS for external access |
| Allowing user-provided Python code injection | Remote code execution, data exfiltration | Only allow structured flow definitions, generate PySpark from validated nodes |
| Storing checkpoint data without encryption | Sensitive data exposure from filesystem access | Enable encryption at rest for checkpoint storage (S3 SSE, HDFS encryption zones) |
| Sharing Spark session across users | User A can access User B's data | Use session-based isolation with Spark Connect session tags, implement multi-tenancy |
| Logging generated PySpark code with credentials | Credentials in log aggregation systems | Redact sensitive config from logs, use secret references instead of literal values |
| No authentication on Spark REST API | Anyone on network can stop/monitor jobs | Enable Spark authentication (`spark.authenticate=true`), use token-based auth |
| Using root user in Spark Docker container | Privilege escalation if container compromised | Run Spark as non-root user, set USER directive in Dockerfile |

## UX Pitfalls

Common user experience mistakes in this domain.

| Pitfall | User Impact | Better Approach |
|---------|-------------|-----------------|
| No feedback during job submission (10s+ wait) | User thinks UI is frozen, submits duplicate jobs | Show immediate "Submitting..." state, async submission with notification |
| "Running" status for failed jobs (until next poll) | User waits indefinitely for failed job | Implement push-based status updates, show error state within 5s |
| No visibility into streaming job progress | Cannot tell if job is healthy or stuck | Show input rate, processing time, lag metrics in real-time |
| Cannot stop streaming job from UI | User frustrated, asks admin to restart Docker | Implement stop button calling `query.stop()`, confirm graceful shutdown |
| Error messages show Java stack traces | User doesn't understand "gRPC UNAVAILABLE" | Translate technical errors to user-friendly messages ("Spark server unreachable") |
| No indication of checkpoint recovery on restart | User confused why job didn't start from beginning | Show "Recovering from checkpoint" status, display last processed offset |
| Flow execution history not retained | Cannot debug past failures | Store execution logs, status transitions, and error details in database |

## "Looks Done But Isn't" Checklist

Things that appear complete but are missing critical pieces.

- [ ] **Spark Connect Integration:** Rate→console works but gRPC channel not closed on shutdown - verify shutdown hooks implemented
- [ ] **Streaming Job Submission:** Job starts but no query handle stored - verify query ID persisted to database
- [ ] **Status Monitoring:** Polling shows "running" but no failure detection - verify timeout and health check logic
- [ ] **Checkpoint Configuration:** Checkpoints work locally but Docker volume not persistent - verify volume mounting in docker-compose
- [ ] **Python Dependencies:** Works with simple nodes but fails with pandas/numpy - verify custom Docker image or PEX dependencies
- [ ] **Error Handling:** Happy path works but Spark server down causes backend crash - verify connection retry and error propagation
- [ ] **Graceful Shutdown:** Can stop job but checkpoint corrupted - verify `stopGracefullyOnShutdown=true` configured
- [ ] **Resource Cleanup:** Memory stable for single job but leaks with repeated executions - verify cache clearing and channel cleanup
- [ ] **Version Compatibility:** Works today but breaks after Docker image update - verify version pinning in Gradle and Dockerfile
- [ ] **Network Configuration:** Works on developer machine but fails in Docker - verify host networking and port exposure

## Recovery Strategies

When pitfalls occur despite prevention, how to recover.

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Checkpoint corruption | MEDIUM | Delete checkpoint directory, restart query (loses state), restore from backup if available |
| gRPC channel leaks | LOW | Restart Java backend process, fix channel management, redeploy |
| Python dependency mismatch | MEDIUM | Rebuild Spark Docker image with correct dependencies, restart Spark Connect server |
| Version mismatch | MEDIUM | Align client and server versions, rebuild Docker image, update Gradle dependencies, restart |
| Lost query handle | MEDIUM | Query Spark REST API for active queries, match by start time/name, restore handle to database |
| Memory leak in Spark server | LOW | Call `catalog.clearCache()` via REST API, restart Spark Connect server if OOM |
| No graceful shutdown (data loss) | HIGH | Enable checkpointing if not present, restore from last valid checkpoint, replay missed data if possible |
| Network connectivity failure | LOW | Verify Docker network config, restart containers with correct `extra_hosts`, test connectivity |
| Streaming job stuck | LOW | Check Spark UI for stage progress, review logs for errors, stop query and restart |
| Checkpoint schema mismatch | HIGH | Cannot recover automatically - requires new checkpoint and state rebuild from source |

## Pitfall-to-Phase Mapping

How roadmap phases should address these pitfalls.

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| Spark Connect API Limitations | Phase 1: Basic Integration | Rate→console test succeeds, code review confirms DataFrame-only APIs |
| gRPC Channel Management | Phase 1: Basic Integration | Shutdown hook closes channel, `netstat` shows no leaked connections after 10 submissions |
| Python Dependency Mismatch | Phase 1: Basic Integration | Custom Docker image builds successfully, rate→console with simple deps works |
| No Query Handle | Phase 1: Basic Integration | Query ID stored in DB, can retrieve status after submission |
| Graceful Shutdown | Phase 1: Basic Integration | Stop job mid-execution, restart successfully from checkpoint |
| Checkpoint Directory Management | Phase 1: Basic Integration | Unique path per flow, restart recovers state, concurrent flows don't interfere |
| Docker Networking | Phase 1: Basic Integration | Backend connects to Spark Connect in Docker, job executes successfully |
| Version Mismatch | Phase 1: Basic Integration | Gradle and Dockerfile use same pinned version variable |
| Inefficient Status Polling | Phase 2: Advanced Nodes | Poll interval ≥5s, status updates feel responsive, CPU usage acceptable |
| Resource Cleanup | Phase 3: Production Hardening | Memory usage stable over 100 job executions, no cache leaks detected |
| Quarkus I/O Thread Blocking | Phase 1: Basic Integration | No blocking warnings in logs, load test shows good throughput |

## Sources

**Spark Connect Architecture and Limitations:**
- [Spark Connect Overview - Apache Spark Documentation](https://spark.apache.org/docs/latest/spark-connect-overview.html)
- [Microsoft Q&A: SparkContext not supported in Spark Connect](https://learn.microsoft.com/en-us/answers/questions/5735212/how-to-fix-attribute-sparkcontext-is-not-supported)
- [Databricks: Standard compute limitations](https://docs.databricks.com/en/compute/access-mode-limitations.html)
- [Adopting Spark Connect - Medium](https://medium.com/data-science/adopting-spark-connect-cdd6de69fa98)

**gRPC and Channel Management:**
- [Use gRPC with Spark Java - MojoAuth](https://mojoauth.com/grpc/use-grpc-with-spark-java/)
- [Use gRPC in Spark Java - SSOJet](https://ssojet.com/grpc/use-grpc-in-spark-java/)
- [gRPC Performance Best Practices](https://grpc.io/docs/guides/performance/)
- [GitHub: Channel creation best practice - grpc-java](https://github.com/grpc/grpc-java/issues/3268)
- [Exploring the Spark Connect gRPC API](https://the.agilesql.club/2024/01/exploring-the-spark-connect-grpc-api/)

**Python Dependency Management:**
- [Databricks Blog: Python Dependency Management in Spark Connect](https://www.databricks.com/blog/python-dependency-management-spark-connect)
- [Tutorial: Running PySpark inside Docker containers - Spot.io](https://spot.io/blog/tutorial-running-pyspark-inside-docker-containers/)
- [Apache Spark: Python Package Management](https://archive.apache.org/dist/spark/docs/3.3.2/api/python/user_guide/python_packaging.html)

**Streaming Lifecycle and Graceful Shutdown:**
- [Graceful shutdown for Spark Structured Streaming - Medium](https://medium.com/@grigor60/graceful-shutdown-for-spark-structured-streaming-why-is-it-complicated-and-how-can-it-be-done-bef2674b731c)
- [Spark Streaming Graceful Shutdown Part 1 - Cloudera Community](https://community.cloudera.com/t5/Community-Articles/Spark-Streaming-Graceful-Shutdown-Part1/ta-p/366958)
- [Stopping a Structured Streaming query - WaitingForCode](https://www.waitingforcode.com/apache-spark-structured-streaming/stopping-structured-streaming-query/read)
- [Spark streaming lifecycle management - GitHub Pages](https://ngorchakova.github.io/jvmwarstories/spark-streaming-lifecycle/)

**Monitoring and Status Polling:**
- [Spark Streaming Jobs: Monitoring and Alerting - Medium](https://medium.com/@arvindpant/streaming-jobs-monitoring-and-alerting-for-silent-failure-part-1-94fdd6eae6cb)
- [Monitoring and Instrumentation - Spark Documentation](https://spark.apache.org/docs/latest/monitoring.html)
- [Databricks: Monitoring Structured Streaming queries](https://docs.databricks.com/aws/en/structured-streaming/stream-monitoring.html)
- [Databricks Blog: Structured Streaming UI](https://www.databricks.com/blog/2020/07/29/a-look-at-the-new-structured-streaming-ui-in-apache-spark-3-0.html)

**Checkpointing and Recovery:**
- [The Ultimate Guide to Checkpoint Location - RisingWave](https://risingwave.com/blog/the-ultimate-guide-to-setting-checkpoint-location-in-spark-streaming/)
- [Databricks: Structured Streaming checkpoints](https://docs.databricks.com/aws/en/structured-streaming/checkpoints)
- [How to Fix Checkpoint Failures - OneUptime](https://oneuptime.com/blog/post/2026-01-24-spark-streaming-checkpoint-failures/view)
- [Databricks: Recover from checkpoint failure](https://docs.databricks.com/aws/en/ldp/recover-streaming)

**Resource Cleanup and Memory Leaks:**
- [Medium: Why DataFrames Won't Unpersist in Spark Connect](https://medium.com/@dhsoni2510/why-your-spark-dataframes-wont-unpersist-and-how-spark-connect-changes-everything-99805eb792d3)
- [Disney+ Debugs Memory Leaks in Spark Streaming - Databricks Blog](https://www.databricks.com/blog/2020/12/16/a-step-by-step-guide-for-debugging-memory-leaks-in-spark-applications.html)
- [Medium: Step-by-step guide for debugging memory leaks](https://medium.com/disney-streaming/a-step-by-step-guide-for-debugging-memory-leaks-in-spark-applications-e0dd05118958)

**Docker and Networking:**
- [GitHub: Troubleshooting Spark Connect with Docker Compose - Bitnami](https://github.com/bitnami/containers/issues/69194)
- [Medium: Setting up Spark standalone cluster on Docker](https://medium.com/@MarinAgli1/setting-up-a-spark-standalone-cluster-on-docker-in-layman-terms-8cbdc9fdd14b)
- [devgem.io: Troubleshooting Spark Worker Connection Issues](https://www.devgem.io/posts/troubleshooting-apache-spark-worker-connection-issues-in-docker)

**Job Submission from Java:**
- [GitHub: How to submit Spark job from Java code](https://github.com/mahmoudparsian/data-algorithms-book/blob/master/misc/how-to-submit-spark-job-from-java-code.md)
- [Apache Spark: SparkLauncher.java](https://github.com/apache/spark/blob/master/launcher/src/main/java/org/apache/spark/launcher/SparkLauncher.java)
- [Apache Spark: Submitting Applications](https://spark.apache.org/docs/latest/submitting-applications.html)

**Quarkus Reactive/Blocking:**
- [Quarkus Blog: RESTEasy Reactive - To block or not to block](https://quarkus.io/blog/resteasy-reactive-smart-dispatch/)
- [Avoiding Blocking Issues When Using Quarkus Reactive](https://weinan.io/2021/10/01/Avoiding-Blocking-Issues-When-Using-Quarkus-Reactive.html)
- [Medium: Quarkus blocking vs non-blocking application](https://medium.com/@mvinicus/quarkus-blocking-vs-non-blocking-application-11d06dd30787)

**Spark Connect Configuration:**
- [Ilum: Spark Connect Configuration Guide](https://ilum.cloud/docs/user-guides/spark-connect/)
- [Databricks Blog: Spark Connect Available in Apache Spark 3.4](https://www.databricks.com/blog/2023/04/18/spark-connect-available-apache-spark.html)
- [PySpark Quickstart: Spark Connect](https://spark.apache.org/docs/latest/api/python/getting_started/quickstart_connect.html)

---
*Pitfalls research for: Adding Spark Connect execution to Java/Quarkus streaming flow designer*
*Researched: 2026-02-08*
