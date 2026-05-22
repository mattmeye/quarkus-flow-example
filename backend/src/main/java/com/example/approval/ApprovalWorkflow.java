package com.example.approval;

import io.quarkiverse.flow.Flow;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.serverlessworkflow.api.types.FlowDirectiveEnum;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.fluent.func.FuncWorkflowBuilder;
import io.serverlessworkflow.impl.WorkflowError;
import io.serverlessworkflow.impl.WorkflowException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.function;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.tryCatch;

/**
 * Three-stage approval workflow defined with the Quarkus Flow Java DSL
 * (CNCF Serverless Workflow specification, DSL 1.0.0).
 *
 * Every async wait step creates a {@link HumanTask} via {@link TaskService}
 * and blocks on its future, so the public REST API is uniform across
 * stages: clients only ever complete or cancel tasks.
 *
 *   AwaitingConfirmation (CONFIRMATION task -> REQUESTER)
 *     -> AwaitingGroup1Approval (APPROVAL task -> GROUP_1)
 *       -> AwaitingGroup2Approval (APPROVAL task -> GROUP_2)
 *         -> Approved / Rejected
 */
@ApplicationScoped
public class ApprovalWorkflow extends Flow {

    private static final Logger log = LoggerFactory.getLogger(ApprovalWorkflow.class);
    private static final SecureRandom RNG = new SecureRandom();

    private static final String ERR_CONFIRMATION_DECLINED = "ERR_CONFIRMATION_DECLINED";
    private static final String ERR_REJECTED_GROUP1 = "ERR_REJECTED_GROUP1";
    private static final String ERR_REJECTED_GROUP2 = "ERR_REJECTED_GROUP2";

    @Inject ApprovalService approvals;
    @Inject TaskService tasks;

    @Override
    public Workflow descriptor() {
        return FuncWorkflowBuilder.workflow("approval", "approval-demo")
                .tasks(

                        tryCatch("confirmationStage",
                                t -> t.tryCatch(function("awaitConfirmation",
                                        (WorkflowInput in) -> doConfirmation(in.requestId())))
                                        .catchError(err -> err.type(ERR_CONFIRMATION_DECLINED),
                                                function("finalizeDeclined",
                                                        (WorkflowInput in) -> finalizeRejected(in.requestId(),
                                                                "Requester did not confirm"))
                                                        .then(FlowDirectiveEnum.END))),

                        function("onSubmitted", (StageInput in) -> {
                            approvals.transitionTo(in.requestId(), ApprovalState.SUBMITTED,
                                    "Confirmation received - entering approval chain");
                            return in;
                        }),

                        tryCatch("group1Stage",
                                t -> t.tryCatch(function("awaitGroup1",
                                        (StageInput in) -> doApproval(in.requestId(),
                                                HumanTask.AssigneeGroup.GROUP_1,
                                                "Approval by group 1",
                                                ApprovalState.AWAITING_GROUP1_APPROVAL,
                                                ERR_REJECTED_GROUP1)))
                                        .catchError(err -> err.type(ERR_REJECTED_GROUP1),
                                                function("finalizeRejectedG1",
                                                        (StageInput in) -> finalizeRejected(in.requestId(),
                                                                "Rejected by approval group 1"))
                                                        .then(FlowDirectiveEnum.END))),

                        tryCatch("group2Stage",
                                t -> t.tryCatch(function("awaitGroup2",
                                        (StageInput in) -> doApproval(in.requestId(),
                                                HumanTask.AssigneeGroup.GROUP_2,
                                                "Approval by group 2",
                                                ApprovalState.AWAITING_GROUP2_APPROVAL,
                                                ERR_REJECTED_GROUP2)))
                                        .catchError(err -> err.type(ERR_REJECTED_GROUP2),
                                                function("finalizeRejectedG2",
                                                        (StageInput in) -> finalizeRejected(in.requestId(),
                                                                "Rejected by approval group 2"))
                                                        .then(FlowDirectiveEnum.END))),

                        function("finalApproval", (StageInput in) -> {
                            approvals.transitionTo(in.requestId(), ApprovalState.APPROVED,
                                    "Approved by both approval groups");
                            approvals.recordOutcome(in.requestId(), "APPROVED");
                            return new WorkflowOutput(in.requestId(), "APPROVED");
                        }))
                .build();
    }

    private StageInput doConfirmation(String requestId) {
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
        if (!"CONFIRMED".equals(result.outcome())) {
            throw new WorkflowException(WorkflowError.error(ERR_CONFIRMATION_DECLINED, 410).build());
        }
        approvals.appendHistory(requestId, "CONFIRMED",
                "Email confirmed and terms accepted by requester");
        return new StageInput(requestId);
    }

    private StageInput doApproval(String requestId, HumanTask.AssigneeGroup group,
                                  String name, ApprovalState waitingState, String errType) {
        approvals.transitionTo(requestId, waitingState, "Waiting for " + group + " decision");
        HumanTask task = tasks.create(requestId,
                HumanTask.Type.APPROVAL, name, group, Map.of());

        TaskResult result = tasks.await(task);
        log.info("Workflow {} {} result: {}", requestId, group, result);
        approvals.appendHistory(requestId, group + "_" + result.outcome(),
                group + " (" + result.actor() + ") decided: " + result.outcome());
        if ("REJECTED".equals(result.outcome())) {
            throw new WorkflowException(WorkflowError.error(errType, 409).build());
        }
        return new StageInput(requestId);
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
    public record StageInput(String requestId) {}

    @RegisterForReflection
    public record WorkflowOutput(String requestId, String outcome) {}
}
