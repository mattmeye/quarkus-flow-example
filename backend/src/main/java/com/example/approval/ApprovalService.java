package com.example.approval;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the {@link ApprovalRequest} aggregate: creation, state transitions,
 * audit log. Per-stage human input is handled by {@link TaskService}.
 */
@ApplicationScoped
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final Map<String, ApprovalRequest> requests = new ConcurrentHashMap<>();

    @Inject
    Instance<ApprovalEventPublisher> eventPublisher;

    public ApprovalRequest create(String requester, String email, String subject, String description) {
        ApprovalRequest r = new ApprovalRequest(requester, email, subject, description);
        requests.put(r.getId(), r);
        r.appendHistory("AWAITING_CONFIRMATION",
                "Request created by " + requester + " <" + email + ">");
        log.info("Created request {} for {}", r.getId(), email);
        publish(ApprovalEvent.requestCreated(r));
        return r;
    }

    public Collection<ApprovalRequest> list() {
        return requests.values();
    }

    public Optional<ApprovalRequest> find(String id) {
        return Optional.ofNullable(requests.get(id));
    }

    public void transitionTo(String requestId, ApprovalState newState, String message) {
        ApprovalRequest r = requests.get(requestId);
        if (r == null) {
            log.warn("transitionTo called for unknown request {}", requestId);
            return;
        }
        r.setState(newState);
        r.appendHistory(newState.name(), message);
        log.info("Request {} -> {}", requestId, newState);
        publish(ApprovalEvent.stateChanged(r, message));
    }

    public void recordOutcome(String requestId, String outcome) {
        ApprovalRequest r = requests.get(requestId);
        if (r != null) {
            r.setOutcome(outcome);
        }
    }

    public void appendHistory(String requestId, String stage, String message) {
        ApprovalRequest r = requests.get(requestId);
        if (r != null) {
            r.appendHistory(stage, message);
        }
    }

    private void publish(ApprovalEvent ev) {
        if (!eventPublisher.isUnsatisfied()) {
            eventPublisher.get().publish(ev);
        }
    }
}
