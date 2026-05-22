package com.example.approval;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Mutable server-side view of an approval request. Held by {@link ApprovalService}
 * for the lifetime of the workflow instance and serialized to clients via REST
 * and the WebSocket event stream.
 */
public class ApprovalRequest {

    public record HistoryEntry(Instant at, String stage, String message) {}

    private static final SecureRandom RNG = new SecureRandom();

    private final String id;
    private final String requester;
    private final String email;
    private final String subject;
    private final String description;
    private final String confirmationToken;
    private final Instant createdAt;
    private volatile ApprovalState state;
    private volatile boolean emailConfirmed;
    private volatile boolean termsAccepted;
    private volatile Instant confirmedAt;
    private volatile Decision group1Decision;
    private volatile Decision group2Decision;
    private volatile String group1Approver;
    private volatile String group2Approver;
    private final List<HistoryEntry> history = Collections.synchronizedList(new ArrayList<>());

    public ApprovalRequest(String requester, String email, String subject, String description) {
        this.id = UUID.randomUUID().toString();
        this.requester = requester;
        this.email = email;
        this.subject = subject;
        this.description = description;
        this.createdAt = Instant.now();
        this.confirmationToken = newToken();
        this.state = ApprovalState.AWAITING_CONFIRMATION;
    }

    private static String newToken() {
        byte[] buf = new byte[24];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public void appendHistory(String stage, String message) {
        history.add(new HistoryEntry(Instant.now(), stage, message));
    }

    public String getId() { return id; }
    public String getRequester() { return requester; }
    public String getEmail() { return email; }
    public String getSubject() { return subject; }
    public String getDescription() { return description; }
    public String getConfirmationToken() { return confirmationToken; }
    public Instant getCreatedAt() { return createdAt; }
    public ApprovalState getState() { return state; }
    public void setState(ApprovalState state) { this.state = state; }
    public boolean isEmailConfirmed() { return emailConfirmed; }
    public void setEmailConfirmed(boolean v) { this.emailConfirmed = v; }
    public boolean isTermsAccepted() { return termsAccepted; }
    public void setTermsAccepted(boolean v) { this.termsAccepted = v; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant t) { this.confirmedAt = t; }
    public Decision getGroup1Decision() { return group1Decision; }
    public void setGroup1Decision(Decision d) { this.group1Decision = d; }
    public Decision getGroup2Decision() { return group2Decision; }
    public void setGroup2Decision(Decision d) { this.group2Decision = d; }
    public String getGroup1Approver() { return group1Approver; }
    public void setGroup1Approver(String a) { this.group1Approver = a; }
    public String getGroup2Approver() { return group2Approver; }
    public void setGroup2Approver(String a) { this.group2Approver = a; }
    public List<HistoryEntry> getHistory() { return List.copyOf(history); }
}
