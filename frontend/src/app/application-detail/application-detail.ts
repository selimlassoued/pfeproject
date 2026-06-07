import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import Keycloak from 'keycloak-js';
import Swal from 'sweetalert2';

import { ApplicationService, CandidateApplicationSummary } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import { JobService } from '../services/job.service';
import { CvAnalysisDrawer } from '../cv-analysis-drawer/cv-analysis-drawer';
import {
  InterviewService, InterviewResponse, ProposalResponse,
} from '../services/interview-service';
import { OfferService, OfferDto } from '../services/offer.service';
import { NotificationSocketService } from '../services/notification-socket.service';
import { SeenTrackerService } from '../services/seen-tracker.service';
import { Subscription } from 'rxjs';
import { normalizeHttpError } from '../utils/http-error';

// Allowed status transitions - mirrors backend logic
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

/**
 * Recruiter-facing application detail. The interview and offer flows used to
 * live here too, but they grew large enough that they now live on dedicated
 * pages (/application/:id/interviews and /application/:id/offer). This page
 * keeps the candidate overview, status pipeline, signal flow, semantic match,
 * and CV access, plus two compact summary cards that link to the dedicated
 * management pages.
 */
@Component({
  selector: 'app-application-detail',
  imports: [CommonModule, FormsModule, RouterLink, CvAnalysisDrawer],
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

  candidateModerated = false;

  rank: number | null = null;
  rankTotal: number | null = null;

  // ── Summary card state ──────────────────────────────────────────────────
  interviews: InterviewResponse[] = [];
  proposals: ProposalResponse[] = [];
  interviewsLoading = false;

  offer: OfferDto | null = null;
  offerLoading = false;

  private wsInterviewSub?: Subscription;
  private wsOfferSub?: Subscription;

  drawerOpen = false;

  // Signal modal
  signalModalOpen = false;
  signalReason = '';
  signaling = false;
  signalError: string | null = null;
  signalSuccess = false;

  // ── Candidate history (recruiter view of this candidate's other apps) ────
  candidateHistory: CandidateApplicationSummary[] = [];
  showHistory = false;
  historyLoading = false;
  private historyLoaded = false;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private interviewService: InterviewService,
    private offerService: OfferService,
    private appService: ApplicationService,
    private jobService: JobService,
    private socket: NotificationSocketService,
    private seen: SeenTrackerService,
  ) {}

  // ── Lifecycle ───────────────────────────────────────────────────────────
  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.error = 'Missing application id';
      return;
    }
    this.loading = true;
    this.appService.getOne(id).subscribe({
      next: (data) => {
        this.app = data;
        this.newStatus = this.allowedStatuses[0] ?? '';
        this.loading = false;
        this.loadInterviewSummary(data.applicationId);
        this.loadOffer(data.applicationId);
        this.loadSemanticMatch(id);
        this.appService.getApplicationRank(id).subscribe({
          next: (r) => { this.rank = r.rank; this.rankTotal = r.total; },
          error: () => {}
        });
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
        this.error = normalizeHttpError(err).message || 'Failed to load application';
        this.loading = false;
      },
    });

    // Live updates: refresh summary card data when the backend pings us via
    // STOMP for any change to this user's interview set or for any offer
    // mutation (created / revised / accepted / declined / withdrawn / expired).
    // The push covers other tabs and the other party's actions, no refresh.
    this.wsInterviewSub = this.socket.interviewListChanged$.subscribe(() => {
      if (this.app?.applicationId) this.loadInterviewSummary(this.app.applicationId);
    });
    this.wsOfferSub = this.socket.offerChanged$.subscribe(ev => {
      // Filter to this application so other recruiter offer events on the same
      // tab (e.g. switching between candidates) don't trigger spurious refetches.
      if (!this.app?.applicationId) return;
      if (ev?.applicationId && ev.applicationId !== this.app.applicationId) return;
      this.loadOffer(this.app.applicationId);
    });
  }

  ngOnDestroy(): void {
    if (this.pollTimer) clearTimeout(this.pollTimer);
    this.wsInterviewSub?.unsubscribe();
    this.wsOfferSub?.unsubscribe();
  }

  // ── Semantic match ──────────────────────────────────────────────────────
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

  // ── Role helpers ────────────────────────────────────────────────────────
  isSuperAdmin(): boolean { return this.keycloak.hasRealmRole('SUPERADMIN'); }
  isAdmin():      boolean { return this.keycloak.hasRealmRole('ADMIN'); }
  isRecruiter():  boolean { return this.keycloak.hasRealmRole('RECRUITER'); }

  /** Only RECRUITER can signal a candidate. ADMIN/SUPERADMIN block directly. */
  canSignal(): boolean {
    return this.isRecruiter() && !this.isAdmin() && !this.isSuperAdmin();
  }

  get recruiterEmail(): string { return this.keycloak.tokenParsed?.['email'] ?? ''; }
  get recruiterId(): string { return this.keycloak.subject ?? ''; }

  // ── Status helpers ──────────────────────────────────────────────────────
  get allowedStatuses(): string[] {
    return ALLOWED_TRANSITIONS[this.app?.status ?? ''] ?? [];
  }

  get isFinalStatus(): boolean { return this.allowedStatuses.length === 0; }

  get isModerated(): boolean {
    return this.candidateModerated
        || this.app?.status === 'FLAGGED'
        || this.app?.status === 'BLOCKED';
  }

  get hasSemanticMatch(): boolean { return this.app?.jobFitScore != null; }

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

  // ── Pipeline helpers ────────────────────────────────────────────────────
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

  // ── Navigation / actions ────────────────────────────────────────────────
  openAnalysis(): void { this.drawerOpen = true; }
  closeDrawer(): void  { this.drawerOpen = false; }

  /** Open/close the "candidate history" section and lazy-load it on first
   *  open. Excludes the currently-viewed application from the result. */
  toggleHistory(): void {
    this.showHistory = !this.showHistory;
    if (this.showHistory && !this.historyLoaded && this.app?.candidateUserId && this.app?.applicationId) {
      this.historyLoading = true;
      this.appService.listByCandidate(this.app.candidateUserId, this.app.applicationId).subscribe({
        next: (rows) => {
          this.candidateHistory = rows || [];
          this.historyLoaded = true;
          this.historyLoading = false;
        },
        error: () => {
          this.candidateHistory = [];
          this.historyLoading = false;
        },
      });
    }
  }

  /** Navigate to one of the candidate's other applications. */
  openHistoryRow(row: CandidateApplicationSummary): void {
    if (row?.applicationId) {
      this.router.navigate(['/application', row.applicationId]);
    }
  }

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

  // ── Status-change buttons ───────────────────────────────────────────────
  get contextualActions(): { label: string; status: string; style: 'primary' | 'secondary' | 'danger' }[] {
    switch (this.app?.status) {
      case 'APPLIED':
        return [
          { label: 'Still deciding', status: 'UNDER_REVIEW', style: 'primary' },
          { label: '✗ Reject',       status: 'REJECTED',     style: 'danger' },
        ];
      case 'UNDER_REVIEW':
        return [
          { label: '✗ Reject',       status: 'REJECTED',     style: 'danger' },
        ];
      case 'INTERVIEW_PHASE':
        return [
          { label: '→ Send Offer',   status: 'OFFER',        style: 'primary' },
          { label: '✗ Reject',       status: 'REJECTED',     style: 'danger' },
        ];
      case 'OFFER':
        return [
          { label: '✓ Hire',         status: 'HIRED',        style: 'primary' },
          { label: '✗ Reject',       status: 'REJECTED',     style: 'danger' },
        ];
      default:
        return [];
    }
  }

  moveTo(status: string) {
    if (!this.app?.applicationId) return;
    if (status === 'REJECTED') {
      Swal.fire({
        title: 'Reject this application?',
        text: 'The candidate will be notified. This action cannot be undone.',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: 'Yes, reject',
        cancelButtonText: 'Cancel',
        confirmButtonColor: '#dc2626',
        cancelButtonColor: '#374151',
        background: '#141c3c',
        color: '#e8f0fe',
      }).then((result) => {
        if (result.isConfirmed) this.applyStatus(status);
      });
      return;
    }
    this.applyStatus(status);
  }

  private applyStatus(status: string) {
    if (!this.app?.applicationId) return;
    this.updatingStatus = true;
    this.appService.updateApplicationStatus(this.app.applicationId, status).subscribe({
      next: (updated) => {
        this.app = updated;
        this.updatingStatus = false;
        if (status === 'HIRED') this.checkQuotaAfterHire();
      },
      error: (err) => {
        this.error = normalizeHttpError(err).message || 'Failed to update status';
        this.updatingStatus = false;
      },
    });
  }

  private checkQuotaAfterHire(): void {
    if (!this.app?.jobId) return;
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

  // ── Signal modal (RECRUITER only) ───────────────────────────────────────
  openSignalModal(): void {
    if (!this.canSignal()) return;
    this.signalModalOpen = true;
    this.signalReason = '';
    this.signalError = null;
    this.signalSuccess = false;
  }

  closeSignalModal(): void { this.signalModalOpen = false; }

  submitSignal(): void {
    if (!this.app?.candidateUserId || !this.signalReason.trim() || !this.canSignal()) return;
    this.signaling = true;
    this.signalError = null;
    this.appService.signalCandidate(this.app.candidateUserId, this.signalReason.trim()).subscribe({
      next: () => {
        this.signalSuccess = true;
        this.signaling = false;
        this.candidateModerated = true;
        setTimeout(() => {
          this.signalModalOpen = false;
          this.signalSuccess = false;
        }, 1500);
      },
      error: (err) => {
        this.signalError = normalizeHttpError(err).message || 'Failed to signal candidate';
        this.signaling = false;
      },
    });
  }

  // ── Summary loaders ─────────────────────────────────────────────────────
  /** Lightweight fetch of interviews + proposals so the summary card can show
   *  counts, the next scheduled interview, and pending/declined badges. The
   *  heavy management lives on /application/:id/interviews. */
  private loadInterviewSummary(applicationId: string): void {
    this.interviewsLoading = true;
    this.interviewService.getByApplication(applicationId).subscribe({
      next: (list) => { this.interviews = list; this.interviewsLoading = false; },
      error: () => { this.interviewsLoading = false; }
    });
    this.interviewService.getProposalsByApplication(applicationId).subscribe({
      next: (list) => { this.proposals = list; },
      error: () => { this.proposals = []; },
    });
  }

  private loadOffer(applicationId: string): void {
    this.offerLoading = true;
    this.offerService.get(applicationId).subscribe({
      next: (o) => { this.offer = o || null; this.offerLoading = false; },
      error: () => { this.offer = null; this.offerLoading = false; },
    });
  }

  // ── Summary getters used by the cards ───────────────────────────────────
  get nextScheduledInterview(): InterviewResponse | null {
    return this.interviews.find(i => i.status === 'SCHEDULED') ?? null;
  }

  get completedInterviewsCount(): number {
    return this.interviews.filter(i => i.status === 'COMPLETED').length;
  }

  get pendingProposal(): ProposalResponse | null {
    return this.proposals.find(p => p.status === 'PENDING') ?? null;
  }

  /** Surfaced only while there's no pending proposal and no scheduled
   *  interview to supersede it - same gating as on the dedicated page. */
  get lastDeclinedProposal(): ProposalResponse | null {
    if (this.pendingProposal || this.nextScheduledInterview) return null;
    const declined = this.proposals
      .filter(p => p.status === 'DECLINED')
      .sort((a, b) => (b.respondedAt || '').localeCompare(a.respondedAt || ''));
    return declined[0] ?? null;
  }

  /** Offers can be sent once we're past the interview phase. Drives the offer
   *  summary card label ("Make offer" vs "Available once interview phase"). */
  get canMakeOffer(): boolean {
    const s = this.app?.status;
    return s === 'INTERVIEW_PHASE' || s === 'OFFER';
  }

  // ── NEW-since-you-saw-it badges ─────────────────────────────────────────
  /** Latest interview-related activity for this application, in epoch ms. */
  private latestInterviewActivityMs(): number {
    let max = 0;
    for (const iv of this.interviews) {
      const t = this.seen.asMs(iv.scheduledAt);
      if (t > max) max = t;
    }
    for (const p of this.proposals) {
      const a = this.seen.asMs(p.respondedAt);
      const b = this.seen.asMs(p.createdAt);
      if (a > max) max = a;
      if (b > max) max = b;
    }
    return max;
  }

  /** Latest offer activity (offer creation + every revision + a response). */
  private latestOfferActivityMs(): number {
    if (!this.offer) return 0;
    let max = Math.max(
      this.seen.asMs(this.offer.createdAt),
      this.seen.asMs(this.offer.respondedAt),
    );
    for (const r of this.offer.revisions || []) {
      const t = this.seen.asMs(r.createdAt);
      if (t > max) max = t;
    }
    return max;
  }

  /** True when there's interview data the recruiter hasn't opened yet. */
  get hasNewInterviews(): boolean {
    if (!this.app?.applicationId) return false;
    return this.seen.isNew(this.app.applicationId, 'interviews',
                            this.latestInterviewActivityMs());
  }

  /** True when there's offer data the recruiter hasn't opened yet. */
  get hasNewOffer(): boolean {
    if (!this.app?.applicationId) return false;
    return this.seen.isNew(this.app.applicationId, 'offer',
                            this.latestOfferActivityMs());
  }
}
