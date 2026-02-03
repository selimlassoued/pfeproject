import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { JobOffer } from '../model/jobOffer.model';

@Injectable({
  providedIn: 'root',
})
export class JobService {
  private readonly API_URL = 'http://localhost:8082/api/jobs';
  // later → gateway: http://localhost:8888/api/jobs

  constructor(private http: HttpClient) {}

  getAllJobs(): Observable<JobOffer[]> {
    return this.http.get<JobOffer[]>(this.API_URL);
  }

  getJobById(id: string): Observable<JobOffer> {
    return this.http.get<JobOffer>(`${this.API_URL}/${id}`);
  }
}
