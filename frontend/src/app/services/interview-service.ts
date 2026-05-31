import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Subject, forkJoin, of } from 'rxjs';
import { switchMap } from 'rxjs/operators';

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
  candidateName?: string;
  recruiterName?: string;
  scheduledAt: string;
  roomUrl: string;
  recordingConsent: boolean;
  status: 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
  createdAt: string;
  invitedRecruiterIds?: string[];
  candidateAdmitted?: boolean;
}
export interface DimensionalScore {
  score: number;
  evidence: string;
}

export type DimensionKey =
  | 'technical_depth'
  | 'problem_solving'
  | 'requirements_coverage'
  | 'claim_verification'
  | 'communication'
  | 'motivation_fit';

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

  // ── Unified phase-by-phase scoring ───────────────────────────────────────
  preInterviewScore: number | null;
  interviewDelta: number | null;
  finalScore: number | null;
  finalGrade: 'A+' | 'A' | 'B' | 'C' | 'D' | null;
  interviewVerdict: 'CONFIRMED' | 'RAISED' | 'LOWERED' | 'NEW' | null;
  dimensionalScores: Record<DimensionKey, DimensionalScore> | null;

  // ── Pre-interview snapshot (so the journey strip needs no extra fetch) ──
  candidateName: string | null;
  recruiterName: string | null;
  jobTitle: string | null;
  candidateSkills: string[] | null;
  candidateSummary: string | null;
  githubScore: 'STRONG' | 'MODERATE' | 'NO_PUBLIC_WORK' | 'INACTIVE' | 'RATE_LIMITED' | null;
  githubFrameworks: string[] | null;
  cvWeaknesses: string[] | null;
  jobFitScore: number | null;
  preInterviewRecommendation: 'STRONG_YES' | 'YES' | 'MAYBE' | 'NO' | null;
  requiredSkillsMatched: string[] | null;
  requiredSkillsMissing: string[] | null;
  semanticStrengths: string[] | null;
  semanticWeaknesses: string[] | null;
}

export interface InterviewQuestion {
  id: string;
  text: string;
  category: 'technical' | 'behavioral' | 'cv_specific';
  status: 'PENDING' | 'ASKED' | 'SKIPPED';
}

// ── Delegation (organizer hands an interview off to another recruiter) ──
export type DelegationStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'CANCELLED';

export interface CreateDelegationRequest {
  toRecruiterId: string;
  message?: string;
}

export interface DelegationResponse {
  id: string;
  interviewId: string;
  fromRecruiterId: string;
  toRecruiterId: string;
  message?: string | null;
  status: DelegationStatus;
  deadline: string;
  createdAt: string;
  respondedAt?: string | null;
  fromRecruiterName?: string | null;
  toRecruiterName?: string | null;
  jobTitle?: string | null;
  interviewScheduledAt?: string | null;
}

// ── Reschedule request (either side proposes new times for a SCHEDULED interview) ──
export type ReschedStatus = 'PENDING' | 'CONFIRMED' | 'DECLINED' | 'EXPIRED' | 'CANCELLED';
export type ReschedProposedBy = 'RECRUITER' | 'CANDIDATE';

export interface CreateReschedRequest {
  proposedSlots: string[];  // ISO LocalDateTime strings, no Z
  deadline: string;         // ISO LocalDateTime string, no Z
  message?: string;
}

export interface ReschedRequestResponse {
  id: string;
  interviewId: string;
  proposedBy: ReschedProposedBy;
  requesterId: string;
  proposedSlots: string[];
  deadline: string;
  status: ReschedStatus;
  confirmedSlot?: string | null;
  message?: string | null;
  createdAt: string;
  respondedAt?: string | null;
}

// ── Interview proposal (recruiter offers 2-4 slots, candidate picks one) ──
export type ProposalStatus = 'PENDING' | 'CONFIRMED' | 'DECLINED' | 'EXPIRED' | 'CANCELLED';

export interface CreateProposalRequest {
  applicationId: string;
  jobId: string;
  recruiterId: string;
  candidateId: string;
  candidateEmail: string;
  recruiterEmail: string;
  jobTitle: string;
  proposedSlots: string[]; // ISO LocalDateTime strings, no Z
  deadline: string;        // ISO LocalDateTime string, no Z
  message?: string;
}

export interface ProposalResponse {
  id: string;
  applicationId: string;
  jobId: string;
  recruiterId: string;
  candidateId: string;
  candidateEmail: string;
  recruiterEmail: string;
  candidateName?: string;
  recruiterName?: string;
  jobTitle: string;
  proposedSlots: string[];
  deadline: string;
  status: ProposalStatus;
  confirmedSlot?: string | null;
  interviewId?: string | null;
  message?: string | null;
  declineReason?: string | null;
  createdAt: string;
  respondedAt?: string | null;
}


@Injectable({ providedIn: 'root' })
export class InterviewService {

  private base = 'http://localhost:8888/api/interviews';

  /** Broadcast that the interview set has changed (scheduled, started, completed, cancelled, …)
   *  so widgets like the navbar "Interview live now" badge can re-fetch immediately
   *  instead of waiting for their own poll interval. */
  private readonly changedSubject = new Subject<void>();
  readonly changed$ = this.changedSubject.asObservable();
  notifyChanged(): void { this.changedSubject.next(); }

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

  /** Every interview across the team - drives the shared calendar. */
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

  /** The organiser admits the waiting candidate - they can then enter the room. */
  admitCandidate(id: string, requesterId: string, admin: boolean): Observable<InterviewResponse> {
    return this.http.patch<InterviewResponse>(
      `${this.base}/${id}/admit?requesterId=${requesterId}&admin=${admin}`, {});
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

/** All interview results for one application - used by the candidate summary
 *  page. Fans out over the application's interviews and joins their results. */
getResultsByApplication(applicationId: string): Observable<InterviewResultResponse[]> {
  return this.getByApplication(applicationId).pipe(
    switchMap(interviews => {
      if (!interviews.length) return of([] as InterviewResultResponse[]);
      return forkJoin(interviews.map(iv => this.getResult(iv.id)));
    })
  );
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

// ── Proposal endpoints ────────────────────────────────────────────────────
createProposal(req: CreateProposalRequest): Observable<ProposalResponse> {
  return this.http.post<ProposalResponse>(`${this.base}/proposals`, req);
}

getProposal(id: string): Observable<ProposalResponse> {
  return this.http.get<ProposalResponse>(`${this.base}/proposals/${id}`);
}

getProposalsByApplication(applicationId: string): Observable<ProposalResponse[]> {
  return this.http.get<ProposalResponse[]>(
    `${this.base}/proposals/application/${applicationId}`);
}

getProposalsByCandidate(candidateId: string): Observable<ProposalResponse[]> {
  return this.http.get<ProposalResponse[]>(
    `${this.base}/proposals/candidate/${candidateId}`);
}

getProposalsByRecruiter(recruiterId: string): Observable<ProposalResponse[]> {
  return this.http.get<ProposalResponse[]>(
    `${this.base}/proposals/recruiter/${recruiterId}`);
}

/** Candidate picks one of the offered slots by its index in proposedSlots. */
pickProposalSlot(id: string, slotIndex: number): Observable<ProposalResponse> {
  return this.http.post<ProposalResponse>(
    `${this.base}/proposals/${id}/pick?slotIndex=${slotIndex}`, {});
}

cancelProposal(id: string, requesterId: string, admin = false): Observable<ProposalResponse> {
  return this.http.post<ProposalResponse>(
    `${this.base}/proposals/${id}/cancel?requesterId=${requesterId}&admin=${admin}`, {});
}

/** Candidate can't make any offered slot - decline so the recruiter re-proposes. */
declineProposal(id: string, reason?: string): Observable<ProposalResponse> {
  return this.http.post<ProposalResponse>(
    `${this.base}/proposals/${id}/decline`, reason ? { reason } : {});
}

// ── Reschedule endpoints ──────────────────────────────────────────────────
proposeReschedule(interviewId: string, req: CreateReschedRequest): Observable<ReschedRequestResponse> {
  return this.http.post<ReschedRequestResponse>(
    `${this.base}/${interviewId}/reschedule`, req);
}

getReschedRequests(interviewId: string): Observable<ReschedRequestResponse[]> {
  return this.http.get<ReschedRequestResponse[]>(
    `${this.base}/${interviewId}/reschedule`);
}

acceptReschedule(requestId: string, slotIndex: number): Observable<ReschedRequestResponse> {
  return this.http.post<ReschedRequestResponse>(
    `${this.base}/reschedule/${requestId}/accept?slotIndex=${slotIndex}`, {});
}

declineReschedule(requestId: string): Observable<ReschedRequestResponse> {
  return this.http.post<ReschedRequestResponse>(
    `${this.base}/reschedule/${requestId}/decline`, {});
}

cancelReschedule(requestId: string): Observable<ReschedRequestResponse> {
  return this.http.post<ReschedRequestResponse>(
    `${this.base}/reschedule/${requestId}/cancel`, {});
}

// ── Delegation endpoints ──────────────────────────────────────────────────
proposeDelegation(interviewId: string, req: CreateDelegationRequest): Observable<DelegationResponse> {
  return this.http.post<DelegationResponse>(
    `${this.base}/${interviewId}/delegate`, req);
}

getDelegationsForInterview(interviewId: string): Observable<DelegationResponse[]> {
  return this.http.get<DelegationResponse[]>(
    `${this.base}/${interviewId}/delegations`);
}

getIncomingDelegations(): Observable<DelegationResponse[]> {
  return this.http.get<DelegationResponse[]>(`${this.base}/delegations/incoming`);
}

getOutgoingDelegations(): Observable<DelegationResponse[]> {
  return this.http.get<DelegationResponse[]>(`${this.base}/delegations/outgoing`);
}

acceptDelegation(id: string): Observable<DelegationResponse> {
  return this.http.post<DelegationResponse>(`${this.base}/delegations/${id}/accept`, {});
}

declineDelegation(id: string): Observable<DelegationResponse> {
  return this.http.post<DelegationResponse>(`${this.base}/delegations/${id}/decline`, {});
}

cancelDelegation(id: string): Observable<DelegationResponse> {
  return this.http.post<DelegationResponse>(`${this.base}/delegations/${id}/cancel`, {});
}
}