import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HumanTask, groupLabel } from '../../models/approval';
import { ApprovalService } from '../../services/approval';

/**
 * Renders an open task and drives it to completion. The form layout is
 * driven by the task's type, but the submit/cancel calls always go through
 * the same two endpoints (/api/tasks/{id}/complete and .../cancel) - so
 * adding new task types in the workflow requires no new REST routes.
 */
@Component({
  selector: 'app-task-panel',
  imports: [FormsModule, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="card">
      <div class="flex items-center gap-4 flex-wrap">
        <h3 class="m-0 text-base font-semibold">{{ task().name }}</h3>
        <span class="text-xs text-muted">
          type: <code class="bg-elev-2 rounded px-1 text-[11px]">{{ task().type }}</code>
        </span>
        <span class="text-xs text-muted">
          assignee: <code class="bg-elev-2 rounded px-1 text-[11px]">{{ groupLabel(task().assigneeGroup) }}</code>
        </span>
        <span class="ml-auto text-xs text-muted">
          created {{ task().createdAt | date:'mediumTime' }}
        </span>
      </div>

      @switch (task().type) {
        @case ('CONFIRMATION') {
          <div class="mt-3 rounded-lg border border-border bg-[#0b1220] px-4 py-3 font-mono text-[13px]">
            <div class="text-muted mb-1">
              <strong>From:</strong> noreply&#64;approval-demo
              &nbsp;<strong>Subject:</strong> Please confirm your approval request
            </div>
            <p class="m-0 mt-1">
              Click below to confirm your email address and accept the
              terms &amp; conditions. The workflow stays suspended until you do.
            </p>
            <p class="m-0 mt-2 text-xs text-muted">
              (Demo: in production the token is delivered by email only.
               Here the token is exposed via the task context.)
            </p>
          </div>

          <label class="mt-3 flex items-start gap-2 text-[13px] cursor-pointer">
            <input type="checkbox" class="mt-0.5"
                   [ngModel]="termsAccepted()"
                   (ngModelChange)="termsAccepted.set($event)" name="terms" />
            <span>I accept the
              <a href="#" class="link" (click)="$event.preventDefault()">Terms &amp; Conditions</a>.
            </span>
          </label>

          <div class="mt-3 flex items-end gap-3 flex-wrap">
            <div class="flex-1 min-w-[200px]">
              <label class="field-label">Actor</label>
              <input class="input" [ngModel]="actor()" (ngModelChange)="actor.set($event)"
                     name="actor" placeholder="your.name@example.com" />
            </div>
            <button class="btn-success"
                    (click)="confirm()"
                    [disabled]="!actor() || !termsAccepted() || submitting()">
              ✓ Confirm
            </button>
            <button class="btn-secondary" (click)="cancel()" [disabled]="!actor() || submitting()">
              Cancel
            </button>
            <span class="text-xs text-muted">
              token: <code class="bg-elev-2 rounded px-1 text-[11px]">{{ contextToken() }}</code>
            </span>
          </div>
        }
        @case ('APPROVAL') {
          <p class="text-xs text-muted mt-2">
            {{ groupLabel(task().assigneeGroup) }} must decide on this request.
          </p>
          <div class="mt-2 flex items-end gap-3 flex-wrap">
            <div class="flex-1 min-w-[200px]">
              <label class="field-label">Actor</label>
              <input class="input" [ngModel]="actor()" (ngModelChange)="actor.set($event)"
                     name="actor" placeholder="your.name@example.com" />
            </div>
            <button class="btn-success" (click)="decide('APPROVED')"
                    [disabled]="!actor() || submitting()">
              Approve
            </button>
            <button class="btn-danger" (click)="decide('REJECTED')"
                    [disabled]="!actor() || submitting()">
              Reject
            </button>
          </div>
        }
      }

      @if (error(); as e) {
        <div class="mt-3 px-3 py-2 rounded-md text-xs text-red-100 bg-red-900/70">{{ e }}</div>
      }
    </div>
  `
})
export class TaskPanel {
  readonly task = input.required<HumanTask>();
  readonly completed = output<HumanTask>();

  protected readonly actor = signal('');
  protected readonly termsAccepted = signal(false);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly contextToken = computed(() => {
    const v = this.task().context['confirmationToken'];
    return typeof v === 'string' ? v : '';
  });

  protected readonly groupLabel = groupLabel;

  private readonly api = inject(ApprovalService);

  protected confirm(): void {
    this.submit('CONFIRMED', {
      token: this.contextToken(),
      termsAccepted: this.termsAccepted()
    });
  }

  protected decide(outcome: 'APPROVED' | 'REJECTED'): void {
    this.submit(outcome, {});
  }

  protected cancel(): void {
    this.error.set(null);
    this.submitting.set(true);
    this.api.cancelTask(this.task().id, this.actor(), 'Cancelled via UI').subscribe({
      next: t => { this.submitting.set(false); this.completed.emit(t); },
      error: err => {
        this.error.set(err?.error?.error ?? 'Task could not be cancelled');
        this.submitting.set(false);
      }
    });
  }

  private submit(outcome: string, payload: Record<string, unknown>): void {
    this.error.set(null);
    this.submitting.set(true);
    this.api.completeTask(this.task().id, this.actor(), outcome, payload).subscribe({
      next: t => { this.submitting.set(false); this.completed.emit(t); },
      error: err => {
        this.error.set(err?.error?.error ?? 'Task could not be completed');
        this.submitting.set(false);
      }
    });
  }
}
