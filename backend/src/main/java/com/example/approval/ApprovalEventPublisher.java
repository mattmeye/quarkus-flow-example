package com.example.approval;

/**
 * Thin abstraction so the service can publish events without compile-time
 * coupling to the WebSocket endpoint.
 */
public interface ApprovalEventPublisher {
    void publish(ApprovalEvent event);
}
