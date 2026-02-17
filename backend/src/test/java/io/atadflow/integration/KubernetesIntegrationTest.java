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
 * 3. Images loaded: k3d image import atadflow/atadflow:1.2.0 apache/spark:4.0.2
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

    private static final String SPARK_OPERATOR_VERSION = "2.4.0";
    private static final String SPARK_VERSION = "4.0.2";
    private static final String SPARK_IMAGE = "apache/spark:" + SPARK_VERSION;

    private static KubernetesClient client;
    private static Process portForwardProcess;
    private static String baseUrl;

    private static int run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .inheritIO()
                .start();
        return p.waitFor();
    }

    private static String runCapture(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        return exitCode == 0 ? output : "";
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

        // 2. Install Kubeflow Spark Operator v2.4.0
        System.out.println("Installing Kubeflow Spark Operator " + SPARK_OPERATOR_VERSION + "...");
        run("helm", "repo", "remove", "spark-operator");
        run("helm", "repo", "add", "spark-operator", "https://kubeflow.github.io/spark-operator");
        run("helm", "repo", "update");
        run("helm", "install", "spark-operator", "spark-operator/spark-operator",
                "--namespace", NAMESPACE,
                "--version", SPARK_OPERATOR_VERSION,
                "--set", "spark.jobNamespaces[0]=" + NAMESPACE,
                "--create-namespace",
                "--wait", "--timeout", "5m");

        // 3. Apply RBAC for Spark
        applySparkRbac();

        // 4. Deploy SparkConnect server via operator CRD
        deploySparkConnect();

        // 5. Wait for Spark Connect server pod to be ready
        System.out.println("Waiting for Spark Connect server pod...");
        Config config = new ConfigBuilder().withNamespace(NAMESPACE).build();
        client = new KubernetesClientBuilder().withConfig(config).build();

        boolean sparkReady = false;
        for (int i = 0; i < 60; i++) { // 5 minutes, polling every 5s
            String endpoints = runCapture("kubectl", "get", "endpoints", "-n", NAMESPACE,
                    "spark-connect-server-server", "-o", "jsonpath={.subsets[*].addresses[*].ip}");
            if (!endpoints.isBlank()) {
                sparkReady = true;
                break;
            }
            if (i % 6 == 0) { // every 30s, dump diagnostic info
                System.out.println("  Spark Connect not ready (attempt " + i + "/60). Checking pods...");
                String pods = runCapture("kubectl", "get", "pods", "-n", NAMESPACE, "-o", "wide");
                System.out.println(pods);
                String sparkConnect = runCapture("kubectl", "get", "sparkconnect", "-n", NAMESPACE, "-o", "yaml");
                System.out.println(sparkConnect);
            }
            Thread.sleep(5_000);
        }
        Assertions.assertTrue(sparkReady,
                "Spark Connect server never became ready. Check spark-operator logs and SparkConnect resource status.");
        System.out.println("Spark Connect server is ready.");

        // 6. Install atadflow Helm chart
        System.out.println("Installing atadflow Helm chart...");
        run("helm", "dependency", "build", CHART_PATH);
        run("helm", "install", RELEASE_NAME, CHART_PATH,
                "--namespace", NAMESPACE,
                "--dependency-update",
                "--set", "postgresql.auth.password=testpass",
                "--set", "image.pullPolicy=Never",
                "--set", "spark.connectUrl=sc://spark-connect-server-server:15002",
                "--set", "securityContext.readOnlyRootFilesystem=false",
                "--wait", "--timeout", "5m");

        // 7. Wait for atadflow deployment readiness
        System.out.println("Waiting for atadflow deployment readiness...");
        client.apps().deployments()
                .inNamespace(NAMESPACE)
                .withName(RELEASE_NAME)
                .waitUntilCondition(
                        d -> d.getStatus() != null
                                && d.getStatus().getReadyReplicas() != null
                                && d.getStatus().getReadyReplicas() > 0,
                        5, TimeUnit.MINUTES);

        // 8. Set up port-forward via kubectl
        System.out.println("Setting up port-forward...");
        portForwardProcess = new ProcessBuilder(
                "kubectl", "port-forward", "-n", NAMESPACE, "svc/" + RELEASE_NAME, "0:80")
                .redirectErrorStream(true)
                .start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(portForwardProcess.getInputStream()));
        String line = reader.readLine();
        System.out.println("Port-forward output: " + line);
        int localPort = Integer.parseInt(line.replaceAll(".*:(\\d+) ->.*", "$1"));
        baseUrl = "http://localhost:" + localPort;

        // 9. Wait for app to respond through port-forward
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
        // Note: Both executor and server templates with containers are required to work around
        // a nil pointer panic in Spark Operator v2.4.0 imageOption (options.go:86/87).
        String sparkConnect = """
                apiVersion: sparkoperator.k8s.io/v1alpha1
                kind: SparkConnect
                metadata:
                  name: spark-connect-server
                  namespace: %s
                spec:
                  image: %s
                  sparkVersion: "%s"
                  server:
                    memory: "1g"
                    template:
                      spec:
                        serviceAccountName: spark
                        containers:
                          - name: spark-connect
                            image: %s
                  executor:
                    instances: 1
                    memory: "1g"
                    cores: 1
                    template:
                      spec:
                        serviceAccountName: spark
                        containers:
                          - name: spark-kubernetes-executor
                            image: %s
                """.formatted(NAMESPACE, SPARK_IMAGE, SPARK_VERSION, SPARK_IMAGE, SPARK_IMAGE);

        Path file = Files.createTempFile("spark-connect", ".yaml");
        Files.writeString(file, sparkConnect);
        run("kubectl", "apply", "-f", file.toString());
        Files.deleteIfExists(file);
    }

    @AfterAll
    static void tearDown() {
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
    void testFullStackFlowSubmission() throws InterruptedException {
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

        // Verify job stays RUNNING for 30 seconds by polling every 5s.
        // If Spark Connect is unavailable, the Python process will fail and the job
        // transitions to FAILED. Sustained RUNNING with no error proves real execution.
        try {
            for (int i = 0; i < 6; i++) {
                TimeUnit.SECONDS.sleep(5);

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
                    Assertions.fail("Job failed after " + ((i + 1) * 5) + "s — Spark execution not working. Error: " + error);
                }

                assertThat(status)
                        .as("Job should be RUNNING at check " + (i + 1) + "/6 (after " + ((i + 1) * 5) + "s)")
                        .isEqualTo("RUNNING");
            }

            // Final check: verify no error message after 30s of sustained RUNNING
            String errorMessage = given()
                    .baseUri(baseUrl)
                    .when().get("/api/jobs/" + jobId)
                    .then().statusCode(200)
                    .extract().path("errorMessage");
            assertThat(errorMessage)
                    .as("Job running for 30s should have no error message — proving real Spark execution")
                    .isNull();

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
                        String s = given()
                                .baseUri(baseUrl)
                                .when().get("/api/jobs/" + jobId)
                                .then().statusCode(200)
                                .extract().path("status");
                        return "CANCELLED".equals(s) || "FAILED".equals(s);
                    });
        }
    }
}
