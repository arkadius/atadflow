# Phase 3: Kubernetes Health Probes & Configurability - Research

**Researched:** 2026-02-15
**Domain:** Kubernetes health probe configuration, Helm chart resource management
**Confidence:** HIGH

## Summary

**Primary recommendation:** This phase is already fully implemented in the Helm chart from Phase 1. No additional work is required - the health probes are correctly configured using Quarkus SmallRye Health endpoints (`/q/health/live` and `/q/health/ready`) and all resource parameters are configurable via values.yaml.

### Verification Results

| Requirement | Status | Evidence |
|-------------|--------|----------|
| HELM-03: Kubernetes health probes | ✅ Complete | deployment.yaml lines 34-49 |
| HELM-04: Configurable resources | ✅ Complete | values.yaml lines 63-103 |

---

## Existing Implementation Analysis

### 1. Health Probes (HELM-03)

**Location:** `chart/templates/deployment.yaml` lines 34-49

```yaml
livenessProbe:
  httpGet:
    path: /q/health/live
    port: http
  initialDelaySeconds: {{ .Values.livenessProbe.initialDelaySeconds }}
  periodSeconds: {{ .Values.livenessProbe.periodSeconds }}
  timeoutSeconds: {{ .Values.livenessProbe.timeoutSeconds }}
  failureThreshold: {{ .Values.livenessProbe.failureThreshold }}
readinessProbe:
  httpGet:
    path: /q/health/ready
    port: http
  initialDelaySeconds: {{ .Values.readinessProbe.initialDelaySeconds }}
  periodSeconds: {{ .Values.readinessProbe.periodSeconds }}
  timeoutSeconds: {{ .Values.readinessProbe.timeoutSeconds }}
  failureThreshold: {{ .Values.readinessProbe.failureThreshold }}
```

**Endpoints verified against:**
- Quarkus SmallRye Health official documentation confirms `/q/health/live` and `/q/health/ready` are the standard endpoints
- Source: https://quarkus.io/guides/smallrye-health

### 2. Configurable Resources (HELM-04)

**Location:** `chart/values.yaml` lines 63-103

```yaml
# Application container resources
resources:
  limits:
    memory: "2Gi"
    cpu: "1000m"
  requests:
    memory: "1Gi"
    cpu: "500m"

# Liveness and readiness probe configuration
livenessProbe:
  initialDelaySeconds: 60
  periodSeconds: 15
  timeoutSeconds: 3
  failureThreshold: 5

readinessProbe:
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 3
  failureThreshold: 3
```

**Resource injection:** `chart/templates/deployment.yaml` line 63:
```yaml
resources:
  {{- toYaml .Values.resources | nindent 12 }}
```

---

## Configuration Assessment

### Probe Timing Analysis

| Probe Type | Initial Delay | Period | Timeout | Failure Threshold | Total Time to Restart |
|------------|---------------|--------|---------|-------------------|----------------------|
| Liveness | 60s | 15s | 3s | 5 | 75s (5 * 15) |
| Readiness | 30s | 10s | 3s | 3 | 30s (3 * 10) |

**Assessment:** Configuration follows Kubernetes best practices:
- Liveness has longer initial delay (60s) to allow full app startup before checks
- Readiness has shorter delay (30s) to accept traffic sooner
- Failure thresholds are reasonable for production workloads
- Liveness doesn't depend on external services (correct for restart decisions)

### Resource Limits Assessment

| Setting | Requests | Limits | Assessment |
|---------|----------|--------|------------|
| Memory | 1Gi | 2Gi | Good 2x headroom |
| CPU | 500m | 1000m | Good 2x headroom |

---

## Standard Stack

This phase uses standard Kubernetes/Helm components:

| Component | Version | Purpose | Why Standard |
|-----------|---------|---------|--------------|
| Kubernetes HTTP Probes | v1 (stable) | Health check mechanism | Native K8s feature |
| Helm | 3.x | Package management | CNCF graduated project |
| Quarkus SmallRye Health | Latest (3.x) | Health endpoints | Standard MicroProfile impl |

---

## Architecture Patterns

### Recommended Project Structure
```
chart/
├── Chart.yaml
├── values.yaml              # Default configuration
├── templates/
│   ├── deployment.yaml      # Contains probes (IMPLEMENTED)
│   ├── service.yaml
│   └── ...
```

### Pattern: HTTP Health Probe Configuration
**What:** Configure liveness and readiness probes using HTTP GET requests
**When to use:** Application exposes HTTP health endpoints
**Example:**
```yaml
livenessProbe:
  httpGet:
    path: /q/health/live
    port: http
  initialDelaySeconds: 60
  periodSeconds: 15
  failureThreshold: 5
```
Source: Kubernetes official docs - https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Health endpoints | Custom /health endpoints | Quarkus SmallRye Health | Standardized, includes DB connectivity checks |
| Resource configuration | Hard-coded values | Helm values.yaml | Allows tuning per-environment |

---

## Common Pitfalls

### Pitfall 1: Liveness probe depending on external services
**What goes wrong:** Container restarts when external dependency (DB) is down
**Why it happens:** Using `/q/health/ready` for liveness (which checks DB)
**How to avoid:** Use `/q/health/live` for liveness (application process only)
**Implementation:** ✅ Correct - uses separate endpoints

### Pitfall 2: Initial delay too short
**What goes wrong:** Container killed before starting
**Why it happens:** Not accounting for application startup time
**How to avoid:** Set initialDelaySeconds > app startup time
**Implementation:** ✅ Correct - 60s liveness, 30s readiness

### Pitfall 3: Hard-coded resources
**What goes wrong:** OOM kills in production, resource waste in dev
**Why it happens:** No values.yaml configuration
**How to avoid:** Make resources configurable
**Implementation:** ✅ Correct - fully configurable

---

## Code Examples

### Verified: Quarkus Health Endpoints
```yaml
# Source: https://quarkus.io/guides/smallrye-health
livenessProbe:
  httpGet:
    path: /q/health/live
    port: http

readinessProbe:
  httpGet:
    path: /q/health/ready
    port: http
```

### Verified: Kubernetes Probe Best Practices
```yaml
# Source: https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/
livenessProbe:
  httpGet:
    path: /healthz
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 10
  failureThreshold: 3
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Command probes | HTTP probes | Kubernetes 1.0 | More reliable, no shell required |
| /health endpoint | /q/health/* (Quarkus) | SmallRye Health 2.0 | Consistent with MicroProfile spec |
| Hard-coded resources | values.yaml config | Helm 2.0+ | Environment-specific tuning |

---

## Open Questions

1. **Should a startup probe be added?**
   - What we know: Quarkus provides `/q/health/started` endpoint
   - What's unclear: Current delays may be sufficient for most deployments
   - Recommendation: Not required - current configuration is production-ready

---

## Sources

### Primary (HIGH confidence)
- Quarkus SmallRye Health Guide - https://quarkus.io/guides/smallrye-health - Confirmed `/q/health/live` and `/q/health/ready` endpoints
- Kubernetes Liveness/Readiness Probes - https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/ - Official best practices

### Secondary (MEDIUM confidence)
- Web search on Kubernetes health probe best practices 2025/2026 - Verified current recommendations align with implementation

---

## Metadata

**Confidence breakdown:**
- Standard Stack: HIGH - Uses standard Kubernetes/Helm/Quarkus components
- Architecture: HIGH - Follows Helm best practices
- Pitfalls: HIGH - Verified implementation avoids all common pitfalls

**Research date:** 2026-02-15
**Valid until:** 90 days (Kubernetes probe config is stable)

---

## Recommendation

**Phase 3 is COMPLETE.** The Helm chart already implements:
- ✅ Kubernetes health probes using `/q/health/live` and `/q/health/ready`
- ✅ Configurable probe timing parameters
- ✅ Configurable container resource limits and requests

**No additional work required.** This phase can be marked as done in the roadmap.
