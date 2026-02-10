import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApplicationDto } from '../model/application.dto';

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

  applyToJob(jobId: string, payload: { githubUrl: string; cv: File }): Observable<any> {
    const form = new FormData();
    form.append('jobId', jobId);
    form.append('githubUrl', payload.githubUrl);
    form.append('cv', payload.cv);

    return this.http.post(this.API_URL, form);
  }
  getApplicationById(id: string) {
  return this.http.get<ApplicationDto>(`${this.API_URL}/${id}`);
}
}
