# Atadflow

## What This Is

A visual streaming flow designer that lets users build Apache Spark Structured Streaming pipelines by connecting nodes in a DAG editor. Users drag source/transform/sink nodes, configure them, and submit the resulting PySpark code to a Spark cluster for execution.

## Core Value

Users can visually design a streaming pipeline and execute it on Spark without writing code.

## Current Milestone: v1.0 Minimal Spark Execution

**Goal:** Prove end-to-end execution — a minimal flow (rate source → console) actually runs on Spark Connect.

**Target features:**
- Real Spark Connect submission (replacing stub)
- Docker-based Spark Connect server
- Job status tracking (PENDING → RUNNING → COMPLETED/FAILED)

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

- ✓ Flow CRUD (create, read, update, delete) — init
- ✓ Visual DAG editor with drag-and-drop node palette — init
- ✓ 6 built-in node types (read-stream, filter, select, group-by, with-watermark, write-stream) — init
- ✓ PySpark code generation from flow graph — init
- ✓ Node configuration forms with per-type config fields — init
- ✓ Custom Python code editing per node — init
- ✓ Job submission/cancellation lifecycle (stub) — init
- ✓ REST API for flows, jobs, node types — init

### Active

<!-- Current scope. Building toward these. -->

- [ ] Spark Connect submission (replace stub with real execution)
- [ ] Docker Compose Spark Connect server setup
- [ ] Job status polling (PENDING → RUNNING → COMPLETED/FAILED)

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- Log/output viewing — adds complexity, not needed to prove execution works
- Kafka sources/sinks — rate source + console sufficient for minimal proof
- Authentication/multi-user — single-user tool for now
- Spark cluster management — use pre-configured Docker Spark Connect

## Context

- Backend: Java 25, Quarkus 3.31.2, Gradle 9.3.1 (Kotlin DSL), PostgreSQL 16
- Frontend: TypeScript, React 19, Vite 7, @xyflow/react, @monaco-editor/react
- SparkSubmissionService is currently a stub (logs + sets status to SUBMITTED)
- CodeGenerationService generates PySpark from topologically sorted flow nodes
- Existing docker-compose.yml has PostgreSQL; needs Spark Connect server added

## Constraints

- **Tech stack**: Java/Quarkus backend, PySpark code generation — no Python backend
- **Spark Connect**: Must use Spark Connect (gRPC) protocol, not spark-submit CLI
- **Docker**: Spark Connect server runs in local Docker container

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| PySpark code generation (not Scala/Java) | Spark Connect Python client is mature and well-documented | ✓ Good |
| Spark Connect over spark-submit | Cleaner integration, no need to shell out to CLI | — Pending |
| Rate source + console for minimal proof | No external dependencies (Kafka), fastest path to prove execution | — Pending |

---
*Last updated: 2026-02-08 after milestone v1.0 initialization*
