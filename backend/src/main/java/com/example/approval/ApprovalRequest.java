package com.example.approval;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The request as the workflow sees it. Stage-specific data
 * (confirmation token, group decisions, approvers) is not stored here —
 * it lives on the {@link HumanTask} instances that the workflow creates
 * at each async step.
 *
 * Persisted via Hibernate ORM / Panache so the request, its history and
 * its tasks survive JVM restarts.
 */
@Entity
@Table(name = "approval_request")
public class ApprovalRequest {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "requester", nullable = false, length = 128)
    private String requester;

    @Column(name = "email", nullable = false, length = 256)
    private String email;

    @Column(name = "subject", nullable = false, length = 256)
    private String subject;

    @Column(name = "description", length = 4096)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private ApprovalState state;

    @Column(name = "outcome", length = 32)
    private String outcome;

    /**
     * Engine-side instance id of the running workflow. Captured by
     * {@code ApprovalResource} before {@code instance.start()} so REST
     * handlers and the scheduler can emit CloudEvents addressed to the
     * specific suspended workflow instance.
     */
    @Column(name = "workflow_instance_id", length = 64)
    private String workflowInstanceId;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("at ASC")
    private List<HistoryEntry> history = new ArrayList<>();

    protected ApprovalRequest() {}

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
        history.add(new HistoryEntry(this, stage, message));
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
    public String getWorkflowInstanceId() { return workflowInstanceId; }
    public void setWorkflowInstanceId(String workflowInstanceId) { this.workflowInstanceId = workflowInstanceId; }
    public List<HistoryEntry> getHistory() { return List.copyOf(history); }
}
