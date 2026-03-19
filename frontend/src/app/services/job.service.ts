import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { JobOffer } from '../model/jobOffer.model';
import { PageResponse } from '../model/page-response';

@Injectable({
  providedIn: 'root',
})
export class JobService {
  private readonly API_URL = 'http://localhost:8888/api/jobs';

  constructor(private http: HttpClient) {}

  getAllJobs(): Observable<JobOffer[]> {
    return this.http.get<JobOffer[]>(this.API_URL);
  }

  getJobById(id: string): Observable<JobOffer> {
    return this.http.get<JobOffer>(`${this.API_URL}/${id}`);
  }

  searchJobs(
    query?: string,
    employmentType?: string,
    jobStatus?: string,
    minSalary?: number,
    maxSalary?: number,
    page: number = 0,
    size: number = 10
  ): Observable<PageResponse<JobOffer>> {
    let params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());

    if (query?.trim()) params = params.set('query', query.trim());
    if (employmentType) params = params.set('employmentType', employmentType);
    if (jobStatus) params = params.set('jobStatus', jobStatus);
    if (minSalary !== undefined && minSalary !== null) params = params.set('minSalary', minSalary.toString());
    if (maxSalary !== undefined && maxSalary !== null) params = params.set('maxSalary', maxSalary.toString());

    return this.http.get<PageResponse<JobOffer>>(`${this.API_URL}/search`, { params });
  }

  createJob(payload: Omit<JobOffer, 'id'>): Observable<JobOffer> {
    return this.http.post<JobOffer>(this.API_URL, payload);
  }
  updateJob(id: string, payload: Omit<JobOffer, 'id'>): Observable<JobOffer> {
    return this.http.put<JobOffer>(`${this.API_URL}/${id}`, payload);
  }

  deleteJob(id: string): Observable<void> {
    return this.http.delete<void>(`${this.API_URL}/${id}`);
  }
}