import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApplicationDto } from '../model/application.dto';
import { PageResponse } from '../model/page-response'; 
@Injectable({ providedIn: 'root' })
export class ApplicationService {
  private readonly API_URL = 'http://localhost:8888/api/applications';

  constructor(private http: HttpClient) {}

  listApplications(filters?: {
    applicationId?: string;
    status?: string;
    jobTitle?: string;
    candidateName?: string;
  }): Observable<ApplicationDto[]> {
    let params = new HttpParams();

    if (filters?.applicationId) params = params.set('applicationId', filters.applicationId);
    if (filters?.status) params = params.set('status', filters.status);
    if (filters?.jobTitle) params = params.set('jobTitle', filters.jobTitle);
    if (filters?.candidateName) params = params.set('candidateName', filters.candidateName);

    return this.http.get<ApplicationDto[]>(this.API_URL, { params });
  }

  getOne(id: string): Observable<ApplicationDto> {
    return this.http.get<ApplicationDto>(`${this.API_URL}/${id}`);
  }

  downloadCv(applicationId: string): Observable<Blob> {
    return this.http.get(`${this.API_URL}/${applicationId}/cv`, {
      responseType: 'blob',
    });
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
  return this.http.get(`${this.API_URL}/me/${applicationId}/cv`, {
    responseType: 'blob',
  });
  }
  updateMyApplication(applicationId: string, payload: { githubUrl?: string; cv?: File }): Observable<ApplicationDto> {
  const form = new FormData();
  if (payload.githubUrl !== undefined) form.append('githubUrl', payload.githubUrl);
  if (payload.cv) form.append('cv', payload.cv);

  return this.http.patch<ApplicationDto>(`${this.API_URL}/me/${applicationId}`, form);
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
  status?: string;
  jobTitle?: string;
  candidateName?: string;
  page?: number;
  size?: number;
}): Observable<PageResponse<ApplicationDto>> {

  let params = new HttpParams();
  if (filters?.applicationId) params = params.set('applicationId', filters.applicationId);
  if (filters?.status) params = params.set('status', filters.status);
  if (filters?.jobTitle) params = params.set('jobTitle', filters.jobTitle);
  if (filters?.candidateName) params = params.set('candidateName', filters.candidateName);

  params = params.set('page', String(filters?.page ?? 0));
  params = params.set('size', String(filters?.size ?? 10));

  return this.http.get<PageResponse<ApplicationDto>>(`${this.API_URL}/paged`, { params });
}
}
