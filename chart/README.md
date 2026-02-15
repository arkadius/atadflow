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

Run the included test:

```bash
helm test atadflow
```

## Upgrading

See [Bitnami PostgreSQL upgrade notes](https://github.com/bitnami/charts/tree/main/bitnami/postgresql#upgrading) for database upgrade considerations.
