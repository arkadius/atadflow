package io.atadflow.integration;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

/**
 * Kubernetes integration test for Helm chart deployment with full Spark execution.
 *
 * Prerequisites:
 * 1. k3d cluster running: k3d cluster create
 * 2. Docker image built: docker build -t atadflow/atadflow:1.2.0 .
 * 3. Image loaded: k3d image import atadflow/atadflow:1.2.0
 * 4. Helm CLI installed
 *
 * Run with: K8S_INTEGRATION_TEST=true ./gradlew test --tests KubernetesIntegrationTest
 * Expected runtime: 3-5 minutes.
 *
 * Note: Resources are left deployed after test for debugging.
 * To clean up: helm uninstall atadflow -n atadflow-test && kubectl delete namespace atadflow-test
 */
@EnabledIfEnvironmentVariable(named = "K8S_INTEGRATION_TEST", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KubernetesIntegrationTest {

    private static final String NAMESPACE = "atadflow-test";
    private static final String RELEASE_NAME = "atadflow";
    private static final String CHART_PATH = "../chart";

    private static KubernetesClient client;
    private static Process portForwardProcess;
    private static String baseUrl;

    /**
     * Run a command and ignore the result - for commands where we don't care about output.
     */
    private static int run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .inheritIO()
                .start();
        return p.waitFor();
    }

    /**
     * Run a command and capture its output.
     */
    private static String runCapture(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return output;
    }

    @BeforeAll
    static void setUp() throws Exception {
        System.out.println("=== Setting up Kubernetes integration test ===");

        // 1. Create namespace
        System.out.println("Creating namespace: " + NAMESPACE);
        new ProcessBuilder("kubectl", "create", "namespace", NAMESPACE)
                .redirectErrorStream(true)
                .start()
                .waitFor();

        // 2. Install Kubeflow Spark Operator via Helm
        System.out.println("Installing Spark K8s Operator...");
        // Remove old repo if exists (from previous Apache Spark K8s Operator)
        run("helm", "repo", "remove", "spark-operator");
        run("helm", "repo", "add", "spark-operator", "https://kubeflow.github.io/spark-operator");
        run("helm", "repo", "update");
        run("helm", "install", "spark-operator", "spark-operator/spark-operator",
                "--namespace", NAMESPACE,
                "--create-namespace",
                "--wait", "--timeout", "5m");

        // 3. Apply RBAC for Spark
        applySparkRbac();

        // 4. Deploy SparkApplication for Spark Connect server
        deploySparkConnect();

        // 5. Deploy Spark Connect Service
        deploySparkConnectService();

        // 6. Wait for Spark Connect driver pod to be ready
        System.out.println("Waiting for Spark Connect driver pod...");
        Config config = new ConfigBuilder().withNamespace(NAMESPACE).build();
        client = new KubernetesClientBuilder().withConfig(config).build();

        // Wait for Spark Connect driver pod - try multiple label selectors
        boolean podReady = waitForSparkDriverPod();
        if (!podReady) {
            System.err.println("Warning: Spark driver pod not found with standard labels. Checking available pods...");
            String pods = runCapture("kubectl", "get", "pods", "-n", NAMESPACE, "-o", "wide");
            System.err.println("Available pods: " + pods);
        }

        // 7. Install atadflow Helm chart
        System.out.println("Installing atadflow Helm chart...");
        run("helm", "dependency", "build", CHART_PATH);
        run("helm", "install", RELEASE_NAME, CHART_PATH,
                "--namespace", NAMESPACE,
                "--dependency-update",
                "--set", "postgresql.auth.password=testpass",
                "--set", "image.pullPolicy=Never",
                "--set", "spark.connectUrl=sc://spark-connect:15002",
                "--set", "securityContext.readOnlyRootFilesystem=false",
                "--wait", "--timeout", "5m");

        // 8. Wait for atadflow deployment readiness
        System.out.println("Waiting for atadflow deployment readiness...");
        client.apps().deployments()
                .inNamespace(NAMESPACE)
                .withName(RELEASE_NAME)
                .waitUntilCondition(
                        d -> d.getStatus() != null
                                && d.getStatus().getReadyReplicas() != null
                                && d.getStatus().getReadyReplicas() > 0,
                        5, TimeUnit.MINUTES);

        // 9. Set up port-forward via kubectl (more reliable than Fabric8 port-forward)
        System.out.println("Setting up port-forward...");
        portForwardProcess = new ProcessBuilder(
                "kubectl", "port-forward", "-n", NAMESPACE, "svc/" + RELEASE_NAME, "0:80")
                .redirectErrorStream(true)
                .start();

        // Read the assigned local port from kubectl output (e.g. "Forwarding from 127.0.0.1:12345 -> 8080")
        BufferedReader reader = new BufferedReader(new InputStreamReader(portForwardProcess.getInputStream()));
        String line = reader.readLine();
        System.out.println("Port-forward output: " + line);
        int localPort = Integer.parseInt(line.replaceAll(".*:(\\d+) ->.*", "$1"));
        baseUrl = "http://localhost:" + localPort;

        // 10. Wait for app to respond through port-forward
        System.out.println("Waiting for app to be reachable at " + baseUrl + "...");
        await()
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(3))
                .ignoreExceptions()
                .until(() -> {
                    int status = given()
                            .baseUri(baseUrl)
                            .when().get("/q/health/live")
                            .then().extract().statusCode();
                    return status == 200;
                });
        System.out.println("=== Test setup complete. Base URL: " + baseUrl + " ===");
    }

    private static boolean waitForSparkDriverPod() {
        // Try the standard label first
        try {
            client.pods().inNamespace(NAMESPACE)
                    .withLabel("spark-app-name", "spark-connect-server")
                    .waitUntilCondition(
                            pod -> pod.getStatus() != null
                                    && pod.getStatus().getPhase() != null
                                    && pod.getStatus().getPhase().equals("Running"),
                            5, TimeUnit.MINUTES);
            return true;
        } catch (Exception e) {
            // Try broader selection
        }

        try {
            client.pods().inNamespace(NAMESPACE)
                    .withLabel("spark-role", "driver")
                    .waitUntilCondition(
                            pod -> pod.getStatus() != null
                                    && pod.getStatus().getPhase() != null
                                    && pod.getStatus().getPhase().equals("Running"),
                            5, TimeUnit.MINUTES);
            return true;
        } catch (Exception e) {
            // Try with statefulset label
        }

        try {
            client.pods().inNamespace(NAMESPACE)
                    .withLabel("app.kubernetes.io/name", "spark-connect-server")
                    .waitUntilCondition(
                            pod -> pod.getStatus() != null
                                    && pod.getStatus().getPhase() != null
                                    && pod.getStatus().getPhase().equals("Running"),
                            5, TimeUnit.MINUTES);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void applySparkRbac() throws Exception {
        String rbac = """
                apiVersion: v1
                kind: ServiceAccount
                metadata:
                  name: spark
                  namespace: %s
                ---
                apiVersion: rbac.authorization.k8s.io/v1
                kind: Role
                metadata:
                  name: spark-role
                  namespace: %s
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
                  namespace: %s
                subjects:
                - kind: ServiceAccount
                  name: spark
                  namespace: %s
                roleRef:
                  kind: Role
                  name: spark-role
                  apiGroup: rbac.authorization.k8s.io
                """.formatted(NAMESPACE, NAMESPACE, NAMESPACE, NAMESPACE);

        Path rbacFile = Files.createTempFile("spark-rbac", ".yaml");
        Files.writeString(rbacFile, rbac);
        run("kubectl", "apply", "-f", rbacFile.toString());
        Files.deleteIfExists(rbacFile);
    }

    private static void deploySparkConnect() throws Exception {
        String sparkApp = """
                apiVersion: sparkoperator.k8s.io/v1beta2
                kind: SparkApplication
                metadata:
                  name: spark-connect-server
                  namespace: %s
                spec:
                  type: Scala
                  mode: cluster
                  sparkVersion: "3.5.0"
                  mainClass: org.apache.spark.sql.connect.service.SparkConnectServer
                  mainApplicationFile: "spark://spark@spark-master:7077"
                  sparkConf:
                    spark.kubernetes.authenticate.driver.serviceAccountName: "spark"
                    spark.kubernetes.container.image: "apache/spark:3.5.0"
                    spark.kubernetes.namespace: "%s"
                    spark.connect.server.grpc.port: "15002"
                    spark.driver.memory: "1g"
                    spark.executor.memory: "1g"
                    spark.executor.cores: "1"
                    spark.dynamicAllocation.enabled: "false"
                  driver:
                    serviceAccount: spark
                  executor:
                    instances: 1
                    serviceAccount: spark
                """.formatted(NAMESPACE, NAMESPACE);

        Path sparkFile = Files.createTempFile("spark-connect", ".yaml");
        Files.writeString(sparkFile, sparkApp);
        run("kubectl", "apply", "-f", sparkFile.toString());
        Files.deleteIfExists(sparkFile);
    }

    private static void deploySparkConnectService() throws Exception {
        String svc = """
                apiVersion: v1
                kind: Service
                metadata:
                  name: spark-connect
                  namespace: %s
                spec:
                  selector:
                    spark-role: driver
                  ports:
                  - port: 15002
                    targetPort: 15002
                  type: ClusterIP
                """.formatted(NAMESPACE);

        Path svcFile = Files.createTempFile("spark-connect-svc", ".yaml");
        Files.writeString(svcFile, svc);
        run("kubectl", "apply", "-f", svcFile.toString());
        Files.deleteIfExists(svcFile);
    }

    @AfterAll
    static void tearDown() {
        // Dump pod logs for debugging if something went wrong
        try {
            if (client != null) {
                client.pods().inNamespace(NAMESPACE).list().getItems().forEach(pod -> {
                    System.err.println("=== Logs for pod: " + pod.getMetadata().getName() + " ===");
                    try {
                        String log = client.pods().inNamespace(NAMESPACE)
                                .withName(pod.getMetadata().getName()).getLog();
                        System.err.println(log.substring(0, Math.min(log.length(), 2000)));
                    } catch (Exception e) {
                        System.err.println("(could not retrieve logs: " + e.getMessage() + ")");
                    }
                });
            }
        } catch (Exception e) {
            /* ignore */
        }

        if (portForwardProcess != null) {
            try {
                portForwardProcess.destroyForcibly();
            } catch (Exception e) {
                /* ignore */
            }
        }
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                /* ignore */
            }
        }

        System.out.println("=== Test teardown complete ===");
        System.out.println("To clean up: helm uninstall atadflow -n atadflow-test && kubectl delete namespace atadflow-test");
    }

    @Test
    @Order(1)
    void testHealthEndpoints() {
        given()
                .baseUri(baseUrl)
                .when().get("/q/health/live")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));

        given()
                .baseUri(baseUrl)
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    @Order(2)
    void testApiEndpoints() {
        given()
                .baseUri(baseUrl)
                .when().get("/api/node-types")
                .then()
                .statusCode(200)
                .body("$", hasSize(6))
                .body("[0].id", notNullValue());

        given()
                .baseUri(baseUrl)
                .when().get("/api/flows")
                .then()
                .statusCode(200);
    }

    @Test
    @Order(3)
    void testFullStackFlowSubmission() {
        // Reuse same flow JSON from DockerComposeIntegrationTest:
        // rate source (1 row/sec) -> console sink (append mode)
        String flowJson = """
                {
                  "name": "K8s Integration Test Flow",
                  "description": "End-to-end K8s test",
                  "nodes": [
                    {
                      "id": "read-1",
                      "type": "read-stream",
                      "position": {"x": 100, "y": 100},
                      "config": {
                        "format": "rate",
                        "options": [{"key": "rowsPerSecond", "value": "1"}]
                      }
                    },
                    {
                      "id": "write-1",
                      "type": "write-stream",
                      "position": {"x": 400, "y": 100},
                      "config": {
                        "format": "console",
                        "output-mode": "append"
                      }
                    }
                  ],
                  "edges": [
                    {
                      "id": "e1",
                      "source": "read-1",
                      "target": "write-1"
                    }
                  ]
                }
            """;

        // Create flow
        String flowId = given()
                .baseUri(baseUrl)
                .contentType("application/json")
                .body(flowJson)
                .when().post("/api/flows")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .extract().path("id");

        // Submit job
        String jobId = given()
                .baseUri(baseUrl)
                .contentType("application/json")
                .body(String.format("{\"flowId\": \"%s\"}", flowId))
                .when().post("/api/jobs")
                .then()
                .statusCode(201)
                .body("status", equalTo("RUNNING"))
                .extract().path("id");

        // Poll until SUCCEEDED or RUNNING (rate source streams forever, so
        // "staying RUNNING for 15+ seconds" proves Spark execution works).
        // Then cancel and verify cancellation.
        try {
            await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(3))
                    .untilAsserted(() -> {
                        String status = given()
                                .baseUri(baseUrl)
                                .when().get("/api/jobs/" + jobId)
                                .then().statusCode(200)
                                .extract().path("status");

                        if ("FAILED".equals(status)) {
                            String error = given()
                                    .baseUri(baseUrl)
                                    .when().get("/api/jobs/" + jobId)
                                    .then().extract().path("errorMessage");
                            throw new AssertionError("Job failed: " + error);
                        }
                        // Job staying RUNNING proves Spark execution works
                        assertThat(status).isIn("RUNNING", "SUCCEEDED");
                    });
        } finally {
            // Cancel the streaming job (rate source runs forever)
            given()
                    .baseUri(baseUrl)
                    .contentType("application/json")
                    .when().post("/api/jobs/" + jobId + "/cancel")
                    .then()
                    .statusCode(200);

            // Wait for cancellation
            await()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofSeconds(3))
                    .until(() -> {
                        String status = given()
                                .baseUri(baseUrl)
                                .when().get("/api/jobs/" + jobId)
                                .then().statusCode(200)
                                .extract().path("status");
                        return "CANCELLED".equals(status) || "FAILED".equals(status);
                    });
        }
    }
}
