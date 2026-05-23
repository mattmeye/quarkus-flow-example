package com.example.approval;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.narayana.jta.QuarkusTransaction;
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

/**
 * Owns all {@link HumanTask}s and brokers their lifecycle between the
 * workflow (which awaits a task) and REST callers (which complete or
 * cancel a task).
 *
 * Persistence-only design: the workflow's wait is implemented as a
 * {@link #awaitResolved} DB poll against the persistent task row, not as
 * a heap-bound {@code CompletableFuture}. Combined with the engine's
 * own JPA persistence (via {@code quarkus-flow-jpa}) that makes the
 * whole workflow cross-restart durable — if the JVM dies mid-wait, the
 * engine resumes from JPA, re-enters the same {@code function}, and the
 * poll either sees the task already resolved (and returns) or keeps
 * polling until it is.
 */
@ApplicationScoped
public class TaskService implements PanacheRepositoryBase<HumanTask, String> {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** How often the workflow function polls the DB while a task is PENDING. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    /** Safety cap on the polling loop to avoid pinning a workflow thread forever. */
    private static final Duration POLL_TIMEOUT = Duration.ofHours(72);

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
        log.info("Created task {} ({}) for request {} assigned to {} (dueAt={}, reminderAt={})",
                task.getId(), type, requestId, group, task.getDueAt(), task.getReminderAt());
        publish(ApprovalEvent.taskCreated(task));
        return task;
    }

    @Transactional
    public Optional<HumanTask> lookup(String taskId) {
        return Optional.ofNullable(findById(taskId));
    }

    /**
     * Used by the workflow to make stage re-entry idempotent after a JVM
     * restart: if an unresolved task for this request and stage already
     * exists, the workflow attaches to it instead of creating a duplicate.
     */
    @Transactional
    public Optional<HumanTask> findPendingFor(String requestId, HumanTask.Type type,
                                              HumanTask.AssigneeGroup group) {
        return find("requestId = ?1 and type = ?2 and assigneeGroup = ?3 and status = ?4",
                requestId, type, group, HumanTask.Status.PENDING).firstResultOptional();
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
    }

    /**
     * Blocks the calling (workflow) thread until the task reaches a
     * terminal state. Implemented as a short-interval DB poll so no
     * non-persistent state (futures, in-process maps) is involved —
     * which is exactly what makes this workflow survive a JVM restart.
     */
    public TaskResult awaitResolved(String taskId) {
        Instant deadline = Instant.now().plus(POLL_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            HumanTask snapshot = readStatus(taskId);
            if (snapshot == null) {
                throw new IllegalArgumentException("Unknown task id: " + taskId);
            }
            if (snapshot.getStatus() != HumanTask.Status.PENDING) {
                TaskResult r = snapshot.asResult();
                if (r != null) return r;
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for task " + taskId, e);
            }
        }
        throw new RuntimeException("Timed out waiting for task " + taskId);
    }

    /**
     * Read the task in its own short-lived transaction so the polling
     * loop never holds a TX open between iterations.
     */
    private HumanTask readStatus(String taskId) {
        return QuarkusTransaction.requiringNew().call(() -> findById(taskId));
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
            }
        }
    }

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
