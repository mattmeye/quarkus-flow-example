import { CommonModule, DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApprovalService } from '../../services/approval.service';
import { ApprovalEventsService } from '../../services/approval-events.service';
import { ApprovalRequest, stateBadgeClass, stateLabel } from '../../models/approval.model';
import { RequestFormComponent } from '../request-form/request-form.component';

@Component({
  selector: 'app-request-list',
  standalone: true,
  imports: [CommonModule, RouterLink, DatePipe, RequestFormComponent],
  template: `
    <app-request-form (created)="onCreated($event)" />

    <div class="card">
      <div class="row" style="margin-bottom: 0.8rem;">
        <h3 style="margin:0;">Requests</h3>
        <span class="spacer"></span>
        <span style="color: var(--fg-muted); font-size: 12px;">
          {{ requests().length }} total · live via WebSocket
        </span>
      </div>

      <div *ngIf="requests().length === 0" style="color: var(--fg-muted); padding: 1rem;">
        No requests yet. Submit one above.
      </div>

      <table *ngIf="requests().length > 0">
        <thead>
          <tr>
            <th>Subject</th><th>Requester</th><th>Email</th><th>State</th>
            <th>Open tasks</th><th>Outcome</th><th>Created</th><th></th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let r of requests()" [routerLink]="['/requests', r.id]">
            <td>{{ r.subject }}</td>
            <td>{{ r.requester }}</td>
            <td>{{ r.email }}</td>
            <td><span [class]="stateClass(r.state)">{{ label(r.state) }}</span></td>
            <td>{{ openCount(r) }}</td>
            <td>{{ r.outcome ?? '—' }}</td>
            <td>{{ r.createdAt | date:'short' }}</td>
            <td><a [routerLink]="['/requests', r.id]">Open →</a></td>
          </tr>
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: 0.55rem 0.6rem; border-bottom: 1px solid var(--border); }
    th { font-size: 11px; text-transform: uppercase; color: var(--fg-muted); }
    tbody tr { cursor: pointer; }
    tbody tr:hover { background: var(--bg-elev-2); }
    a { color: var(--accent); text-decoration: none; }
  `]
})
export class RequestListComponent implements OnInit, OnDestroy {
  requests = signal<ApprovalRequest[]>([]);
  private sub?: Subscription;

  constructor(
    private api: ApprovalService,
    private events: ApprovalEventsService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.reload();
    this.sub = this.events.events$.subscribe(() => this.reload());
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  reload(): void {
    this.api.listRequests().subscribe({
      next: r => this.requests.set(r),
      error: err => console.error(err)
    });
  }

  openCount(r: ApprovalRequest): number {
    return r.tasks.filter(t => t.status === 'PENDING').length;
  }

  onCreated(r: ApprovalRequest): void {
    this.reload();
    this.router.navigate(['/requests', r.id]);
  }

  stateClass = stateBadgeClass;
  label = stateLabel;
}
