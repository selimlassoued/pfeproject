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
  imports: [CommonModule, FormsModule, CvAnalysisDrawer],
  templateUrl: './application-detail.html',
  styleUrl: './application-detail.css',
})
export class ApplicationDetail implements OnInit, OnDestroy {
  private readonly keycloak = inject(Keycloak);

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

  // CV Analysis Drawer
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
    private appService: ApplicationService,
    private jobService: JobService
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) { this.error = 'Missing application id'; return; }

    this.loading = true;
    this.appService.getOne(id).subscribe({
      next: (data) => {
        this.app = data;
        this.newStatus = this.allowedStatuses[0] ?? '';
        this.loading = false;
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
          { label: '→ Move to Review',     status: 'UNDER_REVIEW',    style: 'primary' },
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
}