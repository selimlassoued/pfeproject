import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ScheduleInterviewRequest {
  applicationId: string;
  jobId: string;
  recruiterId: string;
  candidateId: string;
  candidateEmail: string;
  recruiterEmail: string;
  jobTitle: string;
  scheduledAt: string;
  recordingConsent: boolean;
}

export interface InterviewResponse {
  id: string;
  applicationId: string;
  jobId: string;
  jobTitle: string;
  candidateEmail: string;
  recruiterEmail: string;
  recruiterId: string;
  scheduledAt: string;
  roomUrl: string;
  recordingConsent: boolean;
  status: 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
  createdAt: string;
  invitedRecruiterIds?: string[];
}
export interface InterviewResultResponse {
  id: string;
  interviewId: string;
  processingStatus: 'PENDING' | 'TRANSCRIBING' | 'ANALYSING' | 'COMPLETED' | 'FAILED';
  transcript: string | null;
  summary: string | null;
  candidateScore: number | null;
  candidateStrengths: string[] | null;
  candidateWeaknesses: string[] | null;
  suggestedQuestions: string[] | null;
  hiringRecommendation: 'STRONG_YES' | 'YES' | 'MAYBE' | 'NO' | null;
  errorMessage: string | null;
  createdAt: string;
  processedAt: string | null;
}

export interface InterviewQuestion {
  id: string;
  text: string;
  category: 'technical' | 'behavioral' | 'cv_specific';
  status: 'PENDING' | 'ASKED' | 'SKIPPED';
}


@Injectable({ providedIn: 'root' })
export class InterviewService {

  private base = 'http://localhost:8888/api/interviews';
  
  constructor(private http: HttpClient) {}

  schedule(request: ScheduleInterviewRequest): Observable<InterviewResponse> {
    return this.http.post<InterviewResponse>(this.base, request);
  }

  cancelInterview(id: string, requesterId: string, admin: boolean): Observable<InterviewResponse> {
    return this.http.patch<InterviewResponse>(
      `${this.base}/${id}/cancel?requesterId=${requesterId}&admin=${admin}`, {});
  }

  getById(id: string): Observable<InterviewResponse> {
    return this.http.get<InterviewResponse>(`${this.base}/${id}`);
  }

  getByApplication(applicationId: string): Observable<InterviewResponse[]> {
    return this.http.get<InterviewResponse[]>(
      `${this.base}/application/${applicationId}`
    );
  }

  getByCandidate(candidateId: string): Observable<InterviewResponse[]> {
    return this.http.get<InterviewResponse[]>(
      `${this.base}/candidate/${candidateId}`
    );
  }

  getByRecruiter(recruiterId: string): Observable<InterviewResponse[]> {
    return this.http.get<InterviewResponse[]>(
      `${this.base}/recruiter/${recruiterId}`
    );
  }

  /** Every interview across the team — drives the shared calendar. */
  getAll(): Observable<InterviewResponse[]> {
    return this.http.get<InterviewResponse[]>(this.base);
  }

  /** Invite another recruiter so they too can join this interview. */
  invite(id: string, recruiterId: string): Observable<InterviewResponse> {
    return this.http.patch<InterviewResponse>(
      `${this.base}/${id}/invite?recruiterId=${recruiterId}`, {});
  }

  uninvite(id: string, recruiterId: string): Observable<InterviewResponse> {
    return this.http.patch<InterviewResponse>(
      `${this.base}/${id}/uninvite?recruiterId=${recruiterId}`, {});
  }

  /** Ask the organizer of an interview to invite you. */
  requestJoin(id: string, requesterId: string, requesterName: string): Observable<void> {
    return this.http.post<void>(
      `${this.base}/${id}/request-join?requesterId=${requesterId}`
      + `&requesterName=${encodeURIComponent(requesterName)}`, {});
  }

updateConsent(id: string, consent: boolean): Observable<InterviewResponse> {
  return this.http.patch<InterviewResponse>(
    `${this.base}/${id}/consent`,
    { recordingConsent: consent }
  );
}

  start(id: string): Observable<InterviewResponse> {
    return this.http.patch<InterviewResponse>(`${this.base}/${id}/start`, {});
  }

  complete(id: string): Observable<InterviewResponse> {
    return this.http.patch<InterviewResponse>(`${this.base}/${id}/complete`, {});
  }

  cancel(id: string): Observable<InterviewResponse> {
    return this.http.patch<InterviewResponse>(`${this.base}/${id}/cancel`, {});
  }
uploadRecording(interviewId: string, file: File, role: string, joinedAt: string, leftAt: string): Observable<any> {
  const form = new FormData();
  form.append('file', file);
  form.append('role', role);
  form.append('joinedAt', joinedAt);
  form.append('leftAt', leftAt);
  return this.http.post(`${this.base}/${interviewId}/recording`, form);
}

notifyLeft(interviewId: string, role: string): Observable<any> {
  return this.http.post(`${this.base}/${interviewId}/left`, { role });
}
getResult(interviewId: string): Observable<InterviewResultResponse> {
  return this.http.get<InterviewResultResponse>(`${this.base}/${interviewId}/result`);
}
generateQuestions(interviewId: string): Observable<void> {
  return this.http.post<void>(`${this.base}/${interviewId}/questions/generate`, {});
}

getQuestions(interviewId: string): Observable<InterviewQuestion[]> {
  return this.http.get<InterviewQuestion[]>(`${this.base}/${interviewId}/questions`);
}

markQuestion(interviewId: string, questionId: string, status: 'ASKED' | 'SKIPPED'): Observable<void> {
  return this.http.patch<void>(`${this.base}/${interviewId}/questions/${questionId}`, { status });
}
}