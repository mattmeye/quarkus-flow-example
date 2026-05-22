package com.example.approval;

import java.util.Map;

/**
 * Outcome of completing a {@link HumanTask}. The {@code outcome} string is
 * interpreted by the workflow logic that created the task; the
 * {@code payload} carries free-form fields supplied by the caller
 * (approver, decision, accepted-terms flag, ...).
 */
public record TaskResult(String outcome, String actor, Map<String, Object> payload) {

    public static TaskResult of(String outcome, String actor, Map<String, Object> payload) {
        return new TaskResult(outcome,
                actor == null ? "" : actor,
                payload == null ? Map.of() : payload);
    }

    public Object payloadValue(String key) {
        return payload == null ? null : payload.get(key);
    }
}
