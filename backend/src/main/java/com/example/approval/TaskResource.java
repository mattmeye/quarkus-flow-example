package com.example.approval;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Generic REST surface for the workflow's open work items. Two verbs only:
 * complete a task or cancel it. The workflow itself decides what to do
 * based on the task's {@code type} and the supplied {@code outcome}.
 */
@Path("/api/tasks")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TaskResource {

    @Inject TaskService tasks;

    public record CompleteRequest(String actor, String outcome, Map<String, Object> payload) {}

    public record CancelRequest(String actor, String reason) {}

    public record TaskDto(
            String id, String requestId,
            HumanTask.Type type, String name,
            HumanTask.AssigneeGroup assigneeGroup,
            HumanTask.Status status,
            Instant createdAt, Instant completedAt,
            Instant dueAt, Instant reminderAt, boolean reminded,
            Map<String, Object> context,
            String actor, String outcome,
            Map<String, Object> payload) {}

    @GET
    @Transactional
    public List<TaskDto> list(@QueryParam("status") HumanTask.Status status,
                              @QueryParam("group") HumanTask.AssigneeGroup group,
                              @QueryParam("requestId") String requestId) {
        return tasks.list().stream()
                .filter(t -> status == null || t.getStatus() == status)
                .filter(t -> group == null || t.getAssigneeGroup() == group)
                .filter(t -> requestId == null || requestId.equals(t.getRequestId()))
                .map(TaskResource::toDto)
                .toList();
    }

    @GET
    @Path("/{id}")
    @Transactional
    public Response get(@PathParam("id") String id) {
        return tasks.lookup(id)
                .map(t -> Response.ok(toDto(t)).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    @POST
    @Path("/{id}/complete")
    @Transactional
    public Response complete(@PathParam("id") String id, CompleteRequest req) {
        if (req == null || isBlank(req.outcome()) || isBlank(req.actor())) {
            return badRequest("actor and outcome are required");
        }
        try {
            tasks.complete(id, req.actor(), req.outcome(), req.payload());
            return tasks.lookup(id)
                    .map(t -> Response.ok(toDto(t)).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
        } catch (IllegalArgumentException e) {
            // unknown task -> 404, validation failure -> 400
            if (e.getMessage() != null && e.getMessage().startsWith("Unknown task")) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", e.getMessage())).build();
            }
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/cancel")
    @Transactional
    public Response cancel(@PathParam("id") String id, CancelRequest req) {
        if (req == null || isBlank(req.actor())) {
            return badRequest("actor is required");
        }
        try {
            tasks.cancel(id, req.actor(), req.reason());
            return tasks.lookup(id)
                    .map(t -> Response.ok(toDto(t)).build())
                    .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", e.getMessage())).build();
        }
    }

    static TaskDto toDto(HumanTask t) {
        TaskResult r = t.getResult();
        return new TaskDto(
                t.getId(), t.getRequestId(),
                t.getType(), t.getName(),
                t.getAssigneeGroup(), t.getStatus(),
                t.getCreatedAt(), t.getCompletedAt(),
                t.getDueAt(), t.getReminderAt(), t.isReminded(),
                t.getContext(),
                r == null ? null : r.actor(),
                r == null ? null : r.outcome(),
                r == null ? null : r.payload());
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static Response badRequest(String msg) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", msg)).build();
    }
}
