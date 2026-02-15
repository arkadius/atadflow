# State: Atadflow

## Current Position

Phase: 2 of 2 (spark-connect-production-research)
Plan: 02-01 complete
Status: Phase complete
Last activity: 2026-02-15 — Completed 02-01-PLAN.md (Spark Connect production research)

Progress: █ 2/2 phases

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-15)

**Core value:** Users can visually design a streaming pipeline and execute it on Spark without writing code.
**Current focus:** v1.2 Helm Chart Distribution

## Accumulated Context

- v1.0 shipped: end-to-end Spark Connect execution with job lifecycle
- v1.1 shipped: self-contained Docker distribution (670MB image, multi-stage Dockerfile)
- v1.2 shipped: Helm chart with PostgreSQL dependency
- v1.3 (current): Spark Connect production research completed
- Docker Compose: postgres → spark-connect → atadflow with health ordering
- Health checks: PythonLivenessCheck + SmallRye Health (/q/health/live, /q/health/ready)
- 12 backend tests passing + 1 integration test (DockerComposeIntegrationTest)
- PySpark 4.x quirk: query.stop() broken over Spark Connect, using spark.stop() instead
- Helm chart requires `--dependency-update` flag for template rendering
- Spark Connect production: Apache Spark K8s Operator v0.7.0+ recommended

## Decisions

- Used Bitnami PostgreSQL 18.3.0 as chart dependency
- Password via --set flag (no hardcoded secrets)
- Health probes use /q/health endpoints (matching docker-compose)
- Spark Connect URL default: sc://spark-connect:15002
- Apache Spark K8s Operator v0.7.0+ for production deployment (Pattern 3: Helm chart dependency)
- Dynamic allocation: minExecutors=1, maxExecutors=5, initialExecutors=2
- RBAC required: ServiceAccount + Role with pod/service/configmap/secrets permissions

## Session

Last session: 2026-02-15
Stopped at: Phase 2 complete - Spark Connect production research finalized
Resume file: None (milestone complete)
Remaining plans: None (v1.3 milestone complete)
