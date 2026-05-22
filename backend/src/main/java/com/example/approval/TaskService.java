package com.example.approval;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns all {@link HumanTask}s and brokers their lifecycle between
 * the workflow (which awaits a task) and REST callers (which complete
 * or cancel a task). Validation that is specific to a task type
 * (e.g. checking the confirmation token) lives in
 * {@link #validate(HumanTask, String, Map)}.
 */
@ApplicationScoped
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final long AWAIT_TIMEOUT_HOURS = 24;

    private final Map<String, HumanTask> tasks = new ConcurrentHashMap<>();

    @Inject
    Instance<ApprovalEventPublisher> eventPublisher;

    public HumanTask create(String requestId, HumanTask.Type type, String name,
                            HumanTask.AssigneeGroup group, Map<String, Object> context) {
        HumanTask task = new HumanTask(requestId, type, name, group, context);
        tasks.put(task.getId(), task);
        log.info("Created task {} ({}) for request {} assigned to {}",
                task.getId(), type, requestId, group);
        publish(ApprovalEvent.taskCreated(task));
        return task;
    }

    public Optional<HumanTask> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public List<HumanTask> list() {
        return tasks.values().stream()
                .sorted(Comparator.comparing(HumanTask::getCreatedAt).reversed())
                .toList();
    }

    public List<HumanTask> listForRequest(String requestId) {
        return tasks.values().stream()
                .filter(t -> Objects.equals(t.getRequestId(), requestId))
                .sorted(Comparator.comparing(HumanTask::getCreatedAt))
                .toList();
    }

    public TaskResult complete(String taskId, String actor, String outcome, Map<String, Object> payload) {
        HumanTask task = require(taskId);
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

    public void cancel(String taskId, String actor, String reason) {
        HumanTask task = require(taskId);
        if (task.getStatus() != HumanTask.Status.PENDING) {
            throw new IllegalStateException(
                    "Task " + taskId + " is not pending (status=" + task.getStatus() + ")");
        }
        task.markCancelled(actor, reason);
        log.info("Cancelled task {} by={} reason={}", taskId, actor, reason);
        publish(ApprovalEvent.taskCompleted(task));
    }

    /**
     * Blocks the calling (workflow) thread until the task is completed
     * or cancelled, then returns the result.
     */
    public TaskResult await(HumanTask task) {
        try {
            return task.future().get(AWAIT_TIMEOUT_HOURS, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for task " + task.getId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException("Failed waiting for task " + task.getId(), e);
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

    private HumanTask require(String taskId) {
        HumanTask t = tasks.get(taskId);
        if (t == null) throw new IllegalArgumentException("Unknown task id: " + taskId);
        return t;
    }

    private void publish(ApprovalEvent ev) {
        if (!eventPublisher.isUnsatisfied()) {
            eventPublisher.get().publish(ev);
        }
    }
}
