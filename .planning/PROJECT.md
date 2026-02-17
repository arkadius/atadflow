# Atadflow

## What This Is

A visual streaming flow designer that lets users build Apache Spark Structured Streaming pipelines by connecting nodes in a DAG editor. Users drag source/transform/sink nodes, configure them, and execute the resulting PySpark code on a Spark Connect cluster — all without writing code. Deployable via Docker Compose or Helm chart on Kubernetes.

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
- ✓ Helm chart with Bitnami PostgreSQL as chart dependency — v1.2
- ✓ Spark Connect production solution research (Kubeflow Spark Operator) — v1.2
- ✓ Kubernetes health probes (/q/health/live, /q/health/ready) — v1.2
- ✓ Configurable resources via values.yaml — v1.2
- ✓ K8s integration test with full Spark execution on k3d — v1.2
- ✓ Telepresence local development workflow documented — v1.2

### Active

(None — planning next milestone)

### Out of Scope

- Log/output viewing — adds complexity, not needed to prove execution works
- Kafka sources/sinks — rate source + console sufficient for minimal proof
- Authentication/multi-user — single-user tool for now
- Spark cluster management — use pre-configured Spark Connect

## Context

Shipped v1.2 with Helm chart distribution for Kubernetes.

- Backend: Java 25, Quarkus 3.31.2, Gradle 9.3.1 (Kotlin DSL), PostgreSQL 16
- Frontend: TypeScript, React 19, Vite 7, @xyflow/react, @monaco-editor/react
- Spark Connect server: apache/spark:4.0.2 (Docker Compose or Kubeflow Spark Operator on K8s)
- Multi-stage Dockerfile with frontend bundled into backend JAR (670MB)
- Health checks: PythonLivenessCheck + SmallRye Health endpoints
- Docker Compose: postgres → spark-connect → atadflow with health ordering
- Helm chart: Bitnami PostgreSQL subchart, configurable resources, health probes
- 12 backend tests + 2 integration tests (DockerComposeIntegrationTest, KubernetesIntegrationTest)

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
| Bitnami PostgreSQL as Helm subchart | Production-hardened, HTTPS repo for compatibility | ✓ Good |
| Kubeflow Spark Operator for K8s integration test | Stable Helm chart, well-known CRDs | ✓ Good |
| kubectl port-forward over Fabric8 LocalPortForward | Fabric8 was unreliable (NoHttpResponseException) | ✓ Good |
| busybox init container for PostgreSQL readiness | Prevents CrashLoopBackOff during slow DB startup | ✓ Good |
| External k3d cluster for integration testing | User preference, not Testcontainers | ✓ Good |

---
*Last updated: 2026-02-17 after v1.2 milestone*
