package com.example.approval;

import java.time.Instant;

/**
 * Wire format pushed to connected UI clients via the WebSocket endpoint.
 * Emitted whenever a workflow instance changes state, a decision is recorded,
 * or a new request is created.
 */
public record ApprovalEvent(
        String type,
        String requestId,
        ApprovalState state,
        String message,
        Instant at) {

    public static ApprovalEvent stateChanged(ApprovalRequest r, String message) {
        return new ApprovalEvent("STATE_CHANGED", r.getId(), r.getState(), message, Instant.now());
    }

    public static ApprovalEvent created(ApprovalRequest r) {
        return new ApprovalEvent("REQUEST_CREATED", r.getId(), r.getState(), "Request submitted", Instant.now());
    }
}
