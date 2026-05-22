import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ApprovalRequest, ApprovalState } from '../../models/approval';

type NodeId =
  | ApprovalState
  | 'CONFIRM_GATE'
  | 'GROUP1_GATE'
  | 'GROUP2_GATE';

interface FlowNode {
  id: NodeId;
  label: string;
  x: number;
  y: number;
  kind: 'state' | 'gate' | 'terminal';
}

interface FlowEdge {
  from: NodeId;
  to: NodeId;
  label?: string;
}

@Component({
  selector: 'app-flow-diagram',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg viewBox="0 0 1100 260" preserveAspectRatio="xMidYMid meet"
         class="w-full h-[260px] rounded-lg bg-[#0b1220]">
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5"
                markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M0,0 L10,5 L0,10 z" fill="#94a3b8" />
        </marker>
      </defs>

      @for (e of edges; track e.from + '->' + e.to) {
        <g>
          <line [attr.x1]="nodeX(e.from) + nodeW(e.from)/2"
                [attr.y1]="nodeY(e.from)"
                [attr.x2]="nodeX(e.to) - nodeW(e.to)/2"
                [attr.y2]="nodeY(e.to)"
                stroke="#475569" stroke-width="2"
                marker-end="url(#arrow)" />
          @if (e.label) {
            <text [attr.x]="(nodeX(e.from) + nodeX(e.to))/2"
                  [attr.y]="(nodeY(e.from) + nodeY(e.to))/2 - 6"
                  text-anchor="middle" fill="#94a3b8" font-size="11">{{ e.label }}</text>
          }
        </g>
      }

      @for (n of nodes; track n.id) {
        <g [attr.transform]="'translate(' + n.x + ',' + n.y + ')'">
          @if (n.kind !== 'gate') {
            <rect [attr.x]="-nodeW(n.id)/2" y="-22"
                  [attr.width]="nodeW(n.id)" height="44" rx="8"
                  [attr.fill]="fillFor(n)"
                  [attr.stroke]="strokeFor(n)"
                  stroke-width="2"
                  [class.pulse]="isActive(n)" />
          } @else {
            <polygon [attr.points]="diamondPoints(nodeW(n.id), 44)"
                     [attr.fill]="fillFor(n)"
                     [attr.stroke]="strokeFor(n)"
                     stroke-width="2" />
          }
          <text x="0" y="4" text-anchor="middle"
                [attr.fill]="textColor(n)"
                font-size="12" font-weight="700">{{ n.label }}</text>
        </g>
      }
    </svg>
  `,
  styles: [`
    :host { display: block; }
    rect, polygon { transition: fill 0.4s, stroke 0.4s; }
    .pulse { animation: pulse 1.4s ease-in-out infinite; }
    @keyframes pulse {
      0%, 100% { filter: drop-shadow(0 0 0 rgba(56,189,248,0.0)); }
      50%      { filter: drop-shadow(0 0 12px rgba(56,189,248,0.9)); }
    }
  `]
})
export class FlowDiagram {
  readonly request = input<ApprovalRequest | null>(null);

  protected readonly emailConfirmed = computed(() => {
    const r = this.request();
    return r?.tasks.some(t =>
      t.type === 'CONFIRMATION' && t.status === 'COMPLETED' && t.outcome === 'CONFIRMED'
    ) ?? false;
  });
  protected readonly group1Decision = computed<string | null>(() => {
    const r = this.request();
    return r?.tasks.find(t =>
      t.type === 'APPROVAL' && t.assigneeGroup === 'GROUP_1' && t.status === 'COMPLETED'
    )?.outcome ?? null;
  });
  protected readonly group2Decision = computed<string | null>(() => {
    const r = this.request();
    return r?.tasks.find(t =>
      t.type === 'APPROVAL' && t.assigneeGroup === 'GROUP_2' && t.status === 'COMPLETED'
    )?.outcome ?? null;
  });

  protected readonly nodes: FlowNode[] = [
    { id: 'AWAITING_CONFIRMATION',    label: 'Email + Terms',  x:  90, y: 130, kind: 'state' },
    { id: 'CONFIRM_GATE',             label: 'OK?',            x: 220, y: 130, kind: 'gate'  },
    { id: 'SUBMITTED',                label: 'Submitted',      x: 340, y: 130, kind: 'state' },
    { id: 'AWAITING_GROUP1_APPROVAL', label: 'Group 1 review', x: 500, y: 130, kind: 'state' },
    { id: 'GROUP1_GATE',              label: 'G1 ?',           x: 640, y: 130, kind: 'gate'  },
    { id: 'AWAITING_GROUP2_APPROVAL', label: 'Group 2 review', x: 780, y: 130, kind: 'state' },
    { id: 'GROUP2_GATE',              label: 'G2 ?',           x: 910, y: 130, kind: 'gate'  },
    { id: 'APPROVED',                 label: 'Approved',       x:1020, y:  60, kind: 'terminal' },
    { id: 'REJECTED',                 label: 'Rejected',       x:1020, y: 200, kind: 'terminal' }
  ];

  protected readonly edges: FlowEdge[] = [
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

  protected nodeW(id: NodeId): number {
    if (id === 'CONFIRM_GATE' || id === 'GROUP1_GATE' || id === 'GROUP2_GATE') return 56;
    if (id === 'APPROVED' || id === 'REJECTED') return 110;
    return 150;
  }
  protected nodeX(id: NodeId): number { return this.nodes.find(n => n.id === id)!.x; }
  protected nodeY(id: NodeId): number { return this.nodes.find(n => n.id === id)!.y; }

  protected diamondPoints(w: number, h: number): string {
    return `0,${-h/2} ${w/2},0 0,${h/2} ${-w/2},0`;
  }

  protected isActive(n: FlowNode): boolean {
    const r = this.request();
    if (!r) return false;
    if (n.kind === 'gate') return false;
    return r.state === n.id;
  }

  private hasReached(n: FlowNode): boolean {
    const r = this.request();
    if (!r) return false;
    const s = r.state;
    const order: ApprovalState[] = [
      'AWAITING_CONFIRMATION',
      'SUBMITTED',
      'AWAITING_GROUP1_APPROVAL',
      'AWAITING_GROUP2_APPROVAL',
      'APPROVED'
    ];
    if (n.kind === 'gate') {
      if (n.id === 'CONFIRM_GATE') return this.emailConfirmed() || s === 'REJECTED';
      if (n.id === 'GROUP1_GATE')  return !!this.group1Decision();
      if (n.id === 'GROUP2_GATE')  return !!this.group2Decision();
      return false;
    }
    if (n.id === 'REJECTED') return s === 'REJECTED';
    if (s === 'REJECTED') {
      if (n.id === 'AWAITING_CONFIRMATION') return true;
      if (n.id === 'SUBMITTED') return this.emailConfirmed();
      if (n.id === 'AWAITING_GROUP1_APPROVAL') return this.emailConfirmed();
      if (n.id === 'AWAITING_GROUP2_APPROVAL') return this.group1Decision() === 'APPROVED';
      return false;
    }
    const idx = order.indexOf(n.id as ApprovalState);
    const cur = order.indexOf(s);
    return idx >= 0 && cur >= idx;
  }

  protected fillFor(n: FlowNode): string {
    const r = this.request();
    if (this.isActive(n)) return '#0ea5e9';
    if (n.id === 'APPROVED' && r?.state === 'APPROVED') return '#16a34a';
    if (n.id === 'REJECTED' && r?.state === 'REJECTED') return '#dc2626';
    if (this.hasReached(n)) return '#1e3a8a';
    return '#1e293b';
  }

  protected strokeFor(n: FlowNode): string {
    const r = this.request();
    if (this.isActive(n)) return '#38bdf8';
    if (n.id === 'APPROVED' && r?.state === 'APPROVED') return '#22c55e';
    if (n.id === 'REJECTED' && r?.state === 'REJECTED') return '#ef4444';
    return '#334155';
  }

  protected textColor(n: FlowNode): string {
    return this.isActive(n) || this.hasReached(n) ? '#f8fafc' : '#94a3b8';
  }
}
