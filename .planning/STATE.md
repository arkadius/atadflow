# State: Atadflow

## Current Position

Phase: 1 of 1 (helm-chart)
Plan: 01-01 complete
Status: Phase complete
Last activity: 2026-02-15 — Completed 01-01-PLAN.md (Helm chart with PostgreSQL)

Progress: █ 1/1 plans

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-15)

**Core value:** Users can visually design a streaming pipeline and execute it on Spark without writing code.
**Current focus:** v1.2 Helm Chart Distribution

## Accumulated Context

- v1.0 shipped: end-to-end Spark Connect execution with job lifecycle
- v1.1 shipped: self-contained Docker distribution (670MB image, multi-stage Dockerfile)
- v1.2 (current): Helm chart with PostgreSQL dependency
- Docker Compose: postgres → spark-connect → atadflow with health ordering
- Health checks: PythonLivenessCheck + SmallRye Health (/q/health/live, /q/health/ready)
- 12 backend tests passing + 1 integration test (DockerComposeIntegrationTest)
- PySpark 4.x quirk: query.stop() broken over Spark Connect, using spark.stop() instead
- Helm chart requires `--dependency-update` flag for template rendering

## Decisions

- Used Bitnami PostgreSQL 18.3.0 as chart dependency
- Password via --set flag (no hardcoded secrets)
- Health probes use /q/health endpoints (matching docker-compose)
- Spark Connect URL default: sc://spark-connect:15002

## Session

Last session: 2026-02-15
Stopped at: Plan 01-01 complete - Helm chart created with PostgreSQL dependency
Resume file: None (phase complete)
Remaining plans: None
