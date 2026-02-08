# Stack Research: Spark Connect Integration

**Domain:** Streaming Flow Designer with Spark Connect Integration
**Researched:** 2026-02-08
**Confidence:** MEDIUM

## Executive Summary

Adding Spark Connect integration to a Java/Quarkus backend requires choosing between three architectural approaches. **Spark Standalone with REST API submission is recommended** for this project because it's straightforward, Docker-friendly, and doesn't require additional middleware. Spark Connect itself does not support Java clients for submitting PySpark code—it only supports Python and Scala clients. The recommended approach uses Spark's built-in REST submission API (enabled by default on port 6066) with Quarkus REST Client for HTTP communication.

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Apache Spark (Standalone) | 4.0.2 | Spark cluster runtime for executing PySpark jobs | Latest stable release (Feb 5, 2026), supports REST API submission |
| Spark REST Submission API | Built-in | Submit PySpark applications via HTTP | Enabled by default in standalone mode, no additional dependencies |
| Quarkus REST Client | 3.31.0+ | HTTP client for calling Spark REST API | Reactive, built-in to Quarkus, handles async/sync patterns |
| Docker (apache/spark) | 4.0.2 | Containerized Spark server | Official Apache image, includes Spark Connect server and standalone master |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| quarkus-rest-client | 3.31.0+ | Reactive REST client for Spark API calls | Required for all Spark job submissions from backend |
| quarkus-rest-client-jackson | 3.31.0+ | JSON serialization for REST payloads | Required for building submission request JSON |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Docker Compose | Run Spark standalone cluster locally | Configure spark-master with REST API enabled on port 6066 |
| Spark Web UI | Monitor job execution | Available at port 8080 (master) and 4040 (driver application) |
| Spark History Server (optional) | View completed job logs | Useful for debugging failed jobs |

## Installation

### Backend (Gradle Kotlin DSL)

```kotlin
// build.gradle.kts
dependencies {
    // Quarkus REST client for Spark API communication
    implementation("io.quarkus:quarkus-rest-client")
    implementation("io.quarkus:quarkus-rest-client-jackson")
}
```

### Docker Compose Setup

```yaml
# docker-compose.yml
services:
  spark-master:
    image: apache/spark:4.0.2
    container_name: spark-master
    ports:
      - "8080:8080"  # Spark Master Web UI
      - "7077:7077"  # Spark Master communication
      - "6066:6066"  # REST API submission
    environment:
      - SPARK_MODE=master
      - SPARK_MASTER_HOST=spark-master
      - SPARK_MASTER_PORT=7077
      - SPARK_MASTER_WEBUI_PORT=8080
      - SPARK_MASTER_REST_ENABLED=true  # Enable REST API
      - SPARK_MASTER_REST_PORT=6066
    command: >
      /opt/spark/bin/spark-class org.apache.spark.deploy.master.Master
      --host spark-master
      --port 7077
      --webui-port 8080

  spark-worker:
    image: apache/spark:4.0.2
    container_name: spark-worker
    depends_on:
      - spark-master
    ports:
      - "8081:8081"  # Worker Web UI
      - "4040:4040"  # Application UI
    environment:
      - SPARK_MODE=worker
      - SPARK_MASTER_URL=spark://spark-master:7077
      - SPARK_WORKER_WEBUI_PORT=8081
    command: >
      /opt/spark/bin/spark-class org.apache.spark.deploy.worker.Worker
      spark://spark-master:7077
      --webui-port 8081
```

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| Spark Standalone REST API | Apache Livy | If you need multi-tenant job execution, session management, or shared Spark contexts across multiple users |
| Spark Standalone REST API | Spark Connect | If you're building a Python or Scala client (not Java/Quarkus) and want DataFrame API instead of code submission |
| Spark Standalone REST API | spark-submit via ProcessBuilder | If you need synchronous execution with direct access to stdout/stderr, but this is less cloud-native |

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| Spark Connect from Java | No Java client support for executing PySpark code (only Python/Scala clients) | Spark Standalone REST API |
| Apache Livy 0.8.0 | Does not support Spark 4.0 (only up to Spark 3.5.x), development discussion phase for 4.0 | Wait for Livy 1.0 or use Spark REST API |
| spark-connect-client-jvm | Designed for Scala/JVM code execution, not for submitting PySpark scripts | Spark REST API for PySpark submission |
| ProcessBuilder with spark-submit | Requires spark-submit binary in backend container, non-standard for cloud deployments | REST API submission |

## Architecture Decision: REST Submission API

### Why This Approach

1. **Native to Spark Standalone**: Enabled by default, no additional middleware (unlike Livy)
2. **Docker-Friendly**: Master runs in container, backend calls HTTP API
3. **Quarkus-Native**: Uses standard Quarkus REST Client (reactive or blocking)
4. **Current Compatibility**: Works with Spark 4.0.2 today (unlike Livy)
5. **PySpark-Compatible**: Submit .py files directly

### How It Works

```java
// Quarkus REST Client Interface
@Path("/v1/submissions")
@RegisterRestClient(configKey = "spark-api")
public interface SparkSubmissionClient {

    @POST
    @Path("/create")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    SubmissionResponse submitJob(SubmissionRequest request);

    @GET
    @Path("/status/{submissionId}")
    @Produces(MediaType.APPLICATION_JSON)
    StatusResponse getStatus(@PathParam("submissionId") String submissionId);
}

// Submission Request Model
public class SubmissionRequest {
    public String action = "CreateSubmissionRequest";
    public String appResource;  // Path to .py file
    public String clientSparkVersion = "4.0.2";
    public String mainClass = null;  // null for PySpark
    public Map<String, String> environmentVariables = new HashMap<>();
    public List<String> appArgs = new ArrayList<>();
    public Map<String, String> sparkProperties = new HashMap<>();
}
```

### Configuration (application.properties)

```properties
# Spark REST API Client
quarkus.rest-client.spark-api.url=http://localhost:6066
quarkus.rest-client.spark-api.scope=javax.inject.Singleton
```

### Job Submission Flow

1. **Backend**: Generate PySpark code (already implemented in `CodeGenerationService`)
2. **Backend**: Write PySpark code to temporary .py file
3. **Backend**: Upload .py file to Spark-accessible location (volume mount or HDFS)
4. **Backend**: POST to `/v1/submissions/create` with file path
5. **Spark**: Returns `submissionId` (e.g., "driver-20260208123456-0001")
6. **Backend**: Poll GET `/v1/submissions/status/{submissionId}` for status
7. **Backend**: Update job status in PostgreSQL

### Status Polling

```java
@Scheduled(every = "5s")
void pollRunningJobs() {
    List<Job> runningJobs = jobRepository.findByStatus(JobStatus.RUNNING);
    for (Job job : runningJobs) {
        StatusResponse status = sparkClient.getStatus(job.getSubmissionId());
        if ("FINISHED".equals(status.driverState)) {
            job.setStatus(JobStatus.COMPLETED);
        } else if ("FAILED".equals(status.driverState) || "ERROR".equals(status.driverState)) {
            job.setStatus(JobStatus.FAILED);
        }
        jobRepository.persist(job);
    }
}
```

## Spark Connect (Why Not Recommended for This Use Case)

**What is Spark Connect**: A client-server architecture introduced in Spark 3.4 that allows remote connectivity to Spark clusters using gRPC protocol.

**Supported Languages**:
- Python (PySpark) — since Spark 3.4
- Scala — since Spark 3.5
- **Java: NOT SUPPORTED** for Spark Connect client

**Why It Doesn't Fit**:
- Spark Connect is designed for **DataFrame API access** from remote clients
- Java backend cannot use Spark Connect to **submit PySpark code**
- The `spark-connect-client-jvm` library is for Scala/JVM code execution, not PySpark submission
- Connection via `SparkSession.builder().remote("sc://localhost:15002")` only works in Scala/Python

**When to Use Spark Connect**:
- Building a Python or Scala application that needs remote DataFrame API access
- Interactive notebooks (JupyterLab with PySpark)
- Spark Connect is excellent for what it's designed for, but not for Java backends submitting PySpark scripts

## Apache Livy (Why Not Recommended Yet)

**What is Livy**: REST interface for interacting with Apache Spark, providing session management and code execution.

**Current Status**:
- Latest version: 0.8.0
- Supports: Spark 3.0 - 3.5.x, Scala 2.12
- **Spark 4.0 Support**: In discussion phase for future 0.10.0 or 1.0.0 release

**Why Not Now**:
- Spark 4.0 compatibility not available in stable release
- Requires deploying additional middleware service
- More complex than needed for single-user job submission

**When to Use Livy**:
- Multi-tenant environments where users share a Spark cluster
- Session-based interactive execution (like notebooks)
- When Spark 4.0 support is released and your use case requires session management

## Version Compatibility

| Package/Image | Version | Compatible With | Notes |
|---------------|---------|-----------------|-------|
| apache/spark Docker image | 4.0.2 | Spark 4.0.2 | Released Feb 5, 2026 |
| Spark REST API | Built-in | Spark 3.0+ | Enabled by default in standalone mode |
| quarkus-rest-client | 3.31.0+ | Quarkus 3.31.2 | Already in your project |
| Java | 25 | Spark 4.0.2 | Spark supports Java 17+, your Java 25 is compatible |

## Implementation Checklist

- [ ] Add `quarkus-rest-client` and `quarkus-rest-client-jackson` dependencies
- [ ] Create Docker Compose file with Spark master (REST enabled) and worker
- [ ] Create `SparkSubmissionClient` REST client interface
- [ ] Update `SparkSubmissionService` to call REST API instead of stub
- [ ] Implement file writing for generated PySpark code
- [ ] Configure shared volume between Quarkus and Spark containers
- [ ] Add scheduled polling for job status updates
- [ ] Update `Job` entity to store `submissionId` from Spark
- [ ] Test end-to-end: flow → code generation → submission → status tracking

## Sources

**HIGH Confidence (Official Documentation & Recent Releases)**:
- [Apache Spark News](https://spark.apache.org/news/) — Spark 4.0.2 release (Feb 5, 2026)
- [Spark Connect Overview](https://spark.apache.org/docs/latest/spark-connect-overview.html) — Language support verification
- [Spark Standalone Mode](https://spark.apache.org/docs/latest/spark-standalone.html) — REST API configuration
- [Monitoring and Instrumentation](https://spark.apache.org/docs/latest/monitoring.html) — Status tracking APIs

**MEDIUM Confidence (Community & Docker Hub)**:
- [apache/spark Docker Hub](https://hub.docker.com/r/apache/spark/) — Official Docker images
- [Spark REST API Tutorial](https://sparkbyexamples.com/spark/submit-spark-job-via-rest-api/) — REST submission examples
- [Spark Connect Docker Compose](https://medium.com/@yssmelo/spark-connect-launch-spark-applications-anywhere-with-the-client-server-architecture-dbt-f99399c566fe) — Docker setup patterns

**LOW Confidence (Livy Compatibility)**:
- [Apache Livy GitHub Discussions](https://github.com/apache/incubator-livy) — Spark 4.0 support discussion (not released)
- [Livy Compatibility Status](http://www.mail-archive.com/dev@livy.apache.org/msg00233.html) — Scala 2.13 + Spark 4 discussion

---
*Stack research for: Spark Connect Integration (Subsequent Milestone)*
*Researched: 2026-02-08*
*Primary Recommendation: Spark Standalone REST API (not Spark Connect client)*
