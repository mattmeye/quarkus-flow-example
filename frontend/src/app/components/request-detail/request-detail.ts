import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ApprovalService } from '../../services/approval';
import { ApprovalEventsService } from '../../services/approval-events';
import {
  ApprovalRequest, HumanTask, groupLabel, pendingTask,
  stateBadgeClass, stateLabel
} from '../../models/approval';
import { FlowDiagram } from '../flow-diagram/flow-diagram';
import { TaskPanel } from '../task-panel/task-panel';

@Component({
  selector: 'app-request-detail',
  imports: [RouterLink, DatePipe, FlowDiagram, TaskPanel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/" class="link inline-block mb-4">← Back to requests</a>

    @let r = request();
    @if (r) {
      <div class="card">
        <div class="flex items-center gap-3 flex-wrap">
          <h2 class="m-0 text-lg font-semibold">{{ r.subject }}</h2>
          <span [class]="stateClass(r.state)">{{ label(r.state) }}</span>
          <span class="ml-auto text-xs text-muted">{{ r.createdAt | date:'medium' }}</span>
        </div>
        <p class="text-xs text-muted m-0 mt-2">
          Requested by <strong>{{ r.requester }}</strong> &lt;{{ r.email }}&gt;
        </p>
        @if (r.description) {
          <p class="mt-3 m-0">{{ r.description }}</p>
        }
      </div>

      <div class="card">
        <h3 class="m-0 mb-2 text-base font-semibold">Workflow</h3>
        <app-flow-diagram [request]="r" />
      </div>

      @if (pending(); as t) {
        <app-task-panel [task]="t" (completed)="reload()" />
      }

      <div class="card">
        <div class="flex items-center mb-3">
          <h3 class="m-0 text-base font-semibold">Tasks</h3>
          <span class="ml-auto text-xs text-muted">
            generic /api/tasks API · {{ r.tasks.length }} total
          </span>
        </div>
        <div class="overflow-x-auto">
          <table class="w-full border-collapse">
            <thead>
              <tr>
                @for (h of ['#','Type','Assignee','Status','Outcome','Actor','Completed']; track h) {
                  <th class="text-left text-[11px] uppercase text-muted border-b border-border py-2 px-2">{{ h }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (t of r.tasks; track t.id; let i = $index) {
                <tr>
                  <td class="border-b border-border py-2 px-2">{{ i + 1 }}</td>
                  <td class="border-b border-border py-2 px-2">
                    <code class="bg-elev-2 rounded px-1 text-[11px]">{{ t.type }}</code>
                  </td>
                  <td class="border-b border-border py-2 px-2">{{ groupLabel(t.assigneeGroup) }}</td>
                  <td class="border-b border-border py-2 px-2">
                    <span [class]="taskStatusClass(t)">{{ t.status }}</span>
                  </td>
                  <td class="border-b border-border py-2 px-2">{{ t.outcome ?? '—' }}</td>
                  <td class="border-b border-border py-2 px-2">{{ t.actor ?? '—' }}</td>
                  <td class="border-b border-border py-2 px-2">
                    {{ t.completedAt ? (t.completedAt | date:'mediumTime') : '—' }}
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>

      <div class="card">
        <h3 class="m-0 mb-2 text-base font-semibold">History</h3>
        <ul class="list-none p-0 m-0">
          @for (h of r.history; track h.at + h.stage) {
            <li class="grid grid-cols-[110px_220px_1fr] gap-2 py-2 border-b border-dashed border-border text-[13px]">
              <span class="text-muted">{{ h.at | date:'mediumTime' }}</span>
              <strong>{{ h.stage }}</strong>
              <span>{{ h.message }}</span>
            </li>
          }
        </ul>
      </div>
    } @else if (error()) {
      <div class="card text-err">{{ error() }}</div>
    } @else {
      <div class="card">Loading…</div>
    }
  `
})
export class RequestDetail {
  /** Bound from the router (withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly request = signal<ApprovalRequest | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly pending = computed<HumanTask | undefined>(() => {
    const r = this.request();
    return r ? pendingTask(r) : undefined;
  });

  private readonly api = inject(ApprovalService);
  private readonly events = inject(ApprovalEventsService);

  constructor() {
    queueMicrotask(() => this.reload());
    this.events.events$
      .pipe(takeUntilDestroyed())
      .subscribe(ev => {
        if (ev.requestId === this.id()) this.reload();
      });
  }

  protected reload(): void {
    this.api.getRequest(this.id()).subscribe({
      next: r => { this.request.set(r); this.error.set(null); },
      error: err => this.error.set(err?.message ?? 'Failed to load request')
    });
  }

  protected taskStatusClass(t: HumanTask): string {
    return t.status === 'COMPLETED' && t.outcome === 'APPROVED'  ? 'badge badge-approved'
         : t.status === 'COMPLETED' && t.outcome === 'CONFIRMED' ? 'badge badge-approved'
         : t.status === 'COMPLETED' && t.outcome === 'REJECTED'  ? 'badge badge-rejected'
         : t.status === 'CANCELLED' ? 'badge badge-rejected'
         : t.status === 'PENDING'   ? 'badge badge-await1'
         : 'badge badge-submitted';
  }

  protected readonly stateClass = stateBadgeClass;
  protected readonly label = stateLabel;
  protected readonly groupLabel = groupLabel;
}
