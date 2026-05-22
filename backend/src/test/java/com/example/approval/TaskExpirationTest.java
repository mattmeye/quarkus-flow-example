package com.example.approval;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.common.mapper.TypeRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/**
 * Drives a request whose CONFIRMATION task is left untouched and verifies
 * that the scheduled sweep first fires a reminder and then expires the
 * task, sending the workflow into REJECTED.
 */
@QuarkusTest
@TestProfile(TaskExpirationTest.ShortDeadlinesProfile.class)
class TaskExpirationTest {

    public static class ShortDeadlinesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "app.task.confirmation.timeout", "PT1S",
                    "app.task.approval.timeout", "PT1S",
                    "app.task.reminder.offset-fraction", "0.5",
                    "app.task.sweep.every", "100ms"
            );
        }
    }

    @Test
    void unattendedConfirmationTaskIsRemindedThenExpired() {
        String id = given().contentType("application/json")
                .body(Map.of(
                        "requester", "ghost",
                        "email", "ghost@example.com",
                        "subject", "I will never confirm",
                        "description", "ignored on purpose",
                        "termsAcknowledged", true))
                .when().post("/api/requests")
                .then().statusCode(201)
                .extract().path("id");

        Map<String, Object> task = pendingConfirmationFor(id);
        String taskId = task.get("id").toString();

        // 1) reminder fires within the first half of the deadline.
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                given().when().get("/api/tasks/" + taskId)
                        .then().statusCode(200)
                        .body("reminded", equalTo(true))
                        .body("status", equalTo("PENDING")));

        // 2) task expires once the deadline passes.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                given().when().get("/api/tasks/" + taskId)
                        .then().statusCode(200)
                        .body("status", equalTo("EXPIRED"))
                        .body("outcome", equalTo("EXPIRED"))
                        .body("actor", equalTo("SYSTEM")));

        // 3) workflow ends up REJECTED.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                given().when().get("/api/requests/" + id)
                        .then().statusCode(200)
                        .body("state", equalTo("REJECTED")));
    }

    private Map<String, Object> pendingConfirmationFor(String requestId) {
        Map<String, Object>[] holder = new Map[1];
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Map<String, Object>> list = given()
                    .queryParam("requestId", requestId)
                    .queryParam("status", "PENDING")
                    .queryParam("group", "REQUESTER")
                    .when().get("/api/tasks")
                    .then().statusCode(200)
                    .extract().as(new TypeRef<List<Map<String, Object>>>() {});
            Map<String, Object> match = list.stream()
                    .filter(t -> "CONFIRMATION".equals(t.get("type")))
                    .findFirst().orElse(null);
            if (match == null) throw new AssertionError("No pending CONFIRMATION task yet");
            holder[0] = match;
        });
        return holder[0];
    }
}
