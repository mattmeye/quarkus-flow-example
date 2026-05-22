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

import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.function;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.tryCatch;

/**
 * Three-stage approval workflow defined with the Quarkus Flow Java DSL
 * (CNCF Serverless Workflow specification, DSL 1.0.0).
 *
 * Topology:
 *   AwaitingConfirmation -> [requester confirms email + terms] ->
 *     - DECLINED -> Rejected (end)
 *     - CONFIRMED -> Submitted -> AwaitingGroup1Approval -> [Group1 decision] ->
 *         - REJECTED -> Rejected (end)
 *         - APPROVED -> AwaitingGroup2Approval -> [Group2 decision] ->
 *             - REJECTED -> Rejected (end)
 *             - APPROVED -> Approved (end)
 *
 * All three "awaiting" tasks block on a CompletableFuture that is completed
 * by the corresponding REST endpoint when a human action occurs.
 */
@ApplicationScoped
public class ApprovalWorkflow extends Flow {

    private static final Logger log = LoggerFactory.getLogger(ApprovalWorkflow.class);

    private static final String ERR_CONFIRMATION_DECLINED = "ERR_CONFIRMATION_DECLINED";
    private static final String ERR_REJECTED_GROUP1 = "ERR_REJECTED_GROUP1";
    private static final String ERR_REJECTED_GROUP2 = "ERR_REJECTED_GROUP2";

    @Inject
    ApprovalService service;

    @Override
    public Workflow descriptor() {
        return FuncWorkflowBuilder.workflow("approval", "approval-demo")
                .tasks(

                        // -------- Stage 0: async email/terms confirmation by the requester
                        tryCatch(
                                "confirmationStage",
                                t -> t.tryCatch(function("awaitConfirmation",
                                        (WorkflowInput in) -> awaitConfirmation(in.requestId())))
                                        .catchError(err -> err.type(ERR_CONFIRMATION_DECLINED),
                                                function("recordConfirmationDeclined",
                                                        (WorkflowInput in) -> {
                                                            service.transitionTo(in.requestId(),
                                                                    ApprovalState.REJECTED,
                                                                    "Requester did not confirm email / terms");
                                                            return new WorkflowOutput(in.requestId(), "REJECTED");
                                                        })
                                                        .then(FlowDirectiveEnum.END))),

                        // -------- Stage 1: enter the approval chain
                        function("onSubmitted", (Group1Input in) -> {
                            service.transitionTo(in.requestId(), ApprovalState.SUBMITTED,
                                    "Email confirmed and terms accepted - entering approval chain");
                            return in;
                        }),

                        // -------- Stage 2: wait for approval group 1
                        tryCatch(
                                "group1Stage",
                                t -> t.tryCatch(function("awaitGroup1Decision",
                                        (Group1Input in) -> awaitGroup1(in.requestId())))
                                        .catchError(err -> err.type(ERR_REJECTED_GROUP1),
                                                function("recordGroup1Rejection",
                                                        (Group1Input in) -> {
                                                            service.transitionTo(in.requestId(),
                                                                    ApprovalState.REJECTED,
                                                                    "Rejected by approval group 1");
                                                            return new WorkflowOutput(in.requestId(), "REJECTED");
                                                        })
                                                        .then(FlowDirectiveEnum.END))),

                        // -------- Stage 3: wait for approval group 2
                        tryCatch(
                                "group2Stage",
                                t -> t.tryCatch(function("awaitGroup2Decision",
                                        (Group2Input in) -> awaitGroup2(in.requestId())))
                                        .catchError(err -> err.type(ERR_REJECTED_GROUP2),
                                                function("recordGroup2Rejection",
                                                        (Group2Input in) -> {
                                                            service.transitionTo(in.requestId(),
                                                                    ApprovalState.REJECTED,
                                                                    "Rejected by approval group 2");
                                                            return new WorkflowOutput(in.requestId(), "REJECTED");
                                                        })
                                                        .then(FlowDirectiveEnum.END))),

                        // -------- Stage 4: final approval
                        function("finalApproval", (Group2Input in) -> {
                            service.transitionTo(in.requestId(), ApprovalState.APPROVED,
                                    "Approved by both approval groups");
                            return new WorkflowOutput(in.requestId(), "APPROVED");
                        }))
                .build();
    }

    private Group1Input awaitConfirmation(String requestId) {
        // The request is already in AWAITING_CONFIRMATION (set on creation),
        // so we just block here until the requester confirms via REST.
        ApprovalService.ConfirmationResult res = service.awaitConfirmation(requestId);
        log.info("Workflow {} confirmation result: {}", requestId, res);
        if (!res.confirmed()) {
            throw new WorkflowException(WorkflowError.error(ERR_CONFIRMATION_DECLINED, 410).build());
        }
        return new Group1Input(requestId);
    }

    private Group2Input awaitGroup1(String requestId) {
        service.transitionTo(requestId, ApprovalState.AWAITING_GROUP1_APPROVAL,
                "Waiting for approval group 1 decision");
        Decision d = service.awaitDecision(requestId, ApprovalGroup.GROUP_1);
        log.info("Workflow {} received group1 decision: {}", requestId, d);
        if (d == Decision.REJECTED) {
            throw new WorkflowException(WorkflowError.error(ERR_REJECTED_GROUP1, 409).build());
        }
        return new Group2Input(requestId);
    }

    private Group2Input awaitGroup2(String requestId) {
        service.transitionTo(requestId, ApprovalState.AWAITING_GROUP2_APPROVAL,
                "Waiting for approval group 2 decision");
        Decision d = service.awaitDecision(requestId, ApprovalGroup.GROUP_2);
        log.info("Workflow {} received group2 decision: {}", requestId, d);
        if (d == Decision.REJECTED) {
            throw new WorkflowException(WorkflowError.error(ERR_REJECTED_GROUP2, 409).build());
        }
        return new Group2Input(requestId);
    }

    @RegisterForReflection
    public record WorkflowInput(String requestId) {}

    @RegisterForReflection
    public record Group1Input(String requestId) {}

    @RegisterForReflection
    public record Group2Input(String requestId) {}

    @RegisterForReflection
    public record WorkflowOutput(String requestId, String outcome) {}
}
