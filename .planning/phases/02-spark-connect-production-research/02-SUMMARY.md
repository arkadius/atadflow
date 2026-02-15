---
phase: 02-spark-connect-production-research
plan: "01"
subsystem: infra
tags: [spark, kubernetes, operator, production, research]

# Dependency graph
requires:
  - phase: v1.2-helm-chart
    provides: Helm chart structure, Kubernetes deployment patterns
provides:
  - Spark Connect production deployment recommendation
  - Apache Spark K8s Operator integration pattern
  - Configuration templates for SparkApplication + RBAC
affects: [Phase 3 - Kubernetes Health Probes, Phase 4 - K8s Integration]

# Tech tracking
tech-stack:
  added: [Apache Spark K8s Operator 0.7.0+]
  patterns: [Operator-based deployment, SparkApplication CRD, Helm chart dependency]
  research: COMPLETED

key-files:
  created:
    - .planning/phases/02-spark-connect-production-research/02-RESEARCH.md - Full research document
    - .planning/phases/02-spark-connect-production-research/02-SUMMARY.md - This summary

key-decisions:
  - "Apache Spark K8s Operator v0.7.0+ recommended (official Apache project, native Spark Connect support)"
  - "Pattern 3: Helm chart dependency (operator added as subchart to existing atadflow chart)"
  - "Dynamic allocation enabled: minExecutors=1, maxExecutors=5, initialExecutors=2"
  - "RBAC required: ServiceAccount + Role with pod/service/configmap/secrets permissions"

patterns-established:
  - "Pattern: SparkApplication CRD for Spark Connect server deployment"
  - "Pattern: Helm dependency for operator integration"
  - "Pattern: ServiceAccount + RoleBinding for Spark driver permissions"

# Metrics
duration: research-complete
completed: 2026-02-15
---

# Phase 2: Spark Connect Production Research Summary

## Overview

**Research completed:** 2026-02-15  
**Recommendation:** Apache Spark K8s Operator v0.7.0+ with Helm chart dependency

## Decision: Apache Spark K8s Operator

For atadflow's use case (single-tenant, internal tool, already using Spark Connect), the **Apache Spark K8s Operator** is recommended:

| Criteria | Apache Spark K8s Operator | Kubeflow Spark Operator | Manual Deployment |
|----------|---------------------------|--------------------------|-------------------|
| Spark Connect Support | Native | Via workarounds | Manual |
| Official Project | Yes (Apache) | Kubeflow community | N/A |
| Active Development | v0.7.0 (Jan 2026) | Stable | N/A |
| Complexity | Medium | High | Low |

## Recommended Approach: Pattern 3 (Helm Chart Dependency)

Add Apache Spark K8s Operator as a Helm dependency to the existing atadflow chart:

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

## Key Artifacts from Research

### 1. SparkApplication for Spark Connect Server

```yaml
apiVersion: spark.apache.org/v1
kind: SparkApplication
metadata:
  name: spark-connect-server
spec:
  mainClass: org.apache.spark.sql.connect.service.SparkConnectServer
  sparkConf:
    spark.dynamicAllocation.enabled: "true"
    spark.dynamicAllocation.shuffleTracking.enabled: "true"
    spark.dynamicAllocation.minExecutors: "1"
    spark.dynamicAllocation.maxExecutors: "5"
    spark.dynamicAllocation.initialExecutors: "2"
    spark.kubernetes.authenticate.driver.serviceAccountName: "spark"
    spark.kubernetes.container.image: "apache/spark:4.0.2"
    spark.connect.server.grpc.port: "15002"
  runtimeVersions:
    sparkVersion: "4.0.2"
  type: Scala
```

### 2. RBAC Configuration

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spark
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

## Open Questions for Future Phases

1. **High Availability** - Single Spark Connect server is single point of failure
2. **Authentication** - Spark Connect lacks built-in auth; network policies recommended
3. **Bitnami PostgreSQL** - Watch for Broadcom subscription changes

## Dependencies for Downstream Phases

- **Phase 3:** Uses this research to add Spark Connect deployment to Helm chart
- **Phase 4:** Uses this research for K8s integration testing

---

*Phase: 02-spark-connect-production-research*
*Completed: 2026-02-15*
