import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { JobOffer } from '../model/jobOffer.model';

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
