package com.example.approval;

import java.time.Instant;

/**
 * Wire format pushed to connected UI clients via the WebSocket endpoint.
 * Emitted whenever a request transitions, a task is created, or a task is
 * completed/cancelled.
 */
public record ApprovalEvent(
        String type,
        String requestId,
        String taskId,
        ApprovalState state,
        String message,
        Instant at) {

    public static ApprovalEvent requestCreated(ApprovalRequest r) {
        return new ApprovalEvent("REQUEST_CREATED", r.getId(), null,
                r.getState(), "Request submitted", Instant.now());
    }

    public static ApprovalEvent stateChanged(ApprovalRequest r, String message) {
        return new ApprovalEvent("STATE_CHANGED", r.getId(), null,
                r.getState(), message, Instant.now());
    }

    public static ApprovalEvent taskCreated(HumanTask t) {
        return new ApprovalEvent("TASK_CREATED", t.getRequestId(), t.getId(),
                null, "Task created: " + t.getName(), Instant.now());
    }

    public static ApprovalEvent taskCompleted(HumanTask t) {
        String msg = t.getStatus() == HumanTask.Status.CANCELLED
                ? "Task cancelled: " + t.getName()
                : "Task completed: " + t.getName() + " -> "
                  + (t.getResult() == null ? "?" : t.getResult().outcome());
        return new ApprovalEvent("TASK_COMPLETED", t.getRequestId(), t.getId(),
                null, msg, Instant.now());
    }
}
