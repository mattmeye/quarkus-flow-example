import { CommonModule, DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApprovalService } from '../../services/approval.service';
import { ApprovalEventsService } from '../../services/approval-events.service';
import {
  ApprovalRequest, CreatedResponse, stateBadgeClass, stateLabel
} from '../../models/approval.model';
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
            <th>Group 1</th><th>Group 2</th><th>Created</th><th></th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let r of requests()" [routerLink]="['/requests', r.id]">
            <td>{{ r.subject }}</td>
            <td>{{ r.requester }}</td>
            <td>{{ r.email }} <span *ngIf="r.emailConfirmed" title="email confirmed">✓</span></td>
            <td><span [class]="stateClass(r.state)">{{ label(r.state) }}</span></td>
            <td>{{ r.group1Decision ?? '—' }}</td>
            <td>{{ r.group2Decision ?? '—' }}</td>
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
    this.api.list().subscribe({
      next: r => this.requests.set(r),
      error: err => console.error(err)
    });
  }

  onCreated(r: CreatedResponse): void {
    this.reload();
    // jump straight into the detail view so the requester can complete the
    // email/terms confirmation step
    this.router.navigate(['/requests', r.request.id]);
  }

  stateClass = stateBadgeClass;
  label = stateLabel;
}
