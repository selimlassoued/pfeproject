import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export type OfferStatus =
  | 'SENT' | 'NEGOTIATING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'WITHDRAWN';

export type ContractType =
  | 'CDI' | 'CDD' | 'INTERNSHIP' | 'ALTERNANCE' | 'FREELANCE';

export type ProposedBy = 'RECRUITER' | 'CANDIDATE';

export interface OfferRevisionDto {
  id: string;
  offerId: string;
  proposedBy: ProposedBy;
  salary: number;
  currency: string;
  startDate: string;        // ISO date "YYYY-MM-DD"
  contractType: ContractType;
  message: string;
  createdAt: string;        // ISO instant
}

export interface OfferDto {
  id: string;
  applicationId: string;
  jobId: string;
  recruiterId: string;
  candidateUserId: string;
  salary: number;
  currency: string;
  startDate: string;
  contractType: ContractType;
  message?: string;
  status: OfferStatus;
  expiresAt: string;
  createdAt: string;
  respondedAt?: string;
  revisions: OfferRevisionDto[];
}

export interface CreateOfferRequest {
  salary: number;
  currency?: string;
  startDate: string;        // "YYYY-MM-DD"
  contractType: ContractType;
  message?: string;
  expiresAt: string;        // ISO instant string
}

export interface CreateRevisionRequest {
  salary?: number;
  currency?: string;
  startDate?: string;
  contractType?: ContractType;
  message: string;
}

@Injectable({ providedIn: 'root' })
export class OfferService {
  private http = inject(HttpClient);
  private base = 'http://localhost:8888/api/applications';

  create(applicationId: string, req: CreateOfferRequest): Observable<OfferDto> {
    return this.http.post<OfferDto>(`${this.base}/${applicationId}/offer`, req);
  }

  get(applicationId: string): Observable<OfferDto> {
    return this.http.get<OfferDto>(`${this.base}/${applicationId}/offer`);
  }

  myOffers(): Observable<OfferDto[]> {
    return this.http.get<OfferDto[]>(`${this.base}/me/offers`);
  }

  recruiterOffers(recruiterId: string): Observable<OfferDto[]> {
    return this.http.get<OfferDto[]>(`${this.base}/recruiter/${recruiterId}/offers`);
  }

  revise(applicationId: string, req: CreateRevisionRequest): Observable<OfferDto> {
    return this.http.post<OfferDto>(`${this.base}/${applicationId}/offer/revisions`, req);
  }

  accept(applicationId: string): Observable<OfferDto> {
    return this.http.post<OfferDto>(`${this.base}/${applicationId}/offer/accept`, {});
  }

  decline(applicationId: string, reason?: string): Observable<OfferDto> {
    return this.http.post<OfferDto>(`${this.base}/${applicationId}/offer/decline`,
      reason ? { reason } : {});
  }

  withdraw(applicationId: string): Observable<OfferDto> {
    return this.http.post<OfferDto>(`${this.base}/${applicationId}/offer/withdraw`, {});
  }
}
