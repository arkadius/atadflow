# State: Atadflow

## Current Position

Phase: 5 of 5 (fix-k8s-integration-test-api)
Plan: 05-01 pending
Status: Gap closure in progress
Last activity: 2026-02-15 — Created Phase 5 to fix API version mismatch

Progress: ████░ 4/5 phases (80%)

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-15)

**Core value:** Users can visually design a streaming pipeline and execute it on Spark without writing code.
**Current focus:** v1.2 Helm Chart Distribution (gap closure)

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-15)

**Core value:** Users can visually design a streaming pipeline and execute it on Spark without writing code.
**Current focus:** v1.2 Helm Chart Distribution

## Accumulated Context

- v1.0 shipped: end-to-end Spark Connect execution with job lifecycle
- v1.1 shipped: self-contained Docker distribution (670MB image, multi-stage Dockerfile)
- v1.2 shipped: Helm chart with PostgreSQL dependency
- v1.3 shipped: Spark Connect production research completed
- v1.4 shipped: Kubernetes health probes verified
- v1.5 (current): K8s integration test + Telepresence documentation complete
- Docker Compose: postgres → spark-connect → atadflow with health ordering
- Health checks: PythonLivenessCheck + SmallRye Health (/q/health/live, /q/health/ready)
- 12 backend tests passing + 2 integration tests (DockerComposeIntegrationTest, KubernetesIntegrationTest)
- K8s integration test: gated behind K8S_INTEGRATION_TEST=true env var
- PySpark 4.x quirk: query.stop() broken over Spark Connect, using spark.stop() instead
- Helm chart requires `--dependency-update` flag for template rendering
- Spark Connect production: Apache Spark K8s Operator v0.7.0+ recommended
- Kubernetes health probes: Fully configured with production-ready defaults (60s liveness, 30s readiness initial delay)
- Resource configurability: All probe timing and container resources externalized to values.yaml
- Helm test hooks: test-connection (liveness) + test-api (health + API validation)
- Telepresence workflow: Local Quarkus dev against cluster services

## Decisions

- Used Bitnami PostgreSQL 18.3.0 as chart dependency
- Password via --set flag (no hardcoded secrets)
- Health probes use /q/health endpoints (matching docker-compose)
- Spark Connect URL default: sc://spark-connect:15002
- Apache Spark K8s Operator v0.7.0+ for production deployment (Pattern 3: Helm chart dependency)
- Dynamic allocation: minExecutors=1, maxExecutors=5, initialExecutors=2
- RBAC required: ServiceAccount + Role with pod/service/configmap/secrets permissions
- K8s integration test: external k3d cluster (not Testcontainers) per user preference
- Fabric8 Kubernetes Client 7.2.0 for pod management and port-forward

## Session

Last session: 2026-02-15
Stopped at: Phase 4 complete - K8s integration test & documentation finished, audit found API version gap
Resume file: None - Gap closure in progress
Remaining plans: Phase 5 (fix API version mismatch in K8s integration test)
