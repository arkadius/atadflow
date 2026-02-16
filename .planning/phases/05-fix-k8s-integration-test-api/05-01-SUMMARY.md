---
phase: 05-fix-k8s-integration-test-api
plan: 01
subsystem: testing
tags: [kubernetes, helm, k3d, integration-test, spark-operator]

requires:
  - phase: 04-k8s-integration-test-documentation
    provides: K8s integration test skeleton and Helm chart
provides:
  - Verified end-to-end K8s integration test on k3d cluster
  - Fixed Helm chart PostgreSQL subchart loading
  - Fixed deployment secret key for custom user auth
  - Init container for PostgreSQL readiness
affects: []

tech-stack:
  added: [busybox-init-container]
  patterns: [kubectl-port-forward-in-tests, init-container-db-wait]

key-files:
  modified:
    - chart/.helmignore
    - chart/templates/deployment.yaml
    - chart/Chart.yaml
    - backend/src/test/java/io/atadflow/integration/KubernetesIntegrationTest.java

key-decisions:
  - "Use Kubeflow Spark Operator (stable Helm chart) over Apache Spark K8s Operator"
  - "Use kubectl port-forward instead of Fabric8 LocalPortForward (more reliable)"
  - "Add busybox init container to wait for PostgreSQL before app start"

duration: 30min
completed: 2026-02-16
---

# Phase 5 Plan 1: Fix K8s Integration Test Summary

**Fixed Helm chart PostgreSQL integration and K8s integration test — all 3 tests pass on k3d with 0 pod restarts**

## Performance

- **Duration:** ~30 min
- **Started:** 2026-02-16T20:00:00Z
- **Completed:** 2026-02-16T20:30:00Z
- **Tasks:** 1 (manual verification)
- **Files modified:** 5

## Accomplishments
- All 3 integration tests pass: testHealthEndpoints, testApiEndpoints, testFullStackFlowSubmission
- Pod starts cleanly with 0 restarts (init container waits for PostgreSQL)
- Full lifecycle verified: create flow, submit job, poll RUNNING status, cancel job

## Task Commits

1. **Fix Helm chart PostgreSQL integration** - `903e4bf` (fix)
2. **Fix K8s integration test reliability** - `389fe8b` (fix)

## Files Created/Modified
- `chart/.helmignore` - Removed charts/ exclusion that prevented PostgreSQL subchart loading
- `chart/templates/deployment.yaml` - Fixed secret key (password vs postgres-password), added init container
- `chart/Chart.yaml` - Switched to HTTPS repository (OCI not resolving)
- `chart/Chart.lock` - Regenerated
- `backend/.../KubernetesIntegrationTest.java` - Kubeflow Spark Operator, kubectl port-forward, health check wait

## Decisions Made
- Kubeflow Spark Operator chosen over Apache Spark K8s Operator — Kubeflow has stable Helm chart and well-known CRDs
- kubectl port-forward used instead of Fabric8 LocalPortForward — Fabric8 implementation was unreliable (NoHttpResponseException)
- busybox init container added to deployment template — prevents CrashLoopBackOff when PostgreSQL starts slowly

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] .helmignore excluded charts/ directory**
- **Found during:** Initial test run
- **Issue:** PostgreSQL subchart was physically present but Helm ignored it due to `.helmignore` rule
- **Fix:** Removed `charts/` line from `.helmignore`
- **Verification:** `helm template` now renders PostgreSQL StatefulSet, Secret, Service
- **Committed in:** 903e4bf

**2. [Rule 1 - Bug] Wrong secret key for PostgreSQL password**
- **Found during:** Second test run
- **Issue:** Deployment referenced `postgres-password` (superuser) but app connects as custom user `atadflow`
- **Fix:** Changed secretKeyRef key to `password` (custom user key in Bitnami chart)
- **Verification:** App authenticates successfully to PostgreSQL
- **Committed in:** 903e4bf

**3. [Rule 1 - Bug] App crashes before PostgreSQL is ready**
- **Found during:** Third test run
- **Issue:** Quarkus fails fast on DB connection failure, causing CrashLoopBackOff and breaking port-forward
- **Fix:** Added busybox init container that waits for PostgreSQL port 5432
- **Verification:** 0 pod restarts on clean deployment
- **Committed in:** 903e4bf

**4. [Rule 1 - Bug] Fabric8 port-forward unreliable**
- **Found during:** Fourth test run (after init container fix)
- **Issue:** Fabric8 LocalPortForward returned NoHttpResponseException even with healthy pod
- **Fix:** Switched to kubectl port-forward via ProcessBuilder with dynamic port allocation
- **Verification:** All 3 tests pass consistently
- **Committed in:** 389fe8b

---

**Total deviations:** 4 auto-fixed (4 bugs)
**Impact on plan:** All fixes were necessary for the test to run. No scope creep.

## Issues Encountered
None — all issues were resolved through the deviations above.

## Next Phase Readiness
- All phases complete — milestone ready for closure
- No blockers or concerns

---
*Phase: 05-fix-k8s-integration-test-api*
*Completed: 2026-02-16*
