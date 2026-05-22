package com.example.approval;

import io.quarkiverse.flow.Flow;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.fluent.func.FuncWorkflowBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.function;

/**
 * Three-stage approval workflow defined with the Quarkus Flow Java DSL
 * (CNCF Serverless Workflow specification).
 *
 * Every async wait step creates a {@link HumanTask} via {@link TaskService}
 * and blocks on its future, so the public REST API is uniform across
 * stages: clients only ever complete or cancel tasks.
 *
 *   AwaitingConfirmation (CONFIRMATION task -> REQUESTER)
 *     -> AwaitingGroup1Approval (APPROVAL task -> GROUP_1)
 *       -> AwaitingGroup2Approval (APPROVAL task -> GROUP_2)
 *         -> Approved / Rejected
 *
 * The workflow is modelled as a single CNCF Serverless Workflow function
 * task that drives all stages. Branching on outcome happens inline in
 * Java - explicit and safe, with no exception-based control flow.
 */
@ApplicationScoped
public class ApprovalWorkflow extends Flow {

    private static final Logger log = LoggerFactory.getLogger(ApprovalWorkflow.class);
    private static final SecureRandom RNG = new SecureRandom();

    @Inject ApprovalService approvals;
    @Inject TaskService tasks;

    @Override
    public Workflow descriptor() {
        return FuncWorkflowBuilder.workflow("approval", "approval-demo")
                .tasks(
                        function("runApproval",
                                (WorkflowInput in) -> runApproval(in.requestId())))
                .build();
    }

    private WorkflowOutput runApproval(String requestId) {
        // Stage 1: email + terms confirmation
        TaskResult confirmation = doConfirmation(requestId);
        if (!"CONFIRMED".equals(confirmation.outcome())) {
            return finalizeRejected(requestId, "Requester did not confirm");
        }
        approvals.transitionTo(requestId, ApprovalState.SUBMITTED,
                "Confirmation received - entering approval chain");

        // Stage 2: approval by group 1
        TaskResult g1 = doApproval(requestId, HumanTask.AssigneeGroup.GROUP_1,
                "Approval by group 1", ApprovalState.AWAITING_GROUP1_APPROVAL);
        if ("REJECTED".equals(g1.outcome())) {
            return finalizeRejected(requestId, "Rejected by approval group 1");
        }

        // Stage 3: approval by group 2
        TaskResult g2 = doApproval(requestId, HumanTask.AssigneeGroup.GROUP_2,
                "Approval by group 2", ApprovalState.AWAITING_GROUP2_APPROVAL);
        if ("REJECTED".equals(g2.outcome())) {
            return finalizeRejected(requestId, "Rejected by approval group 2");
        }

        approvals.transitionTo(requestId, ApprovalState.APPROVED,
                "Approved by both approval groups");
        approvals.recordOutcome(requestId, "APPROVED");
        return new WorkflowOutput(requestId, "APPROVED");
    }

    private TaskResult doConfirmation(String requestId) {
        approvals.transitionTo(requestId, ApprovalState.AWAITING_CONFIRMATION,
                "Waiting for requester to confirm email and accept terms");
        String token = newToken();
        HumanTask task = tasks.create(requestId,
                HumanTask.Type.CONFIRMATION,
                "Confirm email & accept terms",
                HumanTask.AssigneeGroup.REQUESTER,
                Map.of("confirmationToken", token));

        TaskResult result = tasks.await(task);
        log.info("Workflow {} confirmation result: {}", requestId, result);
        if ("CONFIRMED".equals(result.outcome())) {
            approvals.appendHistory(requestId, "CONFIRMED",
                    "Email confirmed and terms accepted by requester");
        }
        return result;
    }

    private TaskResult doApproval(String requestId, HumanTask.AssigneeGroup group,
                                  String name, ApprovalState waitingState) {
        approvals.transitionTo(requestId, waitingState, "Waiting for " + group + " decision");
        HumanTask task = tasks.create(requestId,
                HumanTask.Type.APPROVAL, name, group, Map.of());

        TaskResult result = tasks.await(task);
        log.info("Workflow {} {} result: {}", requestId, group, result);
        approvals.appendHistory(requestId, group + "_" + result.outcome(),
                group + " (" + result.actor() + ") decided: " + result.outcome());
        return result;
    }

    private WorkflowOutput finalizeRejected(String requestId, String reason) {
        approvals.transitionTo(requestId, ApprovalState.REJECTED, reason);
        approvals.recordOutcome(requestId, "REJECTED");
        return new WorkflowOutput(requestId, "REJECTED");
    }

    private static String newToken() {
        byte[] buf = new byte[24];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    @RegisterForReflection
    public record WorkflowInput(String requestId) {}

    @RegisterForReflection
    public record WorkflowOutput(String requestId, String outcome) {}
}
