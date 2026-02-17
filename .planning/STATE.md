# State: Atadflow

## Current Position

Phase: v1.2 complete — planning next milestone
Plan: N/A
Status: Ready to plan
Last activity: 2026-02-17 — v1.2 milestone complete

Progress: v1.2 SHIPPED

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-17)

**Core value:** Users can visually design a streaming pipeline and execute it on Spark without writing code.
**Current focus:** Planning next milestone

## Accumulated Context

- v1.0 shipped: end-to-end Spark Connect execution with job lifecycle
- v1.1 shipped: self-contained Docker distribution (670MB image, multi-stage Dockerfile)
- v1.2 shipped: Helm chart with PostgreSQL dependency, K8s integration test, Telepresence docs
- Docker Compose: postgres → spark-connect → atadflow with health ordering
- Helm chart: Bitnami PostgreSQL subchart, configurable resources, health probes
- Health checks: PythonLivenessCheck + SmallRye Health (/q/health/live, /q/health/ready)
- 12 backend tests + 2 integration tests (DockerComposeIntegrationTest, KubernetesIntegrationTest)
- K8s integration test: gated behind K8S_INTEGRATION_TEST=true env var

## Decisions

- Bitnami PostgreSQL 18.3.0 as Helm subchart (HTTPS repo)
- Kubeflow Spark Operator for K8s integration testing
- kubectl port-forward for test reliability
- busybox init container for PostgreSQL readiness
- External k3d cluster for integration testing (user preference)

## Session

Last session: 2026-02-17
Stopped at: v1.2 milestone archived
Resume file: None
Remaining plans: None
