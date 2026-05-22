package com.example.approval;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Duration;
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
 * which in turn unblocks the workflow. Pending tasks also carry a
 * {@code dueAt} deadline and a {@code reminderAt} timestamp; a scheduled
 * sweep in {@link TaskService} fires reminders and expires the task once
 * those instants pass.
 */
public class HumanTask {

    public enum Type { CONFIRMATION, APPROVAL }

    public enum Status { PENDING, COMPLETED, CANCELLED, EXPIRED }

    /** Logical actor / group expected to complete the task. */
    public enum AssigneeGroup { REQUESTER, GROUP_1, GROUP_2 }

    /** Synthetic outcome attached to {@link Status#EXPIRED} tasks. */
    public static final String OUTCOME_EXPIRED = "EXPIRED";
    /** Synthetic actor used when the system itself expires a task. */
    public static final String SYSTEM_ACTOR = "SYSTEM";

    private final String id;
    private final String requestId;
    private final Type type;
    private final String name;
    private final AssigneeGroup assigneeGroup;
    private final Instant createdAt;
    private final Instant dueAt;
    private final Instant reminderAt;
    private final Map<String, Object> context;
    private final CompletableFuture<TaskResult> future = new CompletableFuture<>();

    private volatile Status status = Status.PENDING;
    private volatile Instant completedAt;
    private volatile TaskResult result;
    private volatile boolean reminded;

    public HumanTask(String requestId, Type type, String name,
                     AssigneeGroup assigneeGroup, Map<String, Object> context,
                     Duration timeout, double reminderOffsetFraction) {
        this.id = UUID.randomUUID().toString();
        this.requestId = requestId;
        this.type = type;
        this.name = name;
        this.assigneeGroup = assigneeGroup;
        this.createdAt = Instant.now();
        this.context = context == null ? Map.of() : new HashMap<>(context);

        Duration effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofHours(24) : timeout;
        // Reminder fires once `reminderOffsetFraction` of the timeout has elapsed
        // (e.g. 0.75 -> 25% of the window remains before the deadline).
        double frac = (reminderOffsetFraction <= 0 || reminderOffsetFraction >= 1)
                ? 0.75 : reminderOffsetFraction;
        this.dueAt = createdAt.plus(effectiveTimeout);
        this.reminderAt = createdAt.plusMillis((long) (effectiveTimeout.toMillis() * frac));
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

    void markExpired() {
        this.status = Status.EXPIRED;
        this.completedAt = Instant.now();
        this.result = new TaskResult(OUTCOME_EXPIRED, SYSTEM_ACTOR,
                Map.of("reason", "Task expired at " + dueAt));
        future.complete(this.result);
    }

    void markReminded() {
        this.reminded = true;
    }

    public String getId() { return id; }
    public String getRequestId() { return requestId; }
    public Type getType() { return type; }
    public String getName() { return name; }
    public AssigneeGroup getAssigneeGroup() { return assigneeGroup; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDueAt() { return dueAt; }
    public Instant getReminderAt() { return reminderAt; }
    public boolean isReminded() { return reminded; }
    public Instant getCompletedAt() { return completedAt; }
    public Map<String, Object> getContext() { return context; }
    public TaskResult getResult() { return result; }

    @JsonIgnore
    public CompletableFuture<TaskResult> future() { return future; }
}
