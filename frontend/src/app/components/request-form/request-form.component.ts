import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApprovalService } from '../../services/approval.service';
import { CreatedResponse } from '../../models/approval.model';

@Component({
  selector: 'app-request-form',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="card">
      <h3>New Approval Request</h3>
      <div class="row">
        <div style="flex:1; min-width:200px;">
          <label>Requester name</label>
          <input [(ngModel)]="requester" placeholder="Alice Schmidt" />
        </div>
        <div style="flex:1; min-width:240px;">
          <label>Email</label>
          <input type="email" [(ngModel)]="email" placeholder="alice@example.com" />
        </div>
      </div>
      <div style="margin-top: 0.6rem;">
        <label>Subject</label>
        <input [(ngModel)]="subject" placeholder="Production deployment of payments-v2" />
      </div>
      <div style="margin-top: 0.6rem;">
        <label>Description</label>
        <textarea [(ngModel)]="description" rows="2"
                  placeholder="Optional context…"></textarea>
      </div>
      <div style="margin-top: 0.8rem;" class="terms">
        <label class="check">
          <input type="checkbox" [(ngModel)]="termsAcknowledged" />
          <span>
            I have read and I acknowledge the <a href="#" (click)="$event.preventDefault()">Terms &amp; Conditions</a>.
            I will be asked to confirm them again via the email verification step.
          </span>
        </label>
      </div>
      <div *ngIf="error()" class="err">{{ error() }}</div>
      <div class="row" style="margin-top: 0.8rem;">
        <span style="color: var(--fg-muted); font-size: 12px;">
          After submitting we send a confirmation email; the workflow only
          continues once the requester clicks the link.
        </span>
        <span class="spacer"></span>
        <button (click)="submit()" [disabled]="!canSubmit() || submitting()">
          {{ submitting() ? 'Submitting…' : 'Submit request' }}
        </button>
      </div>
    </div>
  `,
  styles: [`
    .check { display: flex; align-items: flex-start; gap: 0.5rem; color: var(--fg); font-size: 13px; }
    .check input { width: auto; margin-top: 3px; }
    .check a { color: var(--accent); }
    .err {
      margin-top: 0.6rem; color: #fecaca; background: #7f1d1d;
      padding: 0.4rem 0.6rem; border-radius: 6px; font-size: 12px;
    }
  `]
})
export class RequestFormComponent {
  @Output() created = new EventEmitter<CreatedResponse>();
  requester = signal('');
  email = signal('');
  subject = signal('');
  description = signal('');
  termsAcknowledged = signal(false);
  submitting = signal(false);
  error = signal<string | null>(null);

  constructor(private api: ApprovalService) {}

  canSubmit(): boolean {
    return !!this.requester() && !!this.subject() && this.isEmail(this.email()) && this.termsAcknowledged();
  }

  private isEmail(v: string): boolean {
    return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v);
  }

  submit(): void {
    this.error.set(null);
    this.submitting.set(true);
    this.api.create({
      requester: this.requester(),
      email: this.email(),
      subject: this.subject(),
      description: this.description(),
      termsAcknowledged: this.termsAcknowledged()
    }).subscribe({
      next: r => {
        this.created.emit(r);
        this.requester.set('');
        this.email.set('');
        this.subject.set('');
        this.description.set('');
        this.termsAcknowledged.set(false);
        this.submitting.set(false);
      },
      error: err => {
        this.error.set(err?.error?.error ?? 'Submit failed');
        this.submitting.set(false);
      }
    });
  }
}
