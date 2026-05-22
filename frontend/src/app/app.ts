import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ApprovalEventsService } from './services/approval-events';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="flex items-center gap-4 px-6 py-3 border-b border-border bg-elev">
      <a routerLink="/" class="text-brand font-extrabold text-base no-underline">
        Approval Flow
      </a>
      <nav class="flex gap-1">
        <a routerLink="/"
           routerLinkActive="bg-elev-2 text-fg"
           [routerLinkActiveOptions]="{exact:true}"
           class="text-muted text-[13px] px-2.5 py-1 rounded-md no-underline hover:text-fg">
          Requests
        </a>
        <a routerLink="/tasks"
           routerLinkActive="bg-elev-2 text-fg"
           class="text-muted text-[13px] px-2.5 py-1 rounded-md no-underline hover:text-fg">
          Task Inbox
        </a>
      </nav>
      <span class="ml-auto flex items-center gap-1.5 text-xs text-muted">
        <span class="inline-block size-2 rounded-full transition-colors"
              [class.bg-warn]="!events.connected()"
              [class.bg-ok]="events.connected()"
              [class.shadow-[0_0_6px]]="true"
              [class.shadow-warn]="!events.connected()"
              [class.shadow-ok]="events.connected()"></span>
        {{ events.connected() ? 'live' : 'connecting…' }}
      </span>
    </header>

    <main class="p-6 max-w-[1200px] mx-auto">
      <router-outlet />
    </main>

    <footer class="px-6 py-4 text-center text-xs text-muted">
      Quarkus Flow (CNCF Serverless Workflow) · REST + WebSocket · generic task API
    </footer>
  `
})
export class App {
  protected readonly events = inject(ApprovalEventsService);

  constructor() {
    this.events.connect();
  }
}
