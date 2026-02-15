# Atadflow Helm Chart

A Helm chart for deploying Atadflow - Visual Streaming Flow Designer for PySpark on Kubernetes.

## Prerequisites

- Kubernetes 1.26+
- Helm 3.x or 4.x
- PostgreSQL client (for testing)

## Quick Start

```bash
# Install the chart with a release name
helm install atadflow ./chart --set postgresql.auth.password=your-password

# Upgrade
helm upgrade atadflow ./chart --set postgresql.auth.password=your-password

# Uninstall
helm uninstall atadflow
```

## Configuration

The following table lists the key configurable parameters of the Atadflow chart:

| Parameter | Description | Default |
|-----------|-------------|---------|
| `replicaCount` | Number of replicas | `1` |
| `image.repository` | Docker image repository | `atadflow/atadflow` |
| `image.tag` | Docker image tag | `1.2.0` |
| `service.type` | Service type | `ClusterIP` |
| `service.port` | Service port | `80` |
| `service.targetPort` | Container target port | `8080` |
| `postgresql.enabled` | Enable PostgreSQL subchart | `true` |
| `postgresql.auth.username` | PostgreSQL username | `atadflow` |
| `postgresql.auth.database` | PostgreSQL database name | `atadflow` |
| `spark.connectUrl` | Spark Connect URL | `sc://spark-connect:15002` |
| `resources.limits.memory` | Memory limit | `2Gi` |
| `resources.limits.cpu` | CPU limit | `1000m` |

## PostgreSQL

The chart includes Bitnami PostgreSQL as a dependency. To set the PostgreSQL password:

```bash
# Via --set
helm install atadflow ./chart --set postgresql.auth.password=my-secret-password

# Via values file
echo "postgresql:
  auth:
    password: my-secret-password" > values-prod.yaml
helm install atadflow ./chart -f values-prod.yaml
```

## Spark Connect

The application requires a Spark Connect endpoint. Configure via:

```bash
helm install atadflow ./chart \
  --set postgresql.auth.password=xxx \
  --set spark.connectUrl=sc://spark-connect:15002
```

For external Spark Connect, update the `spark.connectUrl` value accordingly.

## Health Checks

The application exposes SmallRye Health endpoints:

- Liveness: `/q/health/live`
- Readiness: `/q/health/ready`

These are configured automatically as Kubernetes liveness and readiness probes.

## Testing

### Helm Test (in-cluster)

Run the included in-cluster smoke tests:

```bash
helm test atadflow
```

This runs curl-based checks against health and API endpoints from inside the cluster.

### K8s Integration Test (automated)

A JUnit integration test deploys the full stack (Helm chart + Spark K8s Operator + Spark Connect) on a k3d cluster and verifies the complete application lifecycle including Spark job execution.

```bash
# Prerequisites
k3d cluster create atadflow-test
docker build -t atadflow/atadflow:1.2.0 .
k3d image import atadflow/atadflow:1.2.0 -c atadflow-test

# Run the test
cd backend
K8S_INTEGRATION_TEST=true ./gradlew test --tests KubernetesIntegrationTest

# Cleanup
helm uninstall atadflow -n atadflow-test
kubectl delete namespace atadflow-test
k3d cluster delete atadflow-test
```

Expected runtime: 3-5 minutes.

## Local Development with Telepresence

[Telepresence](https://www.getambassador.io/docs/telepresence) lets you run a local Quarkus dev instance that connects to cluster services (PostgreSQL, Spark Connect), enabling fast code iteration without rebuilding the Docker image.

### Prerequisites

- A running K8s cluster with the Atadflow Helm chart installed
- [Telepresence 2.x](https://www.getambassador.io/docs/telepresence/latest/install) installed locally
- Java 21+ and Gradle for local Quarkus dev mode

### Workflow

```bash
# 1. Connect Telepresence to the cluster
telepresence connect

# 2. Intercept the atadflow service (routes cluster traffic to localhost:8080)
telepresence intercept atadflow --port 8080:http

# 3. Start Quarkus in dev mode (connects to cluster PostgreSQL and Spark via DNS)
cd backend
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://atadflow-postgresql:5432/atadflow \
QUARKUS_DATASOURCE_USERNAME=atadflow \
QUARKUS_DATASOURCE_PASSWORD=<your-password> \
SPARK_CONNECT_URL=sc://spark-connect:15002 \
./gradlew quarkusDev

# 4. Edit code — changes take effect immediately via Quarkus live reload

# 5. End the intercept when done
telepresence leave atadflow
telepresence quit
```

## Upgrading

See [Bitnami PostgreSQL upgrade notes](https://github.com/bitnami/charts/tree/main/bitnami/postgresql#upgrading) for database upgrade considerations.
