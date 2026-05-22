import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { ApprovalService } from '../../services/approval';
import { ApprovalEventsService } from '../../services/approval-events';
import { ApprovalRequest, stateBadgeClass, stateLabel } from '../../models/approval';
import { RequestForm } from '../request-form/request-form';

@Component({
  selector: 'app-request-list',
  imports: [RouterLink, DatePipe, RequestForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-request-form (created)="onCreated($event)" />

    <div class="card">
      <div class="flex items-center mb-3">
        <h3 class="m-0 text-base font-semibold">Requests</h3>
        <span class="ml-auto text-xs text-muted">
          {{ requests().length }} total · live via WebSocket
        </span>
      </div>

      @if (requests().length === 0) {
        <div class="p-4 text-muted">No requests yet. Submit one above.</div>
      } @else {
        <div class="overflow-x-auto">
          <table class="w-full border-collapse">
            <thead>
              <tr>
                @for (h of ['Subject','Requester','Email','State','Open tasks','Outcome','Created','']; track h) {
                  <th class="text-left text-[11px] uppercase text-muted font-semibold border-b border-border py-2 px-2">{{ h }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (r of requests(); track r.id) {
                <tr class="cursor-pointer hover:bg-elev-2 transition-colors"
                    [routerLink]="['/requests', r.id]">
                  <td class="border-b border-border py-2 px-2">{{ r.subject }}</td>
                  <td class="border-b border-border py-2 px-2">{{ r.requester }}</td>
                  <td class="border-b border-border py-2 px-2">{{ r.email }}</td>
                  <td class="border-b border-border py-2 px-2">
                    <span [class]="stateClass(r.state)">{{ label(r.state) }}</span>
                  </td>
                  <td class="border-b border-border py-2 px-2">{{ openCount(r) }}</td>
                  <td class="border-b border-border py-2 px-2">{{ r.outcome ?? '—' }}</td>
                  <td class="border-b border-border py-2 px-2">{{ r.createdAt | date:'short' }}</td>
                  <td class="border-b border-border py-2 px-2">
                    <a class="link" [routerLink]="['/requests', r.id]">Open →</a>
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
export class RequestList {
  protected readonly requests = signal<ApprovalRequest[]>([]);

  private readonly api = inject(ApprovalService);
  private readonly events = inject(ApprovalEventsService);
  private readonly router = inject(Router);

  constructor() {
    this.reload();
    this.events.events$
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.reload());
  }

  protected reload(): void {
    this.api.listRequests().subscribe({
      next: r => this.requests.set(r),
      error: err => console.error(err)
    });
  }

  protected openCount(r: ApprovalRequest): number {
    return r.tasks.filter(t => t.status === 'PENDING').length;
  }

  protected onCreated(r: ApprovalRequest): void {
    this.reload();
    this.router.navigate(['/requests', r.id]);
  }

  protected readonly stateClass = stateBadgeClass;
  protected readonly label = stateLabel;
}
