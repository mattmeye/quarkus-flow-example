import { CommonModule, DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApprovalService } from '../../services/approval.service';
import { ApprovalEventsService } from '../../services/approval-events.service';
import { AssigneeGroup, HumanTask, TaskStatus, groupLabel } from '../../models/approval.model';

@Component({
  selector: 'app-task-inbox',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, DatePipe],
  template: `
    <a routerLink="/" class="back">&larr; Requests</a>

    <div class="card">
      <div class="row" style="margin-bottom: 0.6rem;">
        <h2 style="margin: 0;">Task Inbox</h2>
        <span class="spacer"></span>
        <label class="filter">
          Group
          <select [(ngModel)]="groupFilter" (change)="reload()">
            <option [ngValue]="null">all</option>
            <option ngValue="REQUESTER">Requester</option>
            <option ngValue="GROUP_1">Group 1</option>
            <option ngValue="GROUP_2">Group 2</option>
          </select>
        </label>
        <label class="filter">
          Status
          <select [(ngModel)]="statusFilter" (change)="reload()">
            <option ngValue="PENDING">pending</option>
            <option ngValue="COMPLETED">completed</option>
            <option ngValue="CANCELLED">cancelled</option>
            <option ngValue="EXPIRED">expired</option>
            <option [ngValue]="null">all</option>
          </select>
        </label>
      </div>

      <p class="meta">
        Generic <code>GET /api/tasks</code>. {{ tasks().length }} matching.
      </p>

      <table *ngIf="tasks().length > 0">
        <thead>
          <tr>
            <th>Created</th><th>Type</th><th>Name</th><th>Assignee</th>
            <th>Due</th><th>Status</th><th>Request</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let t of tasks()" [routerLink]="['/requests', t.requestId]">
            <td>{{ t.createdAt | date:'mediumTime' }}</td>
            <td><code>{{ t.type }}</code></td>
            <td>{{ t.name }}</td>
            <td>{{ groupLabel(t.assigneeGroup) }}</td>
            <td>
              <span class="due">{{ t.dueAt | date:'mediumTime' }}</span>
              <span *ngIf="t.status === 'PENDING' && t.reminded" class="reminded" title="Reminder sent">⏰</span>
            </td>
            <td><span [class]="badgeFor(t)">{{ t.status }}</span></td>
            <td><a [routerLink]="['/requests', t.requestId]">{{ shortId(t.requestId) }}</a></td>
          </tr>
        </tbody>
      </table>

      <p *ngIf="tasks().length === 0" class="meta">No tasks matching the filter.</p>
    </div>
  `,
  styles: [`
    .back { display: inline-block; margin-bottom: 1rem; color: var(--accent); text-decoration: none; }
    .meta { color: var(--fg-muted); font-size: 12px; }
    .filter { font-size: 12px; color: var(--fg-muted); display: inline-flex; gap: 6px; align-items: center; margin-left: 1rem; }
    .filter select { width: auto; padding: 4px 8px; }
    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: 0.4rem 0.6rem; border-bottom: 1px solid var(--border); }
    th { color: var(--fg-muted); font-weight: 600; font-size: 11px; text-transform: uppercase; }
    tbody tr { cursor: pointer; }
    tbody tr:hover { background: var(--bg-elev-2); }
    code { background: var(--bg-elev-2); padding: 1px 4px; border-radius: 3px; font-size: 11px; }
    a { color: var(--accent); text-decoration: none; }
    .due { color: var(--fg-muted); font-size: 12px; }
    .reminded { margin-left: 0.4rem; font-size: 13px; }
  `]
})
export class TaskInboxComponent implements OnInit, OnDestroy {
  tasks = signal<HumanTask[]>([]);
  groupFilter: AssigneeGroup | null = null;
  statusFilter: TaskStatus | null = 'PENDING';
  private sub?: Subscription;

  constructor(private api: ApprovalService, private events: ApprovalEventsService) {}

  ngOnInit(): void {
    this.reload();
    this.sub = this.events.events$.subscribe(() => this.reload());
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  reload(): void {
    this.api.listTasks({
      group: this.groupFilter ?? undefined,
      status: this.statusFilter ?? undefined
    }).subscribe(t => this.tasks.set(t));
  }

  badgeFor(t: HumanTask): string {
    if (t.status === 'PENDING') return t.reminded ? 'badge awaiting2' : 'badge awaiting1';
    if (t.status === 'EXPIRED') return 'badge rejected';
    if (t.outcome === 'REJECTED' || t.status === 'CANCELLED') return 'badge rejected';
    return 'badge approved';
  }

  shortId(id: string): string { return id.slice(0, 8); }
  groupLabel = groupLabel;
}
