# State: Atadflow

## Current Position

Phase: 5 of 5 (fix-k8s-integration-test-api) COMPLETE
Plan: 05-01 complete
Status: All phases complete — milestone ready for closure
Last activity: 2026-02-16 — Fixed and verified K8s integration test on k3d

Progress: █████ 5/5 phases (100%)

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-15)

**Core value:** Users can visually design a streaming pipeline and execute it on Spark without writing code.
**Current focus:** v1.2 Helm Chart Distribution — COMPLETE

## Accumulated Context

- v1.0 shipped: end-to-end Spark Connect execution with job lifecycle
- v1.1 shipped: self-contained Docker distribution (670MB image, multi-stage Dockerfile)
- v1.2 shipped: Helm chart with PostgreSQL dependency
- v1.3 shipped: Spark Connect production research completed
- v1.4 shipped: Kubernetes health probes verified
- v1.5 shipped: K8s integration test + Telepresence documentation
- v1.6 shipped: K8s integration test verified on k3d (all 3 tests pass)
- Docker Compose: postgres → spark-connect → atadflow with health ordering
- Health checks: PythonLivenessCheck + SmallRye Health (/q/health/live, /q/health/ready)
- 12 backend tests passing + 2 integration tests (DockerComposeIntegrationTest, KubernetesIntegrationTest)
- K8s integration test: gated behind K8S_INTEGRATION_TEST=true env var
- Helm chart: init container waits for PostgreSQL, correct secret key for custom user
- Kubeflow Spark Operator for integration test (stable Helm chart, well-known CRDs)
- kubectl port-forward in tests (more reliable than Fabric8 LocalPortForward)

## Decisions

- Used Bitnami PostgreSQL 18.3.0 as chart dependency (HTTPS repo, not OCI)
- Password via --set flag (no hardcoded secrets)
- Health probes use /q/health endpoints (matching docker-compose)
- Spark Connect URL default: sc://spark-connect:15002
- Kubeflow Spark Operator for integration testing (stable CRDs)
- RBAC required: ServiceAccount + Role with pod/service/configmap/secrets permissions
- K8s integration test: external k3d cluster (not Testcontainers) per user preference
- kubectl port-forward over Fabric8 LocalPortForward for test reliability
- busybox init container to wait for PostgreSQL before app start

## Session

Last session: 2026-02-16
Stopped at: Phase 5 complete — all phases done, milestone ready for closure
Resume file: None
Remaining plans: None — all 5 phases complete
