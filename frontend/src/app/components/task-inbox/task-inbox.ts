import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApprovalService } from '../../services/approval';
import { ApprovalEventsService } from '../../services/approval-events';
import { AssigneeGroup, HumanTask, TaskStatus, groupLabel } from '../../models/approval';

@Component({
  selector: 'app-task-inbox',
  imports: [FormsModule, RouterLink, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/" class="link inline-block mb-4">← Requests</a>

    <div class="card">
      <div class="flex items-center mb-3 flex-wrap gap-3">
        <h2 class="m-0 text-lg font-semibold">Task Inbox</h2>

        <label class="ml-auto text-xs text-muted flex items-center gap-2">
          Group
          <select class="input w-auto py-1 px-2"
                  [ngModel]="groupFilter()"
                  (ngModelChange)="groupFilter.set($event)"
                  name="group">
            <option [ngValue]="null">all</option>
            <option ngValue="REQUESTER">Requester</option>
            <option ngValue="GROUP_1">Group 1</option>
            <option ngValue="GROUP_2">Group 2</option>
          </select>
        </label>

        <label class="text-xs text-muted flex items-center gap-2">
          Status
          <select class="input w-auto py-1 px-2"
                  [ngModel]="statusFilter()"
                  (ngModelChange)="statusFilter.set($event)"
                  name="status">
            <option ngValue="PENDING">pending</option>
            <option ngValue="COMPLETED">completed</option>
            <option ngValue="CANCELLED">cancelled</option>
            <option [ngValue]="null">all</option>
          </select>
        </label>
      </div>

      <p class="text-xs text-muted">
        Generic <code class="bg-elev-2 rounded px-1 text-[11px]">GET /api/tasks</code>. {{ tasks().length }} matching.
      </p>

      @if (tasks().length === 0) {
        <p class="text-xs text-muted">No tasks matching the filter.</p>
      } @else {
        <div class="overflow-x-auto">
          <table class="w-full border-collapse">
            <thead>
              <tr>
                @for (h of ['Created','Type','Name','Assignee','Status','Request']; track h) {
                  <th class="text-left text-[11px] uppercase text-muted border-b border-border py-2 px-2">{{ h }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (t of tasks(); track t.id) {
                <tr class="cursor-pointer hover:bg-elev-2 transition-colors"
                    [routerLink]="['/requests', t.requestId]">
                  <td class="border-b border-border py-2 px-2">{{ t.createdAt | date:'mediumTime' }}</td>
                  <td class="border-b border-border py-2 px-2">
                    <code class="bg-elev-2 rounded px-1 text-[11px]">{{ t.type }}</code>
                  </td>
                  <td class="border-b border-border py-2 px-2">{{ t.name }}</td>
                  <td class="border-b border-border py-2 px-2">{{ groupLabel(t.assigneeGroup) }}</td>
                  <td class="border-b border-border py-2 px-2">
                    <span [class]="badgeFor(t)">{{ t.status }}</span>
                  </td>
                  <td class="border-b border-border py-2 px-2">
                    <a class="link" [routerLink]="['/requests', t.requestId]">{{ shortId(t.requestId) }}</a>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>
  `
})
export class TaskInbox {
  protected readonly tasks = signal<HumanTask[]>([]);
  protected readonly groupFilter = signal<AssigneeGroup | null>(null);
  protected readonly statusFilter = signal<TaskStatus | null>('PENDING');

  private readonly api = inject(ApprovalService);
  private readonly events = inject(ApprovalEventsService);

  constructor() {
    effect(() => {
      this.groupFilter();
      this.statusFilter();
      this.reload();
    });
    this.events.events$
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.reload());
  }

  protected reload(): void {
    this.api.listTasks({
      group: this.groupFilter() ?? undefined,
      status: this.statusFilter() ?? undefined
    }).subscribe(t => this.tasks.set(t));
  }

  protected badgeFor(t: HumanTask): string {
    if (t.status === 'PENDING') return 'badge badge-await1';
    if (t.outcome === 'REJECTED' || t.status === 'CANCELLED') return 'badge badge-rejected';
    return 'badge badge-approved';
  }

  protected shortId(id: string): string { return id.slice(0, 8); }
  protected readonly groupLabel = groupLabel;
}
