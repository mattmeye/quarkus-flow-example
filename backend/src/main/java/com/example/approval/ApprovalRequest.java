package com.example.approval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The request as the workflow sees it. Stage-specific data
 * (confirmation token, group decisions, approvers) is no longer stored
 * here - it lives on the {@link HumanTask} instances that the workflow
 * creates at each async step.
 */
public class ApprovalRequest {

    public record HistoryEntry(Instant at, String stage, String message) {}

    private final String id;
    private final String requester;
    private final String email;
    private final String subject;
    private final String description;
    private final Instant createdAt;
    private volatile ApprovalState state;
    private volatile String outcome;
    private final List<HistoryEntry> history = Collections.synchronizedList(new ArrayList<>());

    public ApprovalRequest(String requester, String email, String subject, String description) {
        this.id = UUID.randomUUID().toString();
        this.requester = requester;
        this.email = email;
        this.subject = subject;
        this.description = description;
        this.createdAt = Instant.now();
        this.state = ApprovalState.AWAITING_CONFIRMATION;
    }

    public void appendHistory(String stage, String message) {
        history.add(new HistoryEntry(Instant.now(), stage, message));
    }

    public String getId() { return id; }
    public String getRequester() { return requester; }
    public String getEmail() { return email; }
    public String getSubject() { return subject; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public ApprovalState getState() { return state; }
    public void setState(ApprovalState state) { this.state = state; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public List<HistoryEntry> getHistory() { return List.copyOf(history); }
}
