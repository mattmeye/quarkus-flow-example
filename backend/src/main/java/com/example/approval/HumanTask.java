package com.example.approval;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A generic asynchronous step the workflow is waiting on. Modelled as a
 * "human task" - akin to BPMN user tasks / Camunda external tasks - so the
 * REST API exposed to clients stays uniform regardless of which stage of
 * the workflow is currently active.
 *
 * The entity itself is persisted via JPA; the per-task
 * {@link java.util.concurrent.CompletableFuture} the workflow blocks on
 * lives separately in {@code TaskService} (it cannot be serialised and is
 * scoped to the JVM lifetime).
 *
 * Pending tasks also carry a {@code dueAt} deadline and a
 * {@code reminderAt} timestamp; a scheduled sweep in {@code TaskService}
 * fires reminders and expires the task once those instants pass.
 */
@Entity
@Table(name = "approval_task")
public class HumanTask {

    public enum Type { CONFIRMATION, APPROVAL }

    public enum Status { PENDING, COMPLETED, CANCELLED, EXPIRED }

    /** Logical actor / group expected to complete the task. */
    public enum AssigneeGroup { REQUESTER, GROUP_1, GROUP_2 }

    /** Synthetic outcome attached to {@link Status#EXPIRED} tasks. */
    public static final String OUTCOME_EXPIRED = "EXPIRED";
    /** Synthetic actor used when the system itself expires a task. */
    public static final String SYSTEM_ACTOR = "SYSTEM";

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    /** Stored as a plain FK so the load path doesn't need to fetch the parent eagerly. */
    @Column(name = "request_id", length = 64, nullable = false)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private Type type;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignee_group", nullable = false, length = 32)
    private AssigneeGroup assigneeGroup;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "reminder_at", nullable = false)
    private Instant reminderAt;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "context", columnDefinition = "TEXT")
    private Map<String, Object> context;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "reminded", nullable = false)
    private boolean reminded;

    @Column(name = "actor", length = 128)
    private String actor;

    @Column(name = "outcome", length = 32)
    private String outcome;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "payload", columnDefinition = "TEXT")
    private Map<String, Object> payload;

    protected HumanTask() {}

    public HumanTask(String requestId, Type type, String name,
                     AssigneeGroup assigneeGroup, Map<String, Object> context,
                     Duration timeout, double reminderOffsetFraction) {
        this.id = UUID.randomUUID().toString();
        this.requestId = requestId;
        this.type = type;
        this.name = name;
        this.assigneeGroup = assigneeGroup;
        this.createdAt = Instant.now();
        this.context = context == null ? new HashMap<>() : new HashMap<>(context);

        Duration effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofHours(24) : timeout;
        double frac = (reminderOffsetFraction <= 0 || reminderOffsetFraction >= 1)
                ? 0.75 : reminderOffsetFraction;
        this.dueAt = createdAt.plus(effectiveTimeout);
        this.reminderAt = createdAt.plusMillis((long) (effectiveTimeout.toMillis() * frac));
    }

    void markCompleted(TaskResult result) {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
        this.actor = result.actor();
        this.outcome = result.outcome();
        this.payload = result.payload();
    }

    void markCancelled(String actor, String reason) {
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
        this.actor = actor;
        this.outcome = "CANCELLED";
        this.payload = Map.of("reason", reason == null ? "" : reason);
    }

    void markExpired() {
        this.status = Status.EXPIRED;
        this.completedAt = Instant.now();
        this.actor = SYSTEM_ACTOR;
        this.outcome = OUTCOME_EXPIRED;
        this.payload = Map.of("reason", "Task expired at " + dueAt);
    }

    void markReminded() {
        this.reminded = true;
    }

    /** Result view consumed by the workflow's `await`. */
    @JsonIgnore
    @Transient
    public TaskResult asResult() {
        if (outcome == null) return null;
        return new TaskResult(outcome, actor == null ? "" : actor,
                payload == null ? Map.of() : payload);
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
    public Map<String, Object> getContext() {
        return context == null ? Map.of() : context;
    }
    public String getActor() { return actor; }
    public String getOutcome() { return outcome; }
    public Map<String, Object> getPayload() {
        return payload == null ? Map.of() : payload;
    }

    /** Legacy accessor — the workflow expects a {@link TaskResult} after completion. */
    public TaskResult getResult() { return asResult(); }
}
