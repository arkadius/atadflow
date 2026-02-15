# Phase 2: Spark Connect Production Research - Research

**Researched:** 2026-02-15
**Domain:** Apache Spark Connect deployment on Kubernetes
**Confidence:** HIGH

## Summary

Research identifies three viable approaches for deploying Spark Connect on Kubernetes: (1) **Apache Spark K8s Operator** - the official operator from Apache Spark, now at v0.7.0 (Jan 2026) with native Spark Connect server support; (2) **Kubeflow Spark Operator** - the older, battle-tested operator from Kubeflow community, excellent for job submission but not purpose-built for Spark Connect servers; (3) **Simple Deployment** - direct Spark Connect server deployment without an operator, similar to the current docker-compose setup.

For atadflow's use case (single-tenant, internal tool, already using Spark Connect), the **Apache Spark K8s Operator** is recommended. It provides: native Spark Connect server support as a long-running application, tight integration with Apache Spark releases, active development (v0.7.0 in Jan 2026), and operator-pattern benefits (declarative configuration, lifecycle management).

**Primary recommendation:** Use Apache Spark K8s Operator v0.7.0+ with Helm chart to deploy Spark Connect server as a SparkApplication with dynamic allocation enabled. Add the operator as a Helm dependency to existing atadflow chart.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Apache Spark K8s Operator | 0.7.0+ | Kubernetes operator for Spark applications | Official Apache Spark subproject, active development |
| Spark Connect Server | 4.0.2+ | Long-running gRPC server for Spark Connect | Already in use by atadflow |
| Helm | 3.0+ | Package manager for Kubernetes | Industry standard for K8s deployments |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Apache Spark Docker Image | 4.0.2 | Container image for Spark Driver/Executors | Already in use |
| PostgreSQL (Bitnami) | 18.3.0 | Existing database dependency | Already in chart |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Apache Spark K8s Operator | Kubeflow Spark Operator | Kubeflow is more mature but lacks native Spark Connect server support; requires more manual configuration |
| Apache Spark K8s Operator | Manual spark-connect deployment | Simpler but loses operator benefits (declarative config, lifecycle management, observability) |
| Apache Spark K8s Operator | Stackable Spark Operator | More opinionated, adds complexity; considered experimental |
| Bitnami PostgreSQL | PostgreSQL operator | Current chart uses Bitnami; simpler than operator |

## Architecture Patterns

### Recommended Project Structure
```
chart/
├── Chart.yaml              # Add spark-kubernetes-operator as dependency
├── values.yaml             # Configure operator and Spark Connect
├── templates/
│   ├── spark-connect.yaml  # SparkApplication for Connect server (optional, can be in values)
```

### Pattern 1: Apache Spark K8s Operator with Spark Connect Server
**What:** Deploy Spark Connect server as a long-running SparkApplication using the operator
**When to Use:** Production deployments requiring operator benefits
**Example:**
```yaml
# Source: https://github.com/apache/spark-kubernetes-operator/blob/main/examples/spark-connect-server.yaml
apiVersion: spark.apache.org/v1
kind: SparkApplication
metadata:
  name: spark-connect-server
spec:
  mainClass: "org.apache.spark.sql.connect.service.SparkConnectServer"
  sparkConf:
    spark.dynamicAllocation.enabled: "true"
    spark.dynamicAllocation.shuffleTracking.enabled: "true"
    spark.dynamicAllocation.minExecutors: "3"
    spark.dynamicAllocation.maxExecutors: "3"
    spark.kubernetes.authenticate.driver.serviceAccountName: "spark"
    spark.kubernetes.container.image: "apache/spark:4.0.2"
  runtimeVersions:
    sparkVersion: "4.0.2"
```

### Pattern 2: Simple Spark Connect Deployment (Current Approach)
**What:** Deploy Spark Connect server as a simple Kubernetes Deployment without operator
**When to Use:** Development, simple deployments, or when operator overhead is unwanted
**Example:**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: spark-connect
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
        command: ["/opt/spark/sbin/start-connect-server.sh"]
        args:
          - "--packages"
          - "org.apache.spark:spark-connect_2.13:4.0.2"
        ports:
        - containerPort: 15002
---
apiVersion: v1
kind: Service
metadata:
  name: spark-connect
spec:
  selector:
    app: spark-connect
  ports:
  - port: 15002
    targetPort: 15002
  type: ClusterIP
```

### Pattern 3: Helm Chart Dependency (Recommended for atadflow)
**What:** Add Apache Spark K8s Operator as Helm dependency
**When to Use:** When you want operator features integrated with existing chart
**Example:**
```yaml
# chart/Chart.yaml
dependencies:
  - name: postgresql
    version: "18.3.0"
    repository: "https://charts.bitnami.com/bitnami"
  - name: spark-kubernetes-operator
    version: "1.5.0"
    repository: "https://apache.github.io/spark-kubernetes-operator"
```

### Anti-Patterns to Avoid
- **Running Spark Connect without health checks:** Spark Connect server can fail silently; always configure liveness/readiness probes
- **Using static executor allocation in multi-tenant scenario:** Dynamic allocation is recommended for production
- **Not setting resource limits:** Spark can consume all available cluster resources; always set memory/cpu limits
- **Using deprecated Bitnami images in production:** Bitnami moved behind Broadcom subscription (Sept 2025); consider alternatives

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Spark job lifecycle management | Custom controller | Apache Spark K8s Operator | Operator handles retry, state tracking, cleanup |
| Spark Connect server deployment | Custom Deployment + Service | Operator's SparkApplication | Native integration with Spark lifecycle |
| Job scheduling on Kubernetes | Custom cron + submit script | Spark Operator + optional Airflow | Proper job queuing, retries, observability |
| Dynamic scaling | Manual executor management | Spark dynamic allocation | Automatic scaling based on workload |

**Key insight:** The Spark Connect server is a long-running service (like a database), not batch jobs. The operator handles this well with the SparkApplication resource that supports `RunningHealthy` state.

## Common Pitfalls

### Pitfall 1: Spark Connect Server Not Exposing Correct Port
**What goes wrong:** Default Spark Connect uses port 15002, but container might not expose it correctly
**Why it happens:** Different Spark versions use different default ports; misconfiguration in Service
**How to avoid:** Explicitly set `spark.connect.server.grpc.port` in sparkConf and ensure K8s Service targets correct port
**Warning signs:** Connection refused errors from client, "Failed to connect to Spark Connect server"

### Pitfall 2: Dynamic Allocation Not Working Across Restart
**What goes wrong:** Executors from previous sessions linger after driver restart
**Why it happens:** Shuffle data not cleaned up; executor pods remain orphaned
**How to avoid:** Set `spark.kubernetes.executor.deleteOnTerminate` and configure proper cleanup
**Warning signs:** Many executor pods stuck in Pending or Running state

### Pitfall 3: Image Pull Secrets Not Configured
**What goes wrong:** Private registry images fail to pull
**Why it happens:** Forgetting to configure `imagePullSecrets` in SparkApplication spec
**How to avoid:** Add `imagePullSecrets` to spec or use default service account with appropriate secrets
**Warning signs:** ImagePullBackOff in pod events

### Pitfall 4: Service Account Permissions Missing
**What goes wrong:** Spark driver can't create executor pods or services
**Why it happens:** RBAC not configured for the service account
**How to avoid:** Create ServiceAccount with proper RBAC (create, get, list, delete pods; create services)
**Warning signs:** "Failed to create pod" or "Unauthorized" in driver logs

### Pitfall 5: Mixing Spark Versions
**What goes wrong:** Client and server Spark versions incompatible
**Why it happens:** Using different PySpark version on client than server
**How to avoid:** Match client PySpark version with server Spark version (e.g., PySpark 4.0.2 with Spark 4.0.2)
**Warning signs:** Protocol errors, serialization failures

## Code Examples

### SparkApplication for Spark Connect Server
```yaml
# Source: Apache Spark K8s Operator Examples
apiVersion: spark.apache.org/v1
kind: SparkApplication
metadata:
  name: spark-connect-server
  namespace: default
spec:
  mainClass: org.apache.spark.sql.connect.service.SparkConnectServer
  sparkConf:
    # Dynamic allocation for scaling
    spark.dynamicAllocation.enabled: "true"
    spark.dynamicAllocation.shuffleTracking.enabled: "true"
    spark.dynamicAllocation.minExecutors: "1"
    spark.dynamicAllocation.maxExecutors: "5"
    spark.dynamicAllocation.initialExecutors: "2"
    
    # Kubernetes settings
    spark.kubernetes.authenticate.driver.serviceAccountName: "spark"
    spark.kubernetes.container.image: "apache/spark:4.0.2"
    spark.kubernetes.namespace: "default"
    
    # Spark Connect specific
    spark.connect.server.grpc.port: "15002"
    
    # Resource management
    spark.executor.memory: "4g"
    spark.executor.cores: "2"
    spark.driver.cores: "2"
    spark.driver.memory: "2g"
    
    # Logging
    spark.eventLog.enabled: "false"
  runtimeVersions:
    sparkVersion: "4.0.2"
  type: Scala
```

### ServiceAccount and RBAC
```yaml
# Source: Apache Spark K8s Operator
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spark
  namespace: default
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: spark-role
rules:
- apiGroups: [""]
  resources: ["pods", "services", "configmaps", "secrets"]
  verbs: ["create", "delete", "get", "list", "watch"]
- apiGroups: [""]
  resources: ["pods/log"]
  verbs: ["get"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: spark-role-binding
subjects:
- kind: ServiceAccount
  name: spark
  namespace: default
roleRef:
  kind: Role
  name: spark-role
  apiGroup: rbac.authorization.k8s.io
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| spark-submit to K8s | Spark Connect | Spark 3.4+ (2023) | Decoupled client-server, better for apps |
| Manual K8s Deployment | K8s Operator | 2018+ | Declarative config, lifecycle management |
| Static Allocation | Dynamic Allocation | Spark 3.0+ | Auto-scaling executors |
| Standalone Spark Cluster | Native K8s scheduler | Spark 2.3+ (2018) | No separate cluster manager needed |
| Kubeflow Operator | Apache Spark K8s Operator | 2025+ | Official Apache project, tighter integration |

**Deprecated/outdated:**
- Mesos scheduler: Deprecated in Spark 3.x
- Standalone cluster mode: Not recommended for new deployments
- spark-submit over Connect: Spark Connect is preferred for new applications

## Open Questions

1. **High Availability for Spark Connect Server**
   - What we know: Currently no native HA solution; single point of failure
   - What's unclear: How to achieve true HA (multiple replicas with shared state)
   - Recommendation: For atadflow's single-tenant use case, start with single instance; consider Kubernetes Deployment with readiness probe for basic resilience

2. **Authentication/Authorization in Production**
   - What we know: Spark Connect lacks built-in auth; community working on it
   - What's unclear: Timeline for production-ready auth
   - Recommendation: Use network policies to restrict access; consider OAuth2/kerberos if needed

3. **Bitnami PostgreSQL Deprecation**
   - What we know: Bitnami charts moving behind Broadcom subscription
   - What's unclear: Long-term availability of free tier
   - Recommendation: Monitor; consider operator-based PostgreSQL or managed service

## Sources

### Primary (HIGH confidence)
- Apache Spark K8s Operator Official Documentation - https://apache.github.io/spark-kubernetes-operator/
- Apache Spark K8s Operator GitHub - https://github.com/apache/spark-kubernetes-operator
- Spark Connect Server YAML Example - https://github.com/apache/spark-kubernetes-operator/blob/main/examples/spark-connect-server.yaml

### Secondary (MEDIUM confidence)
- Kubeflow Spark Operator Getting Started - https://kubeflow.org/docs/components/spark-operator/getting-started/
- Medium: Deploying Spark Connect on Kubernetes - https://medium.com/@redwanalkurdi/deploying-spark-connect-on-kubernetes-a-comprehensive-guide-19a1185d70ba

### Tertiary (LOW confidence)
- Stackable Spark K8s Documentation - https://docs.stackable.tech/home/nightly/spark-k8s/usage-guide/spark-connect
- Ilum Spark Connect Documentation - https://ilum.cloud/docs/features/spark_connect/

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Official Apache Spark project, well-documented
- Architecture: HIGH - Clear patterns from official examples
- Pitfalls: MEDIUM - Based on community knowledge, some verified

**Research date:** 2026-02-15
**Valid until:** 90 days - Spark operator space is relatively stable; Spark Connect is newer but API is stabilizing
