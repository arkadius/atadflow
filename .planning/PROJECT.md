# Atadflow

## What This Is

A visual streaming flow designer that lets users build Apache Spark Structured Streaming pipelines by connecting nodes in a DAG editor. Users drag source/transform/sink nodes, configure them, and execute the resulting PySpark code on a Spark Connect cluster — all without writing code.

## Core Value

Users can visually design a streaming pipeline and execute it on Spark without writing code.

## Requirements

### Validated

- ✓ Flow CRUD (create, read, update, delete) — init
- ✓ Visual DAG editor with drag-and-drop node palette — init
- ✓ 6 built-in node types (read-stream, filter, select, group-by, with-watermark, write-stream) — init
- ✓ PySpark code generation from flow graph — init
- ✓ Node configuration forms with per-type config fields — init
- ✓ Custom Python code editing per node — init
- ✓ Job submission/cancellation lifecycle (stub) — init
- ✓ REST API for flows, jobs, node types — init
- ✓ Spark Connect server in Docker Compose (port 15002) — v1.0
- ✓ PySpark code generation using SparkSession.builder.remote() — v1.0
- ✓ Python/PySpark availability check on startup — v1.0
- ✓ Real async subprocess execution via ProcessBuilder — v1.0
- ✓ Job status transitions (PENDING -> RUNNING -> SUCCEEDED/FAILED/CANCELLED) — v1.0
- ✓ Process cancellation with graceful Spark session shutdown — v1.0
- ✓ Frontend polls active jobs every 5s — v1.0
- ✓ Temp file cleanup after execution — v1.0
- ✓ Multi-stage Dockerfile (Node + Gradle + JRE + Python/PySpark) — v1.1
- ✓ Frontend served by Quarkus with SPA routing — v1.1
- ✓ Python dependencies bundled (pyspark[connect], pandas, pyarrow, numpy) — v1.1
- ✓ Health checks (PythonLivenessCheck, /q/health endpoints) — v1.1
- ✓ Docker Compose with health-based service ordering — v1.1
- ✓ DockerComposeIntegrationTest (end-to-end deployment validation) — v1.1

### Active

## Current Milestone: v1.2 Helm Chart Distribution

**Goal:** Deploy Atadflow on Kubernetes via Helm chart with proper resource management and production-ready configuration.

**Target features:**
- Helm chart for Kubernetes deployment (Atadflow + PostgreSQL + Spark Connect)
- Configurable resources, replicas, and environment via values.yaml
- Kubernetes health probes using existing /q/health endpoints

### Out of Scope

- Log/output viewing — adds complexity, not needed to prove execution works
- Kafka sources/sinks — rate source + console sufficient for minimal proof
- Authentication/multi-user — single-user tool for now
- Spark cluster management — use pre-configured Spark Connect

## Context

Shipped v1.1 with self-contained Docker distribution (670MB image).

- Backend: Java 25, Quarkus 3.31.2, Gradle 9.3.1 (Kotlin DSL), PostgreSQL 16
- Frontend: TypeScript, React 19, Vite 7, @xyflow/react, @monaco-editor/react
- Spark Connect server: apache/spark:4.0.2 in Docker Compose (port 15002)
- Multi-stage Dockerfile with frontend bundled into backend JAR
- Health checks: PythonLivenessCheck + SmallRye Health endpoints
- Docker Compose: postgres → spark-connect → atadflow with health ordering
- 12 backend tests passing + 1 integration test (DockerComposeIntegrationTest)

## Constraints

- **Tech stack**: Java/Quarkus backend, PySpark code generation — no Python backend
- **Spark Connect**: Must use Spark Connect (gRPC) protocol, not spark-submit CLI
- **Docker**: Existing Dockerfile and docker-compose.yml as foundation
- **PySpark 4.x quirk**: StreamingQuery.stop() over Spark Connect triggers COMMAND_NOT_SET bug — use spark.stop() instead

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| PySpark code generation (not Scala/Java) | Spark Connect Python client is mature and well-documented | ✓ Good |
| Spark Connect over spark-submit | Cleaner integration, no need to shell out to CLI | ✓ Good |
| Rate source + console for minimal proof | No external dependencies (Kafka), fastest path to prove execution | ✓ Good |
| apache/spark:4.0.2 Docker image | Standard way to run Spark Connect server | ✓ Good |
| QuarkusTransaction.requiringNew() for async callbacks | Self-calls bypass CDI proxy interceptors | ✓ Good |
| spark.stop() over query.stop() for cancellation | query.stop() triggers COMMAND_NOT_SET bug in PySpark 4.x | ✓ Good |
| Process.onExit() + ManagedExecutor | Context-propagated async process monitoring | ✓ Good |
| useEffect cleanup pattern for polling | Simpler lifecycle handling, automatic cleanup | ✓ Good |
| Multi-stage Dockerfile (670MB) | All deps in one image, no external Python install needed | ✓ Good |
| Frontend bundled in META-INF/resources/ | Single artifact deployment, SPA routing via Vert.x filter | ✓ Good |

---
*Last updated: 2026-02-15 after v1.2 milestone start*
