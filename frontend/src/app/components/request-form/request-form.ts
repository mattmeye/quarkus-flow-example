import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApprovalService } from '../../services/approval';
import { ApprovalRequest } from '../../models/approval';

@Component({
  selector: 'app-request-form',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="card">
      <h3 class="m-0 mb-3 text-base font-semibold">New Approval Request</h3>

      <div class="grid sm:grid-cols-2 gap-3">
        <div>
          <label class="field-label">Requester name</label>
          <input class="input" [ngModel]="requester()" (ngModelChange)="requester.set($event)"
                 name="requester" placeholder="Alice Schmidt" />
        </div>
        <div>
          <label class="field-label">Email</label>
          <input class="input" type="email" [ngModel]="email()" (ngModelChange)="email.set($event)"
                 name="email" placeholder="alice@example.com" />
        </div>
      </div>

      <div class="mt-3">
        <label class="field-label">Subject</label>
        <input class="input" [ngModel]="subject()" (ngModelChange)="subject.set($event)"
               name="subject" placeholder="Production deployment of payments-v2" />
      </div>

      <div class="mt-3">
        <label class="field-label">Description</label>
        <textarea class="input min-h-16" rows="2"
                  [ngModel]="description()" (ngModelChange)="description.set($event)"
                  name="description" placeholder="Optional context…"></textarea>
      </div>

      <label class="mt-4 flex items-start gap-2 text-[13px] cursor-pointer">
        <input type="checkbox" class="mt-0.5"
               [ngModel]="termsAcknowledged()"
               (ngModelChange)="termsAcknowledged.set($event)"
               name="terms" />
        <span>
          I have read and acknowledge the
          <a href="#" class="link" (click)="$event.preventDefault()">Terms &amp; Conditions</a>.
          I will be asked to confirm them again via the email verification step.
        </span>
      </label>

      @if (error(); as e) {
        <div class="mt-3 px-3 py-2 rounded-md text-xs text-red-100 bg-red-900/70">{{ e }}</div>
      }

      <div class="mt-4 flex items-center gap-4 flex-wrap">
        <span class="text-xs text-muted">
          After submitting, the workflow creates a CONFIRMATION task that
          stays open until the requester confirms via email.
        </span>
        <button class="btn ml-auto" (click)="submit()" [disabled]="!canSubmit() || submitting()">
          {{ submitting() ? 'Submitting…' : 'Submit request' }}
        </button>
      </div>
    </div>
  `
})
export class RequestForm {
  readonly created = output<ApprovalRequest>();

  protected readonly requester = signal('');
  protected readonly email = signal('');
  protected readonly subject = signal('');
  protected readonly description = signal('');
  protected readonly termsAcknowledged = signal(false);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly canSubmit = computed(() =>
    !!this.requester() &&
    !!this.subject() &&
    isEmail(this.email()) &&
    this.termsAcknowledged()
  );

  private readonly api = inject(ApprovalService);

  protected submit(): void {
    this.error.set(null);
    this.submitting.set(true);
    this.api.createRequest({
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

function isEmail(v: string): boolean {
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v);
}
