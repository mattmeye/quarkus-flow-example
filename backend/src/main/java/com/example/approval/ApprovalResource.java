package com.example.approval;

import io.serverlessworkflow.impl.WorkflowInstance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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

@Path("/api/requests")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ApprovalResource {

    private static final Logger log = LoggerFactory.getLogger(ApprovalResource.class);
    private static final Pattern EMAIL_RE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Inject ApprovalService approvals;
    @Inject TaskService tasks;
    @Inject ApprovalWorkflow workflow;

    public record CreateRequest(
            String requester, String email, String subject, String description,
            Boolean termsAcknowledged) {}

    public record RequestDto(
            String id, String requester, String email, String subject, String description,
            ApprovalState state, String outcome,
            Instant createdAt,
            List<TaskResource.TaskDto> tasks,
            List<HistoryDto> history) {}

    public record HistoryDto(Instant at, String stage, String message) {}

    @POST
    @Transactional
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

        ApprovalRequest r = approvals.create(req.requester(), req.email(), req.subject(),
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

        return Response.status(Response.Status.CREATED).entity(toDto(r)).build();
    }

    @GET
    @Transactional
    public List<RequestDto> list() {
        return approvals.list().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(this::toDto).collect(Collectors.toList());
    }

    @GET
    @Path("/{id}")
    @Transactional
    public Response get(@PathParam("id") String id) {
        return approvals.lookup(id)
                .map(r -> Response.ok(toDto(r)).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static Response badRequest(String msg) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", msg)).build();
    }

    RequestDto toDto(ApprovalRequest r) {
        List<HistoryDto> hist = r.getHistory().stream()
                .map(h -> new HistoryDto(h.getAt(), h.getStage(), h.getMessage()))
                .collect(Collectors.toList());
        List<TaskResource.TaskDto> taskDtos = tasks.listForRequest(r.getId()).stream()
                .map(TaskResource::toDto)
                .collect(Collectors.toList());
        return new RequestDto(
                r.getId(), r.getRequester(), r.getEmail(), r.getSubject(), r.getDescription(),
                r.getState(), r.getOutcome(),
                r.getCreatedAt(),
                taskDtos, hist);
    }
}
