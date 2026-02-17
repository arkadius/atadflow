# Technology Stack — Helm Chart Kubernetes Distribution

**Project:** Atadflow v1.2 Helm Chart
**Researched:** 2026-02-15
**Confidence:** MEDIUM (WebSearch verified with official docs)

## Executive Summary

This stack document covers NEW additions for Helm chart Kubernetes deployment. Existing stack (Quarkus 3.31.2, PostgreSQL 16, PySpark 4.0.2) remains unchanged. Focus is on packaging and orchestration layers only.

## Recommended Stack

### Core Helm Infrastructure

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Helm | 3.x | Package manager, chart orchestration | Industry standard for K8s deployments, supports OCI registries, dependency management |
| Chart API Version | v2 | Chart metadata format | Current standard for Helm 3+, required for dependency features |
| Kubernetes | 1.32+ | Container orchestration | Apache Spark Operator requires 1.32+, k3d supports latest versions |

**Rationale:** Helm 3 removed Tiller (server-side component), improving security and simplifying operations. Chart API v2 is the only supported version for Helm 3 charts. Version remains `v2` as of February 2026.

### PostgreSQL Dependency

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Bitnami PostgreSQL Chart | 16.x | Database subchart | PostgreSQL 16 compatible (matches existing stack), production hardened, configurable via values |
| Repository | `oci://registry-1.docker.io/bitnamicharts` | OCI registry for Helm charts | **CRITICAL:** As of August 28, 2025, Bitnami stopped publishing new chart updates to Docker Hub OCI. Existing charts remain available but no longer receive updates. |

**Installation syntax:**
```yaml
# In Chart.yaml
dependencies:
  - name: postgresql
    version: "~16.0"
    repository: "oci://registry-1.docker.io/bitnamicharts"
    condition: postgresql.enabled
```

**IMPORTANT CAVEAT:** The Bitnami public OCI registry (`oci://registry-1.docker.io/bitnamicharts`) no longer receives updates. For production use, consider:
1. Using the specific PostgreSQL 16.x version that matches your needs (frozen, no updates)
2. Migrating to Bitnami Secure Images (commercial subscription with continued updates)
3. Switching to an alternative PostgreSQL Helm chart (e.g., CloudNativePG, Zalando Postgres Operator)

For this milestone (dev/demo environment), the existing Bitnami chart at Docker Hub OCI is acceptable since it's already PostgreSQL 16 compatible and won't need security updates during development.

### Spark Connect on Kubernetes

**Three Options Evaluated:**

| Option | Maturity | Spark Connect Support | Recommendation |
|--------|----------|----------------------|----------------|
| Apache Spark Operator | Production (v0.7.0, Jan 2026) | YES — Native SparkCluster CRD | **RECOMMENDED** |
| Kubeflow Spark Operator | Mature (v2.x, 2023+) | YES — Added SparkConnect CRD | Alternative |
| Stackable Spark Operator | Production (25.7+) | YES — Since 25.7 (2025) | Alternative |
| Plain Deployment/StatefulSet | Manual | N/A (DIY) | Not recommended |

#### RECOMMENDED: Apache Spark Operator

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Apache Spark Operator | 0.7.0 (chart 1.5.0) | Manage Spark Connect servers | Official Apache project, designed for Spark 3.5+, first-class Spark Connect support, active development |
| Helm Chart Repository | `https://apache.github.io/spark-kubernetes-operator` | Operator installation | Official Apache Helm repo |
| Spark Image | `apache/spark:4.0.2` | Spark Connect server runtime | Matches existing PySpark 4.0.2 client version |

**Installation:**
```bash
helm repo add spark https://apache.github.io/spark-kubernetes-operator
helm repo update
helm install spark-operator spark/spark-kubernetes-operator
```

**Why Apache Spark Operator over alternatives:**
- **Official Apache project**: Part of Apache Spark umbrella, not third-party
- **Spark Connect first-class**: Designed for Spark 3.5+ era with Spark Connect as core capability
- **Modern architecture**: Uses SparkCluster CRD for managing long-running Spark Connect servers (vs Kubeflow's job-oriented SparkApplication)
- **Active development**: Latest release January 2026, aligned with Spark 4.x
- **Requirements match**: Needs K8s 1.32+ which we can provide with k3d

**Kubeflow/Stackable when to use:**
- Kubeflow: If you're already using Kubeflow ML platform, or need mature job scheduling features
- Stackable: If you need full Stackable Data Platform integration (HDFS, Kafka, etc.)

**Configuration approach:**
The Atadflow Helm chart will declare Apache Spark Operator as an **optional dependency** (condition-based). Users can:
1. Use bundled operator (default for k3d development)
2. Bring their own Spark Operator installation (production clusters)
3. Deploy Spark Connect manually without operator (edge cases)

### Testing Infrastructure

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| k3d | 5.x | Local K8s clusters for testing | Lightweight, fast cluster creation/deletion, integrates with Helm, runs k3s in Docker |
| helm test | Native | Basic chart validation | Built into Helm, tests defined as Helm hooks, sufficient for smoke tests |
| Terratest (optional) | Latest | Integration testing (Go) | More control than chart-testing, better for complex scenarios, timeout configuration |
| chart-testing (ct) | Latest | Lint and install tests | Helm chart best practices validation, CI/CD integration |

**Rationale:**
- **k3d**: k3s in Docker provides multi-node clusters on a single machine, minimal resource usage (423-502 MiB), perfect for local development and CI/CD pipelines
- **helm test**: Zero additional dependencies, define test pods as Helm hooks with `helm.sh/hook: test` annotation
- **Terratest over ct**: Terratest chosen for integration tests because chart-testing has 3-minute default timeout (too short for PostgreSQL/Spark startup) and limited configuration options
- **ct for linting**: chart-testing still valuable for validating chart best practices and structure

**Test strategy:**
```
Local dev:     k3d cluster + helm install + helm test
CI/CD:         k3d cluster + ct lint + Terratest integration tests
Pre-release:   Multi-node k3d cluster with resource limits
```

### Development Workflow: Telepresence

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Telepresence | 2.26.0 | Local debugging against K8s cluster | Intercept cluster traffic to local Quarkus dev, no need to rebuild/redeploy, supports Java debugging |

**Key capabilities (as of v2.26.0, Jan 2026):**
- **Intercepts**: Route traffic from K8s service to local `localhost:8080` (Quarkus dev mode)
- **Admin controls**: Cluster admins can revoke intercepts (improved for shared environments)
- **Environment injection**: `--env-file` flag populates local env vars from K8s ConfigMaps/Secrets

**Setup for Quarkus:**
```bash
# 1. Connect to cluster
telepresence connect

# 2. Intercept Atadflow service
telepresence intercept atadflow \
  --port 8080:8080 \
  --env-file .env.k8s

# 3. Run Quarkus in dev mode
cd backend && ./gradlew quarkusDev

# 4. Traffic to atadflow.namespace.svc.cluster.local → localhost:8080
```

**IDE integration:**
- IntelliJ IDEA: Native Telepresence plugin for breakpoint debugging
- VS Code: Use Telepresence CLI + standard Java debugger

**When to use:**
- Developing features that require PostgreSQL/Spark Connect in K8s
- Testing Helm chart configurations without rebuild cycles
- Debugging service-to-service interactions

### Quarkus Kubernetes Integration

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| quarkus-kubernetes | 3.31.2 | Generate K8s manifests | Auto-generate Deployment, Service, Ingress from `application.properties` annotations |
| quarkus-helm (optional) | Latest | Generate Helm templates | Quarkiverse extension, converts Kubernetes extension output to Helm templates |

**Configuration approach:**

**Option 1: Manual Helm templates** (RECOMMENDED)
- Write `templates/deployment.yaml`, `templates/service.yaml` manually
- Full control over Helm values structure
- Use existing SmallRye Health endpoints: `/q/health/live`, `/q/health/ready`

**Option 2: quarkus-helm extension**
- Add `io.quarkiverse.helm:quarkus-helm` dependency
- Configure via `quarkus.kubernetes.*` properties
- Extension generates Helm chart from properties
- **Trade-off**: Less Helm idioms, more Quarkus-centric

**For Atadflow v1.2:** Use Option 1 (manual templates) because:
- Milestone already has working Dockerfile and health checks
- Need custom values for PostgreSQL/Spark dependencies
- Better alignment with Helm best practices
- Quarkus Kubernetes extension not needed (manual templates clearer)

**Health checks mapping:**
```yaml
# Existing Quarkus endpoints work as-is in K8s
livenessProbe:
  httpGet:
    path: /q/health/live
    port: 8080
readinessProbe:
  httpGet:
    path: /q/health/ready
    port: 8080
```

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| kubectl | 1.32+ | K8s CLI operations | Debugging, manual operations, CI/CD |
| docker | 20.10+ | Container runtime for k3d | Required for k3d local clusters |
| gh (GitHub CLI) | 2.x | PR creation, release management | If using GitHub for repo hosting |

## Alternatives Considered

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| Package Manager | Helm 3 | Kustomize | Helm has better templating for subcharts, values hierarchy, versioning |
| PostgreSQL | Bitnami chart | CloudNativePG | Bitnami more familiar, existing stack uses plain PostgreSQL (not operator-managed) |
| Spark Operator | Apache Spark Operator | Kubeflow Spark Operator | Apache is official, Spark Connect first-class, modern CRDs |
| Spark Operator | Apache Spark Operator | Manual Deployment | Operator handles scaling, updates, CRD patterns more K8s-native |
| Local K8s | k3d | minikube | k3d faster startup (seconds vs minutes), better Docker integration, lighter |
| Local K8s | k3d | kind | k3d has built-in load balancer, registry support, better k3s compatibility |
| Testing | Terratest | chart-testing (ct) | Terratest has better timeout config, programmatic control (ct good for linting) |
| Dev Workflow | Telepresence | kubectl port-forward | Telepresence handles env vars, service mesh, more seamless than port-forward |

## Chart Structure

```
atadflow/
├── Chart.yaml              # Chart metadata, dependencies
├── values.yaml             # Default configuration values
├── templates/
│   ├── deployment.yaml     # Atadflow deployment
│   ├── service.yaml        # ClusterIP service
│   ├── ingress.yaml        # Optional ingress (condition: ingress.enabled)
│   ├── configmap.yaml      # Quarkus configuration
│   ├── _helpers.tpl        # Template helpers
│   └── tests/
│       └── test-connection.yaml  # helm test hook
├── charts/                 # Downloaded dependencies (gitignored)
└── Chart.lock              # Locked dependency versions
```

## Installation Commands

```bash
# Development setup (k3d)
k3d cluster create atadflow --agents 2
helm repo add spark https://apache.github.io/spark-kubernetes-operator
helm repo update
helm dependency update ./atadflow
helm install atadflow ./atadflow --values values-dev.yaml

# Testing
helm test atadflow

# Telepresence intercept for local dev
telepresence intercept atadflow --port 8080:8080

# Cleanup
helm uninstall atadflow
k3d cluster delete atadflow
```

## Integration with Existing Stack

### No Changes Required

| Component | Integration Point | Notes |
|-----------|------------------|-------|
| Quarkus 3.31.2 | Use existing Dockerfile | Multi-stage build produces image, Helm deploys it |
| SmallRye Health | Map to K8s probes | `/q/health/live` → livenessProbe, `/q/health/ready` → readinessProbe |
| PostgreSQL 16 | Subchart dependency | Bitnami chart creates PostgreSQL 16 instance, Quarkus connects via Service |
| Spark Connect | Operator-managed | Apache Spark Operator creates Spark Connect server, Quarkus clients connect via Service |
| Gradle 9.3.1 | Build before Helm | `./gradlew build` → `docker build` → `helm install` |

### Environment Variables

```yaml
# values.yaml structure
postgresql:
  enabled: true
  auth:
    database: atadflow
    username: atadflow
    # password: set via --set or sealed secrets

sparkConnect:
  enabled: true
  image: apache/spark:4.0.2
  server:
    port: 15002

atadflow:
  image:
    repository: atadflow
    tag: latest
  env:
    QUARKUS_DATASOURCE_JDBC_URL: "jdbc:postgresql://{{ .Release.Name }}-postgresql:5432/atadflow"
    SPARK_REMOTE: "sc://{{ .Release.Name }}-spark-connect:15002"
```

## Dependency Management

```yaml
# Chart.yaml
apiVersion: v2
name: atadflow
version: 1.2.0
appVersion: "1.2.0"

dependencies:
  - name: postgresql
    version: "~16.0"
    repository: "oci://registry-1.docker.io/bitnamicharts"
    condition: postgresql.enabled
    tags:
      - database

  # Optional: bundle Spark Operator or assume it's pre-installed
  - name: spark-kubernetes-operator
    version: "~1.5.0"
    repository: "https://apache.github.io/spark-kubernetes-operator"
    condition: sparkOperator.enabled
    tags:
      - compute
```

**Notes:**
- `~16.0` accepts 16.x.x (SemVer patch updates)
- `condition` fields allow enabling/disabling subcharts via values
- `oci://` repository syntax for OCI registries (PostgreSQL)
- `https://` repository syntax for traditional Helm repos (Spark Operator)

## Version Constraints

| Dependency | Minimum | Recommended | Notes |
|------------|---------|-------------|-------|
| Helm | 3.0.0 | 3.x latest | Helm 2 not supported (deprecated) |
| Kubernetes | 1.32.0 | 1.32+ | Apache Spark Operator requirement |
| Docker | 20.10.0 | 20.10+ | For k3d local clusters |
| PostgreSQL chart | 16.0.0 | 16.x | Match existing PostgreSQL 16 |
| Spark Operator | 0.7.0 | 0.7+ | Spark 4.x support |

## Migration Path

**From Docker Compose (v1.1) to Helm (v1.2):**

1. **Image unchanged**: Use same Dockerfile, push to registry accessible by K8s
2. **Database**: PostgreSQL data migration (if needed) via pg_dump → Helm install with persistence
3. **Spark Connect**: Docker container → Operator-managed SparkCluster CRD
4. **Configuration**: docker-compose environment vars → Helm values.yaml
5. **Networking**: Docker network → K8s Services (ClusterIP default)
6. **Persistence**: Docker volumes → PersistentVolumeClaims (via Bitnami subchart)

**What stays the same:**
- Application code (zero changes)
- Health check endpoints
- gRPC Spark Connect protocol
- PostgreSQL connection string format (host changes to K8s Service DNS)

## CI/CD Considerations

```yaml
# Example GitHub Actions workflow step
- name: Test Helm Chart
  run: |
    k3d cluster create test --agents 1
    helm dependency update ./atadflow
    helm install atadflow ./atadflow --values values-test.yaml --wait --timeout 5m
    helm test atadflow
    k3d cluster delete test
```

## Confidence Assessment

| Area | Confidence | Rationale |
|------|------------|-----------|
| Helm Chart Structure | HIGH | Official Helm docs, established patterns, Chart API v2 confirmed current |
| PostgreSQL Subchart | MEDIUM | Bitnami chart verified, but OCI registry deprecation noted (still usable for dev) |
| Apache Spark Operator | HIGH | Official docs, recent release (Jan 2026), explicit Spark Connect support confirmed |
| Testing Tools | MEDIUM | WebSearch + official docs, patterns verified across multiple sources |
| Telepresence | HIGH | Official release notes (v2.26.0, Jan 2026), Java setup guides confirmed |
| Quarkus Integration | HIGH | Quarkus docs, SmallRye Health well-established, no new extensions needed |

## Sources

### Helm & Chart Structure
- [Helm Best Practices](https://helm.sh/docs/chart_best_practices/)
- [Helm Charts: The Complete Guide for 2026](https://devtoolbox.dedyn.io/blog/helm-charts-complete-guide)
- [Charts | Helm](https://helm.sh/docs/topics/charts/)
- [Managing Helm Chart Dependencies and Subcharts](https://oneuptime.com/blog/post/2026-01-17-helm-chart-dependencies-subcharts/view)
- [Use OCI-based registries | Helm](https://helm.sh/docs/topics/registries/)

### PostgreSQL Helm Chart
- [Bitnami PostgreSQL Helm chart](https://artifacthub.io/packages/helm/bitnami/postgresql)
- [Bitnami PostgreSQL chart GitHub](https://github.com/bitnami/charts/tree/main/bitnami/postgresql)
- [Upcoming changes to Bitnami catalog](https://github.com/bitnami/charts/issues/35164) (Aug 2025 OCI deprecation)

### Spark on Kubernetes
- [Apache Spark Kubernetes Operator](https://apache.github.io/spark-kubernetes-operator/)
- [Apache Spark Kubernetes Operator GitHub](https://github.com/apache/spark-kubernetes-operator)
- [Running Spark on Kubernetes - Spark 4.1.0 Documentation](https://spark.apache.org/docs/latest/running-on-kubernetes.html)
- [How to Set Up Kubernetes Batch Processing with Apache Spark Operator](https://oneuptime.com/blog/post/2026-02-09-batch-processing-spark-operator/view)
- [Kubeflow Spark Operator](https://github.com/kubeflow/spark-operator)
- [Spark Connect support Issue #1801](https://github.com/kubeflow/spark-operator/issues/1801)
- [Stackable Spark Connect Documentation](https://docs.stackable.tech/home/stable/spark-k8s/usage-guide/spark-connect/)
- [Stackable Spark Connect Release](https://stackable.tech/en/lets-spark-connect/)

### Testing Tools
- [Automated Testing for Kubernetes and Helm Charts using Terratest](https://blog.gruntwork.io/automated-testing-for-kubernetes-and-helm-charts-using-terratest-a4ddc4e67344)
- [Testing Helm Charts with Chart Testing (ct) and helm test](https://oneuptime.com/blog/post/2026-01-17-helm-chart-testing-ct-helm-test/view)
- [How to Write and Run Tests for Helm Charts](https://oneuptime.com/blog/post/2026-01-17-helm-chart-testing-unittest-conftest/view)
- [Advanced Test Practices For Helm Charts](https://medium.com/@zelldon91/advanced-test-practices-for-helm-charts-587caeeb4cb)

### k3d & Local Development
- [k3d GitHub](https://github.com/k3d-io/k3d)
- [K3d for Local Kubernetes Development](https://devtron.ai/blog/k3d-for-local-kubernetes-development/)
- [K3S + K3D = K8S a new perfect match for dev and test](https://www.sokube.io/en/blog/k3s-k3d-k8s-a-new-perfect-match-for-dev-and-test-en)

### Telepresence
- [Telepresence 2.26 Release](https://telepresence.io/blog/telepresence-2.26)
- [Telepresence Quick Start - Java](https://www.getambassador.io/docs/latest/telepresence/quick-start/qs-java/)
- [Remote debugging using Telepresence | IntelliJ IDEA](https://www.jetbrains.com/help/idea/telepresence.html)
- [Configure intercept using CLI](https://telepresence.io/docs/2.19/reference/intercepts/cli)

### Quarkus Kubernetes
- [Kubernetes extension - Quarkus](https://quarkus.io/guides/deploying-to-kubernetes)
- [SmallRye Health - Quarkus](https://quarkus.io/guides/smallrye-health)
- [Helm Extension for Quarkus](https://docs.quarkiverse.io/quarkus-helm/dev/index.html)

## Open Questions

1. **PostgreSQL chart migration strategy**: Should we switch from Bitnami (no updates) to CloudNativePG or stick with frozen Bitnami for consistency? → Defer to production deployment phase
2. **Spark Operator bundling**: Should Helm chart include Spark Operator as dependency or assume pre-installation? → Default to bundled for dev convenience, document BYOO (Bring Your Own Operator) pattern
3. **Image registry**: Where to host Atadflow images? → Milestone scope: local registry for k3d, document external registry setup
4. **Ingress controller**: Which ingress implementation for k3d? → Traefik (k3s default) vs NGINX vs skip for v1.2
5. **Secrets management**: How to handle PostgreSQL passwords in values? → Document pattern: Helm secrets, sealed-secrets, or external-secrets-operator (user choice)
