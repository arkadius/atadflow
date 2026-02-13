package io.atadflow.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for Docker Compose stack.
 *
 * To run this test:
 * 1. Start the stack: docker compose up -d
 * 2. Run with: INTEGRATION_TEST=true ./gradlew test --tests DockerComposeIntegrationTest
 * 3. Stop the stack: docker compose down
 */
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TEST", matches = "true")
public class DockerComposeIntegrationTest {

    private static final String BASE_URL = "http://localhost:8080";

    @Test
    void testFrontendServed() {
        given()
            .baseUri(BASE_URL)
            .when().get("/")
            .then()
            .statusCode(200)
            .contentType("text/html")
            .body(containsString("<div id=\"root\">"));
    }

    @Test
    void testHealthEndpoints() {
        given()
            .baseUri(BASE_URL)
            .when().get("/q/health/live")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"));

        given()
            .baseUri(BASE_URL)
            .when().get("/q/health/ready")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"));

        given()
            .baseUri(BASE_URL)
            .when().get("/q/health")
            .then()
            .statusCode(200)
            .body("checks.find { it.name == 'python' }.status", equalTo("UP"));
    }

    @Test
    void testApiEndpoints() {
        // Test node types endpoint
        given()
            .baseUri(BASE_URL)
            .when().get("/api/node-types")
            .then()
            .statusCode(200)
            .body("$", hasSize(6))
            .body("[0].id", notNullValue());

        // Test flows endpoint
        given()
            .baseUri(BASE_URL)
            .when().get("/api/flows")
            .then()
            .statusCode(200);

        // Test jobs endpoint
        given()
            .baseUri(BASE_URL)
            .when().get("/api/jobs")
            .then()
            .statusCode(200);
    }

    @Test
    void testFullStackFlowSubmission() {
        // Create flow with read-stream → write-stream pipeline
        String flowJson = """
            {
              "name": "Docker Integration Test Flow",
              "description": "End-to-end test",
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
            .baseUri(BASE_URL)
            .contentType("application/json")
            .body(flowJson)
            .when().post("/api/flows")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("name", equalTo("Docker Integration Test Flow"))
            .extract().path("id");

        // Submit job
        String jobId = given()
            .baseUri(BASE_URL)
            .contentType("application/json")
            .body(String.format("{\"flowId\": \"%s\"}", flowId))
            .when().post("/api/jobs")
            .then()
            .statusCode(201)
            .body("status", equalTo("RUNNING"))
            .body("flowId", equalTo(flowId))
            .extract().path("id");

        // Verify job exists and doesn't immediately fail
        given()
            .baseUri(BASE_URL)
            .when().get("/api/jobs/" + jobId)
            .then()
            .statusCode(200)
            .body("id", equalTo(jobId))
            .body("flowId", equalTo(flowId));

        // Wait a few seconds to ensure job doesn't immediately fail
        // (a healthy job should stay in RUNNING state)
        try {
            await()
                .atMost(Duration.ofSeconds(10))
                .pollDelay(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    String status = given()
                        .baseUri(BASE_URL)
                        .when().get("/api/jobs/" + jobId)
                        .then().statusCode(200)
                        .extract().path("status");

                    // Job should still be running (not failed immediately)
                    if ("FAILED".equals(status)) {
                        String errorMessage = given()
                            .baseUri(BASE_URL)
                            .when().get("/api/jobs/" + jobId)
                            .then().extract().path("errorMessage");
                        throw new AssertionError("Job failed with error: " + errorMessage);
                    }
                });
        } finally {
            // Clean up: Cancel the job (rate source runs forever)
            given()
                .baseUri(BASE_URL)
                .contentType("application/json")
                .when().post("/api/jobs/" + jobId + "/cancel")
                .then()
                .statusCode(200);

            // Wait for cancellation to complete
            await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    String status = given()
                        .baseUri(BASE_URL)
                        .when().get("/api/jobs/" + jobId)
                        .then().statusCode(200)
                        .extract().path("status");
                    return "CANCELLED".equals(status) || "FAILED".equals(status);
                });
        }
    }
}
