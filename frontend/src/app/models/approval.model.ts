export type ApprovalState =
  | 'AWAITING_CONFIRMATION'
  | 'SUBMITTED'
  | 'AWAITING_GROUP1_APPROVAL'
  | 'AWAITING_GROUP2_APPROVAL'
  | 'APPROVED'
  | 'REJECTED';

export type Decision = 'APPROVED' | 'REJECTED';

export interface HistoryEntry {
  at: string;
  stage: string;
  message: string;
}

export interface ApprovalRequest {
  id: string;
  requester: string;
  email: string;
  subject: string;
  description: string;
  state: ApprovalState;
  emailConfirmed: boolean;
  termsAccepted: boolean;
  confirmationToken: string | null;
  group1Decision: Decision | null;
  group1Approver: string | null;
  group2Decision: Decision | null;
  group2Approver: string | null;
  createdAt: string;
  confirmedAt: string | null;
  history: HistoryEntry[];
}

export interface CreatedResponse {
  request: ApprovalRequest;
  confirmationLink: string;
}

export interface ApprovalEvent {
  type: 'REQUEST_CREATED' | 'STATE_CHANGED';
  requestId: string;
  state: ApprovalState;
  message: string;
  at: string;
}

export function stateBadgeClass(s: ApprovalState): string {
  switch (s) {
    case 'AWAITING_CONFIRMATION': return 'badge confirm';
    case 'SUBMITTED': return 'badge submitted';
    case 'AWAITING_GROUP1_APPROVAL': return 'badge awaiting1';
    case 'AWAITING_GROUP2_APPROVAL': return 'badge awaiting2';
    case 'APPROVED': return 'badge approved';
    case 'REJECTED': return 'badge rejected';
  }
}

export function stateLabel(s: ApprovalState): string {
  switch (s) {
    case 'AWAITING_CONFIRMATION': return 'Awaiting confirmation';
    case 'SUBMITTED': return 'Submitted';
    case 'AWAITING_GROUP1_APPROVAL': return 'Awaiting Group 1';
    case 'AWAITING_GROUP2_APPROVAL': return 'Awaiting Group 2';
    case 'APPROVED': return 'Approved';
    case 'REJECTED': return 'Rejected';
  }
}
