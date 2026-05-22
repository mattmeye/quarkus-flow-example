package com.example.approval;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.time.Duration;
import java.util.Map;

@QuarkusTest
class ApprovalFlowTest {

    @Test
    void happyPathConfirmAndApproveByBothGroups() {
        String id = submitRequest("alice@example.com", "Need access");
        assertState(id, "AWAITING_CONFIRMATION");
        String token = given().when().get("/api/approvals/" + id)
                .then().statusCode(200).extract().path("confirmationToken");

        confirm(id, token, true);
        awaitState(id, "AWAITING_GROUP1_APPROVAL");

        decide(id, 1, "bob-g1", "APPROVED");
        awaitState(id, "AWAITING_GROUP2_APPROVAL");

        decide(id, 2, "carol-g2", "APPROVED");
        awaitState(id, "APPROVED");
    }

    @Test
    void rejectionAtConfirmationStepEndsAsRejected() {
        String id = submitRequest("ed@example.com", "Forget about it");
        assertState(id, "AWAITING_CONFIRMATION");

        given().when().post("/api/approvals/" + id + "/cancel")
                .then().statusCode(200);

        awaitState(id, "REJECTED");
    }

    @Test
    void rejectionByGroup1EndsAsRejected() {
        String id = submitRequest("frank@example.com", "Probably not");
        String token = given().when().get("/api/approvals/" + id)
                .then().extract().path("confirmationToken");

        confirm(id, token, true);
        awaitState(id, "AWAITING_GROUP1_APPROVAL");

        decide(id, 1, "g1-rejecter", "REJECTED");
        awaitState(id, "REJECTED");
    }

    @Test
    void rejectionByGroup2EndsAsRejected() {
        String id = submitRequest("gina@example.com", "Borderline");
        String token = given().when().get("/api/approvals/" + id)
                .then().extract().path("confirmationToken");

        confirm(id, token, true);
        awaitState(id, "AWAITING_GROUP1_APPROVAL");

        decide(id, 1, "g1-ok", "APPROVED");
        awaitState(id, "AWAITING_GROUP2_APPROVAL");

        decide(id, 2, "g2-no", "REJECTED");
        awaitState(id, "REJECTED");
    }

    @Test
    void confirmingWithWrongTokenIsRejected() {
        String id = submitRequest("hank@example.com", "Bad token");
        given().contentType("application/json")
                .body(Map.of("token", "not-the-right-token", "termsAccepted", true))
                .when().post("/api/approvals/" + id + "/confirm")
                .then().statusCode(404);
    }

    @Test
    void confirmingWithoutAcceptingTermsIsRejected() {
        String id = submitRequest("ivy@example.com", "No terms");
        String token = given().when().get("/api/approvals/" + id)
                .then().extract().path("confirmationToken");
        given().contentType("application/json")
                .body(Map.of("token", token, "termsAccepted", false))
                .when().post("/api/approvals/" + id + "/confirm")
                .then().statusCode(400);
    }

    @Test
    void submitMissingFieldsIsRejected() {
        given().contentType("application/json")
                .body(Map.of("requester", "x", "email", "x@y.z", "subject", "s",
                        "termsAcknowledged", false))
                .when().post("/api/approvals")
                .then().statusCode(400);
    }

    @Test
    void submitInvalidEmailIsRejected() {
        given().contentType("application/json")
                .body(Map.of("requester", "x", "email", "not-an-email", "subject", "s",
                        "termsAcknowledged", true))
                .when().post("/api/approvals")
                .then().statusCode(400);
    }

    // ----- helpers -----

    private String submitRequest(String email, String subject) {
        return given().contentType("application/json")
                .body(Map.of(
                        "requester", "tester",
                        "email", email,
                        "subject", subject,
                        "description", "auto-test",
                        "termsAcknowledged", true))
                .when().post("/api/approvals")
                .then().statusCode(201)
                .body("request.id", notNullValue())
                .extract().path("request.id");
    }

    private void confirm(String id, String token, boolean terms) {
        given().contentType("application/json")
                .body(Map.of("token", token, "termsAccepted", terms))
                .when().post("/api/approvals/" + id + "/confirm")
                .then().statusCode(200);
    }

    private void decide(String id, int group, String approver, String decision) {
        given().contentType("application/json")
                .body(Map.of("approver", approver, "decision", decision))
                .when().post("/api/approvals/" + id + "/group" + group + "/decision")
                .then().statusCode(200);
    }

    private void assertState(String id, String state) {
        given().when().get("/api/approvals/" + id)
                .then().statusCode(200).body("state", equalTo(state));
    }

    private void awaitState(String id, String state) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                given().when().get("/api/approvals/" + id)
                        .then().statusCode(200).body("state", equalTo(state)));
    }
}
