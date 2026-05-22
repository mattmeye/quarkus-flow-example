export type ApprovalState =
  | 'AWAITING_CONFIRMATION'
  | 'SUBMITTED'
  | 'AWAITING_GROUP1_APPROVAL'
  | 'AWAITING_GROUP2_APPROVAL'
  | 'APPROVED'
  | 'REJECTED';

export type TaskType = 'CONFIRMATION' | 'APPROVAL';
export type TaskStatus = 'PENDING' | 'COMPLETED' | 'CANCELLED';
export type AssigneeGroup = 'REQUESTER' | 'GROUP_1' | 'GROUP_2';

export interface HumanTask {
  id: string;
  requestId: string;
  type: TaskType;
  name: string;
  assigneeGroup: AssigneeGroup;
  status: TaskStatus;
  createdAt: string;
  completedAt: string | null;
  context: Record<string, unknown>;
  actor: string | null;
  outcome: string | null;
  payload: Record<string, unknown> | null;
}

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
  outcome: string | null;
  createdAt: string;
  tasks: HumanTask[];
  history: HistoryEntry[];
}

export interface ApprovalEvent {
  type: 'REQUEST_CREATED' | 'STATE_CHANGED' | 'TASK_CREATED' | 'TASK_COMPLETED';
  requestId: string;
  taskId: string | null;
  state: ApprovalState | null;
  message: string;
  at: string;
}

export function stateBadgeClass(s: ApprovalState): string {
  switch (s) {
    case 'AWAITING_CONFIRMATION':    return 'badge badge-confirm';
    case 'SUBMITTED':                return 'badge badge-submitted';
    case 'AWAITING_GROUP1_APPROVAL': return 'badge badge-await1';
    case 'AWAITING_GROUP2_APPROVAL': return 'badge badge-await2';
    case 'APPROVED':                 return 'badge badge-approved';
    case 'REJECTED':                 return 'badge badge-rejected';
  }
}

export function stateLabel(s: ApprovalState): string {
  switch (s) {
    case 'AWAITING_CONFIRMATION':    return 'Awaiting confirmation';
    case 'SUBMITTED':                return 'Submitted';
    case 'AWAITING_GROUP1_APPROVAL': return 'Awaiting Group 1';
    case 'AWAITING_GROUP2_APPROVAL': return 'Awaiting Group 2';
    case 'APPROVED':                 return 'Approved';
    case 'REJECTED':                 return 'Rejected';
  }
}

export function groupLabel(g: AssigneeGroup): string {
  switch (g) {
    case 'REQUESTER': return 'Requester';
    case 'GROUP_1':   return 'Approval Group 1';
    case 'GROUP_2':   return 'Approval Group 2';
  }
}

export function pendingTask(r: ApprovalRequest): HumanTask | undefined {
  return r.tasks.find(t => t.status === 'PENDING');
}
