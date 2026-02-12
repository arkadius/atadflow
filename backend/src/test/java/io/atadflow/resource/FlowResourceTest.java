package io.atadflow.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class FlowResourceTest {

    @Test
    void listFlows() {
        given()
                .when().get("/api/flows")
                .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    void createAndGetFlow() {
        String id = given()
                .contentType("application/json")
                .body("""
                        {"name": "Test Flow", "description": "A test flow"}
                        """)
                .when().post("/api/flows")
                .then()
                .statusCode(201)
                .body("name", equalTo("Test Flow"))
                .body("id", notNullValue())
                .extract().path("id");

        given()
                .when().get("/api/flows/" + id)
                .then()
                .statusCode(200)
                .body("name", equalTo("Test Flow"))
                .body("nodes", hasSize(0))
                .body("edges", hasSize(0));
    }

    @Test
    void updateFlowWithNodesAndEdges() {
        String id = given()
                .contentType("application/json")
                .body("""
                        {"name": "Pipeline", "description": "test"}
                        """)
                .when().post("/api/flows")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .body("""
                        {
                          "name": "Pipeline Updated",
                          "description": "updated",
                          "nodes": [
                            {"nodeKey": "n1", "label": "Source", "nodeType": "read-stream", "positionX": 0, "positionY": 0, "config": {"format": "rate"}},
                            {"nodeKey": "n2", "label": "Sink", "nodeType": "write-stream", "positionX": 300, "positionY": 0, "config": {"format": "console", "output-mode": "append"}}
                          ],
                          "edges": [
                            {"sourceNode": "n1", "targetNode": "n2", "sourceHandle": "out", "targetHandle": "in"}
                          ]
                        }
                        """)
                .when().put("/api/flows/" + id)
                .then()
                .statusCode(200)
                .body("name", equalTo("Pipeline Updated"))
                .body("nodes", hasSize(2))
                .body("edges", hasSize(1));
    }

    @Test
    void deleteFlow() {
        String id = given()
                .contentType("application/json")
                .body("""
                        {"name": "To Delete", "description": ""}
                        """)
                .when().post("/api/flows")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .when().delete("/api/flows/" + id)
                .then()
                .statusCode(204);

        given()
                .when().get("/api/flows/" + id)
                .then()
                .statusCode(404);
    }

    @Test
    void getFlowNotFound() {
        given()
                .when().get("/api/flows/00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404);
    }

    @Test
    void generateCode() {
        String id = given()
                .contentType("application/json")
                .body("""
                        {"name": "Code Gen Test", "description": ""}
                        """)
                .when().post("/api/flows")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType("application/json")
                .body("""
                        {
                          "name": "Code Gen Test",
                          "description": "",
                          "nodes": [
                            {"nodeKey": "n1", "label": "Source", "nodeType": "read-stream", "positionX": 0, "positionY": 0, "config": {"format": "rate"}},
                            {"nodeKey": "n2", "label": "Sink", "nodeType": "write-stream", "positionX": 300, "positionY": 0, "config": {"format": "console", "output-mode": "append"}}
                          ],
                          "edges": [
                            {"sourceNode": "n1", "targetNode": "n2", "sourceHandle": "out", "targetHandle": "in"}
                          ]
                        }
                        """)
                .when().put("/api/flows/" + id)
                .then()
                .statusCode(200);

        given()
                .when().get("/api/flows/" + id + "/code")
                .then()
                .statusCode(200)
                .body(containsString("SparkSession"))
                .body(containsString("readStream"))
                .body(containsString("writeStream"))
                .body(containsString("import signal"))
                .body(containsString("import sys"))
                .body(containsString("signal.signal(signal.SIGTERM"))
                .body(containsString("spark.stop()"))
                .body(containsString("def handle_shutdown"));
    }

    @Test
    void listNodeTypes() {
        given()
                .when().get("/api/node-types")
                .then()
                .statusCode(200)
                .body("$", hasSize(6))
                .body("id", hasItems("read-stream", "filter", "select", "group-by", "with-watermark", "write-stream"));
    }
}
