import { CommonModule, DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApprovalService } from '../../services/approval.service';
import { ApprovalEventsService } from '../../services/approval-events.service';
import {
  ApprovalRequest, HumanTask, groupLabel, pendingTask,
  stateBadgeClass, stateLabel
} from '../../models/approval.model';
import { FlowDiagramComponent } from '../flow-diagram/flow-diagram.component';
import { TaskPanelComponent } from '../task-panel/task-panel.component';

@Component({
  selector: 'app-request-detail',
  standalone: true,
  imports: [CommonModule, RouterLink, DatePipe, FlowDiagramComponent, TaskPanelComponent],
  template: `
    <a routerLink="/" class="back">&larr; Back to requests</a>

    <ng-container *ngIf="request() as r">
      <div class="card">
        <div class="row">
          <h2 style="margin: 0;">{{ r.subject }}</h2>
          <span [class]="stateClass(r.state)">{{ label(r.state) }}</span>
          <span class="spacer"></span>
          <span class="meta">{{ r.createdAt | date:'medium' }}</span>
        </div>
        <p class="meta" style="margin: 0.5rem 0 0;">
          Requested by <strong>{{ r.requester }}</strong> &lt;{{ r.email }}&gt;
        </p>
        <p *ngIf="r.description" style="margin: 0.7rem 0 0;">{{ r.description }}</p>
      </div>

      <div class="card">
        <h3 style="margin-top: 0;">Workflow</h3>
        <app-flow-diagram [request]="r" />
      </div>

      <app-task-panel *ngIf="pending() as t"
                      [task]="t"
                      (completed)="reload()" />

      <div class="card">
        <div class="row" style="margin-bottom: 0.6rem;">
          <h3 style="margin: 0;">Tasks</h3>
          <span class="meta">generic /api/tasks API · {{ r.tasks.length }} total</span>
        </div>
        <table>
          <thead>
            <tr>
              <th>#</th><th>Type</th><th>Assignee</th><th>Status</th>
              <th>Outcome</th><th>Actor</th><th>Completed</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let t of r.tasks; let i = index">
              <td>{{ i + 1 }}</td>
              <td><code>{{ t.type }}</code></td>
              <td>{{ groupLabel(t.assigneeGroup) }}</td>
              <td><span [class]="taskStatusClass(t)">{{ t.status }}</span></td>
              <td>{{ t.outcome ?? '—' }}</td>
              <td>{{ t.actor ?? '—' }}</td>
              <td>{{ t.completedAt ? (t.completedAt | date:'mediumTime') : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="card">
        <h3 style="margin-top: 0;">History</h3>
        <ul class="history">
          <li *ngFor="let h of r.history">
            <span class="ts">{{ h.at | date:'mediumTime' }}</span>
            <strong>{{ h.stage }}</strong>
            <span>{{ h.message }}</span>
          </li>
        </ul>
      </div>
    </ng-container>

    <div *ngIf="!request() && !error()" class="card">Loading…</div>
    <div *ngIf="error()" class="card" style="color: var(--red);">{{ error() }}</div>
  `,
  styles: [`
    .back { display: inline-block; margin-bottom: 1rem; color: var(--accent); text-decoration: none; }
    .meta { color: var(--fg-muted); font-size: 12px; }
    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: 0.4rem 0.6rem; border-bottom: 1px solid var(--border); }
    th { color: var(--fg-muted); font-weight: 600; font-size: 11px; text-transform: uppercase; }
    code { background: var(--bg-elev-2); padding: 1px 4px; border-radius: 3px; font-size: 11px; }
    .history { list-style: none; padding: 0; margin: 0; }
    .history li {
      display: grid; grid-template-columns: 110px 220px 1fr; gap: 0.6rem;
      padding: 0.4rem 0; border-bottom: 1px dashed var(--border);
      font-size: 13px;
    }
    .history .ts { color: var(--fg-muted); }
  `]
})
export class RequestDetailComponent implements OnInit, OnDestroy {
  request = signal<ApprovalRequest | null>(null);
  error = signal<string | null>(null);
  pending = computed(() => {
    const r = this.request();
    return r ? pendingTask(r) : undefined;
  });
  private id!: string;
  private sub?: Subscription;

  constructor(
    private route: ActivatedRoute,
    private api: ApprovalService,
    private events: ApprovalEventsService
  ) {}

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id')!;
    this.reload();
    this.sub = this.events.events$.subscribe(ev => {
      if (ev.requestId === this.id) {
        this.reload();
      }
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  reload(): void {
    this.api.getRequest(this.id).subscribe({
      next: r => { this.request.set(r); this.error.set(null); },
      error: err => this.error.set(err?.message ?? 'Failed to load request')
    });
  }

  taskStatusClass(t: HumanTask): string {
    return t.status === 'COMPLETED' && t.outcome === 'APPROVED' ? 'badge approved'
         : t.status === 'COMPLETED' && t.outcome === 'CONFIRMED' ? 'badge approved'
         : t.status === 'COMPLETED' && t.outcome === 'REJECTED' ? 'badge rejected'
         : t.status === 'CANCELLED' ? 'badge rejected'
         : t.status === 'PENDING' ? 'badge awaiting1'
         : 'badge submitted';
  }

  stateClass = stateBadgeClass;
  label = stateLabel;
  groupLabel = groupLabel;
}
