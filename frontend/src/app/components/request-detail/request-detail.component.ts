import { CommonModule, DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApprovalService } from '../../services/approval.service';
import { ApprovalEventsService } from '../../services/approval-events.service';
import {
  ApprovalRequest, Decision, stateBadgeClass, stateLabel
} from '../../models/approval.model';
import { FlowDiagramComponent } from '../flow-diagram/flow-diagram.component';

@Component({
  selector: 'app-request-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, DatePipe, FlowDiagramComponent],
  template: `
    <a routerLink="/" class="back">&larr; Back to requests</a>

    <ng-container *ngIf="request() as r">
      <div class="card">
        <div class="row">
          <h2 style="margin: 0;">{{ r.subject }}</h2>
          <span [class]="stateClass(r.state)">{{ label(r.state) }}</span>
          <span class="spacer"></span>
          <span style="color: var(--fg-muted); font-size: 12px;">
            {{ r.createdAt | date:'medium' }}
          </span>
        </div>
        <p style="color: var(--fg-muted); margin: 0.5rem 0 0;">
          Requested by <strong>{{ r.requester }}</strong> &lt;{{ r.email }}&gt;
        </p>
        <p *ngIf="r.description" style="margin: 0.7rem 0 0;">{{ r.description }}</p>
      </div>

      <div class="card">
        <h3 style="margin-top: 0;">Workflow</h3>
        <app-flow-diagram [request]="r" />
      </div>

      <!-- Async stage 0: email + terms confirmation -->
      <div class="card" *ngIf="r.state === 'AWAITING_CONFIRMATION'">
        <h3 style="margin-top:0;">Email confirmation pending</h3>
        <div class="mailbox">
          <div class="hdr">
            <strong>From:</strong> noreply&#64;approval-demo &nbsp;
            <strong>To:</strong> {{ r.email }}
          </div>
          <div class="subj"><strong>Subject:</strong> Please confirm your approval request</div>
          <p>Hi {{ r.requester }},</p>
          <p>Click the button below to confirm your email address and accept the
             terms &amp; conditions. The workflow stays suspended until you do.</p>
          <p style="font-size: 11px; color: var(--fg-muted);">
            (Demo: this is normally an emailed link. Here we simulate the click below.)
          </p>
        </div>

        <div class="terms" style="margin-top: 0.8rem;">
          <label class="check">
            <input type="checkbox" [(ngModel)]="termsAccepted" />
            <span>I accept the <a href="#" (click)="$event.preventDefault()">Terms &amp; Conditions</a>.</span>
          </label>
        </div>

        <div class="row" style="margin-top: 0.8rem;">
          <button class="success"
                  (click)="confirm()"
                  [disabled]="!termsAccepted() || submitting()">
            ✓ Confirm email &amp; accept terms
          </button>
          <button class="secondary" (click)="cancel()" [disabled]="submitting()">
            Cancel request
          </button>
          <span class="spacer"></span>
          <span style="color: var(--fg-muted); font-size: 12px;">
            Token: <code>{{ r.confirmationToken }}</code>
          </span>
        </div>
      </div>

      <!-- Stage 1 -->
      <div class="card" *ngIf="r.state === 'AWAITING_GROUP1_APPROVAL'">
        <h3>Group 1 Decision</h3>
        <div class="row">
          <div style="flex:1; min-width:200px;">
            <label>Approver</label>
            <input [(ngModel)]="approver" placeholder="your.name@example.com" />
          </div>
          <button class="success" (click)="decide(1, 'APPROVED')" [disabled]="!approver() || submitting()">
            Approve
          </button>
          <button class="danger" (click)="decide(1, 'REJECTED')" [disabled]="!approver() || submitting()">
            Reject
          </button>
        </div>
      </div>

      <!-- Stage 2 -->
      <div class="card" *ngIf="r.state === 'AWAITING_GROUP2_APPROVAL'">
        <h3>Group 2 Decision</h3>
        <div class="row">
          <div style="flex:1; min-width:200px;">
            <label>Approver</label>
            <input [(ngModel)]="approver" placeholder="your.name@example.com" />
          </div>
          <button class="success" (click)="decide(2, 'APPROVED')" [disabled]="!approver() || submitting()">
            Approve
          </button>
          <button class="danger" (click)="decide(2, 'REJECTED')" [disabled]="!approver() || submitting()">
            Reject
          </button>
        </div>
      </div>

      <div class="card">
        <h3 style="margin-top: 0;">Audit trail</h3>
        <table>
          <tr>
            <th>Email confirmed</th>
            <td>
              <span *ngIf="r.emailConfirmed; else nope" class="badge approved">YES</span>
              <ng-template #nope><em style="color: var(--fg-muted);">no</em></ng-template>
            </td>
            <td>{{ r.confirmedAt ? (r.confirmedAt | date:'medium') : '—' }}</td>
          </tr>
          <tr>
            <th>Terms accepted</th>
            <td>
              <span *ngIf="r.termsAccepted; else nope2" class="badge approved">YES</span>
              <ng-template #nope2><em style="color: var(--fg-muted);">no</em></ng-template>
            </td>
            <td></td>
          </tr>
          <tr>
            <th>Group 1</th>
            <td>
              <span *ngIf="r.group1Decision; else g1pending"
                    [class]="r.group1Decision === 'APPROVED' ? 'badge approved' : 'badge rejected'">
                {{ r.group1Decision }}
              </span>
              <ng-template #g1pending><em style="color: var(--fg-muted);">pending</em></ng-template>
            </td>
            <td>{{ r.group1Approver ?? '—' }}</td>
          </tr>
          <tr>
            <th>Group 2</th>
            <td>
              <span *ngIf="r.group2Decision; else g2pending"
                    [class]="r.group2Decision === 'APPROVED' ? 'badge approved' : 'badge rejected'">
                {{ r.group2Decision }}
              </span>
              <ng-template #g2pending><em style="color: var(--fg-muted);">pending</em></ng-template>
            </td>
            <td>{{ r.group2Approver ?? '—' }}</td>
          </tr>
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
    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: 0.4rem 0.6rem; border-bottom: 1px solid var(--border); }
    th { color: var(--fg-muted); font-weight: 600; width: 140px; }
    .history { list-style: none; padding: 0; margin: 0; }
    .history li {
      display: grid; grid-template-columns: 110px 220px 1fr; gap: 0.6rem;
      padding: 0.4rem 0; border-bottom: 1px dashed var(--border);
      font-size: 13px;
    }
    .history .ts { color: var(--fg-muted); }
    .mailbox {
      background: #0b1220; border: 1px solid var(--border);
      border-radius: 8px; padding: 0.8rem 1rem;
      font-family: ui-monospace, "SF Mono", monospace; font-size: 13px;
    }
    .mailbox .hdr, .mailbox .subj { color: var(--fg-muted); margin-bottom: 0.3rem; }
    .check { display: flex; align-items: flex-start; gap: 0.5rem; font-size: 13px; }
    .check input { width: auto; margin-top: 3px; }
    .check a { color: var(--accent); }
    code { background: var(--bg-elev-2); padding: 1px 4px; border-radius: 3px; font-size: 11px; }
  `]
})
export class RequestDetailComponent implements OnInit, OnDestroy {
  request = signal<ApprovalRequest | null>(null);
  error = signal<string | null>(null);
  approver = signal('');
  termsAccepted = signal(false);
  submitting = signal(false);
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
    this.api.get(this.id).subscribe({
      next: r => { this.request.set(r); this.error.set(null); },
      error: err => this.error.set(err?.message ?? 'Failed to load request')
    });
  }

  confirm(): void {
    const r = this.request();
    if (!r || !r.confirmationToken) return;
    this.submitting.set(true);
    this.api.confirm(this.id, r.confirmationToken, this.termsAccepted()).subscribe({
      next: rr => { this.request.set(rr); this.submitting.set(false); },
      error: err => {
        this.error.set(err?.error?.error ?? 'Confirmation failed');
        this.submitting.set(false);
      }
    });
  }

  cancel(): void {
    this.submitting.set(true);
    this.api.cancel(this.id).subscribe({
      next: r => { this.request.set(r); this.submitting.set(false); },
      error: err => {
        this.error.set(err?.error?.error ?? 'Cancel failed');
        this.submitting.set(false);
      }
    });
  }

  decide(group: 1 | 2, d: Decision): void {
    this.submitting.set(true);
    this.api.decide(this.id, group, this.approver(), d).subscribe({
      next: r => { this.request.set(r); this.submitting.set(false); },
      error: err => {
        this.error.set(err?.error?.error ?? 'Decision failed');
        this.submitting.set(false);
      }
    });
  }

  stateClass = stateBadgeClass;
  label = stateLabel;
}
