import { CommonModule, DatePipe } from '@angular/common';
import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HumanTask, groupLabel } from '../../models/approval.model';
import { ApprovalService } from '../../services/approval.service';

/**
 * Renders an open task and drives it to completion. The form layout is
 * driven by the task's type, but the submit/cancel calls always go through
 * the same two endpoints (/api/tasks/{id}/complete and .../cancel) - so
 * adding new task types in the workflow requires no new REST routes.
 */
@Component({
  selector: 'app-task-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, DatePipe],
  template: `
    <div class="card">
      <div class="row">
        <h3 style="margin: 0;">{{ task.name }}</h3>
        <span class="meta">type: <code>{{ task.type }}</code></span>
        <span class="meta">assignee: <code>{{ groupLabel(task.assigneeGroup) }}</code></span>
        <span class="spacer"></span>
        <span class="meta">created {{ task.createdAt | date:'mediumTime' }}</span>
      </div>

      <!-- CONFIRMATION task -->
      <ng-container *ngIf="task.type === 'CONFIRMATION'">
        <div class="mailbox">
          <div class="hdr">
            <strong>From:</strong> noreply&#64;approval-demo &nbsp;
            <strong>Subject:</strong> Please confirm your approval request
          </div>
          <p>Click below to confirm your email address and accept the
             terms &amp; conditions. The workflow stays suspended until you do.</p>
          <p class="small">
            (Demo: in production the token is delivered by email only.
             Here the token is exposed via the task context.)
          </p>
        </div>
        <div class="terms">
          <label class="check">
            <input type="checkbox" [(ngModel)]="termsAccepted" />
            <span>I accept the <a href="#" (click)="$event.preventDefault()">Terms &amp; Conditions</a>.</span>
          </label>
        </div>
        <div class="row" style="margin-top: 0.8rem;">
          <div style="flex:1; min-width:200px;">
            <label>Actor</label>
            <input [(ngModel)]="actor" placeholder="your.name@example.com" />
          </div>
          <button class="success"
                  (click)="confirm()"
                  [disabled]="!actor() || !termsAccepted() || submitting()">
            ✓ Confirm
          </button>
          <button class="secondary" (click)="cancel()" [disabled]="!actor() || submitting()">
            Cancel
          </button>
          <span class="meta">token: <code>{{ contextToken() }}</code></span>
        </div>
      </ng-container>

      <!-- APPROVAL task -->
      <ng-container *ngIf="task.type === 'APPROVAL'">
        <p class="small">
          {{ groupLabel(task.assigneeGroup) }} must decide on this request.
        </p>
        <div class="row">
          <div style="flex:1; min-width:200px;">
            <label>Actor</label>
            <input [(ngModel)]="actor" placeholder="your.name@example.com" />
          </div>
          <button class="success" (click)="decide('APPROVED')" [disabled]="!actor() || submitting()">
            Approve
          </button>
          <button class="danger" (click)="decide('REJECTED')" [disabled]="!actor() || submitting()">
            Reject
          </button>
        </div>
      </ng-container>

      <div *ngIf="error()" class="err">{{ error() }}</div>
    </div>
  `,
  styles: [`
    .meta { color: var(--fg-muted); font-size: 12px; }
    code { background: var(--bg-elev-2); padding: 1px 4px; border-radius: 3px; font-size: 11px; }
    .mailbox {
      background: #0b1220; border: 1px solid var(--border);
      border-radius: 8px; padding: 0.8rem 1rem; margin-top: 0.8rem;
      font-family: ui-monospace, monospace; font-size: 13px;
    }
    .mailbox .hdr { color: var(--fg-muted); margin-bottom: 0.3rem; }
    .small { font-size: 12px; color: var(--fg-muted); }
    .check { display: flex; align-items: flex-start; gap: 0.5rem; font-size: 13px; margin-top: 0.6rem; }
    .check input { width: auto; margin-top: 3px; }
    .check a { color: var(--accent); }
    .err {
      margin-top: 0.6rem; color: #fecaca; background: #7f1d1d;
      padding: 0.4rem 0.6rem; border-radius: 6px; font-size: 12px;
    }
  `]
})
export class TaskPanelComponent {
  @Input({ required: true }) task!: HumanTask;
  @Output() completed = new EventEmitter<HumanTask>();

  actor = signal('');
  termsAccepted = signal(false);
  submitting = signal(false);
  error = signal<string | null>(null);

  groupLabel = groupLabel;

  constructor(private api: ApprovalService) {}

  contextToken(): string {
    const v = this.task.context['confirmationToken'];
    return typeof v === 'string' ? v : '';
  }

  confirm(): void {
    this.submit('CONFIRMED', {
      token: this.contextToken(),
      termsAccepted: this.termsAccepted()
    });
  }

  decide(outcome: 'APPROVED' | 'REJECTED'): void {
    this.submit(outcome, {});
  }

  private submit(outcome: string, payload: Record<string, unknown>): void {
    this.error.set(null);
    this.submitting.set(true);
    this.api.completeTask(this.task.id, this.actor(), outcome, payload).subscribe({
      next: t => { this.submitting.set(false); this.completed.emit(t); },
      error: err => {
        this.error.set(err?.error?.error ?? 'Task could not be completed');
        this.submitting.set(false);
      }
    });
  }

  cancel(): void {
    this.error.set(null);
    this.submitting.set(true);
    this.api.cancelTask(this.task.id, this.actor(), 'Cancelled via UI').subscribe({
      next: t => { this.submitting.set(false); this.completed.emit(t); },
      error: err => {
        this.error.set(err?.error?.error ?? 'Task could not be cancelled');
        this.submitting.set(false);
      }
    });
  }
}
