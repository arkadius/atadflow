package io.atadflow.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class JobResourceTest {

    @Test
    void listJobs() {
        given()
                .when().get("/api/jobs")
                .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    void submitAndGetJob() {
        // Create a flow first
        String flowId = given()
                .contentType("application/json")
                .body("""
                        {"name": "Job Test Flow", "description": ""}
                        """)
                .when().post("/api/flows")
                .then()
                .statusCode(201)
                .extract().path("id");

        // Submit a job
        String jobId = given()
                .contentType("application/json")
                .body("{\"flowId\": \"" + flowId + "\"}")
                .when().post("/api/jobs")
                .then()
                .statusCode(201)
                .body("flowId", equalTo(flowId))
                .body("status", equalTo("SUBMITTED"))
                .extract().path("id");

        // Get the job
        given()
                .when().get("/api/jobs/" + jobId)
                .then()
                .statusCode(200)
                .body("id", equalTo(jobId))
                .body("status", equalTo("SUBMITTED"));
    }

    @Test
    void cancelJob() {
        String flowId = given()
                .contentType("application/json")
                .body("""
                        {"name": "Cancel Test Flow", "description": ""}
                        """)
                .when().post("/api/flows")
                .then()
                .statusCode(201)
                .extract().path("id");

        String jobId = given()
                .contentType("application/json")
                .body("{\"flowId\": \"" + flowId + "\"}")
                .when().post("/api/jobs")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .when().post("/api/jobs/" + jobId + "/cancel")
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELLED"));
    }

    @Test
    void listJobsByFlowId() {
        String flowId = given()
                .contentType("application/json")
                .body("""
                        {"name": "Filter Test Flow", "description": ""}
                        """)
                .when().post("/api/flows")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .body("{\"flowId\": \"" + flowId + "\"}")
                .when().post("/api/jobs")
                .then()
                .statusCode(201);

        given()
                .when().get("/api/jobs?flowId=" + flowId)
                .then()
                .statusCode(200)
                .body("$", hasSize(1));
    }

    @Test
    void getJobNotFound() {
        given()
                .when().get("/api/jobs/00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404);
    }
}
