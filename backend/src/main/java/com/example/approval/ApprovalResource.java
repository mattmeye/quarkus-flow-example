package com.example.approval;

import io.serverlessworkflow.impl.WorkflowInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Path("/api/approvals")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ApprovalResource {

    private static final Logger log = LoggerFactory.getLogger(ApprovalResource.class);
    private static final Pattern EMAIL_RE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Inject ApprovalService service;
    @Inject ApprovalWorkflow workflow;

    public record CreateRequest(
            String requester, String email, String subject, String description,
            Boolean termsAcknowledged) {}

    public record CreatedResponse(RequestDto request, String confirmationLink) {}

    public record ConfirmRequest(String token, Boolean termsAccepted) {}

    public record DecisionRequest(String approver, Decision decision) {}

    public record RequestDto(
            String id, String requester, String email, String subject, String description,
            ApprovalState state, boolean emailConfirmed, boolean termsAccepted,
            String confirmationToken,
            Decision group1Decision, String group1Approver,
            Decision group2Decision, String group2Approver,
            Instant createdAt, Instant confirmedAt, List<HistoryDto> history) {}

    public record HistoryDto(Instant at, String stage, String message) {}

    @POST
    public Response create(CreateRequest req) {
        if (req == null || isBlank(req.requester()) || isBlank(req.subject()) || isBlank(req.email())) {
            return badRequest("requester, email and subject are required");
        }
        if (!EMAIL_RE.matcher(req.email()).matches()) {
            return badRequest("Invalid email address");
        }
        if (req.termsAcknowledged() == null || !req.termsAcknowledged()) {
            return badRequest("Terms must be acknowledged at submission time");
        }

        ApprovalRequest r = service.create(req.requester(), req.email(), req.subject(),
                req.description() == null ? "" : req.description());

        WorkflowInstance instance = workflow.instance(new ApprovalWorkflow.WorkflowInput(r.getId()));
        instance.start().whenComplete((model, err) -> {
            if (err != null) {
                log.error("Workflow {} failed", r.getId(), err);
            } else {
                log.info("Workflow {} completed: {}", r.getId(),
                        model.as(ApprovalWorkflow.WorkflowOutput.class).orElse(null));
            }
        });

        // In a real system this URL would be e-mailed to the requester.
        // For the demo we return it directly so the UI can simulate the click.
        String link = "/api/approvals/" + r.getId() + "/confirm?token=" + r.getConfirmationToken();
        return Response.status(Response.Status.CREATED)
                .entity(new CreatedResponse(toDto(r), link))
                .build();
    }

    @GET
    public List<RequestDto> list() {
        return service.list().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toDto).collect(Collectors.toList());
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        return service.find(id)
                .map(r -> Response.ok(toDto(r)).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    /**
     * Async confirmation step: the requester confirms ownership of the email
     * address (via the token included in the confirmation link) and explicitly
     * accepts the terms. Unblocks the workflow's awaitConfirmation task.
     */
    @POST
    @Path("/{id}/confirm")
    public Response confirm(@PathParam("id") String id, ConfirmRequest req) {
        if (req == null || isBlank(req.token())) {
            return badRequest("token is required");
        }
        if (req.termsAccepted() == null || !req.termsAccepted()) {
            return badRequest("Terms must be accepted to confirm");
        }
        try {
            service.confirm(id, req.token(), req.termsAccepted());
            return service.find(id)
                    .map(r -> Response.ok(toDto(r)).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/cancel")
    public Response cancel(@PathParam("id") String id) {
        try {
            service.cancelConfirmation(id, "Cancelled by requester before confirmation");
            return service.find(id)
                    .map(r -> Response.ok(toDto(r)).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/group1/decision")
    public Response group1Decision(@PathParam("id") String id, DecisionRequest req) {
        return submitDecision(id, ApprovalGroup.GROUP_1, req);
    }

    @POST
    @Path("/{id}/group2/decision")
    public Response group2Decision(@PathParam("id") String id, DecisionRequest req) {
        return submitDecision(id, ApprovalGroup.GROUP_2, req);
    }

    private Response submitDecision(String id, ApprovalGroup group, DecisionRequest req) {
        if (req == null || req.decision() == null || isBlank(req.approver())) {
            return badRequest("approver and decision are required");
        }
        try {
            service.recordDecision(id, group, req.decision(), req.approver());
            return service.find(id)
                    .map(r -> Response.ok(toDto(r)).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage())).build();
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static Response badRequest(String msg) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", msg)).build();
    }

    private RequestDto toDto(ApprovalRequest r) {
        List<HistoryDto> hist = r.getHistory().stream()
                .map(h -> new HistoryDto(h.at(), h.stage(), h.message()))
                .collect(Collectors.toList());
        // Demo: token is exposed so the UI can simulate clicking the email
        // link. A real system would only deliver it via the email channel.
        String token = r.getState() == ApprovalState.AWAITING_CONFIRMATION
                ? r.getConfirmationToken() : null;
        return new RequestDto(
                r.getId(), r.getRequester(), r.getEmail(), r.getSubject(), r.getDescription(),
                r.getState(), r.isEmailConfirmed(), r.isTermsAccepted(),
                token,
                r.getGroup1Decision(), r.getGroup1Approver(),
                r.getGroup2Decision(), r.getGroup2Approver(),
                r.getCreatedAt(), r.getConfirmedAt(), hist);
    }
}
