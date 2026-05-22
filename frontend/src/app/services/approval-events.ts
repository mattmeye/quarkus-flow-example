import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Observable, Subject, share } from 'rxjs';
import { ApprovalEvent } from '../models/approval';

const WS_URL = 'ws://localhost:8080/approval-events';

@Injectable({ providedIn: 'root' })
export class ApprovalEventsService {
  private socket?: WebSocket;
  private readonly subject = new Subject<ApprovalEvent>();
  private readonly connectedState = signal(false);

  /** Public stream of approval events. */
  readonly events$: Observable<ApprovalEvent> = this.subject.asObservable().pipe(share());

  /** Reactive connection status as a signal. */
  readonly connected = computed(() => this.connectedState());

  constructor() {
    inject(DestroyRef).onDestroy(() => this.socket?.close());
  }

  connect(): void {
    if (this.socket && this.socket.readyState <= 1) return;

    const ws = new WebSocket(WS_URL);
    this.socket = ws;

    ws.onopen = () => this.connectedState.set(true);
    ws.onclose = () => {
      this.connectedState.set(false);
      setTimeout(() => this.connect(), 2000);
    };
    ws.onerror = () => ws.close();
    ws.onmessage = ev => {
      try {
        this.subject.next(JSON.parse(ev.data) as ApprovalEvent);
      } catch (e) {
        console.error('Bad WS payload', e);
      }
    };
  }
}
