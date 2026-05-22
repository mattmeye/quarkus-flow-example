package com.example.approval;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Optional;

/**
 * Owns the {@link ApprovalRequest} aggregate: creation, state transitions,
 * audit log. Per-stage human input is handled by {@link TaskService}.
 *
 * Backed by Hibernate ORM / Panache — all mutations run inside a
 * {@link Transactional} boundary so the persisted state stays
 * consistent across process restarts.
 */
@ApplicationScoped
public class ApprovalService implements PanacheRepositoryBase<ApprovalRequest, String> {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    @Inject
    Instance<ApprovalEventPublisher> eventPublisher;

    @Transactional
    public ApprovalRequest create(String requester, String email, String subject, String description) {
        ApprovalRequest r = new ApprovalRequest(requester, email, subject, description);
        r.appendHistory("AWAITING_CONFIRMATION",
                "Request created by " + requester + " <" + email + ">");
        persist(r);
        log.info("Created request {} for {}", r.getId(), email);
        publish(ApprovalEvent.requestCreated(r));
        return r;
    }

    @Transactional
    public Collection<ApprovalRequest> list() {
        return listAll();
    }

    @Transactional
    public Optional<ApprovalRequest> lookup(String id) {
        return Optional.ofNullable(findById(id));
    }

    @Transactional
    public void transitionTo(String requestId, ApprovalState newState, String message) {
        ApprovalRequest r = findById(requestId);
        if (r == null) {
            log.warn("transitionTo called for unknown request {}", requestId);
            return;
        }
        r.setState(newState);
        r.appendHistory(newState.name(), message);
        log.info("Request {} -> {}", requestId, newState);
        publish(ApprovalEvent.stateChanged(r, message));
    }

    @Transactional
    public void recordOutcome(String requestId, String outcome) {
        ApprovalRequest r = findById(requestId);
        if (r != null) {
            r.setOutcome(outcome);
        }
    }

    @Transactional
    public void appendHistory(String requestId, String stage, String message) {
        ApprovalRequest r = findById(requestId);
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
