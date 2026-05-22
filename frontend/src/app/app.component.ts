import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApprovalEventsService } from './services/approval-events.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink],
  template: `
    <header>
      <a routerLink="/" class="brand">Approval Flow</a>
      <span class="ws" [class.up]="connected()">
        <span class="dot"></span>
        {{ connected() ? 'live' : 'connecting…' }}
      </span>
    </header>
    <main>
      <router-outlet />
    </main>
    <footer>
      <span>Quarkus Flow (CNCF Serverless Workflow) &middot; REST + WebSocket</span>
    </footer>
  `,
  styles: [`
    header {
      display: flex; align-items: center; gap: 1rem;
      padding: 0.8rem 1.5rem; border-bottom: 1px solid var(--border);
      background: var(--bg-elev);
    }
    .brand { color: var(--accent); text-decoration: none; font-weight: 800; font-size: 1.1rem; }
    .ws {
      display: inline-flex; align-items: center; gap: 6px;
      margin-left: auto; font-size: 12px; color: var(--fg-muted);
    }
    .ws .dot {
      width: 8px; height: 8px; border-radius: 50%;
      background: var(--amber); box-shadow: 0 0 6px var(--amber);
    }
    .ws.up .dot { background: var(--green); box-shadow: 0 0 6px var(--green); }
    main { padding: 1.5rem; max-width: 1200px; margin: 0 auto; }
    footer { padding: 1rem 1.5rem; color: var(--fg-muted); font-size: 12px; text-align: center; }
  `]
})
export class AppComponent implements OnInit, OnDestroy {
  connected = signal(false);
  private sub?: Subscription;

  constructor(private events: ApprovalEventsService) {}

  ngOnInit(): void {
    this.events.connect();
    this.sub = this.events.status$.subscribe(s => this.connected.set(s));
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }
}
