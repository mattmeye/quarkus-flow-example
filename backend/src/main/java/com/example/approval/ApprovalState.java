package com.example.approval;

/**
 * Spiegelt 1:1 die States aus approval.sw.json wider.
 * Die Reihenfolge bestimmt die Darstellung im Flow-Diagramm der UI.
 */
public enum ApprovalState {
    AWAITING_CONFIRMATION,
    SUBMITTED,
    AWAITING_GROUP1_APPROVAL,
    AWAITING_GROUP2_APPROVAL,
    APPROVED,
    REJECTED
}
