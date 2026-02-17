# Feature Landscape

**Domain:** Helm Chart Kubernetes Deployment for Spark Streaming Application
**Researched:** 2026-02-15

## Table Stakes

Features users expect. Missing = product feels incomplete.

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| ConfigMap/Secret mounting | Standard K8s configuration pattern | Low | Health check endpoints already exist |
| Resource requests/limits | Production K8s requirement | Low | CPU/memory for atadflow, postgres, spark-connect |
| Liveness/readiness probes | Required for traffic routing & restarts | Low | Leverage existing `/q/health` endpoints |
| Image configuration (registry, tag, pull policy) | Standard for private registries | Low | `image.repository`, `image.tag`, `image.pullPolicy` |
| PostgreSQL persistence (PVC) | Data must survive pod restarts | Low | Bitnami chart handles this via `postgresql.primary.persistence.*` |
| PostgreSQL credentials management | Security best practice | Low | Auto-generate passwords, store in secrets |
| Service exposure (ClusterIP) | Internal cluster communication | Low | Atadflow → PostgreSQL, Atadflow → Spark Connect |
| Rolling updates | Zero-downtime deployments | Low | K8s native with `strategy.rollingUpdate` |
| Namespace support | Multi-tenant K8s clusters | Low | Standard Helm `.Release.Namespace` |
| values.yaml documentation | Users must understand config options | Low | Inline comments for every value |

## Differentiators

Features that set product apart. Not expected, but valued.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Ingress with TLS | Public HTTPS access to Atadflow UI | Medium | Optional, supports multiple ingress controllers |
| PostgreSQL init scripts via ConfigMap | Auto-create schema on first deploy | Low | Bitnami supports `initdbScriptsCM` |
| Spark Operator integration | Production-grade Spark Connect (auto-scaling, monitoring, declarative) | High | Replaces manual Spark Connect deployment |
| Horizontal Pod Autoscaler (HPA) | Auto-scale Atadflow based on CPU/memory | Medium | Requires metrics-server, useful for high load |
| Helm test hooks | Smoke tests verify deployment health | Medium | POST /api/flows, check health endpoints |
| NetworkPolicy | Restrict traffic to/from Atadflow | Medium | Security hardening for production |
| ServiceMonitor (Prometheus) | Metrics scraping for observability | Medium | Requires Prometheus Operator |
| PodDisruptionBudget | Maintain availability during node drains | Low | Ensure 1 replica stays up during upgrades |
| Spark Connect HA (multiple replicas) | High availability for Spark Connect server | High | Requires load balancing, session affinity |
| values.schema.json | Validate user values at install time | Low | Catch config errors before deployment |

## Anti-Features

Features to explicitly NOT build.

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| Embedded Spark cluster | Massive complexity, not K8s-native | Use Spark Operator with SparkApplication CRD |
| Multi-region PostgreSQL | Out of scope, use external managed DB | Document how to point to external PostgreSQL |
| Custom autoscaling (non-HPA) | Reinventing the wheel | Use standard K8s HPA |
| Built-in GitOps (ArgoCD/Flux) | Not a chart responsibility | Document integration patterns |
| Multi-tenancy isolation | Application-level concern | Single deployment per namespace |
| Backup/restore automation | Operational tooling, not deployment | Document velero/external backup solutions |
| Certificate management | Solved by cert-manager | Document cert-manager integration for ingress |

## Feature Dependencies

```
PostgreSQL persistence → PVC → StorageClass (cluster prerequisite)
Ingress with TLS → Ingress Controller (cluster prerequisite) → cert-manager (optional)
HPA → metrics-server (cluster prerequisite)
ServiceMonitor → Prometheus Operator (cluster prerequisite)
Spark Operator → SparkApplication CRD → spark-operator installed
Helm tests → kubectl (user environment)
```

## MVP Recommendation

### Phase 1: Core Deployment (Table Stakes)
1. **Basic Helm chart structure** (Low complexity)
   - templates/deployment.yaml, service.yaml, configmap.yaml, secret.yaml
   - values.yaml with sensible defaults
   - Chart.yaml with PostgreSQL dependency

2. **PostgreSQL subchart integration** (Low complexity)
   - Bitnami postgresql as Chart.yaml dependency
   - Credentials auto-generation and injection
   - Init script for schema creation (if needed)

3. **Health checks and resource limits** (Low complexity)
   - Liveness/readiness probes using `/q/health`
   - Resource requests/limits in values.yaml
   - Rolling update strategy

4. **Service exposure** (Low complexity)
   - ClusterIP service for atadflow
   - Environment variable configuration for PostgreSQL/Spark Connect URLs

### Phase 2: Production Hardening (Selected Differentiators)
5. **Ingress with optional TLS** (Medium complexity)
   - Configurable ingress class
   - TLS secret reference
   - Path-based routing

6. **Spark Operator integration** (High complexity)
   - Replace manual Spark Connect deployment
   - SparkApplication template for Spark Connect server
   - Document spark-operator prerequisite

7. **Helm tests** (Medium complexity)
   - test-connection.yaml (PostgreSQL connectivity)
   - test-health.yaml (Atadflow health endpoint)
   - test-smoke.yaml (Create flow, verify API)

8. **values.schema.json** (Low complexity)
   - Validate required fields
   - Type checking for ports, replicas, etc.

### Defer to Future Iterations
- HPA (wait for production load patterns)
- ServiceMonitor (wait for Prometheus adoption)
- NetworkPolicy (security hardening for regulated environments)
- PodDisruptionBudget (once multi-replica validated)
- Spark Connect HA (complex, needs load balancing research)

## Feature Complexity Notes

**Low Complexity** (1-2 days):
- Leverages existing K8s primitives
- Well-documented Helm patterns
- No external dependencies beyond K8s

**Medium Complexity** (3-5 days):
- Requires cluster prerequisites (ingress controller, metrics-server)
- Integration testing needed
- Documentation for multiple configurations

**High Complexity** (1-2 weeks):
- New architectural components (Spark Operator)
- Multiple integration points
- Likely needs deeper research phase
- Production validation required

## Spark Connect Deployment Options

### Option 1: Manual Deployment (Simple, Not Recommended)
- Deploy Spark Connect server as standard Deployment
- Expose via Service on port 15002 (gRPC)
- **Cons:** No auto-scaling, manual job lifecycle, no Spark-specific optimizations

### Option 2: Spark Operator (Recommended)
- Use SparkApplication CRD with `spec.server.service` for Spark Connect
- **Pros:**
  - Declarative Spark application management
  - Automatic spark-submit handling
  - Built-in restart policies and retries
  - Prometheus metrics export
  - Dynamic allocation support
  - ConfigMap/volume mounting
  - Cron scheduling for batch jobs
- **Cons:**
  - Requires spark-operator installed in cluster
  - Additional CRD complexity
  - Learning curve for SparkApplication spec

**Recommendation:** Start with Option 2 (Spark Operator) if targeting production. Document spark-operator as prerequisite. Provides future scalability.

## Integration Testing Strategy

### k3d Setup (Local Kubernetes)
- Deploy k3d cluster with local registry
- Install spark-operator via Helm
- Deploy Atadflow chart with test values

### Test Levels
1. **ct lint** - YAML validation, SemVer, Chart.yaml schema
2. **ct install** - Deploy chart, wait for ready, helm test
3. **Smoke tests** (via helm test hooks):
   - PostgreSQL connectivity
   - Atadflow health endpoint
   - API smoke test (POST /api/flows, verify response)
   - Spark Connect connectivity (submit simple query)

### CI Integration
```bash
# Install k3d cluster
k3d cluster create test-cluster --registry-create test-registry

# Install spark-operator
helm repo add spark-operator https://kubeflow.github.io/spark-operator
helm install spark-operator spark-operator/spark-operator

# Test Atadflow chart
ct lint --all
ct install --all
```

## Telepresence Developer Workflow

### Ideal Loop
1. **Setup:** `telepresence connect` (VPN to cluster)
2. **Intercept:** `telepresence intercept atadflow --port 8080:8080`
3. **Develop:** Run Quarkus dev mode locally (`./gradlew quarkusDev`)
4. **Test:** Requests to cluster route to local dev server
5. **Iterate:** Code changes hot-reload instantly
6. **Cleanup:** `telepresence leave atadflow`

### Benefits
- No container build/push/deploy cycle
- Access cluster PostgreSQL/Spark Connect from localhost
- Test with production-like environment
- Personal intercepts (no conflict with team)

### Prerequisites
- Telepresence CLI installed
- kubectl access to cluster
- Service must be running in cluster (for intercept)

## README Documentation Requirements

### Sections Needed
1. **Prerequisites**
   - Kubernetes cluster (version requirement)
   - kubectl configured
   - Helm 3.x installed
   - spark-operator installed (link to docs)
   - Optional: Ingress controller, cert-manager

2. **Installation**
   - Add Helm repository
   - Install command with minimal values
   - Common configuration examples

3. **Configuration**
   - Table of key values.yaml parameters
   - PostgreSQL configuration
   - Spark Connect configuration
   - Ingress/TLS setup

4. **Verification**
   - Run helm test
   - Check pod status
   - Access UI (port-forward or ingress)

5. **Development Workflow**
   - Telepresence setup
   - Local development with cluster deps
   - Debugging tips

6. **Troubleshooting**
   - Common errors (ImagePullBackOff, CrashLoopBackOff)
   - PostgreSQL connection issues
   - Spark Connect connectivity

7. **Uninstallation**
   - helm uninstall command
   - PVC cleanup (manual if needed)

## Sources

### Helm Best Practices
- [Helm Values Best Practices](https://helm.sh/docs/chart_best_practices/values/)
- [Helm Charts: The Complete Guide for 2026](https://devtoolbox.dedyn.io/blog/helm-charts-complete-guide)
- [Chart Testing (ct) Documentation](https://github.com/helm/chart-testing)

### PostgreSQL Helm Integration
- [Bitnami PostgreSQL Helm Chart](https://artifacthub.io/packages/helm/bitnami/postgresql)
- [Bitnami PostgreSQL GitHub](https://github.com/bitnami/charts/tree/main/bitnami/postgresql)
- [PostgreSQL Init Scripts Patterns](https://deepwiki.com/bitnami/charts/4.1-postgresql)

### Spark on Kubernetes
- [Running Spark on Kubernetes](https://spark.apache.org/docs/latest/running-on-kubernetes.html)
- [Spark Connect Overview](https://spark.apache.org/docs/latest/spark-connect-overview.html)
- [Kubeflow Spark Operator](https://kubeflow.github.io/spark-operator/)
- [Spark Operator Features](https://www.kubeflow.org/docs/components/spark-operator/overview/)

### Testing & Development
- [Helm Chart Testing with ct](https://oneuptime.com/blog/post/2026-01-17-helm-chart-testing-ct-helm-test/view)
- [Helm Test Documentation](https://helm.sh/docs/topics/chart_tests/)
- [Telepresence Documentation](https://telepresence.io/)
- [Kubernetes Local Development with Telepresence](https://oneuptime.com/blog/post/2026-01-19-kubernetes-telepresence-local-debugging/view)

### Kubernetes Best Practices
- [Kubernetes Deployment Best Practices 2026](https://oneuptime.com/blog/post/2026-01-30-how-to-create-kubernetes-deployments-best-practices/view)
- [Kubernetes Configuration Good Practices](https://kubernetes.io/blog/2025/11/25/configuration-good-practices/)
