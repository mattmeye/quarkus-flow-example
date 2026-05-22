package com.example.approval;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A generic asynchronous step the workflow is waiting on. Modelled as a
 * "human task" - akin to BPMN user tasks / Camunda external tasks - so the
 * REST API exposed to clients stays uniform regardless of which stage of
 * the workflow is currently active.
 *
 * The workflow holds a reference to a task and awaits its
 * {@link #future()}. REST callers complete or cancel the task by id,
 * which in turn unblocks the workflow.
 */
public class HumanTask {

    public enum Type { CONFIRMATION, APPROVAL }

    public enum Status { PENDING, COMPLETED, CANCELLED }

    /** Logical actor / group expected to complete the task. */
    public enum AssigneeGroup { REQUESTER, GROUP_1, GROUP_2 }

    private final String id;
    private final String requestId;
    private final Type type;
    private final String name;
    private final AssigneeGroup assigneeGroup;
    private final Instant createdAt;
    private final Map<String, Object> context;
    private final CompletableFuture<TaskResult> future = new CompletableFuture<>();

    private volatile Status status = Status.PENDING;
    private volatile Instant completedAt;
    private volatile TaskResult result;

    public HumanTask(String requestId, Type type, String name,
                     AssigneeGroup assigneeGroup, Map<String, Object> context) {
        this.id = UUID.randomUUID().toString();
        this.requestId = requestId;
        this.type = type;
        this.name = name;
        this.assigneeGroup = assigneeGroup;
        this.createdAt = Instant.now();
        this.context = context == null ? Map.of() : new HashMap<>(context);
    }

    void markCompleted(TaskResult result) {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
        this.result = result;
        future.complete(result);
    }

    void markCancelled(String actor, String reason) {
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
        this.result = new TaskResult("CANCELLED", actor, Map.of("reason", reason == null ? "" : reason));
        future.complete(this.result);
    }

    public String getId() { return id; }
    public String getRequestId() { return requestId; }
    public Type getType() { return type; }
    public String getName() { return name; }
    public AssigneeGroup getAssigneeGroup() { return assigneeGroup; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Map<String, Object> getContext() { return context; }
    public TaskResult getResult() { return result; }

    @JsonIgnore
    public CompletableFuture<TaskResult> future() { return future; }
}
