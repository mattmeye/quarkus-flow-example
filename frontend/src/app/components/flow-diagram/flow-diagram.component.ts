import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { ApprovalRequest, ApprovalState } from '../../models/approval.model';

type NodeId =
  | ApprovalState
  | 'CONFIRM_GATE'
  | 'GROUP1_GATE'
  | 'GROUP2_GATE';

interface Node {
  id: NodeId;
  label: string;
  x: number;
  y: number;
  kind: 'state' | 'gate' | 'terminal';
}

interface Edge {
  from: NodeId;
  to: NodeId;
  label?: string;
}

@Component({
  selector: 'app-flow-diagram',
  standalone: true,
  imports: [CommonModule],
  template: `
    <svg viewBox="0 0 1100 260" preserveAspectRatio="xMidYMid meet" class="flow">
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5"
                markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" fill="#94a3b8" />
        </marker>
      </defs>

      <g *ngFor="let e of edges">
        <line [attr.x1]="nodeX(e.from) + nodeW(e.from)/2"
              [attr.y1]="nodeY(e.from)"
              [attr.x2]="nodeX(e.to) - nodeW(e.to)/2"
              [attr.y2]="nodeY(e.to)"
              stroke="#475569" stroke-width="2"
              marker-end="url(#arrow)" />
        <text *ngIf="e.label"
              [attr.x]="(nodeX(e.from) + nodeX(e.to))/2"
              [attr.y]="(nodeY(e.from) + nodeY(e.to))/2 - 6"
              text-anchor="middle" fill="#94a3b8" font-size="11">{{ e.label }}</text>
      </g>

      <g *ngFor="let n of nodes" [attr.transform]="'translate(' + n.x + ',' + n.y + ')'">
        <rect *ngIf="n.kind !== 'gate'"
              [attr.x]="-nodeW(n.id)/2" y="-22"
              [attr.width]="nodeW(n.id)" height="44" rx="8"
              [attr.fill]="fillFor(n)"
              [attr.stroke]="strokeFor(n)"
              stroke-width="2"
              [class.pulse]="isActive(n)" />
        <polygon *ngIf="n.kind === 'gate'"
                 [attr.points]="diamondPoints(nodeW(n.id), 44)"
                 [attr.fill]="fillFor(n)"
                 [attr.stroke]="strokeFor(n)"
                 stroke-width="2" />
        <text x="0" y="4" text-anchor="middle"
              [attr.fill]="textColor(n)"
              font-size="12" font-weight="700">{{ n.label }}</text>
      </g>
    </svg>
  `,
  styles: [`
    .flow { width: 100%; height: 260px; background: #0b1220; border-radius: 8px; }
    rect, polygon { transition: fill 0.4s, stroke 0.4s; }
    .pulse { animation: pulse 1.4s ease-in-out infinite; }
    @keyframes pulse {
      0%, 100% { filter: drop-shadow(0 0 0 rgba(56,189,248,0.0)); }
      50%      { filter: drop-shadow(0 0 12px rgba(56,189,248,0.9)); }
    }
  `]
})
export class FlowDiagramComponent {
  @Input() request: ApprovalRequest | null = null;

  nodes: Node[] = [
    { id: 'AWAITING_CONFIRMATION',    label: 'Email + Terms',    x:  90, y: 130, kind: 'state' },
    { id: 'CONFIRM_GATE',             label: 'OK?',              x: 220, y: 130, kind: 'gate'  },
    { id: 'SUBMITTED',                label: 'Submitted',        x: 340, y: 130, kind: 'state' },
    { id: 'AWAITING_GROUP1_APPROVAL', label: 'Group 1 review',   x: 500, y: 130, kind: 'state' },
    { id: 'GROUP1_GATE',              label: 'G1 ?',             x: 640, y: 130, kind: 'gate'  },
    { id: 'AWAITING_GROUP2_APPROVAL', label: 'Group 2 review',   x: 780, y: 130, kind: 'state' },
    { id: 'GROUP2_GATE',              label: 'G2 ?',             x: 910, y: 130, kind: 'gate'  },
    { id: 'APPROVED',                 label: 'Approved',         x:1020, y:  60, kind: 'terminal' },
    { id: 'REJECTED',                 label: 'Rejected',         x:1020, y: 200, kind: 'terminal' }
  ];

  edges: Edge[] = [
    { from: 'AWAITING_CONFIRMATION', to: 'CONFIRM_GATE' },
    { from: 'CONFIRM_GATE', to: 'SUBMITTED', label: 'confirm' },
    { from: 'CONFIRM_GATE', to: 'REJECTED', label: 'cancel' },
    { from: 'SUBMITTED', to: 'AWAITING_GROUP1_APPROVAL' },
    { from: 'AWAITING_GROUP1_APPROVAL', to: 'GROUP1_GATE' },
    { from: 'GROUP1_GATE', to: 'AWAITING_GROUP2_APPROVAL', label: 'approve' },
    { from: 'GROUP1_GATE', to: 'REJECTED', label: 'reject' },
    { from: 'AWAITING_GROUP2_APPROVAL', to: 'GROUP2_GATE' },
    { from: 'GROUP2_GATE', to: 'APPROVED', label: 'approve' },
    { from: 'GROUP2_GATE', to: 'REJECTED', label: 'reject' }
  ];

  nodeW(id: NodeId): number {
    if (id === 'CONFIRM_GATE' || id === 'GROUP1_GATE' || id === 'GROUP2_GATE') return 56;
    if (id === 'APPROVED' || id === 'REJECTED') return 110;
    return 150;
  }

  nodeX(id: NodeId): number { return this.nodes.find(n => n.id === id)!.x; }
  nodeY(id: NodeId): number { return this.nodes.find(n => n.id === id)!.y; }

  diamondPoints(w: number, h: number): string {
    return `0,${-h/2} ${w/2},0 0,${h/2} ${-w/2},0`;
  }

  isActive(n: Node): boolean {
    if (!this.request) return false;
    if (n.kind === 'gate') return false;
    return this.request.state === n.id;
  }

  private emailConfirmed(): boolean {
    return !!this.request?.tasks.some(t => t.type === 'CONFIRMATION' && t.outcome === 'CONFIRMED');
  }

  private groupDecision(group: 'GROUP_1' | 'GROUP_2'): string | null {
    const t = this.request?.tasks.find(
      x => x.type === 'APPROVAL' && x.assigneeGroup === group && x.outcome != null
    );
    return t?.outcome ?? null;
  }

  private hasReached(n: Node): boolean {
    if (!this.request) return false;
    const s = this.request.state;
    const order: ApprovalState[] = [
      'AWAITING_CONFIRMATION',
      'SUBMITTED',
      'AWAITING_GROUP1_APPROVAL',
      'AWAITING_GROUP2_APPROVAL',
      'APPROVED'
    ];
    const confirmed = this.emailConfirmed();
    const g1 = this.groupDecision('GROUP_1');
    const g2 = this.groupDecision('GROUP_2');
    if (n.kind === 'gate') {
      if (n.id === 'CONFIRM_GATE') return confirmed || s === 'REJECTED';
      if (n.id === 'GROUP1_GATE') return !!g1;
      if (n.id === 'GROUP2_GATE') return !!g2;
      return false;
    }
    if (n.id === 'REJECTED') return s === 'REJECTED';
    if (s === 'REJECTED') {
      // mark whichever states *did* run before rejection as reached
      if (n.id === 'AWAITING_CONFIRMATION') return true;
      if (n.id === 'SUBMITTED') return confirmed;
      if (n.id === 'AWAITING_GROUP1_APPROVAL') return confirmed;
      if (n.id === 'AWAITING_GROUP2_APPROVAL') return g1 === 'APPROVED';
      return false;
    }
    const idx = order.indexOf(n.id as ApprovalState);
    const cur = order.indexOf(s);
    return idx >= 0 && cur >= idx;
  }

  fillFor(n: Node): string {
    if (this.isActive(n)) return '#0ea5e9';
    if (n.id === 'APPROVED' && this.request?.state === 'APPROVED') return '#16a34a';
    if (n.id === 'REJECTED' && this.request?.state === 'REJECTED') return '#dc2626';
    if (this.hasReached(n)) return '#1e3a8a';
    return '#1e293b';
  }

  strokeFor(n: Node): string {
    if (this.isActive(n)) return '#38bdf8';
    if (n.id === 'APPROVED' && this.request?.state === 'APPROVED') return '#22c55e';
    if (n.id === 'REJECTED' && this.request?.state === 'REJECTED') return '#ef4444';
    return '#334155';
  }

  textColor(n: Node): string {
    return this.isActive(n) || this.hasReached(n) ? '#f8fafc' : '#94a3b8';
  }
}
