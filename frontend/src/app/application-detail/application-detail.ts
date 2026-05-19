import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import { FormsModule } from '@angular/forms';
import { CvAnalysisDrawer } from '../cv-analysis-drawer/cv-analysis-drawer';
import { JobService } from '../services/job.service';
import Keycloak from 'keycloak-js';
import Swal from 'sweetalert2';
import { InterviewResponse } from '../services/interview-service';
import { ScheduleInterview } from '../schedule-interview/schedule-interview';
import{ InterviewService } from '../services/interview-service';
import { UserService } from '../services/user-service';
import { AdminUserRow } from '../model/admin_users.type';

// Allowed status transitions — mirrors backend logic
const ALLOWED_TRANSITIONS: Record<string, string[]> = {
  APPLIED:          ['UNDER_REVIEW', 'REJECTED'],
  UNDER_REVIEW:     ['INTERVIEW_PHASE', 'REJECTED'],
  INTERVIEW_PHASE:  ['OFFER', 'REJECTED'],
  OFFER:            ['HIRED', 'REJECTED'],
  HIRED:            [],
  REJECTED:         [],
  FLAGGED:          [],
  BLOCKED:          [],
  WITHDRAWN:        [],
};

@Component({
  selector: 'app-application-detail',
  imports: [CommonModule, FormsModule, CvAnalysisDrawer,ScheduleInterview],
  templateUrl: './application-detail.html',
  styleUrl: './application-detail.css',
})
export class ApplicationDetail implements OnInit, OnDestroy {

  private keycloak = inject(Keycloak);

  newStatus = '';
  updatingStatus = false;
  app: ApplicationDto | null = null;
  loading = false;
  error: string | null = null;
  semanticLoading = false;
  semanticPending = false;

  private pollTimer: ReturnType<typeof setTimeout> | null = null;

  // Candidate moderation status — loaded separately from application status
  candidateModerated = false;

  // Rank badge
  rank: number | null = null;
  rankTotal: number | null = null;

  interviews: InterviewResponse[] = [];
  interviewsLoading = false;
  showScheduleForm = false;
  scheduledInterview: InterviewResponse | null = null;
  cancellingId: string | null = null;

  // ── Recruiter invitations to the interview room ──
  recruiters: AdminUserRow[] = [];
  inviteSelection = '';
  invitingId: string | null = null;
  requestingJoin = false;
  joinRequestSent = false;


  drawerOpen = false;

  // Signal modal
  signalModalOpen = false;
  signalReason = '';
  signaling = false;
  signalError: string | null = null;
  signalSuccess = false;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private interviewService: InterviewService,
    private appService: ApplicationService,
    private jobService: JobService,
    private userService: UserService
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) { this.error = 'Missing application id'; return; }
    if (id) {
      this.loadApplication(id);
    }
    this.loadRecruiters();
    if (!id) {
      this.error = 'Missing application id';
      return;
    }

    // Exactly one getOne() call. loadSemanticMatch enriches `this.app` with
    // jobFitScore asynchronously — a second concurrent getOne() would resolve
    // later and overwrite that enrichment (plain getOne carries no semantic
    // match), silently blanking the score in the view.
    this.loading = true;
    this.appService.getOne(id).subscribe({
      next: (data) => {
        this.app = data;
        this.newStatus = this.allowedStatuses[0] ?? '';
        this.loading = false;
        this.loadInterviews(data.applicationId);
        this.loadSemanticMatch(id);
        this.appService.getApplicationRank(id).subscribe({
          next: (r) => { this.rank = r.rank; this.rankTotal = r.total; },
          error: () => {}
        });
        // Load candidate moderation status separately
        if (data.candidateUserId) {
          this.appService.getCandidateModerationStatus(data.candidateUserId).subscribe({
            next: (s) => {
              this.candidateModerated = s?.status === 'FLAGGED' || s?.status === 'BLOCKED';
            },
            error: () => { this.candidateModerated = false; }
          });
        }
      },
      error: (err) => {
        this.error = err?.error?.message || 'Failed to load application';
        this.loading = false;
      },
    });
  }

  ngOnDestroy(): void {
    if (this.pollTimer) clearTimeout(this.pollTimer);
  }

  private loadSemanticMatch(applicationId: string, attempt = 0): void {
    if (attempt === 0) this.semanticLoading = true;
    this.appService.getSemanticMatch(applicationId).subscribe({
      next: (match) => {
        this.semanticLoading = false;
        if (!match) {
          this.semanticPending = true;
          if (attempt < 25) {
            this.pollTimer = setTimeout(() => this.loadSemanticMatch(applicationId, attempt + 1), 5000);
          }
          return;
        }
        this.semanticPending = false;
        this.app = { ...(this.app ?? {} as ApplicationDto), ...match } as ApplicationDto;
        if (this.app?.status === 'APPLIED') {
          this.pollTimer = setTimeout(() => this.refreshSemanticMatch(applicationId), 15000);
        }
      },
      error: () => {
        this.semanticLoading = false;
        this.semanticPending = true;
        if (attempt < 25) {
          this.pollTimer = setTimeout(() => this.loadSemanticMatch(applicationId, attempt + 1), 5000);
        }
      }
    });
  }

  private refreshSemanticMatch(applicationId: string): void {
    if (this.app?.status !== 'APPLIED') return;
    this.appService.getSemanticMatch(applicationId).subscribe({
      next: (match) => {
        if (!match) {
          this.semanticPending = true;
          this.pollTimer = setTimeout(() => this.loadSemanticMatch(applicationId, 0), 0);
          return;
        }
        this.semanticPending = false;
        this.app = { ...(this.app ?? {} as ApplicationDto), ...match } as ApplicationDto;
        this.pollTimer = setTimeout(() => this.refreshSemanticMatch(applicationId), 15000);
      },
      error: () => {
        this.pollTimer = setTimeout(() => this.refreshSemanticMatch(applicationId), 15000);
      }
    });
  }

  // ── Role helpers ──────────────────────────────────────────────────────────

  isSuperAdmin(): boolean { return this.keycloak.hasRealmRole('SUPERADMIN'); }
  isAdmin():      boolean { return this.keycloak.hasRealmRole('ADMIN'); }
  isRecruiter():  boolean { return this.keycloak.hasRealmRole('RECRUITER'); }

  /**
   * Only RECRUITER can signal a candidate.
   * ADMIN and SUPERADMIN block directly — they don't signal.
   */
  canSignal(): boolean {
    return this.isRecruiter() && !this.isAdmin() && !this.isSuperAdmin();
  }

  // ── Status helpers ────────────────────────────────────────────────────────

  get allowedStatuses(): string[] {
    return ALLOWED_TRANSITIONS[this.app?.status ?? ''] ?? [];
  }

  get isFinalStatus(): boolean {
    return this.allowedStatuses.length === 0;
  }

  get isModerated(): boolean {
    return this.candidateModerated
        || this.app?.status === 'FLAGGED'
        || this.app?.status === 'BLOCKED';
  }

  get hasSemanticMatch(): boolean {
    return this.app?.jobFitScore != null;
  }

  semanticScoreLabel(score: number | null | undefined): string {
    if (score == null) return 'Pending';
    if (score >= 80) return 'Strong fit';
    if (score >= 60) return 'Good fit';
    if (score >= 40) return 'Partial fit';
    return 'Low fit';
  }

  recommendationLabel(value: string | null | undefined): string {
    const v = (value || 'REVIEW').toUpperCase();
    if (v === 'HIRE') return 'Hire';
    if (v === 'INTERVIEW') return 'Interview';
    if (v === 'REJECT') return 'Reject';
    return 'Review';
  }

  // ── Pipeline helpers ──────────────────────────────────────────────────────

  private readonly PIPELINE_ORDER = ['APPLIED', 'UNDER_REVIEW', 'INTERVIEW_PHASE', 'OFFER', 'HIRED'];

  isStepDone(step: string): boolean {
    if (!this.app?.status || this.isModerated) return false;
    const currentIdx = this.PIPELINE_ORDER.indexOf(this.app.status);
    const stepIdx    = this.PIPELINE_ORDER.indexOf(step);
    return stepIdx < currentIdx;
  }

  stepLabel(step: string): string {
    const labels: Record<string, string> = {
      APPLIED: 'Applied', UNDER_REVIEW: 'Review',
      INTERVIEW_PHASE: 'Interview', OFFER: 'Offer', HIRED: 'Hired',
    };
    return labels[step] ?? step;
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  openAnalysis(): void { this.drawerOpen = true; }
  closeDrawer(): void  { this.drawerOpen = false; }

  goToJob() {
    if (this.app?.jobId) this.router.navigate(['/jobs', this.app.jobId]);
  }

  goToUser() {
    if (this.app?.candidateUserId) this.router.navigate(['/user', this.app.candidateUserId]);
  }

  downloadCv() {
    if (!this.app?.applicationId) return;
    this.appService.downloadCv(this.app.applicationId).subscribe(blob => {
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = this.app?.cvFileName || 'cv.pdf';
      a.click();
      window.URL.revokeObjectURL(url);
    });
  }

  backToList(): void { this.router.navigate(['/listApplications']); }

  // Contextual action buttons per status
  get contextualActions(): { label: string; status: string; style: 'primary' | 'secondary' | 'danger' }[] {
    switch (this.app?.status) {
      case 'APPLIED':
        return [
          { label: '→ Move to Interview',  status: 'INTERVIEW_PHASE', style: 'primary' },
          { label: 'Still deciding',     status: 'UNDER_REVIEW',    style: 'secondary' },
          { label: '✗ Reject',             status: 'REJECTED',         style: 'danger' },
        ];
      case 'UNDER_REVIEW':
        return [
          { label: '→ Move to Interview',  status: 'INTERVIEW_PHASE', style: 'primary' },
          { label: '✗ Reject',             status: 'REJECTED',         style: 'danger' },
        ];
      case 'INTERVIEW_PHASE':
        return [
          { label: '→ Send Offer',         status: 'OFFER',            style: 'primary' },
          { label: '✗ Reject',             status: 'REJECTED',         style: 'danger' },
        ];
      case 'OFFER':
        return [
          { label: '✓ Hire',               status: 'HIRED',            style: 'primary' },
          { label: '✗ Reject',             status: 'REJECTED',         style: 'danger' },
        ];
      default:
        return [];
    }
  }

  moveTo(status: string) {
    if (!this.app?.applicationId) return;
    this.updatingStatus = true;
    this.appService.updateApplicationStatus(this.app.applicationId, status).subscribe({
      next: (updated) => {
        this.app = updated;
        this.updatingStatus = false;
        if (status === 'HIRED') this.checkQuotaAfterHire();
      },
      error: (err) => {
        this.error = err?.error?.message || 'Failed to update status';
        this.updatingStatus = false;
      },
    });
  }

  private checkQuotaAfterHire(): void {
    if (!this.app?.jobId) return;
    // Wait for afterCommit listener to run incrementHired + closeJob
    setTimeout(() => {
      this.jobService.getJobById(this.app!.jobId).subscribe({
        next: (job) => {
          if (job.jobStatus === 'CLOSED' || (job.openings != null && (job.hiredCount ?? 0) >= job.openings)) {
            Swal.fire({
              title: 'Position filled',
              html: `The hiring quota for <strong>${job.title}</strong> has been reached ` +
                    `(${job.hiredCount}/${job.openings}). The job has been closed automatically ` +
                    `and remaining candidates were rejected.`,
              icon: 'info',
              confirmButtonText: 'Got it',
              background: '#0f1623',
              color: '#f8fafc',
              confirmButtonColor: '#3b82f6',
            });
          }
        },
        error: () => {}
      });
    }, 2000);
  }

  updateStatus() {
    if (!this.app?.applicationId || !this.newStatus) return;
    this.moveTo(this.newStatus);
  }

  // ── Signal modal — RECRUITER only ─────────────────────────────────────────

  openSignalModal(): void {
    if (!this.canSignal()) return;
    this.signalModalOpen = true;
    this.signalReason = '';
    this.signalError = null;
    this.signalSuccess = false;
  }

  closeSignalModal(): void {
    this.signalModalOpen = false;
  }

  submitSignal(): void {
    if (!this.app?.candidateUserId || !this.signalReason.trim() || !this.canSignal()) return;

    this.signaling = true;
    this.signalError = null;

    this.appService.signalCandidate(this.app.candidateUserId, this.signalReason.trim()).subscribe({
      next: () => {
        this.signalSuccess = true;
        this.signaling = false;
        // Set moderated immediately so button disappears right away
        this.candidateModerated = true;
        setTimeout(() => {
          this.signalModalOpen = false;
          this.signalSuccess = false;
        }, 1500);
      },
      error: (err) => {
        this.signalError = err?.error?.message || 'Failed to signal candidate';
        this.signaling = false;
      },
    });
  }

  loadInterviews(applicationId: string) {
    this.interviewsLoading = true;
    this.interviewService.getByApplication(applicationId).subscribe({
      next: (list: InterviewResponse[]) => {
        this.interviews = list;
        this.interviewsLoading = false;
      },
      error: () => { this.interviewsLoading = false; }
    });
  }

  viewSummary() {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) this.router.navigate(['/application', id, 'summary']);
  }

  // The most recent non-cancelled/completed interview
  get activeInterview(): InterviewResponse | null {
    return this.interviews.find(
      i => i.status === 'SCHEDULED' || i.status === 'IN_PROGRESS'
    ) ?? null;
  }

  /** A recruiter may only cancel interviews they scheduled — admins can cancel any. */
  canCancel(iv: InterviewResponse): boolean {
    return this.isAdmin() || this.isSuperAdmin() || iv.recruiterId === this.recruiterId;
  }

  cancelInterview(interview: InterviewResponse) {
  if (!confirm('Are you sure you want to cancel this interview?')) return;
  this.cancellingId = interview.id;
  const admin = this.isAdmin() || this.isSuperAdmin();
  this.interviewService.cancelInterview(interview.id, this.recruiterId, admin).subscribe({
    next: (updated) => {
      const idx = this.interviews.findIndex(i => i.id === updated.id);
      if (idx !== -1) this.interviews[idx] = updated;
      this.cancellingId = null;
    },
    error: () => { this.cancellingId = null; }
  });
}
  get canSchedule(): boolean {
    return this.app?.status === 'INTERVIEW_PHASE' && this.activeInterview === null;
  }
  get recruiterEmail(): string {
  return this.keycloak.tokenParsed?.['email'] ?? '';
}

get recruiterId(): string {
  return this.keycloak.subject ?? '';
}
get isKeycloakReady(): boolean {
  return !!this.keycloak.tokenParsed && !!this.keycloak.subject;
}
  onInterviewScheduled(interview: InterviewResponse) {
  this.scheduledInterview = interview;
  this.showScheduleForm = false;
  if (this.app!.applicationId) {
    this.loadInterviews(this.app!.applicationId);
  }
    this.scheduledInterview = null;
}
joinInterview() {
  const iv = this.activeInterview;
  if (!iv || !this.canJoin(iv)) return;
  this.router.navigate(['/interview', iv.id, 'room']);
}

// ── Interview-room access ────────────────────────────────────────────────

/** Load the team's recruiters so the organizer can pick who to invite. */
private loadRecruiters(): void {
  this.userService.listUsers({ max: 200 })
    .then(users => {
      this.recruiters = users.filter(u =>
        ((u.roles ?? []).includes('RECRUITER') || u.role === 'RECRUITER')
        && u.id !== this.recruiterId);
    })
    .catch(() => { this.recruiters = []; });
}

/** A recruiter may join only their own interview, one they're invited to, or as admin. */
canJoin(iv: InterviewResponse): boolean {
  return this.isAdmin() || this.isSuperAdmin()
    || iv.recruiterId === this.recruiterId
    || (iv.invitedRecruiterIds ?? []).includes(this.recruiterId);
}

/** Only the recruiter who scheduled the interview manages its invitations. */
canManageInvites(iv: InterviewResponse): boolean {
  return iv.recruiterId === this.recruiterId;
}

/** Notify the organizer that this recruiter would like to be invited. */
requestToJoin(iv: InterviewResponse): void {
  this.requestingJoin = true;
  const t = this.keycloak.tokenParsed as Record<string, string> | undefined;
  const name = t?.['name']
    || `${t?.['given_name'] ?? ''} ${t?.['family_name'] ?? ''}`.trim()
    || t?.['preferred_username']
    || this.recruiterEmail
    || 'A recruiter';
  this.interviewService.requestJoin(iv.id, this.recruiterId, name).subscribe({
    next: () => { this.requestingJoin = false; this.joinRequestSent = true; },
    error: () => { this.requestingJoin = false; },
  });
}

/** Recruiters not yet invited — drives the invite dropdown. */
availableRecruiters(iv: InterviewResponse): AdminUserRow[] {
  const invited = new Set(iv.invitedRecruiterIds ?? []);
  return this.recruiters.filter(r => !invited.has(r.id));
}

recruiterLabel(id: string): string {
  const r = this.recruiters.find(x => x.id === id);
  if (!r) return 'Recruiter';
  const name = `${r.firstName ?? ''} ${r.lastName ?? ''}`.trim();
  return name || r.username || r.email || 'Recruiter';
}

inviteRecruiter(iv: InterviewResponse): void {
  if (!this.inviteSelection) return;
  this.invitingId = iv.id;
  this.interviewService.invite(iv.id, this.inviteSelection).subscribe({
    next: updated => {
      const idx = this.interviews.findIndex(i => i.id === updated.id);
      if (idx !== -1) this.interviews[idx] = updated;
      this.inviteSelection = '';
      this.invitingId = null;
    },
    error: () => { this.invitingId = null; },
  });
}

uninviteRecruiter(iv: InterviewResponse, recruiterId: string): void {
  this.invitingId = iv.id;
  this.interviewService.uninvite(iv.id, recruiterId).subscribe({
    next: updated => {
      const idx = this.interviews.findIndex(i => i.id === updated.id);
      if (idx !== -1) this.interviews[idx] = updated;
      this.invitingId = null;
    },
    error: () => { this.invitingId = null; },
  });
}
viewResult(interviewId: string) {
  this.router.navigate(['/interview', interviewId, 'result']);
}
}
