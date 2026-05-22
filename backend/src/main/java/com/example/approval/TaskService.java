package com.example.approval;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns all {@link HumanTask}s and brokers their lifecycle between the
 * workflow (which awaits a task) and REST callers (which complete or
 * cancel a task).
 *
 * Persistent fields (status, dueAt, reminderAt, …) live on the JPA
 * entity, so every transition survives a restart. The per-task
 * {@link CompletableFuture} the workflow blocks on is intentionally NOT
 * persisted — it is JVM-scoped state held in {@link #futures}. Within a
 * single JVM lifetime that bridges the persistent transitions to the
 * workflow's `tasks.await(...)` call; across restarts a workflow that
 * was mid-await would need to be resumed via an event (out of scope for
 * the demo).
 */
@ApplicationScoped
public class TaskService implements PanacheRepositoryBase<HumanTask, String> {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final long AWAIT_TIMEOUT_HOURS = 72;

    /** In-memory CompletableFutures keyed by task id. Not persisted. */
    private final Map<String, CompletableFuture<TaskResult>> futures = new ConcurrentHashMap<>();

    @Inject
    Instance<ApprovalEventPublisher> eventPublisher;

    @ConfigProperty(name = "app.task.confirmation.timeout", defaultValue = "PT24H")
    Duration confirmationTimeout;

    @ConfigProperty(name = "app.task.approval.timeout", defaultValue = "PT48H")
    Duration approvalTimeout;

    @ConfigProperty(name = "app.task.reminder.offset-fraction", defaultValue = "0.75")
    double reminderOffsetFraction;

    @Transactional
    public HumanTask create(String requestId, HumanTask.Type type, String name,
                            HumanTask.AssigneeGroup group, Map<String, Object> context) {
        Duration timeout = switch (type) {
            case CONFIRMATION -> confirmationTimeout;
            case APPROVAL -> approvalTimeout;
        };
        HumanTask task = new HumanTask(requestId, type, name, group, context,
                timeout, reminderOffsetFraction);
        persist(task);
        futures.put(task.getId(), new CompletableFuture<>());
        log.info("Created task {} ({}) for request {} assigned to {} (dueAt={}, reminderAt={})",
                task.getId(), type, requestId, group, task.getDueAt(), task.getReminderAt());
        publish(ApprovalEvent.taskCreated(task));
        return task;
    }

    @Transactional
    public Optional<HumanTask> lookup(String taskId) {
        return Optional.ofNullable(findById(taskId));
    }

    @Transactional
    public List<HumanTask> list() {
        return find("ORDER BY createdAt DESC").list();
    }

    @Transactional
    public List<HumanTask> listForRequest(String requestId) {
        return find("requestId = ?1 ORDER BY createdAt ASC", requestId).list();
    }

    @Transactional
    public TaskResult complete(String taskId, String actor, String outcome, Map<String, Object> payload) {
        HumanTask task = requireManaged(taskId);
        if (task.getStatus() != HumanTask.Status.PENDING) {
            throw new IllegalStateException(
                    "Task " + taskId + " is not pending (status=" + task.getStatus() + ")");
        }
        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("outcome is required");
        }
        validate(task, outcome, payload);
        TaskResult result = TaskResult.of(outcome, actor, payload);
        task.markCompleted(result);
        log.info("Completed task {} with outcome={} by={}", taskId, outcome, actor);
        publish(ApprovalEvent.taskCompleted(task));
        signal(taskId, result);
        return result;
    }

    @Transactional
    public void cancel(String taskId, String actor, String reason) {
        HumanTask task = requireManaged(taskId);
        if (task.getStatus() != HumanTask.Status.PENDING) {
            throw new IllegalStateException(
                    "Task " + taskId + " is not pending (status=" + task.getStatus() + ")");
        }
        task.markCancelled(actor, reason);
        log.info("Cancelled task {} by={} reason={}", taskId, actor, reason);
        publish(ApprovalEvent.taskCompleted(task));
        signal(taskId, task.asResult());
    }

    /**
     * Blocks the calling (workflow) thread until the task is completed,
     * cancelled or expired, then returns the result.
     */
    public TaskResult await(HumanTask task) {
        CompletableFuture<TaskResult> f = futures.computeIfAbsent(task.getId(), k -> new CompletableFuture<>());
        try {
            return f.get(AWAIT_TIMEOUT_HOURS, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for task " + task.getId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException("Failed waiting for task " + task.getId(), e);
        } finally {
            futures.remove(task.getId());
        }
    }

    /**
     * Periodic sweep over pending tasks. Cheap: one indexed query and at
     * most one row update per pending task. Runs every second by default;
     * the interval is configurable via {@code app.task.sweep.every}.
     */
    @Scheduled(every = "${app.task.sweep.every:1s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void sweep() {
        Instant now = Instant.now();
        List<HumanTask> pending = find("status = ?1", HumanTask.Status.PENDING).list();
        for (HumanTask task : pending) {
            if (!task.isReminded() && !now.isBefore(task.getReminderAt())) {
                task.markReminded();
                log.info("Reminder fired for task {} ({}) - due at {}",
                        task.getId(), task.getType(), task.getDueAt());
                publish(ApprovalEvent.taskReminder(task));
            }
            if (!now.isBefore(task.getDueAt())) {
                task.markExpired();
                log.info("Expired task {} ({}) at {}",
                        task.getId(), task.getType(), task.getDueAt());
                publish(ApprovalEvent.taskExpired(task));
                signal(task.getId(), task.asResult());
            }
        }
    }

    private void signal(String taskId, TaskResult result) {
        CompletableFuture<TaskResult> f = futures.get(taskId);
        if (f != null && !f.isDone()) {
            f.complete(result);
        }
    }

    /**
     * Per-type validation. Kept inline for the demo; in a real system this
     * would be a CDI-discovered {@code TaskValidator} per type.
     */
    private void validate(HumanTask task, String outcome, Map<String, Object> payload) {
        switch (task.getType()) {
            case CONFIRMATION -> {
                if ("CONFIRMED".equals(outcome)) {
                    String expected = (String) task.getContext().get("confirmationToken");
                    Object token = payload == null ? null : payload.get("token");
                    Object terms = payload == null ? null : payload.get("termsAccepted");
                    if (expected != null && !expected.equals(token)) {
                        throw new IllegalArgumentException("Invalid confirmation token");
                    }
                    if (!Boolean.TRUE.equals(terms)) {
                        throw new IllegalArgumentException("Terms must be accepted to confirm");
                    }
                } else if (!"CANCELLED".equals(outcome)) {
                    throw new IllegalArgumentException(
                            "CONFIRMATION tasks accept outcome CONFIRMED or CANCELLED, got " + outcome);
                }
            }
            case APPROVAL -> {
                if (!"APPROVED".equals(outcome) && !"REJECTED".equals(outcome)) {
                    throw new IllegalArgumentException(
                            "APPROVAL tasks accept outcome APPROVED or REJECTED, got " + outcome);
                }
            }
        }
    }

    private HumanTask requireManaged(String taskId) {
        HumanTask t = findById(taskId);
        if (t == null) throw new IllegalArgumentException("Unknown task id: " + taskId);
        return t;
    }

    private void publish(ApprovalEvent ev) {
        if (!eventPublisher.isUnsatisfied()) {
            eventPublisher.get().publish(ev);
        }
    }
}
