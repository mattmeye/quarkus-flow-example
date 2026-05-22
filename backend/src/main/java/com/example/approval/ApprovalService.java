package com.example.approval;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Holds all approval requests in memory and brokers asynchronous decisions
 * between REST endpoints and the workflow's awaiting tasks via per-request
 * CompletableFutures.
 *
 * The workflow runs on the Quarkus Flow executor (not on the request thread),
 * so blocking inside an awaiting task is safe.
 */
@ApplicationScoped
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final long AWAIT_TIMEOUT_HOURS = 24;

    /** Result of the confirmation step (whether the requester confirmed and accepted). */
    public record ConfirmationResult(boolean confirmed, String reason) {
        public static ConfirmationResult ok() { return new ConfirmationResult(true, null); }
        public static ConfirmationResult declined(String reason) { return new ConfirmationResult(false, reason); }
    }

    private final Map<String, ApprovalRequest> requests = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ConfirmationResult>> confirmationWaiters = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Decision>> group1Waiters = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Decision>> group2Waiters = new ConcurrentHashMap<>();

    @Inject
    Instance<ApprovalEventPublisher> eventPublisher;

    public ApprovalRequest create(String requester, String email, String subject, String description) {
        ApprovalRequest r = new ApprovalRequest(requester, email, subject, description);
        requests.put(r.getId(), r);
        confirmationWaiters.put(r.getId(), new CompletableFuture<>());
        group1Waiters.put(r.getId(), new CompletableFuture<>());
        group2Waiters.put(r.getId(), new CompletableFuture<>());
        r.appendHistory("AWAITING_CONFIRMATION",
                "Request created by " + requester + " <" + email + ">. "
                        + "Confirmation email dispatched (token=" + r.getConfirmationToken() + ").");
        log.info("Created request {} for {} (confirmation token={})",
                r.getId(), email, r.getConfirmationToken());
        publish(ApprovalEvent.created(r));
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

    public ConfirmationResult awaitConfirmation(String requestId) {
        return await(confirmationWaiters.get(requestId), requestId, "confirmation");
    }

    public Decision awaitDecision(String requestId, ApprovalGroup group) {
        CompletableFuture<Decision> waiter = (group == ApprovalGroup.GROUP_1)
                ? group1Waiters.get(requestId) : group2Waiters.get(requestId);
        return await(waiter, requestId, group.name());
    }

    private <T> T await(CompletableFuture<T> waiter, String requestId, String what) {
        if (waiter == null) {
            throw new IllegalStateException(
                    "No waiter registered for request " + requestId + " (" + what + ")");
        }
        try {
            return waiter.get(AWAIT_TIMEOUT_HOURS, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for " + what, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException("Failed waiting for " + what, e);
        }
    }

    /**
     * Records the requester's confirmation of email + terms.
     * Triggers the workflow's awaiting "confirmation" task to resume.
     */
    public void confirm(String requestId, String token, boolean termsAccepted) {
        ApprovalRequest r = requireRequest(requestId);
        if (r.getState() != ApprovalState.AWAITING_CONFIRMATION) {
            throw new IllegalStateException(
                    "Request " + requestId + " is not awaiting confirmation; current state is " + r.getState());
        }
        if (!r.getConfirmationToken().equals(token)) {
            throw new IllegalArgumentException("Invalid confirmation token");
        }
        if (!termsAccepted) {
            throw new IllegalArgumentException("Terms must be accepted to confirm the request");
        }
        r.setEmailConfirmed(true);
        r.setTermsAccepted(true);
        r.setConfirmedAt(Instant.now());
        r.appendHistory("CONFIRMED",
                "Email " + r.getEmail() + " confirmed and terms accepted by requester");
        CompletableFuture<ConfirmationResult> f = confirmationWaiters.get(requestId);
        if (f != null) f.complete(ConfirmationResult.ok());
    }

    /**
     * Allows the requester to abandon the request before it enters the
     * approval chain. The workflow will short-circuit to REJECTED.
     */
    public void cancelConfirmation(String requestId, String reason) {
        ApprovalRequest r = requireRequest(requestId);
        if (r.getState() != ApprovalState.AWAITING_CONFIRMATION) {
            throw new IllegalStateException(
                    "Request " + requestId + " is not awaiting confirmation");
        }
        CompletableFuture<ConfirmationResult> f = confirmationWaiters.get(requestId);
        if (f != null) f.complete(ConfirmationResult.declined(reason));
    }

    public void recordDecision(String requestId, ApprovalGroup group, Decision decision, String approver) {
        ApprovalRequest r = requireRequest(requestId);
        if (group == ApprovalGroup.GROUP_1) {
            if (r.getState() != ApprovalState.AWAITING_GROUP1_APPROVAL) {
                throw new IllegalStateException(
                        "Request " + requestId + " is not awaiting group 1; current state is " + r.getState());
            }
            r.setGroup1Decision(decision);
            r.setGroup1Approver(approver);
            r.appendHistory("GROUP_1_" + decision.name(),
                    "Group 1 (" + approver + ") decided: " + decision.name());
            CompletableFuture<Decision> f = group1Waiters.get(requestId);
            if (f != null) f.complete(decision);
        } else {
            if (r.getState() != ApprovalState.AWAITING_GROUP2_APPROVAL) {
                throw new IllegalStateException(
                        "Request " + requestId + " is not awaiting group 2; current state is " + r.getState());
            }
            r.setGroup2Decision(decision);
            r.setGroup2Approver(approver);
            r.appendHistory("GROUP_2_" + decision.name(),
                    "Group 2 (" + approver + ") decided: " + decision.name());
            CompletableFuture<Decision> f = group2Waiters.get(requestId);
            if (f != null) f.complete(decision);
        }
    }

    private ApprovalRequest requireRequest(String id) {
        ApprovalRequest r = requests.get(id);
        if (r == null) throw new IllegalArgumentException("Unknown request id: " + id);
        return r;
    }

    private void publish(ApprovalEvent ev) {
        if (!eventPublisher.isUnsatisfied()) {
            eventPublisher.get().publish(ev);
        }
    }
}
