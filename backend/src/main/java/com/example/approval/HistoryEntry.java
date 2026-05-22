package com.example.approval;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Per-request audit entry. Persistent — survives JVM restarts via the
 * configured JPA datasource.
 */
@Entity
@Table(name = "approval_history")
public class HistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    @JsonIgnore
    private ApprovalRequest request;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Column(name = "stage", nullable = false, length = 64)
    private String stage;

    @Column(name = "message", length = 1024)
    private String message;

    protected HistoryEntry() {}

    public HistoryEntry(ApprovalRequest request, String stage, String message) {
        this.request = request;
        this.at = Instant.now();
        this.stage = stage;
        this.message = message;
    }

    public Long getId() { return id; }
    public ApprovalRequest getRequest() { return request; }
    public Instant getAt() { return at; }
    public String getStage() { return stage; }
    public String getMessage() { return message; }

    /** Accessors with the legacy short-form names so existing JSON payloads stay stable. */
    public Instant at() { return at; }
    public String stage() { return stage; }
    public String message() { return message; }
}
