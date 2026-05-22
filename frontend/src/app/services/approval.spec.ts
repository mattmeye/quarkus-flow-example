import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApprovalService } from './approval';

describe('ApprovalService', () => {
  let svc: ApprovalService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ApprovalService,
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    svc = TestBed.inject(ApprovalService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs to /api/requests with termsAcknowledged on create', () => {
    svc.createRequest({
      requester: 'A', email: 'a@b.de', subject: 's',
      description: 'd', termsAcknowledged: true
    }).subscribe();
    const req = httpMock.expectOne('http://localhost:8080/api/requests');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.termsAcknowledged).toBe(true);
    req.flush({});
  });

  it('completes any task through the same generic endpoint', () => {
    svc.completeTask('t1', 'me', 'CONFIRMED', { token: 'x', termsAccepted: true }).subscribe();
    const r1 = httpMock.expectOne('http://localhost:8080/api/tasks/t1/complete');
    expect(r1.request.body).toEqual({
      actor: 'me', outcome: 'CONFIRMED',
      payload: { token: 'x', termsAccepted: true }
    });
    r1.flush({});

    svc.completeTask('t2', 'me', 'APPROVED', {}).subscribe();
    const r2 = httpMock.expectOne('http://localhost:8080/api/tasks/t2/complete');
    expect(r2.request.body.outcome).toBe('APPROVED');
    r2.flush({});

    svc.completeTask('t3', 'me', 'REJECTED', {}).subscribe();
    const r3 = httpMock.expectOne('http://localhost:8080/api/tasks/t3/complete');
    expect(r3.request.body.outcome).toBe('REJECTED');
    r3.flush({});
  });

  it('cancels a task with actor + reason', () => {
    svc.cancelTask('tX', 'ed', 'changed my mind').subscribe();
    const r = httpMock.expectOne('http://localhost:8080/api/tasks/tX/cancel');
    expect(r.request.method).toBe('POST');
    expect(r.request.body).toEqual({ actor: 'ed', reason: 'changed my mind' });
    r.flush({});
  });

  it('supports filtering tasks by status / group / requestId', () => {
    svc.listTasks({ status: 'PENDING', group: 'GROUP_2', requestId: 'r-1' }).subscribe();
    const req = httpMock.expectOne(r =>
      r.url === 'http://localhost:8080/api/tasks' &&
      r.params.get('status') === 'PENDING' &&
      r.params.get('group') === 'GROUP_2' &&
      r.params.get('requestId') === 'r-1');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });
});
