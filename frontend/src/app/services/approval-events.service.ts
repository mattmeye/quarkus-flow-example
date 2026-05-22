import { Injectable, OnDestroy } from '@angular/core';
import { Observable, Subject, share } from 'rxjs';
import { ApprovalEvent } from '../models/approval.model';

const WS_URL = 'ws://localhost:8080/approval-events';

@Injectable({ providedIn: 'root' })
export class ApprovalEventsService implements OnDestroy {
  private socket?: WebSocket;
  private subject = new Subject<ApprovalEvent>();
  private connected$ = new Subject<boolean>();
  readonly events$: Observable<ApprovalEvent> = this.subject.asObservable().pipe(share());
  readonly status$: Observable<boolean> = this.connected$.asObservable().pipe(share());

  connect(): void {
    if (this.socket && this.socket.readyState <= 1) {
      return;
    }
    const ws = new WebSocket(WS_URL);
    this.socket = ws;

    ws.onopen = () => this.connected$.next(true);
    ws.onclose = () => {
      this.connected$.next(false);
      setTimeout(() => this.connect(), 2000);
    };
    ws.onerror = () => ws.close();
    ws.onmessage = ev => {
      try {
        const data = JSON.parse(ev.data) as ApprovalEvent;
        this.subject.next(data);
      } catch (e) {
        console.error('Bad WS payload', e);
      }
    };
  }

  ngOnDestroy(): void {
    this.socket?.close();
  }
}
