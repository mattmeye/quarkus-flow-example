import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApprovalService } from './approval.service';

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

  it('POSTs the create payload including termsAcknowledged', () => {
    svc.create({
      requester: 'A', email: 'a@b.de', subject: 's',
      description: 'd', termsAcknowledged: true
    }).subscribe();
    const req = httpMock.expectOne('http://localhost:8080/api/approvals');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.termsAcknowledged).toBeTrue();
    req.flush({ request: {}, confirmationLink: '' });
  });

  it('POSTs the confirmation with token + termsAccepted', () => {
    svc.confirm('abc', 'tok', true).subscribe();
    const req = httpMock.expectOne('http://localhost:8080/api/approvals/abc/confirm');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ token: 'tok', termsAccepted: true });
    req.flush({});
  });

  it('routes group decisions to the correct path', () => {
    svc.decide('id-1', 1, 'me', 'APPROVED').subscribe();
    httpMock.expectOne('http://localhost:8080/api/approvals/id-1/group1/decision').flush({});

    svc.decide('id-1', 2, 'me', 'REJECTED').subscribe();
    httpMock.expectOne('http://localhost:8080/api/approvals/id-1/group2/decision').flush({});
  });
});
