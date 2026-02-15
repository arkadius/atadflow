# Roadmap: Atadflow v1.2 Helm Chart Distribution

**Status:** In Progress
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
**Plans:** 1 plan

Plans:
- [ ] 02-01-PLAN.md — Finalize research with summary document

---

### Phase 3: Kubernetes Health Probes & Configurability

**Goal:** Kubernetes health probes using existing /q/health endpoints, configurable resources via values.yaml
**Depends on:** Phase 1
**Plans:** —

---

### Phase 4: K8s Integration Test & Documentation

**Goal:** Integration test proving deployment works on K8s, README section on Telepresence
**Depends on:** Phase 3
**Plans:** —

---

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| HELM-01: Helm chart with PostgreSQL as chart dependency | Phase 1 | ✓ Complete |
| HELM-02: Spark Connect production solution research | Phase 2 | Pending |
| HELM-03: Kubernetes health probes using /q/health | Phase 3 | Pending |
| HELM-04: Configurable resources via values.yaml | Phase 3 | Pending |
| HELM-05: K8s integration test | Phase 4 | Pending |
| HELM-06: README Telepresence section | Phase 4 | Pending |

---
*Last updated: 2026-02-15*
