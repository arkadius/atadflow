# Roadmap: Atadflow v1.2 Helm Chart Distribution

**Status:** Complete
**Milestone:** v1.2 Helm Chart Distribution

## Overview

Deploy Atadflow on Kubernetes via Helm chart with production-ready Spark execution, tested end-to-end on a real cluster.

## Phases

### Phase 1: Helm Chart with PostgreSQL

**Goal:** Create Helm chart with PostgreSQL as chart dependency
**Depends on:** None
**Plans:** 1 plan (COMPLETE)

Plans:
- [x] 01-01-PLAN.md — Create Helm chart with Bitnami PostgreSQL dependency, values.yaml, and templates

---

### Phase 2: Spark Connect Production Research

**Goal:** Research production-ready Spark Connect solution
**Depends on:** None
**Plans:** 1 plan (COMPLETE)

Plans:
- [x] 02-01-PLAN.md — Finalize research with summary document

---

### Phase 3: Kubernetes Health Probes & Configurability

**Goal:** Kubernetes health probes using existing /q/health endpoints, configurable resources via values.yaml
**Depends on:** Phase 1
**Plans:** 1 plan (COMPLETE)

Plans:
- [x] 03-01-PLAN.md — Verify health probes and configurable resources

---

### Phase 4: K8s Integration Test & Documentation

**Goal:** Integration test proving full-stack deployment on K8s with Spark execution, Telepresence docs
**Depends on:** Phase 3
**Plans:** 1 plan (COMPLETE)

Plans:
- [x] 04-01-PLAN.md — K8s integration test (external k3d + Spark K8s Operator + full lifecycle), Helm test hook, Telepresence README

---

### Phase 5: Fix K8s Integration Test API Version

**Goal:** Fix SparkApplication API version mismatch and verify integration test runs successfully
**Depends on:** Phase 4
**Plans:** 1 plan (COMPLETE)

Plans:
- [x] 05-01-PLAN.md — Fix Helm chart issues and verify integration test on k3d cluster

---

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| HELM-01: Helm chart with PostgreSQL as chart dependency | Phase 1 | ✓ Complete |
| HELM-02: Spark Connect production solution research | Phase 2 | ✓ Complete |
| HELM-03: Kubernetes health probes using /q/health | Phase 3 | ✓ Complete |
| HELM-04: Configurable resources via values.yaml | Phase 3 | ✓ Complete |
| HELM-05: K8s integration test | Phase 4 | ✓ Complete |
| HELM-06: README Telepresence section | Phase 4 | ✓ Complete |
| HELM-05: K8s integration test (verified on k3d) | Phase 5 | ✓ Complete |

---
*Last updated: 2026-02-16*
