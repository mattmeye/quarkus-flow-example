import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  ApprovalRequest, AssigneeGroup, HumanTask, TaskStatus
} from '../models/approval';

const API = 'http://localhost:8080/api';

export interface CreatePayload {
  requester: string;
  email: string;
  subject: string;
  description: string;
  termsAcknowledged: boolean;
}

export interface TaskFilter {
  status?: TaskStatus;
  group?: AssigneeGroup;
  requestId?: string;
}

@Injectable({ providedIn: 'root' })
export class ApprovalService {
  private readonly http = inject(HttpClient);

  listRequests(): Observable<ApprovalRequest[]> {
    return this.http.get<ApprovalRequest[]>(`${API}/requests`);
  }

  getRequest(id: string): Observable<ApprovalRequest> {
    return this.http.get<ApprovalRequest>(`${API}/requests/${id}`);
  }

  createRequest(req: CreatePayload): Observable<ApprovalRequest> {
    return this.http.post<ApprovalRequest>(`${API}/requests`, req);
  }

  listTasks(filter: TaskFilter = {}): Observable<HumanTask[]> {
    let params = new HttpParams();
    if (filter.status) params = params.set('status', filter.status);
    if (filter.group) params = params.set('group', filter.group);
    if (filter.requestId) params = params.set('requestId', filter.requestId);
    return this.http.get<HumanTask[]>(`${API}/tasks`, { params });
  }

  getTask(id: string): Observable<HumanTask> {
    return this.http.get<HumanTask>(`${API}/tasks/${id}`);
  }

  completeTask(id: string, actor: string, outcome: string,
               payload: Record<string, unknown> = {}): Observable<HumanTask> {
    return this.http.post<HumanTask>(
      `${API}/tasks/${id}/complete`,
      { actor, outcome, payload }
    );
  }

  cancelTask(id: string, actor: string, reason: string): Observable<HumanTask> {
    return this.http.post<HumanTask>(
      `${API}/tasks/${id}/cancel`,
      { actor, reason }
    );
  }
}
