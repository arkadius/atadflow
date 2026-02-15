# Phase 1: Helm Chart with PostgreSQL - Research

**Researched:** 2026-02-15
**Domain:** Kubernetes Helm chart deployment with PostgreSQL dependency
**Confidence:** HIGH

## Summary

This phase involves creating a Helm chart for the Atadflow application with PostgreSQL as a chart dependency. Based on research, the recommended approach is to use the Bitnami PostgreSQL Helm chart as a dependency, following Helm 4 best practices. The chart should be structured with proper values management, include resource limits, and handle secrets appropriately.

**Primary recommendation:** Use Bitnami PostgreSQL 18.x chart as dependency with version range (~18.3.0), configure application to connect via Kubernetes internal DNS, and use external secrets or values overrides for credentials.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Helm | 3.x or 4.x | Package manager for Kubernetes | Industry standard for K8s deployments |
| Bitnami PostgreSQL | ~18.3.0 | PostgreSQL as chart dependency | Most widely used, well-maintained, production-ready |
| Kubernetes | 1.26+ | Container orchestration | Target deployment platform |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Helm OCI | Latest | Pull Bitnami charts from OCI registry | For automated CI/CD pipelines |
| External Secrets Operator | Latest | Manage secrets externally | For production-grade secret management |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Bitnami PostgreSQL | CloudNativePG Operator | More complex setup, but production-grade with automated failover |
| Bitnami PostgreSQL | PostgreSQL official chart | Less features, fewer configuration options |
| Helm 3 | Helm 4 | Helm 4 has new features but Helm 3 is more widely |

## Architecture adopted Patterns

### Recommended Project Structure
```
atadflow/
├── Chart.yaml              # Chart metadata with dependencies
├── values.yaml             # Default configuration values
├── values-dev.yaml         # Development overrides
├── values-prod.yaml        # Production overrides
├── templates/
│   ├── _helpers.tpl        # Template helper functions
│   ├── deployment.yaml    # Application deployment
│   ├── service.yaml       # Application service
│   ├── ingress.yaml       # Ingress (optional)
│   └── tests/
│       └── test-connection.yaml
├── .helmignore
└── README.md
```

### Pattern 1: Chart.yaml with PostgreSQL Dependency
**What:** Declarative dependency on Bitnami PostgreSQL chart
**When to use:** Always for PostgreSQL as chart dependency
**Example:**
```yaml
# Chart.yaml
apiVersion: v2
name: atadflow
description: A Helm chart for Atadflow - Visual Streaming Flow Designer
type: application
version: 0.1.0
appVersion: "1.1.0"

dependencies:
  - name: postgresql
    version: "~18.3.0"
    repository: "oci://registry-1.docker.io/bitnamicharts"
    condition: postgresql.enabled
```

### Pattern 2: Application Connection to PostgreSQL
**What:** Configure app to connect to PostgreSQL via Kubernetes internal DNS
**When to use:** Always for database connection within same Kubernetes cluster
**Example:**
```yaml
# In values.yaml or deployment
extraEnvVars:
  - name: DATABASE_URL
    value: "jdbc:postgresql://{{ .Release.Name }}-postgresql:5432/atadflow"
  - name: QUARKUS_DATASOURCE_USERNAME
    value: "atadflow"
  - name: QUARKUS_DATASOURCE_PASSWORD
    valueFrom:
      secretKeyRef:
        name: {{ .Release.Name }}-postgresql
        key: postgres-password
```

### Pattern 3: Subchart Value Configuration
**What:** Override PostgreSQL subchart values from parent chart
**When to use:** To customize PostgreSQL configuration
**Example:**
```yaml
# values.yaml
postgresql:
  enabled: true
  auth:
    username: atadflow
    database: atadflow
  primary:
    persistence:
      enabled: true
      size: 8Gi
    resources:
      requests:
        memory: "256Mi"
        cpu: "250m"
      limits:
        memory: "512Mi"
        cpu: "500m"
```

### Anti-Patterns to Avoid
- **Hardcoding secrets in values.yaml:** Never store passwords in plain text - use external secrets or require user to provide via --set
- **Using latest tag for images:** Always pin to specific versions for reproducibility
- **Missing resource limits:** Always define requests and limits to prevent resource exhaustion
- **No health checks:** Always include liveness and readiness probes

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| PostgreSQL installation | Custom StatefulSet with PostgreSQL | Bitnami PostgreSQL chart | Handles init scripts, persistence, backups, upgrades |
| Secret management | ConfigMap with base64-encoded secrets | External Secrets Operator or sealed secrets | Proper encryption, rotation, audit trail |
| Database initialization | Custom init container | Bitnami init scripts or initContainer in chart | Well-tested, handles edge cases |

**Key insight:** Bitnami charts are production-hardened by VMware/Broadcom with millions of downloads - they handle edge cases you'd miss in custom implementations.

## Common Pitfalls

### Pitfall 1: Values Not Applied
**What goes wrong:** Changes to values.yaml have no effect on deployed resources
**Why it happens:** Wrong values file path or incorrect .Values path in templates
**How to avoid:** Use `helm template . -f values-prod.yaml` to validate before deploy
**Warning signs:** `helm get values <release>` shows different values than expected

### Pitfall 2: YAML Indentation Errors
**What goes wrong:** Helm fails with YAML parsing errors
**Why it happens:** Wrong indentation, tabs instead of spaces, or incorrect nesting
**How to avoid:** Validate YAML with `yamllint` before deploy; use 2-space indentation
**Warning signs:** "error converting YAML to a struct" errors

### Pitfall 3: Nil Pointer Errors
**What goes wrong:** "nil pointer evaluating interface" error during template rendering
**Why it happens:** Referencing a value that doesn't exist in values.yaml
**How to avoid:** Use default values: `{{ .Values.myValue | default "default" }}`
**Warning signs:** Template renders fine but fails on helm install/upgrade

### Pitfall 4: Hardcoded Secrets
**What goes wrong:** Passwords exposed in values.yaml or rendered templates
**Why it happens:** Storing credentials directly in configuration files
**How to avoid:** Use `--set` for secrets, External Secrets Operator, or require users to create secrets before install
**Warning signs:** `helm get manifest` shows plain text passwords

### Pitfall 5: Image Pull Secrets for Private Registry
**What goes wrong:** Deployment fails to pull application image from private registry
**Why it happens:** Missing imagePullSecrets configuration
**How to avoid:** Define `imagePullSecrets` in values.yaml when using private registries
**Warning signs:** ImagePullBackOff status on pods

## Code Examples

### Complete Chart.yaml with PostgreSQL Dependency
```yaml
# Source: Helm Best Practices
apiVersion: v2
name: atadflow
description: A Helm chart for Atadflow - Visual Streaming Flow Designer for PySpark
type: application
version: 0.1.0
appVersion: "1.1.0"

dependencies:
  - name: postgresql
    version: "~18.3.0"
    repository: "oci://registry-1.docker.io/bitnamicharts"
    condition: postgresql.enabled
```

### Deployment with PostgreSQL Environment Variables
```yaml
# Source: Standard Kubernetes patterns
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "atadflow.fullname" . }}
  labels:
    {{- include "atadflow.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      {{- include "atadflow.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "atadflow.selectorLabels" . | nindent 8 }}
    spec:
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      serviceAccountName: {{ include "atadflow.serviceAccountName" . }}
      securityContext:
        {{- toYaml .Values.podSecurityContext | nindent 8 }}
      containers:
        - name: {{ .Chart.Name }}
          securityContext:
            {{- toYaml .Values.securityContext | nindent 12 }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
          livenessProbe:
            httpGet:
              path: /q/health/live
              port: http
          readinessProbe:
            httpGet:
              path: /q/health/ready
              port: http
          env:
            - name: DATABASE_URL
              value: "jdbc:postgresql://{{ .Release.Name }}-postgresql:5432/atadflow"
            - name: QUARKUS_DATASOURCE_USERNAME
              value: "{{ .Values.postgresql.auth.username }}"
            - name: QUARKUS_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ .Release.Name }}-postgresql
                  key: postgres-password
            - name: SPARK_CONNECT_URL
              value: "{{ .Values.spark.connectUrl }}"
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
```

### values.yaml with PostgreSQL Configuration
```yaml
# Source: Bitnami chart patterns
replicaCount: 1

image:
  repository: atadflow/atadflow
  tag: "1.1.0"
  pullPolicy: IfNotPresent
  pullSecrets: []

service:
  type: ClusterIP
  port: 80
  targetPort: 8080

postgresql:
  enabled: true
  auth:
    username: atadflow
    database: atadflow
    # Password should be set via --set or external secrets
    # password: ""
  primary:
    persistence:
      enabled: true
      size: 8Gi
    resources:
      requests:
        memory: "256Mi"
        cpu: "250m"
      limits:
        memory: "512Mi"
        cpu: "500m"

spark:
  # External Spark Connect URL - set to empty if Spark should also be deployed
  connectUrl: "sc://spark-connect:15002"

resources:
  limits:
    memory: "2Gi"
    cpu: "1000m"
  requests:
    memory: "1Gi"
    cpu: "500m"

nodeSelector: {}

tolerations: []

affinity: {}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Helm 2 (Tiller) | Helm 3/4 (no Tiller) | 2019 (Helm 3) | Cluster security improved, no persistent storage needed |
| Chart dependencies via repos | OCI registry support | Helm 3.8+ | Standardized chart distribution |
| Bitnami PostgreSQL 12.x | Bitnami PostgreSQL 18.x | 2025-2026 | PostgreSQL 16 support, improved security |
| Secrets in values.yaml | External Secrets Operator | 2020+ | Production-grade secret management |

**Deprecated/outdated:**
- Tiller (Helm 2): Replaced by Helm 3/4 cluster security model
- Stable charts repository: Migrated to Bitnami charts on OCI registry
- ConfigMap secrets: Use External Secrets Operator or sealed secrets

## Open Questions

1. **Image Registry Strategy**
   - What we know: Need to push Docker image to a registry accessible from Kubernetes
   - What's unclear: Which registry (Docker Hub, GHCR, ECR, self-hosted)?
   - Recommendation: Use existing infrastructure - likely GHCR or Docker Hub for initial deployment

2. **Spark Connect Deployment**
   - What we know: App requires Spark Connect URL; docker-compose includes Spark Connect
   - What's unclear: Should Spark Connect be included in Helm chart or deployed separately?
   - Recommendation: Initially keep external, document for future enhancement to include in chart

3. **Secret Management**
   - What we know: PostgreSQL credentials need to be managed securely
   - What's unclear: Use simple --set approach or External Secrets Operator?
   - Recommendation: Start with --set for simplicity, plan for External Secrets Operator migration

4. **Ingress Configuration**
   - What we know: Need to expose application on HTTP
   - What's unclear: Ingress controller availability in target cluster?
   - Recommendation: Make ingress optional via values.yaml

## Sources

### Primary (HIGH confidence)
- Helm Official Docs - Dependencies best practices: https://helm.sh/docs/chart_best_practices/dependencies/
- Helm Official Docs - Chart best practices: https://helm.sh/docs/chart_best_practices/
- Artifact Hub - Bitnami PostgreSQL chart: https://artifacthub.io/packages/helm/bitnami/postgresql

### Secondary (MEDIUM confidence)
- Bitnami PostgreSQL Chart GitHub: https://github.com/bitnami/charts/tree/main/bitnami/postgresql
- Helm 4 Best Practices 2025: https://user-cube.medium.com/helm-best-practices-2025

### Tertiary (LOW confidence)
- Helm Chart Mistakes article: https://www.devopstraininginstitute.com/blog/12-helm-chart-mistakes-how-to-fix-them (needs verification with official docs)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Official Helm docs and Bitnami chart verified
- Architecture: HIGH - Standard Helm patterns from official documentation
- Pitfalls: HIGH - Common issues documented across multiple sources, verified against official docs

**Research date:** 2026-02-15
**Valid until:** 2026-03-15 (30 days for stable technology)
