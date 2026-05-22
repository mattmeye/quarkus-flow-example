import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApprovalRequest, CreatedResponse, Decision } from '../models/approval.model';

const API = 'http://localhost:8080/api/approvals';

export interface CreatePayload {
  requester: string;
  email: string;
  subject: string;
  description: string;
  termsAcknowledged: boolean;
}

@Injectable({ providedIn: 'root' })
export class ApprovalService {
  constructor(private http: HttpClient) {}

  list(): Observable<ApprovalRequest[]> {
    return this.http.get<ApprovalRequest[]>(API);
  }

  get(id: string): Observable<ApprovalRequest> {
    return this.http.get<ApprovalRequest>(`${API}/${id}`);
  }

  create(req: CreatePayload): Observable<CreatedResponse> {
    return this.http.post<CreatedResponse>(API, req);
  }

  confirm(id: string, token: string, termsAccepted: boolean): Observable<ApprovalRequest> {
    return this.http.post<ApprovalRequest>(
      `${API}/${id}/confirm`,
      { token, termsAccepted }
    );
  }

  cancel(id: string): Observable<ApprovalRequest> {
    return this.http.post<ApprovalRequest>(`${API}/${id}/cancel`, {});
  }

  decide(id: string, group: 1 | 2, approver: string, decision: Decision): Observable<ApprovalRequest> {
    return this.http.post<ApprovalRequest>(
      `${API}/${id}/group${group}/decision`,
      { approver, decision }
    );
  }
}
