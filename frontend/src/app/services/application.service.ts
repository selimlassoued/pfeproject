import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { ApplicationDto } from '../model/application.dto';
import { PageResponse } from '../model/page-response';
import { CvAnalysis } from '../model/cv-analysis.model';

@Injectable({ providedIn: 'root' })
export class ApplicationService {
  private readonly API_URL   = 'http://localhost:8888/api/applications';
  private readonly AUDIT_URL = 'http://localhost:8888/api/audit';

  constructor(private http: HttpClient) {}

  // ── GitHub link validation ───────────────────────────────────────────────

  checkGithubLink(url: string): Observable<boolean> {
    const params = new HttpParams().set('url', url);
    return this.http.get<boolean>(`${this.API_URL}/check-github`, { params });
  }

  // ── Applications ─────────────────────────────────────────────────────────

  listApplications(filters?: {
    applicationId?: string;
    status?: string;
    jobTitle?: string;
    candidateName?: string;
  }): Observable<ApplicationDto[]> {
    let params = new HttpParams();
    if (filters?.applicationId) params = params.set('applicationId', filters.applicationId);
    if (filters?.status)        params = params.set('status', filters.status);
    if (filters?.jobTitle)      params = params.set('jobTitle', filters.jobTitle);
    if (filters?.candidateName) params = params.set('candidateName', filters.candidateName);
    return this.http.get<ApplicationDto[]>(this.API_URL, { params });
  }

  getOne(id: string): Observable<ApplicationDto> {
    return this.http.get<ApplicationDto>(`${this.API_URL}/${id}`);
  }

  getSemanticMatch(id: string): Observable<Partial<ApplicationDto> | null> {
    return this.http.get<Partial<ApplicationDto> | null>(`${this.API_URL}/${id}/match`, {
      observe: 'response'
    }).pipe(
      map(resp => resp.status === 202 ? null : (resp.body ?? null))
    );
  }

  downloadCv(applicationId: string): Observable<Blob> {
    return this.http.get(`${this.API_URL}/${applicationId}/cv`, { responseType: 'blob' });
  }

  applyToJob(jobId: string, payload: { githubUrl: string; cv: File }): Observable<ApplicationDto> {
    const form = new FormData();
    form.append('jobId', jobId);
    form.append('githubUrl', payload.githubUrl);
    form.append('cv', payload.cv);
    return this.http.post<ApplicationDto>(this.API_URL, form);
  }

  getMyApplicationByJob(jobId: string): Observable<ApplicationDto> {
    return this.http.get<ApplicationDto>(`${this.API_URL}/me/by-job/${jobId}`);
  }

  getMyApplications(): Observable<ApplicationDto[]> {
    return this.http.get<ApplicationDto[]>(`${this.API_URL}/me`);
  }

  getMyApplicationById(id: string): Observable<ApplicationDto> {
    return this.http.get<ApplicationDto>(`${this.API_URL}/me/${id}`);
  }

  downloadMyCv(applicationId: string): Observable<Blob> {
    return this.http.get(`${this.API_URL}/me/${applicationId}/cv`, { responseType: 'blob' });
  }

  /**
   * Recruiter-side: list every application this candidate has submitted
   * across all jobs. Used by the "Candidate history" section on
   * application-detail. Excludes the current application via excludeId so
   * recruiters only see OTHER applications.
   */
  listByCandidate(candidateUserId: string, excludeId?: string): Observable<CandidateApplicationSummary[]> {
    let params = new HttpParams();
    if (excludeId) params = params.set('excludeId', excludeId);
    return this.http.get<CandidateApplicationSummary[]>(
      `${this.API_URL}/by-candidate/${encodeURIComponent(candidateUserId)}`,
      { params },
    );
  }

  updateMyApplication(
    applicationId: string,
    payload: { githubUrl?: string; cv?: File }
  ): Observable<ApplicationDto> {
    const form = new FormData();
    if (payload.githubUrl !== undefined) form.append('githubUrl', payload.githubUrl);
    if (payload.cv) form.append('cv', payload.cv);
    return this.http.patch<ApplicationDto>(`${this.API_URL}/me/${applicationId}`, form);
  }

  /**
   * Candidate withdraws their own application (soft delete).
   * Only allowed when status is APPLIED.
   * Application stays in DB with WITHDRAWN status.
   * Hidden from candidate dashboard after withdrawal.
   * Candidate can re-apply to the same job after withdrawing.
   */
  withdrawApplication(applicationId: string): Observable<void> {
    return this.http.delete<void>(`${this.API_URL}/me/${applicationId}`);
  }

  updateApplicationStatus(applicationId: string, status: string): Observable<ApplicationDto> {
    return this.http.patch<ApplicationDto>(
      `${this.API_URL}/${applicationId}/status`,
      null,
      { params: { status } }
    );
  }

  listApplicationsPaged(filters?: {
    applicationId?: string;
    jobId?: string;
    status?: string;
    jobTitle?: string;
    candidateName?: string;
    page?: number;
    size?: number;
  }): Observable<PageResponse<ApplicationDto>> {
    let params = new HttpParams();
    if (filters?.applicationId) params = params.set('applicationId', filters.applicationId);
    if (filters?.jobId)         params = params.set('jobId', filters.jobId);
    if (filters?.status)        params = params.set('status', filters.status);
    if (filters?.jobTitle)      params = params.set('jobTitle', filters.jobTitle);
    if (filters?.candidateName) params = params.set('candidateName', filters.candidateName);
    params = params.set('page', String(filters?.page ?? 0));
    params = params.set('size', String(filters?.size ?? 10));
    return this.http.get<PageResponse<ApplicationDto>>(`${this.API_URL}/paged`, { params });
  }

  getApplicationRank(applicationId: string): Observable<{ rank: number; total: number }> {
    return this.http.get<{ rank: number; total: number }>(`${this.API_URL}/${applicationId}/rank`);
  }

  getCvAnalysis(applicationId: string): Observable<CvAnalysis> {
    return this.http.get<CvAnalysis>(`${this.API_URL}/${applicationId}/analysis`);
  }

  hasCvAnalysis(applicationId: string): Observable<boolean> {
    return this.http.get<boolean>(`${this.API_URL}/${applicationId}/analysis/exists`);
  }

  retryAnalysis(applicationId: string): Observable<void> {
    return this.http.post<void>(`${this.API_URL}/${applicationId}/analysis/retry`, null);
  }

  rematchApplicationsForJob(jobId: string): Observable<void> {
    return this.http.post<void>(`${this.API_URL}/internal/job/${jobId}/rematch`, null);
  }

  shortlistApplications(jobId: string, threshold: number): Observable<{ shortlisted: number; skipped: number; threshold: number }> {
    return this.http.post<{ shortlisted: number; skipped: number; threshold: number }>(
      `${this.API_URL}/internal/job/${jobId}/shortlist`,
      null,
      { params: { threshold: String(threshold) } }
    );
  }

  // ── Moderation ────────────────────────────────────────────────────────────

  signalCandidate(candidateUserId: string, reason: string): Observable<void> {
    return this.http.post<void>(
      `${this.API_URL}/signal/${candidateUserId}`,
      { reason }
    );
  }

  unsignalCandidate(candidateUserId: string, reason: string): Observable<void> {
    return this.http.delete<void>(
      `${this.API_URL}/signal/${candidateUserId}`,
      { body: { reason } }
    );
  }

  getCandidateModerationStatus(candidateUserId: string): Observable<{ status: string }> {
    return this.http.get<{ status: string }>(
      `${this.AUDIT_URL}/candidate/${candidateUserId}/moderation-status`
    );
  }

  getLastFlaggedBy(candidateUserId: string): Observable<{ flaggedBy: string }> {
    return this.http.get<{ flaggedBy: string }>(
      `${this.AUDIT_URL}/candidate/${candidateUserId}/flagged-by`
    );
  }
}

/** Slim row returned by GET /api/applications/by-candidate/{id}. Mirrors the
 *  backend's CandidateApplicationSummaryDto. */
export interface CandidateApplicationSummary {
  applicationId:    string;
  jobId:            string | null;
  jobTitle:         string | null;
  appliedAt:        string;
  status:           string | null;
  previousStatus:   string | null;
  withdrawalReason: string | null;
  fitScore:         number | null;
}