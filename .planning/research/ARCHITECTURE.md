# Architecture Patterns: Helm Chart Kubernetes Distribution

**Domain:** Helm chart deployment for existing Docker-based Quarkus + React application
**Researched:** 2026-02-15
**Confidence:** HIGH

## Executive Summary

This architecture research focuses on integrating Helm chart Kubernetes deployment with Atadflow's existing Docker-based architecture (Quarkus backend, React frontend, PostgreSQL persistence, Spark Connect execution). The migration maintains the existing multi-stage Dockerfile but adapts deployment from Docker Compose to Kubernetes with Helm templating, PostgreSQL subchart integration, and two viable Spark Connect deployment options.

**Key findings:**
1. Helm charts conventionally live in `helm/` or `charts/` at project root
2. Existing Dockerfile maps cleanly to K8s Deployment with minimal changes
3. Bitnami PostgreSQL subchart (v12.5.8+) provides production-ready database with service DNS
4. Spark Connect has two viable K8s options: Spark Operator (scalable) vs Simple Deployment (simpler)
5. Integration tests use `kubectl port-forward` + existing test harness
6. Telepresence intercepts traffic to Quarkus pod, Python subprocess execution unaffected
7. Build order: Helm structure → PostgreSQL → Spark Connect → Atadflow deployment → Integration tests → Telepresence docs

## Recommended Architecture

### Project Structure

```
atadflow/
├── backend/
├── frontend/
├── Dockerfile                 # UNCHANGED - reused by K8s
├── docker-compose.yml         # KEPT - local dev option
├── helm/
│   └── atadflow/
│       ├── Chart.yaml         # Metadata + dependencies
│       ├── values.yaml        # Configuration defaults
│       ├── templates/
│       │   ├── deployment.yaml
│       │   ├── service.yaml
│       │   ├── configmap.yaml
│       │   ├── secret.yaml    # DB credentials
│       │   ├── _helpers.tpl   # Reusable template functions
│       │   └── NOTES.txt      # Post-install instructions
│       └── charts/            # Downloaded dependencies (gitignored)
└── k8s-integration-tests/     # NEW - kubectl-based tests
```

**Rationale:** Standard Helm structure per [official charts documentation](https://helm.sh/docs/topics/charts/). `helm/` directory at root is conventional for projects with Helm as secondary concern (Docker is primary). Chart dependencies downloaded to `charts/` subdirectory.

**Confidence:** HIGH - Verified against [Helm best practices](https://helm.sh/docs/chart_best_practices/) and [2026 Helm guide](https://devtoolbox.dedyn.io/blog/helm-charts-complete-guide).

---

## Component Integration Mapping

### 1. Dockerfile → Kubernetes Deployment

**Existing Dockerfile:**
- Multi-stage build: Node 22 → Gradle/JDK 25 → JRE 25 + Python 3.12
- Final image: 670MB with Quarkus + PySpark + frontend bundled
- HEALTHCHECK: `curl -f http://localhost:8080/q/health/live`
- Exposed port: 8080

**Kubernetes Deployment Mapping:**

```yaml
# helm/atadflow/templates/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "atadflow.fullname" . }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app.kubernetes.io/name: {{ include "atadflow.name" . }}
  template:
    metadata:
      labels:
        app.kubernetes.io/name: {{ include "atadflow.name" . }}
    spec:
      containers:
      - name: atadflow
        image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
        imagePullPolicy: {{ .Values.image.pullPolicy }}
        ports:
        - containerPort: 8080
          name: http
          protocol: TCP
        env:
        - name: DATABASE_URL
          value: "jdbc:postgresql://{{ include "atadflow.postgresql.fullname" . }}:5432/{{ .Values.postgresql.auth.database }}"
        - name: QUARKUS_DATASOURCE_USERNAME
          valueFrom:
            secretKeyRef:
              name: {{ include "atadflow.fullname" . }}-db
              key: username
        - name: QUARKUS_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: {{ include "atadflow.fullname" . }}-db
              key: password
        - name: SPARK_CONNECT_URL
          value: "{{ .Values.spark.connectUrl }}"
        - name: PYTHON_EXECUTABLE
          value: "python3"
        livenessProbe:
          httpGet:
            path: /q/health/live
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
          timeoutSeconds: 3
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /q/health/ready
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 10
          timeoutSeconds: 3
          failureThreshold: 3
        startupProbe:
          httpGet:
            path: /q/health/live
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 12
        resources:
          {{- toYaml .Values.resources | nindent 10 }}
```

**Key mappings:**
- Dockerfile `HEALTHCHECK` → `livenessProbe` (existing `/q/health/live` endpoint)
- SmallRye Health → `readinessProbe` (existing `/q/health/ready` endpoint)
- New `startupProbe` → handles initial 60s startup period (PythonLivenessCheck takes time)
- Dockerfile `EXPOSE 8080` → `containerPort: 8080`
- Docker Compose `environment:` → K8s `env:` with ConfigMap/Secret references

**Health Probe Types:**
Per [Kubernetes documentation](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/):
- **Liveness:** Detects deadlocks, triggers restart (maps to existing PythonLivenessCheck)
- **Readiness:** Controls traffic routing, removes pod from service if failing
- **Startup:** Protects slow-starting containers (60s for Python/PySpark initialization)

**Confidence:** HIGH - Direct mapping from existing Docker health checks to K8s probes. [Quarkus SmallRye Health](https://quarkus.io/guides/smallrye-health) already provides `/q/health/live` and `/q/health/ready` endpoints.

---

### 2. PostgreSQL Subchart Integration

**Existing Docker Compose:**
```yaml
postgres:
  image: postgres:16
  environment:
    POSTGRES_DB: atadflow
    POSTGRES_USER: atadflow
    POSTGRES_PASSWORD: atadflow
  # atadflow connects via: jdbc:postgresql://postgres:5432/atadflow
```

**Helm Chart Integration:**

**Chart.yaml:**
```yaml
apiVersion: v2
name: atadflow
version: 0.1.0
appVersion: "1.2.0"
dependencies:
- name: postgresql
  version: ~12.5.8
  repository: https://charts.bitnami.com/bitnami
  condition: postgresql.enabled
```

**values.yaml:**
```yaml
postgresql:
  enabled: true
  auth:
    username: atadflow
    password: atadflow  # Override in production
    database: atadflow
  primary:
    persistence:
      enabled: true
      size: 8Gi
```

**Service DNS Resolution:**
Kubernetes creates a Service for the PostgreSQL pod. Service DNS follows pattern: `<release-name>-postgresql.<namespace>.svc.cluster.local` (short form: `<release-name>-postgresql`).

**Application Connection:**
```yaml
# In deployment.yaml env section
- name: DATABASE_URL
  value: "jdbc:postgresql://{{ include "atadflow.postgresql.fullname" . }}:5432/{{ .Values.postgresql.auth.database }}"
```

The `atadflow.postgresql.fullname` helper generates: `{{ .Release.Name }}-postgresql` (e.g., `atadflow-postgresql` for `helm install atadflow ./helm/atadflow`).

**Credentials Management:**
Per [Helm secrets best practices](https://phoenixnap.com/kb/helm-environment-variables):
1. Development: Credentials in `values.yaml` (gitignored)
2. Production: Override via `--set` or separate values file
3. Kubernetes Secret created by helper template:

```yaml
# templates/secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: {{ include "atadflow.fullname" . }}-db
type: Opaque
stringData:
  username: {{ .Values.postgresql.auth.username }}
  password: {{ .Values.postgresql.auth.password }}
```

**Confidence:** HIGH - [Bitnami PostgreSQL chart](https://artifacthub.io/packages/helm/bitnami/postgresql) is production-ready (v12.5.8 updated Feb 2026). [Recent guide (Jan 2026)](https://oneuptime.com/blog/post/2026-01-17-helm-postgresql-kubernetes-deployment/view) confirms best practices. [Subchart dependency pattern](https://oneuptime.com/blog/post/2026-01-30-helm-subcharts-dependencies/view) is standard approach.

---

### 3. Spark Connect on Kubernetes: Architecture Options

#### Option A: Spark Operator (Recommended for Production)

**Architecture:**
```
Atadflow Pod → SparkConnect CRD → Spark Operator → Spark Connect Server Pod(s)
                                        ↓
                                 Spark Driver/Executor Pods
```

**Implementation:**

1. **Install Apache Spark Kubernetes Operator:**
```bash
helm repo add spark https://apache.github.io/spark-kubernetes-operator
helm repo update
helm install spark-operator spark/spark-kubernetes-operator
```

2. **Define SparkConnect CRD:**
```yaml
# helm/atadflow/templates/spark-connect.yaml (if operator in cluster)
apiVersion: spark.apache.org/v1alpha1
kind: SparkConnect
metadata:
  name: {{ include "atadflow.fullname" . }}-spark
spec:
  image: apache/spark:4.0.2
  server:
    replicas: 1
    service:
      type: ClusterIP
      port: 15002
```

3. **Connection from Atadflow:**
```yaml
# In deployment.yaml
- name: SPARK_CONNECT_URL
  value: "sc://{{ include "atadflow.fullname" . }}-spark:15002"
```

**Pros:**
- Operator manages Spark Connect lifecycle automatically
- Scalable: Can increase `replicas` for multi-job parallelism
- Production-ready: Handles restarts, health monitoring
- Native support for Spark 4.0 Connect API

**Cons:**
- Requires operator installation (cluster-level or namespace CRD)
- Additional complexity for simple single-user tool
- Operator must be pre-installed or chart must conditionally install it

**Confidence:** MEDIUM - [Apache Spark Operator added SparkConnect CRD](https://apache.github.io/spark-kubernetes-operator/) support in v1.5.0 (Jan 2026). However, operator installation adds dependency. Documentation shows successful deployments, but this is newer feature.

#### Option B: Simple Deployment (Recommended for MVP)

**Architecture:**
```
Atadflow Pod → Spark Connect Service → Spark Connect Deployment Pod
                                              ↓
                                       Spark driver/executors in same pod
```

**Implementation:**

```yaml
# helm/atadflow/templates/spark-connect-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "atadflow.fullname" . }}-spark-connect
spec:
  replicas: 1
  selector:
    matchLabels:
      app: spark-connect
  template:
    metadata:
      labels:
        app: spark-connect
    spec:
      containers:
      - name: spark-connect
        image: apache/spark:4.0.2
        command:
        - /opt/spark/sbin/start-connect-server.sh
        args:
        - --packages
        - org.apache.spark:spark-connect_2.13:4.0.2
        - --conf
        - spark.jars.ivy=/tmp/.ivy2
        ports:
        - containerPort: 15002
          name: grpc
        - containerPort: 4040
          name: ui
        env:
        - name: SPARK_NO_DAEMONIZE
          value: "true"
        - name: SPARK_USER_NAME
          value: "spark"
        - name: HOME
          value: "/tmp"
        livenessProbe:
          httpGet:
            path: /
            port: 4040
          initialDelaySeconds: 30
          periodSeconds: 10
          failureThreshold: 10
---
apiVersion: v1
kind: Service
metadata:
  name: {{ include "atadflow.fullname" . }}-spark-connect
spec:
  selector:
    app: spark-connect
  ports:
  - port: 15002
    targetPort: 15002
    name: grpc
  - port: 4040
    targetPort: 4040
    name: ui
```

**Pros:**
- Direct translation from existing Docker Compose setup
- No external dependencies (operator)
- Simpler debugging (single pod)
- Matches existing subprocess-based job execution model
- Faster initial implementation

**Cons:**
- Single replica (no scaling for concurrent jobs)
- Manual lifecycle management
- Not production-optimized for multi-tenant scenarios

**Confidence:** HIGH - Direct port of existing Docker Compose configuration. [Official Spark documentation](https://spark.apache.org/docs/latest/running-on-kubernetes.html) shows standard K8s deployment. Existing health check (port 4040) maps to `livenessProbe`.

**Recommendation:** **Option B (Simple Deployment)** for v1.2 milestone. Simpler integration with existing subprocess execution model, no operator dependency, faster path to working K8s deployment. Option A can be explored in future milestone for multi-user/scalability needs.

---

### 4. ConfigMap and Secret Injection

**Current Environment Variables (from application.properties):**
```properties
%prod.quarkus.datasource.jdbc.url=${DATABASE_URL:jdbc:postgresql://postgres:5432/atadflow}
%prod.quarkus.datasource.username=atadflow
%prod.quarkus.datasource.password=atadflow
%prod.spark.connect.url=${SPARK_CONNECT_URL:sc://spark-connect:15002}
%prod.python.executable=python3
```

**Kubernetes Mapping:**

**ConfigMap (non-sensitive):**
```yaml
# templates/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "atadflow.fullname" . }}
data:
  python.executable: "python3"
  spark.connect.url: "sc://{{ include "atadflow.fullname" . }}-spark-connect:15002"
```

**Secret (sensitive):**
```yaml
# templates/secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: {{ include "atadflow.fullname" . }}-db
type: Opaque
stringData:
  username: {{ .Values.postgresql.auth.username | quote }}
  password: {{ .Values.postgresql.auth.password | quote }}
  database-url: "jdbc:postgresql://{{ include "atadflow.postgresql.fullname" . }}:5432/{{ .Values.postgresql.auth.database }}"
```

**Deployment Environment Injection:**
Per [Helm environment variables guide](https://phoenixnap.com/kb/helm-environment-variables):
```yaml
# In deployment.yaml containers section
env:
- name: PYTHON_EXECUTABLE
  valueFrom:
    configMapKeyRef:
      name: {{ include "atadflow.fullname" . }}
      key: python.executable
- name: SPARK_CONNECT_URL
  valueFrom:
    configMapKeyRef:
      name: {{ include "atadflow.fullname" . }}
      key: spark.connect.url
- name: DATABASE_URL
  valueFrom:
    secretKeyRef:
      name: {{ include "atadflow.fullname" . }}-db
      key: database-url
- name: QUARKUS_DATASOURCE_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ include "atadflow.fullname" . }}-db
      key: username
- name: QUARKUS_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "atadflow.fullname" . }}-db
      key: password
```

**Best Practices:**
1. ConfigMaps for non-sensitive config (Python path, Spark URL structure)
2. Secrets for credentials (DB password, future API keys)
3. Parameterize via `values.yaml` for environment-specific overrides
4. Per [2026 Helm guide](https://jiminbyun.medium.com/how-to-manage-environment-variables-in-helm-charts-a-comprehensive-guide-eac379703099): avoid hardcoding, use `{{ .Values.* }}` references

**Confidence:** HIGH - Standard Kubernetes pattern verified across [multiple](https://phoenixnap.com/kb/helm-environment-variables) [recent](https://jiminbyun.medium.com/how-to-manage-environment-variables-in-helm-charts-a-comprehensive-guide-eac379703099) [sources](https://medium.com/gammastack/mounting-environment-variables-safely-with-kubernetes-secrets-and-helm-chart-764420dc787b).

---

## Integration Test Architecture

### Test Environment: k3d

**Rationale:** k3d wraps k3s (lightweight Kubernetes) in Docker, starts <5 seconds, fully compliant with standard K8s. Per [k3d guide](https://devtron.ai/blog/k3d-for-local-kubernetes-development/), ideal for local Helm testing. No k3d-specific assumptions in Helm chart (works on any K8s cluster).

**Setup:**
```bash
k3d cluster create atadflow-test
kubectl config use-context k3d-atadflow-test
helm install atadflow ./helm/atadflow
```

**Confidence:** HIGH - [k3d is standard for local K8s testing](https://medium.com/@munza/local-kubernetes-with-k3d-helm-dashboard-6510d906431b), [supports Helm natively](https://docs.k3s.io/add-ons/helm).

### Test Strategy: kubectl port-forward + Existing Test Harness

**Architecture:**
```
Integration Test Process (JVM)
  ↓
kubectl port-forward (localhost:8080 → atadflow-pod:8080)
  ↓
Atadflow Pod (Quarkus)
  ↓
PostgreSQL Pod (Bitnami subchart)
  ↓
Spark Connect Pod (Simple Deployment)
```

**Implementation:**
```java
// k8s-integration-tests/src/test/java/io/atadflow/k8s/HelmDeploymentTest.java
@QuarkusTest
public class HelmDeploymentTest {

    private Process portForwardProcess;

    @BeforeEach
    void setupPortForward() throws IOException {
        // Start kubectl port-forward 8080:8080
        portForwardProcess = new ProcessBuilder(
            "kubectl", "port-forward",
            "deployment/atadflow", "8080:8080"
        ).start();

        // Wait for port-forward to establish
        Thread.sleep(2000);
    }

    @Test
    void testHealthEndpoints() {
        given()
            .when().get("http://localhost:8080/q/health/live")
            .then().statusCode(200);

        given()
            .when().get("http://localhost:8080/q/health/ready")
            .then().statusCode(200);
    }

    @Test
    void testFlowExecution() {
        // Reuse existing DockerComposeIntegrationTest logic
        // POST /api/flows → GET /api/flows/{id} → POST /api/jobs → poll status
    }

    @AfterEach
    void teardownPortForward() {
        if (portForwardProcess != null) {
            portForwardProcess.destroy();
        }
    }
}
```

**Alternative Access Methods:**
Per [kubectl port-forward testing guide (Feb 2026)](https://oneuptime.com/blog/post/2026-02-09-kubectl-port-forward-testing/view):

1. **kubectl port-forward** (recommended): Localhost access, no cluster networking changes
2. **kubectl exec**: Direct pod shell access for debugging
3. **NodePort Service**: Exposes on node IP (not portable across clusters)
4. **LoadBalancer**: Requires cloud provider (not available in k3d without MetalLB)

**Confidence:** HIGH - Port-forward is [standard testing approach](https://kubernetes.io/docs/tasks/access-application-cluster/port-forward-access-application-cluster/), reuses existing REST-Assured test suite from DockerComposeIntegrationTest.

### Test Execution Flow

```bash
# CI or local testing
k3d cluster create atadflow-test
helm install atadflow ./helm/atadflow --wait --timeout 5m
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=atadflow --timeout=120s
./gradlew :k8s-integration-tests:test
helm uninstall atadflow
k3d cluster delete atadflow-test
```

**Confidence:** MEDIUM - Pattern validated across [k3d Helm testing examples](https://medium.com/@munza/local-kubernetes-with-k3d-helm-dashboard-6510d906431b), but integration with existing Gradle test suite needs implementation details.

---

## Telepresence Architecture

### How It Works

Per [Telepresence documentation](https://kubernetes.io/docs/tasks/debug/debug-cluster/local-debugging/):

1. **Traffic Manager:** Deployed as pod in cluster, coordinates intercepts
2. **Traffic Agent:** Injected into target pod, proxies traffic
3. **Local Daemon:** Runs on developer machine, receives intercepted traffic
4. **VPN Tunnel:** Two-way network proxy between local machine and cluster

**Architecture Diagram:**
```
Developer Laptop                  Kubernetes Cluster
─────────────────                 ──────────────────
Local Quarkus App                 atadflow Deployment
(port 8080)                         ↓
    ↑                             Traffic Agent (sidecar)
    │                               ↑
Telepresence Daemon ←──VPN Tunnel──┤
                                    │
                                Traffic Manager Pod
                                    ↑
                              Service (atadflow)
                                    ↑
                              Ingress/LoadBalancer
```

### Intercept Command

```bash
# Connect to cluster
telepresence connect

# Intercept atadflow deployment
telepresence intercept atadflow --port 8080:8080

# Now run local Quarkus app
cd backend
./gradlew quarkusDev

# All traffic to atadflow service → localhost:8080
# Local app sees cluster resources (PostgreSQL, Spark Connect)
```

**What Gets Intercepted:**
- HTTP requests to atadflow Service → routed to localhost:8080
- Environment variables from pod → injected into local process
- ConfigMaps/Secrets → accessible locally

**What Doesn't Get Intercepted:**
- Python subprocess execution (runs locally, connects to cluster Spark Connect)
- Database connections (local app connects to cluster PostgreSQL via service DNS)

Per [Telepresence intercept guide](https://www.getambassador.io/docs/telepresence/latest/howtos/intercepts/): "Intercepts redirect incoming traffic to a service in your cluster to your local environment instead."

**PySpark Subprocess Behavior:**
When Atadflow executes PySpark via ProcessBuilder:
1. Local Quarkus app receives HTTP request (intercepted)
2. Generates PySpark code with `SparkSession.builder.remote("sc://atadflow-spark-connect:15002")`
3. Spawns Python subprocess on developer laptop
4. Python subprocess connects to cluster Spark Connect (via VPN tunnel)
5. Spark Connect executes streaming job in cluster

**Confidence:** MEDIUM-HIGH - [Recent guide (Jan 2026)](https://oneuptime.com/blog/post/2026-01-19-kubernetes-telepresence-local-debugging/view) confirms architecture. Subprocess execution over VPN tunnel is supported but not explicitly documented for Spark Connect use case.

### Telepresence vs kubectl port-forward

| Feature | Telepresence | kubectl port-forward |
|---------|-------------|---------------------|
| Traffic interception | Yes (transparent replacement) | No (manual forwarding) |
| Two-way networking | Yes (VPN) | One-way (localhost → pod) |
| Environment variables | Injected from pod | Manual setup |
| Use case | Active development | Testing/debugging |
| Setup complexity | Medium (daemon install) | Low (built-in kubectl) |

**Recommendation:** Document both approaches:
- Telepresence for rapid iteration (change Java code, see results without rebuild)
- kubectl port-forward for integration testing (automated CI)

**Confidence:** HIGH - [Official K8s documentation](https://kubernetes.io/docs/tasks/debug/debug-cluster/local-debugging/) and [Ambassador docs](https://telepresence.io/docs/concepts/faster/) provide clear guidance.

---

## Component Dependencies and Build Order

### Dependency Graph

```
1. Helm Chart Structure (Chart.yaml, values.yaml, _helpers.tpl)
   └─→ 2. PostgreSQL Subchart Integration
       └─→ 3. Spark Connect Deployment
           └─→ 4. Atadflow Deployment (depends on PostgreSQL + Spark Connect)
               └─→ 5. Service + Ingress (optional)
                   └─→ 6. Integration Tests
                       └─→ 7. Telepresence Documentation
```

### Suggested Build Order

| Phase | Component | Rationale | Validation |
|-------|-----------|-----------|------------|
| 1 | **Helm Chart Scaffold** | Foundation for all other components | `helm lint`, `helm template` renders |
| 2 | **PostgreSQL Subchart** | Required dependency for Atadflow | `helm dependency update`, PostgreSQL pod starts |
| 3 | **Spark Connect Deployment** | Required for job execution | Spark Connect pod healthy, port 15002 accessible |
| 4 | **Atadflow Deployment** | Core application | Pod starts, health probes pass |
| 5 | **ConfigMap/Secret Integration** | Environment configuration | Environment variables injected correctly |
| 6 | **Service Resource** | Network access to Atadflow | Service routes traffic to pod |
| 7 | **Integration Tests** | End-to-end validation | Tests pass on k3d cluster |
| 8 | **Telepresence Documentation** | Developer experience | Intercept works, local dev functional |

**Critical Path:** 1 → 2 → 3 → 4 (nothing can proceed until Helm structure + dependencies are working)

**Parallel Work Opportunities:**
- ConfigMap/Secret templates can be written alongside Deployment (both use same values)
- Integration test scaffolding can be prepared while Helm chart is being built
- Telepresence documentation is independent (can be written last)

**Confidence:** HIGH - Standard Helm development workflow, dependencies clearly defined.

---

## New vs Modified Components

### New Components

| Component | Location | Purpose |
|-----------|----------|---------|
| `helm/atadflow/Chart.yaml` | Project root | Helm chart metadata and dependencies |
| `helm/atadflow/values.yaml` | Project root | Configuration defaults |
| `helm/atadflow/templates/*.yaml` | Project root | Kubernetes manifests (Deployment, Service, ConfigMap, Secret) |
| `helm/atadflow/templates/_helpers.tpl` | Project root | Reusable template functions |
| `helm/atadflow/templates/NOTES.txt` | Project root | Post-install instructions |
| `k8s-integration-tests/` | Project root | Kubectl-based integration tests |
| `README-TELEPRESENCE.md` | Project root | Developer guide for Telepresence |

### Modified Components

| Component | Changes | Rationale |
|-----------|---------|-----------|
| `Dockerfile` | **NONE** | Reused as-is by K8s Deployment |
| `docker-compose.yml` | **NONE** (kept for local dev) | K8s replaces for production, Docker Compose remains local option |
| `application.properties` | **NONE** | Existing `%prod` profile already uses env vars |
| `.gitignore` | Add `helm/atadflow/charts/` | Ignore downloaded subchart dependencies |
| `README.md` | Add K8s deployment section | Document `helm install` process |

**Key Insight:** Existing architecture is K8s-ready. No backend/frontend code changes needed. Docker Compose env var pattern maps directly to K8s ConfigMap/Secret injection.

**Confidence:** HIGH - Clean separation of concerns allows Helm addition without modifying existing components.

---

## Integration Points Summary

### Existing → Kubernetes Mappings

| Existing Component | Kubernetes Equivalent | Integration Method |
|--------------------|----------------------|-------------------|
| Dockerfile | Container image in Deployment | Direct reuse via `image:` spec |
| Docker Compose `environment:` | ConfigMap + Secret | `valueFrom` with `configMapKeyRef`/`secretKeyRef` |
| Docker Compose `healthcheck:` | Liveness/Readiness/Startup probes | `httpGet` to existing `/q/health/*` endpoints |
| Docker Compose service DNS | K8s Service DNS | Replace `postgres` with `{{ .Release.Name }}-postgresql` |
| Docker Compose `depends_on:` | Helm dependency + init containers (optional) | Chart.yaml dependencies + readiness probes |
| Port 8080 | Service targetPort | Direct mapping |
| PythonLivenessCheck | Startup probe | Handles 60s initialization period |

### External Dependencies

| Dependency | Source | Version | Integration |
|------------|--------|---------|-------------|
| Bitnami PostgreSQL | https://charts.bitnami.com/bitnami | ~12.5.8 | Chart.yaml dependency |
| Apache Spark image | docker.io/apache/spark | 4.0.2 | Deployment image reference |
| k3d (testing) | https://k3d.io/ | Latest | Local test cluster |
| Telepresence (optional) | https://telepresence.io/ | Latest | Developer tooling |

**Confidence:** HIGH - All dependencies are stable, actively maintained, and documented as of Feb 2026.

---

## Risks and Mitigations

### Risk 1: Spark Connect Multi-Job Concurrency
**Risk:** Single Spark Connect pod may not handle concurrent job submissions well.
**Mitigation:** Start with Simple Deployment (single pod). If concurrency issues arise, migrate to Spark Operator with multiple replicas. Existing subprocess model already serializes jobs at Quarkus level.
**Severity:** Low (single-user tool, sequential execution expected)

### Risk 2: Persistent Volume for PostgreSQL
**Risk:** Data loss if PostgreSQL pod restarts without persistent volume.
**Mitigation:** Bitnami subchart defaults to `persistence.enabled: true`. Ensure StorageClass is available in target cluster (k3d includes local-path provisioner by default).
**Severity:** Medium (critical for production, k3d handles automatically)

### Risk 3: Telepresence VPN Overhead for PySpark
**Risk:** Network latency from laptop → cluster Spark Connect over VPN.
**Mitigation:** Document this as expected behavior. For performance testing, use in-cluster execution (without intercept). Telepresence is development tool, not production deployment.
**Severity:** Low (development-only concern)

### Risk 4: Helm Subchart Version Compatibility
**Risk:** Bitnami PostgreSQL chart major version changes could break compatibility.
**Mitigation:** Pin to minor version range in Chart.yaml (`version: ~12.5.8` allows 12.5.x, blocks 12.6+). Test upgrades in staging before production.
**Severity:** Low (standard Helm versioning practice)

**Confidence:** MEDIUM - Risks are standard Kubernetes concerns with well-known mitigations. No Atadflow-specific architectural blockers identified.

---

## Sources

### Helm Chart Structure
- [Charts | Helm](https://helm.sh/docs/topics/charts/)
- [Best Practices | Helm](https://helm.sh/docs/chart_best_practices/)
- [Helm Charts: The Complete Guide for 2026 | DevToolbox Blog](https://devtoolbox.dedyn.io/blog/helm-charts-complete-guide)
- [How to Organize Your Helm Charts for Efficient Kubernetes Deployments](https://www.anantacloud.com/post/how-to-organize-your-helm-charts-for-efficient-kubernetes-deployments)

### Kubernetes Health Probes
- [Liveness, Readiness, and Startup Probes | Kubernetes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/)
- [Configure Liveness, Readiness and Startup Probes | Kubernetes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- [How to Build Health Probes for Kubernetes in Spring Boot](https://oneuptime.com/blog/post/2026-01-25-health-probes-kubernetes-spring-boot/view)
- [Options available for Health Checks with Helm Charts | by Chris Harwell | Medium](https://chrisharwell94.medium.com/options-available-for-health-checks-with-helm-charts-b139f26f70aa)

### PostgreSQL Subchart
- [Bitnami Secure Images Helm chart for PostgreSQL](https://artifacthub.io/packages/helm/bitnami/postgresql)
- [charts/bitnami/postgresql at main · bitnami/charts](https://github.com/bitnami/charts/tree/main/bitnami/postgresql)
- [How to Implement Helm Subcharts Dependencies](https://oneuptime.com/blog/post/2026-01-30-helm-subcharts-dependencies/view)
- [Deploying PostgreSQL on Kubernetes with Helm](https://oneuptime.com/blog/post/2026-01-17-helm-postgresql-kubernetes-deployment/view)

### Spark Connect on Kubernetes
- [Apache Spark™ K8s Operator | spark-kubernetes-operator](https://apache.github.io/spark-kubernetes-operator/)
- [Spark Connect support · Issue #1801 · kubeflow/spark-operator](https://github.com/kubeflow/spark-operator/issues/1801)
- [Running Spark on Kubernetes - Spark 4.1.0 Documentation](https://spark.apache.org/docs/latest/running-on-kubernetes.html)
- [Spark Connect :: spark-k8s :: Stackable Documentation](https://docs.stackable.tech/home/stable/spark-k8s/usage-guide/spark-connect/)

### ConfigMap and Secret Injection
- [How to Use Environment Variables with Helm Charts](https://phoenixnap.com/kb/helm-environment-variables)
- [How to Manage Environment Variables in Helm Charts: A Comprehensive Guide | by Jimin | Medium](https://jiminbyun.medium.com/how-to-manage-environment-variables-in-helm-charts-a-comprehensive-guide-eac379703099)
- [Mount Environment Variables Safely with Kubernetes Secrets and Helm chart | by Ketan Saxena | GAMMASTACK | Medium](https://medium.com/gammastack/mounting-environment-variables-safely-with-kubernetes-secrets-and-helm-chart-764420dc787b)
- [Referencing Kubernetes Secret in Helm Chart | Baeldung on Ops](https://www.baeldung.com/ops/helm-chart-kubernetes-secret-reference)

### Integration Testing
- [How to Use kubectl port-forward for Testing Service Connectivity](https://oneuptime.com/blog/post/2026-02-09-kubectl-port-forward-testing/view)
- [Use Port Forwarding to Access Applications in a Cluster | Kubernetes](https://kubernetes.io/docs/tasks/access-application-cluster/port-forward-access-application-cluster/)
- [Kubectl Port-Forward: Complete Guide for Kubernetes Developers](https://lenshq.io/blog/kubernetes-port-forward)

### k3d Local Testing
- [K3d for Local Kubernetes Development | Devtron](https://devtron.ai/blog/k3d-for-local-kubernetes-development/)
- [Run Kubernetes Cluster Locally with k3d and Helm | Medium](https://medium.com/@munza/local-kubernetes-with-k3d-helm-dashboard-6510d906431b)
- [Helm Charts: The Complete Guide for 2026 | DevToolbox Blog](https://devtoolbox.dedyn.io/blog/helm-charts-complete-guide)

### Telepresence
- [GitHub - telepresenceio/telepresence: Local development against a remote Kubernetes or OpenShift cluster](https://github.com/telepresenceio/telepresence)
- [How to Debug Locally with Telepresence in Kubernetes](https://oneuptime.com/blog/post/2026-01-19-kubernetes-telepresence-local-debugging/view)
- [Developing and debugging services locally using telepresence | Kubernetes](https://kubernetes.io/docs/tasks/debug/debug-cluster/local-debugging/)
- [Intercept a service in your own environment | Ambassador Telepresence](https://www.getambassador.io/docs/telepresence/latest/howtos/intercepts/)
- [Making the remote local: Faster feedback, collaboration and debugging | Telepresence](https://telepresence.io/docs/concepts/faster/)
