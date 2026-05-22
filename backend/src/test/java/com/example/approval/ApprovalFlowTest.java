package com.example.approval;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.common.mapper.TypeRef;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@QuarkusTest
class ApprovalFlowTest {

    @Test
    void happyPathThroughAllThreeTasks() {
        String id = submit("alice@example.com", "Need access");

        // confirmation task
        var confirmation = waitForTask(id, "CONFIRMATION", "REQUESTER");
        String token = (String) confirmation.get("context").toString().contains("confirmationToken")
                ? ((Map<?,?>) confirmation.get("context")).get("confirmationToken").toString()
                : null;
        complete(confirmation.get("id").toString(), "alice", "CONFIRMED",
                Map.of("token", token, "termsAccepted", true));
        awaitState(id, "AWAITING_GROUP1_APPROVAL");

        // group 1 task
        var g1 = waitForTask(id, "APPROVAL", "GROUP_1");
        complete(g1.get("id").toString(), "bob-g1", "APPROVED", Map.of());
        awaitState(id, "AWAITING_GROUP2_APPROVAL");

        // group 2 task
        var g2 = waitForTask(id, "APPROVAL", "GROUP_2");
        complete(g2.get("id").toString(), "carol-g2", "APPROVED", Map.of());
        awaitState(id, "APPROVED");

        // verify outcome and that all tasks are now COMPLETED
        given().when().get("/api/requests/" + id)
                .then().statusCode(200)
                .body("outcome", equalTo("APPROVED"))
                .body("tasks.size()", equalTo(3))
                .body("tasks.status", equalTo(List.of("COMPLETED", "COMPLETED", "COMPLETED")));
    }

    @Test
    void cancellingConfirmationTaskRejectsTheRequest() {
        String id = submit("ed@example.com", "Forget it");
        var task = waitForTask(id, "CONFIRMATION", "REQUESTER");

        given().contentType("application/json")
                .body(Map.of("actor", "ed", "reason", "changed my mind"))
                .when().post("/api/tasks/" + task.get("id") + "/cancel")
                .then().statusCode(200);

        awaitState(id, "REJECTED");
    }

    @Test
    void rejectingAtGroup1EndsTheFlow() {
        String id = submit("frank@example.com", "Maybe");
        confirmFirstTask(id);
        var g1 = waitForTask(id, "APPROVAL", "GROUP_1");
        complete(g1.get("id").toString(), "g1-no", "REJECTED", Map.of());
        awaitState(id, "REJECTED");

        // group 2 task must never have been created
        given().queryParam("requestId", id)
                .queryParam("group", "GROUP_2")
                .when().get("/api/tasks")
                .then().statusCode(200)
                .body("size()", equalTo(0));
    }

    @Test
    void rejectingAtGroup2EndsTheFlow() {
        String id = submit("gina@example.com", "Tight");
        confirmFirstTask(id);

        var g1 = waitForTask(id, "APPROVAL", "GROUP_1");
        complete(g1.get("id").toString(), "g1-ok", "APPROVED", Map.of());

        var g2 = waitForTask(id, "APPROVAL", "GROUP_2");
        complete(g2.get("id").toString(), "g2-no", "REJECTED", Map.of());
        awaitState(id, "REJECTED");
    }

    @Test
    void completingWithWrongTokenIsRejected() {
        String id = submit("hank@example.com", "Bad token");
        var task = waitForTask(id, "CONFIRMATION", "REQUESTER");
        given().contentType("application/json")
                .body(Map.of("actor", "hank", "outcome", "CONFIRMED",
                        "payload", Map.of("token", "wrong", "termsAccepted", true)))
                .when().post("/api/tasks/" + task.get("id") + "/complete")
                .then().statusCode(400);
    }

    @Test
    void completingWithoutAcceptingTermsIsRejected() {
        String id = submit("ivy@example.com", "No terms");
        var task = waitForTask(id, "CONFIRMATION", "REQUESTER");
        String token = ((Map<?,?>) task.get("context")).get("confirmationToken").toString();
        given().contentType("application/json")
                .body(Map.of("actor", "ivy", "outcome", "CONFIRMED",
                        "payload", Map.of("token", token, "termsAccepted", false)))
                .when().post("/api/tasks/" + task.get("id") + "/complete")
                .then().statusCode(400);
    }

    @Test
    void invalidOutcomeIsRejected() {
        String id = submit("jane@example.com", "Bad outcome");
        var task = waitForTask(id, "CONFIRMATION", "REQUESTER");
        given().contentType("application/json")
                .body(Map.of("actor", "jane", "outcome", "GIBBERISH", "payload", Map.of()))
                .when().post("/api/tasks/" + task.get("id") + "/complete")
                .then().statusCode(400);
    }

    @Test
    void completingTwiceConflicts() {
        String id = submit("kev@example.com", "Twice");
        var task = waitForTask(id, "CONFIRMATION", "REQUESTER");
        String token = ((Map<?,?>) task.get("context")).get("confirmationToken").toString();
        complete(task.get("id").toString(), "kev", "CONFIRMED",
                Map.of("token", token, "termsAccepted", true));
        given().contentType("application/json")
                .body(Map.of("actor", "kev", "outcome", "CONFIRMED",
                        "payload", Map.of("token", token, "termsAccepted", true)))
                .when().post("/api/tasks/" + task.get("id") + "/complete")
                .then().statusCode(409);
    }

    @Test
    void submitInvalidEmailIsRejected() {
        given().contentType("application/json")
                .body(Map.of("requester", "x", "email", "not-an-email", "subject", "s",
                        "termsAcknowledged", true))
                .when().post("/api/requests")
                .then().statusCode(400);
    }

    // ----- helpers -----

    private String submit(String email, String subject) {
        return given().contentType("application/json")
                .body(Map.of(
                        "requester", "tester",
                        "email", email,
                        "subject", subject,
                        "description", "auto-test",
                        "termsAcknowledged", true))
                .when().post("/api/requests")
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("state", equalTo("AWAITING_CONFIRMATION"))
                .extract().path("id");
    }

    private void confirmFirstTask(String requestId) {
        var task = waitForTask(requestId, "CONFIRMATION", "REQUESTER");
        String token = ((Map<?,?>) task.get("context")).get("confirmationToken").toString();
        complete(task.get("id").toString(), "tester", "CONFIRMED",
                Map.of("token", token, "termsAccepted", true));
    }

    private Map<String, Object> waitForTask(String requestId, String type, String group) {
        final Map<String, Object>[] holder = new Map[1];
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<Map<String, Object>> list = given()
                    .queryParam("requestId", requestId)
                    .queryParam("status", "PENDING")
                    .queryParam("group", group)
                    .when().get("/api/tasks")
                    .then().statusCode(200)
                    .extract().as(new TypeRef<List<Map<String, Object>>>() {});
            Map<String, Object> match = list.stream()
                    .filter(t -> type.equals(t.get("type")))
                    .findFirst().orElse(null);
            if (match == null) {
                throw new AssertionError("No pending " + type + " task for " + group);
            }
            holder[0] = match;
        });
        return holder[0];
    }

    private void complete(String taskId, String actor, String outcome, Map<String, Object> payload) {
        given().contentType("application/json")
                .body(Map.of("actor", actor, "outcome", outcome, "payload", payload))
                .when().post("/api/tasks/" + taskId + "/complete")
                .then().statusCode(200);
    }

    private void awaitState(String id, String state) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                given().when().get("/api/requests/" + id)
                        .then().statusCode(200).body("state", equalTo(state)));
    }
}
