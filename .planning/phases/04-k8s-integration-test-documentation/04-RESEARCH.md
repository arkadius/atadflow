# Phase 4: K8s Integration Test & Documentation - Research

**Researched:** 2026-02-15
**Domain:** Kubernetes integration testing, Helm testing, local development workflow
**Confidence:** HIGH

## Summary

Phase 4 requires creating a Kubernetes integration test that validates the Helm chart deployment works end-to-end, plus documentation for Telepresence-based local development. The standard approach combines **Testcontainers K3s module** for spinning up a real Kubernetes cluster in tests, **Fabric8 Kubernetes Java client** for cluster interaction, and Helm's built-in test framework for chart validation.

The existing `DockerComposeIntegrationTest` provides a proven pattern: use REST-Assured to test API endpoints after waiting for deployment health. The K8s test will follow the same pattern but orchestrate via Helm install instead of docker-compose up.

**Primary recommendation:** Use Testcontainers K3s module (testcontainers-k3s:2.0.2) with Quarkus test-kubernetes-client for integration testing. For Telepresence documentation, focus on intercept workflow for rapid iteration without container rebuilds.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Testcontainers K3s | 2.0.2 | Spin up K3s cluster in Docker for tests | Official Testcontainers module, real K8s in CI/local |
| Fabric8 Kubernetes Client | 7.5.2+ | Interact with K8s cluster from Java | Official Quarkus K8s client, mature mock server |
| Quarkus Test Kubernetes Client | latest | Mock K8s server for unit tests | Integrates with @QuarkusTest, CRUD mode |
| REST-Assured | (existing) | Test HTTP endpoints | Already used in DockerComposeIntegrationTest |
| Awaitility | (existing) | Wait for async conditions | Already used for job status polling |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Telepresence | 2.26+ | Local dev against remote K8s | Development workflow, not test code |
| k3d | 5.x | Local K8s cluster (manual dev) | Developer setup, not automated tests |
| Helm CLI | 3.x | Install charts, run helm test | Both manual testing and programmatic |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Testcontainers K3s | Kind container (kindcontainer) | Kind more common but K3s is lighter (400MB vs 800MB+) |
| Fabric8 client | Official K8s Java client | Fabric8 has better Quarkus integration and mock server |
| Testcontainers | @QuarkusIntegrationTest with real k3d | Test would require pre-existing cluster, not isolated |

**Installation:**
```gradle
testImplementation("org.testcontainers:testcontainers-k3s:2.0.2")
testImplementation("io.quarkus:quarkus-test-kubernetes-client")
testImplementation("io.fabric8:kubernetes-client:7.5.2")
```

## Architecture Patterns

### Recommended Test Structure
```
backend/src/test/java/io/atadflow/integration/
├── DockerComposeIntegrationTest.java       # Existing
├── KubernetesIntegrationTest.java          # New - Helm chart deployment test
└── README.md                                # Test documentation
```

### Pattern 1: Testcontainers K3s with Helm Install
**What:** Start K3s container, install Helm chart programmatically, test via port-forward or LoadBalancer
**When to use:** Integration tests validating Helm chart works on real K8s
**Example:**
```java
// Source: https://java.testcontainers.org/modules/k3s/
@Testcontainers
public class KubernetesIntegrationTest {

    @Container
    static K3sContainer k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.30.0-k3s1"))
        .withHelm3(helm -> {
            // Add Bitnami repo for PostgreSQL dependency
            helm.repo.add.run("bitnami", "https://charts.bitnami.com/bitnami");
            helm.repo.update.run();

            // Install chart with dependency update
            helm.dependency.update.run("../chart");
            helm.install
                .withName("atadflow-test")
                .withSet("postgresql.auth.password", "testpass")
                .run("../chart");
        });

    @Test
    void testChartDeployment() {
        String kubeConfig = k3s.getKubeConfigYaml();
        Config config = Config.fromKubeconfig(kubeConfig);
        try (KubernetesClient client = new KubernetesClientBuilder().withConfig(config).build()) {
            // Wait for deployment ready
            client.apps().deployments()
                .inNamespace("default")
                .withName("atadflow-test")
                .waitUntilCondition(d -> d.getStatus().getReadyReplicas() != null
                    && d.getStatus().getReadyReplicas() > 0,
                    3, TimeUnit.MINUTES);

            // Port-forward and test with REST-Assured
            // ... (see Pattern 2)
        }
    }
}
```

### Pattern 2: Port-Forward for REST-Assured Testing
**What:** Forward K8s service port to localhost, use existing REST-Assured tests
**When to use:** Testing HTTP endpoints in K8s without LoadBalancer or Ingress
**Example:**
```java
// Source: https://kubernetes.io/docs/tasks/access-application-cluster/port-forward-access-application-cluster/
LocalPortForward portForward = client.services()
    .inNamespace("default")
    .withName("atadflow-test")
    .portForward(80);

int localPort = portForward.getLocalPort();
String baseUrl = "http://localhost:" + localPort;

// Reuse existing REST-Assured patterns
given()
    .baseUri(baseUrl)
    .when().get("/q/health/live")
    .then()
    .statusCode(200)
    .body("status", equalTo("UP"));
```

### Pattern 3: Helm Test Hooks
**What:** Add test job to chart/templates/tests/ with `helm.sh/hook: test` annotation
**When to use:** Chart-level smoke tests that run with `helm test <release>`
**Example:**
```yaml
# Source: https://helm.sh/docs/topics/chart_tests/
# chart/templates/tests/test-api.yaml
apiVersion: v1
kind: Pod
metadata:
  name: "{{ include "atadflow.fullname" . }}-test-api"
  annotations:
    "helm.sh/hook": test
spec:
  restartPolicy: Never
  containers:
    - name: test
      image: curlimages/curl:8.5.0
      command:
        - sh
        - -c
        - |
          curl -f http://{{ include "atadflow.fullname" . }}:{{ .Values.service.port }}/api/node-types
          curl -f http://{{ include "atadflow.fullname" . }}:{{ .Values.service.port }}/api/flows
```

### Pattern 4: Telepresence Intercept Workflow
**What:** Replace remote K8s pod with local process, access cluster resources
**When to use:** Local development iteration without docker build/push cycles
**Example:**
```bash
# Source: https://telepresence.io/docs/concepts/faster/
# 1. Connect to cluster
telepresence connect

# 2. Intercept service
telepresence intercept atadflow --port 8080:http

# 3. Run local Quarkus dev mode (connects to cluster PostgreSQL/Spark)
cd backend
./gradlew quarkusDev

# 4. Test changes immediately - traffic routes to localhost:8080
# 5. End intercept
telepresence leave atadflow
```

### Anti-Patterns to Avoid
- **Pre-existing cluster requirement:** Tests should not assume k3d/minikube already running - use Testcontainers for isolation
- **Hardcoded timeouts:** Use `waitUntilCondition()` with reasonable max time, not `Thread.sleep(30000)`
- **Skipping cleanup:** Always close KubernetesClient and LocalPortForward in try-with-resources or @AfterEach
- **Testing Helm templates with regex:** Use `helm template` + YAML parsing, not string matching on raw output
- **Ignoring pod logs on failure:** Capture and print pod logs when test fails for debugging

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Kubernetes cluster for tests | Shell script to start k3d/kind | Testcontainers K3s module | Automatic lifecycle, parallel test isolation, CI-friendly |
| Kubeconfig management | Write YAML files to ~/.kube | K3sContainer.getKubeConfigYaml() | Temporary, test-scoped, no global state pollution |
| Helm install orchestration | ProcessBuilder to run helm CLI | K3sContainer.withHelm3() fluent API | Type-safe, integrated with container lifecycle |
| Service reachability | kubectl port-forward subprocess | Fabric8 client.services().portForward() | Managed lifecycle, auto-cleanup on close |
| Deployment readiness | Polling with kubectl get | client.waitUntilCondition() | Built-in timeout, condition DSL, cleaner code |

**Key insight:** Testcontainers handles container lifecycle (start, stop, cleanup) and provides K8s/Helm integration via fluent API. Hand-rolling with shell scripts loses test isolation, parallel execution, and automatic cleanup.

## Common Pitfalls

### Pitfall 1: K3s Requires Privileged Mode
**What goes wrong:** Test fails with "K3s container requires privileged mode" or "cannot create containerd namespace"
**Why it happens:** K3s runs Kubernetes in Docker, spawning its own containers - requires privileged container
**How to avoid:** Testcontainers K3s module handles this automatically, but won't work in rootless Docker or environments blocking privileged containers (some CI systems)
**Warning signs:** `K3sContainer` fails immediately on start, Docker logs show containerd errors

### Pitfall 2: BTRFS Filesystem Incompatibility
**What goes wrong:** K3s container starts but pods fail to schedule with storage errors
**Why it happens:** K3s has known issues with BTRFS filesystems at `/var/lib/docker`
**How to avoid:** Use ext4/xfs for Docker data directory, or test on different filesystem
**Warning signs:** K3s starts but `kubectl get pods` shows "FailedMount" or storage-related errors

### Pitfall 3: Helm Dependency Update Not Run
**What goes wrong:** Helm install fails with "found in Chart.yaml, but missing in charts/ directory"
**Why it happens:** PostgreSQL subchart not downloaded before install
**How to avoid:** Run `helm.dependency.update.run("../chart")` before `helm.install`
**Warning signs:** Error message mentioning "postgresql" chart not found

### Pitfall 4: Port-Forward Not Closed
**What goes wrong:** Test hangs on shutdown, or subsequent tests fail with "address already in use"
**Why it happens:** LocalPortForward binds local port, must be closed to release
**How to avoid:** Use try-with-resources: `try (LocalPortForward pf = client.services()...portForward(80)) { ... }`
**Warning signs:** Tests pass individually but fail when run together, ports remain bound after test

### Pitfall 5: Waiting for Job Pods with kubectl wait
**What goes wrong:** `kubectl wait --for=condition=Ready pod` hangs forever on completed job pods
**Why it happens:** Job pods transition Pending → ContainerCreating → Completed, never reaching Ready
**How to avoid:** For deployments use `waitUntilCondition(d -> d.getStatus().getReadyReplicas() > 0)`, not pod-level Ready check
**Warning signs:** Wait times out even though deployment succeeded

### Pitfall 6: Telepresence VPN Conflicts
**What goes wrong:** Telepresence connect fails or routes traffic incorrectly when VPN is active
**Why it happens:** Telepresence creates network routes that conflict with corporate VPN
**How to avoid:** Document VPN compatibility in README, provide workarounds (disconnect VPN during intercept)
**Warning signs:** `telepresence connect` succeeds but services unreachable, DNS resolution fails

### Pitfall 7: Image Pull from Private Registry
**What goes wrong:** K8s deployment stuck in ImagePullBackOff for atadflow/atadflow:1.2.0
**Why it happens:** K3s container can't pull from Docker Hub if image only exists locally
**How to avoid:** Load image into K3s: `k3s.copyFileToContainer(MountableFile.forHostPath("path/to/image.tar"), "/tmp/image.tar")` + `k3s.execInContainer("ctr", "images", "import", "/tmp/image.tar")`
**Warning signs:** Pod events show "Failed to pull image" even though image exists in local Docker

## Code Examples

Verified patterns from official sources:

### Full K3s Integration Test Structure
```java
// Source: https://java.testcontainers.org/modules/k3s/
// Source: https://quarkus.io/guides/kubernetes-client
package io.atadflow.integration;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.k3s.K3sContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "K8S_INTEGRATION_TEST", matches = "true")
public class KubernetesIntegrationTest {

    @Container
    static K3sContainer k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.30.0-k3s1"))
        .withHelm3(helm -> {
            helm.repo.add.run("bitnami", "https://charts.bitnami.com/bitnami");
            helm.repo.update.run();
            helm.dependency.update.run("./chart");
            helm.install
                .withName("atadflow-test")
                .withSet("postgresql.auth.password", "testpass")
                .run("./chart");
        });

    @Test
    void testHelmChartDeployment() throws Exception {
        String kubeConfig = k3s.getKubeConfigYaml();
        Config config = Config.fromKubeconfig(kubeConfig);

        try (KubernetesClient client = new KubernetesClientBuilder().withConfig(config).build()) {
            // Wait for deployment ready
            client.apps().deployments()
                .inNamespace("default")
                .withName("atadflow-test")
                .waitUntilCondition(
                    d -> d.getStatus().getReadyReplicas() != null
                        && d.getStatus().getReadyReplicas() > 0,
                    3, TimeUnit.MINUTES
                );

            // Port-forward service
            Service svc = client.services()
                .inNamespace("default")
                .withName("atadflow-test")
                .get();

            try (LocalPortForward pf = client.services()
                    .inNamespace("default")
                    .withName("atadflow-test")
                    .portForward(80)) {

                int localPort = pf.getLocalPort();
                String baseUrl = "http://localhost:" + localPort;

                // Test health endpoints (reuse DockerComposeIntegrationTest patterns)
                given()
                    .baseUri(baseUrl)
                    .when().get("/q/health/live")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("UP"));

                given()
                    .baseUri(baseUrl)
                    .when().get("/api/node-types")
                    .then()
                    .statusCode(200);
            }
        }
    }
}
```

### Helm Test Job for API Validation
```yaml
# Source: https://helm.sh/docs/topics/chart_tests/
# chart/templates/tests/test-api.yaml
apiVersion: v1
kind: Pod
metadata:
  name: "{{ include "atadflow.fullname" . }}-test-api"
  labels:
    {{- include "atadflow.labels" . | nindent 4 }}
  annotations:
    "helm.sh/hook": test
spec:
  activeDeadlineSeconds: 120
  restartPolicy: Never
  containers:
    - name: curl
      image: curlimages/curl:8.5.0
      command:
        - sh
        - -c
        - |
          echo "Testing health endpoints..."
          curl -f http://{{ include "atadflow.fullname" . }}:{{ .Values.service.port }}/q/health/live || exit 1
          curl -f http://{{ include "atadflow.fullname" . }}:{{ .Values.service.port }}/q/health/ready || exit 1

          echo "Testing API endpoints..."
          curl -f http://{{ include "atadflow.fullname" . }}:{{ .Values.service.port }}/api/node-types || exit 1

          # Verify node types count
          TYPES=$(curl -s http://{{ include "atadflow.fullname" . }}:{{ .Values.service.port }}/api/node-types | grep -o '"id"' | wc -l)
          if [ "$TYPES" -ne "6" ]; then
            echo "Expected 6 node types, got $TYPES"
            exit 1
          fi

          echo "All tests passed!"
      resources:
        requests:
          memory: "32Mi"
          cpu: "50m"
        limits:
          memory: "64Mi"
          cpu: "200m"
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Shell scripts with kubectl | Testcontainers K3s module | 2023 | Type-safe, isolated, parallel tests |
| Minikube/Kind for tests | K3s container (lighter) | 2021 | Faster startup (10s vs 60s), less memory |
| Mock K8s API responses | Real K8s in Docker | 2020 | Tests actual deployment, not mocks |
| Manual `helm install` in CI | Programmatic via container | 2022 | No external kubectl/helm CLI dependency |
| helm template + kubeval | helm test hooks + integration | 2024 | Runtime validation, not just YAML lint |

**Deprecated/outdated:**
- **KubernetesServer mock for integration tests:** Still useful for unit tests (fast), but integration tests should use real K8s
- **@QuarkusIntegrationTest with existing cluster:** Requires pre-setup, not isolated - use Testcontainers instead
- **helm-unittest for chart testing:** Now prefer combination of template tests + helm test hooks for comprehensive coverage

## Open Questions

1. **Image availability in K3s container**
   - What we know: atadflow/atadflow:1.2.0 image might not be pullable from Docker Hub inside K3s container
   - What's unclear: Best way to make local image available - load via `ctr images import` or push to in-cluster registry?
   - Recommendation: Document both approaches in test setup, prefer simpler `ctr import` for CI

2. **Spark Connect dependency in tests**
   - What we know: Chart expects Spark Connect at `sc://spark-connect:15002` (external)
   - What's unclear: Should test deploy Spark too, or stub the connection?
   - Recommendation: Override `spark.connectUrl` to empty/stub in test values - deployment validation doesn't require real Spark execution

3. **Test execution time**
   - What we know: K3s startup ~10-20s, Helm install with PostgreSQL ~30-60s, image pull varies
   - What's unclear: Acceptable CI time budget for this test
   - Recommendation: Gate with `K8S_INTEGRATION_TEST=true` env var (like DockerComposeIntegrationTest), document expected runtime (2-3 minutes)

## Sources

### Primary (HIGH confidence)
- [Testcontainers K3s Module Documentation](https://java.testcontainers.org/modules/k3s/) - K3sContainer API, Helm integration, kubeconfig retrieval
- [Quarkus Kubernetes Client Guide](https://quarkus.io/guides/kubernetes-client) - KubernetesClient injection, testing with @WithKubernetesTestServer
- [Helm Chart Tests Documentation](https://helm.sh/docs/topics/chart_tests/) - Test hook annotations, helm test command, best practices
- [Fabric8 Kubernetes Client 7.5 Release](https://blog.marcnuri.com/fabric8-kubernetes-client-7-5) - Latest features, Kubernetes 1.34 support, OpenShift 4.20
- [Telepresence Official Docs](https://telepresence.io/docs/concepts/faster/) - Intercept workflow, installation, use cases

### Secondary (MEDIUM confidence)
- [How to Use kubectl port-forward for Testing Service Connectivity](https://oneuptime.com/blog/post/2026-02-09-kubectl-port-forward-testing/view) - Port-forward testing patterns (verified with official K8s docs)
- [Testing Helm Charts with Chart Testing (ct) and helm test](https://oneuptime.com/blog/post/2026-01-17-helm-chart-testing-ct-helm-test/view) - Comprehensive testing strategy (aligns with official Helm docs)
- [How to Use K3s with Helm](https://oneuptime.com/blog/post/2026-02-02-k3s-helm-charts/view) - K3s + Helm integration patterns
- [k3d for Local Kubernetes Development](https://devtoolbox.dedyn.io/blog/kubernetes-complete-guide) - k3d usage, registry setup (verified with k3d.io docs)

### Tertiary (LOW confidence)
- [Testing Quarkus Web Applications: Component & Integration Tests](https://www.infoq.com/articles/testing-quarkus-integration/) - General patterns, not K8s-specific
- [Advanced Testing with Quarkus](https://piotrminkowski.com/2023/02/08/advanced-testing-with-quarkus/) - 2023 content, may have outdated library versions

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Testcontainers K3s is official module, Fabric8 is Quarkus-recommended, Helm is standard
- Architecture: HIGH - Patterns verified from official docs (Testcontainers, Helm, Fabric8), proven in community
- Pitfalls: MEDIUM-HIGH - Privileged mode and BTRFS issues documented in official sources, others from community experience

**Research date:** 2026-02-15
**Valid until:** 2026-03-15 (30 days - stable ecosystem, versions locked)
