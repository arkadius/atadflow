# State: Atadflow

## Current Position

Phase: 3 of 4 (kubernetes-health-probes)
Plan: 03-01 complete
Status: Phase complete
Last activity: 2026-02-15 — Completed 03-01-PLAN.md (Health probes verification)

Progress: ███░ 3/4 phases

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-15)

**Core value:** Users can visually design a streaming pipeline and execute it on Spark without writing code.
**Current focus:** v1.2 Helm Chart Distribution

## Accumulated Context

- v1.0 shipped: end-to-end Spark Connect execution with job lifecycle
- v1.1 shipped: self-contained Docker distribution (670MB image, multi-stage Dockerfile)
- v1.2 shipped: Helm chart with PostgreSQL dependency
- v1.3 shipped: Spark Connect production research completed
- v1.4 (current): Kubernetes health probes verified
- Docker Compose: postgres → spark-connect → atadflow with health ordering
- Health checks: PythonLivenessCheck + SmallRye Health (/q/health/live, /q/health/ready)
- 12 backend tests passing + 1 integration test (DockerComposeIntegrationTest)
- PySpark 4.x quirk: query.stop() broken over Spark Connect, using spark.stop() instead
- Helm chart requires `--dependency-update` flag for template rendering
- Spark Connect production: Apache Spark K8s Operator v0.7.0+ recommended
- Kubernetes health probes: Fully configured with production-ready defaults (60s liveness, 30s readiness initial delay)
- Resource configurability: All probe timing and container resources externalized to values.yaml

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
Stopped at: Phase 3 complete - Kubernetes health probes verified
Resume file: None (phase complete)
Remaining plans: Phase 4 (K8s integration test & documentation)
