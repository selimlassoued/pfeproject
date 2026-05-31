import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink, Router } from '@angular/router';
import { JobService } from '../services/job.service';
import { JobOffer } from '../model/jobOffer.model';
import { AuthService } from '../services/AuthService.service';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import Swal from 'sweetalert2';

const STATUS_LIST = ['APPLIED', 'UNDER_REVIEW', 'INTERVIEW_PHASE', 'OFFER', 'HIRED', 'REJECTED'] as const;
type AppStatus = typeof STATUS_LIST[number];

@Component({
  selector: 'app-job-details',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './job-details.html',
  styleUrl: './job-details.css',
})
export class JobDetails implements OnInit {
  private readonly jobService         = inject(JobService);
  private readonly applicationService = inject(ApplicationService);
  private readonly route              = inject(ActivatedRoute);
  private readonly router             = inject(Router);
  private readonly authService        = inject(AuthService);

  job                 = signal<JobOffer | null>(null);
  loading             = signal(true);
  error               = signal<string | null>(null);
  myApplication       = signal<ApplicationDto | null>(null);
  checkingApplication = signal(false);

  readonly STATUS_LIST = STATUS_LIST;
  readonly STATUS_COLORS: Record<AppStatus, { color: string; bg: string }> = {
    APPLIED:          { color: '#79a4e9', bg: 'rgba(121,164,233,0.12)' },
    UNDER_REVIEW:     { color: '#fbbf24', bg: 'rgba(251,191,36,0.12)'  },
    INTERVIEW_PHASE:  { color: '#a78bfa', bg: 'rgba(167,139,250,0.12)' },
    OFFER:            { color: '#34d399', bg: 'rgba(52,211,153,0.12)'  },
    HIRED:            { color: '#4ade80', bg: 'rgba(74,222,128,0.12)'  },
    REJECTED:         { color: '#f87171', bg: 'rgba(248,113,113,0.12)' },
  };
  formatEmploymentType(type: string | null | undefined): string {
  if (!type) return '';

  return type
    .toLowerCase()              // FULL_TIME → full_time
    .replace(/_/g, ' ')         // full_time → full time
    .replace(/\b\w/g, c => c.toUpperCase()); // full time → Full Time
}

  activeStatus  = signal<AppStatus>('APPLIED');
  appLoading    = signal(false);
  applications  = signal<ApplicationDto[]>([]);
  appPage       = signal(0);
  appTotalPages = signal(1);
  appTotal      = signal(0);
  readonly APP_PAGE_SIZE = 8;

  statusCounts = signal<Partial<Record<AppStatus, number>>>({});
  statusCountsLoaded = signal(false);   // true once all per-status counts have loaded

  // Smart shortlist
  shortlistOpen      = signal(false);
  shortlistThreshold = signal(70);
  shortlisting       = signal(false);
  shortlistResult    = signal<{ shortlisted: number; skipped: number } | null>(null);

  // ── Role helpers - strict hierarchy ──────────────────────────────────────

  get isSuperAdmin(): boolean { return this.authService.isSuperAdmin(); }
  get isAdmin():      boolean { return this.authService.isAdmin(); }
  get isRecruiter():  boolean { return this.authService.isRecruiter(); }
  get isCandidate():  boolean { return this.authService.isCandidate(); }

  /** Only RECRUITER can create/edit/delete jobs */
  get canManageJobs(): boolean { return this.isRecruiter; }

  /** RECRUITER + ADMIN + SUPERADMIN can see applications panel */
  get showAppsPanel(): boolean { return this.isRecruiter || this.isAdmin || this.isSuperAdmin; }

  get actionLabel(): string { return this.myApplication() ? 'View application' : 'Apply'; }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) { this.error.set('Job ID not found.'); this.loading.set(false); return; }

    this.loadJob(id);
    if (this.isCandidate)   this.loadMyApplicationForJob(id);
    if (this.showAppsPanel) this.loadStatusCounts(id);
  }

  private loadJob(id: string): void {
    this.loading.set(true);
    this.error.set(null);
    this.jobService.getJobById(id).subscribe({
      next:  job => { this.job.set(job); this.loading.set(false); if (this.showAppsPanel) this.loadApps(); },
      error: err => {
        this.loading.set(false);
        this.error.set(err?.status === 404 ? 'Job not found.' : `Failed to load job (HTTP ${err?.status ?? '?'}).`);
      },
    });
  }

  private loadMyApplicationForJob(jobId: string): void {
    this.checkingApplication.set(true);
    this.applicationService.getMyApplicationByJob(jobId).subscribe({
      next:  app => { this.myApplication.set(app); this.checkingApplication.set(false); },
      error: err => { if (err?.status !== 404) console.error(err); this.checkingApplication.set(false); },
    });
  }

  private loadStatusCounts(jobId: string): void {
    const counts: Partial<Record<AppStatus, number>> = {};
    let done = 0;
    const finish = () => {
      if (done === STATUS_LIST.length) {
        this.statusCounts.set({ ...counts });
        this.statusCountsLoaded.set(true);   // unlocks canEditJob for PUBLISHED jobs
      }
    };
    for (const s of STATUS_LIST) {
      this.applicationService.listApplicationsPaged({ jobId, status: s, page: 0, size: 1 })
        .subscribe({
          next:  res => { counts[s] = res.totalElements; done++; finish(); },
          error: ()  => { done++; finish(); }
        });
    }
  }

  loadApps(): void {
    const job = this.job();
    if (!job) return;
    this.appLoading.set(true);
    this.applicationService.listApplicationsPaged({
      jobId:  job.id,
      status: this.activeStatus(),
      page:   this.appPage(),
      size:   this.APP_PAGE_SIZE,
    }).subscribe({
      next:  res => { this.applications.set(res.content ?? []); this.appTotalPages.set(res.totalPages ?? 1); this.appTotal.set(res.totalElements ?? 0); this.appLoading.set(false); },
      error: ()  => this.appLoading.set(false),
    });
  }

  setStatus(s: AppStatus): void { this.activeStatus.set(s); this.appPage.set(0); this.loadApps(); }
  appPrev(): void { if (this.appPage() > 0) { this.appPage.update(p => p - 1); this.loadApps(); } }
  appNext(): void { if (this.appPage() + 1 < this.appTotalPages()) { this.appPage.update(p => p + 1); this.loadApps(); } }

  openApplication(app: ApplicationDto): void { this.router.navigate(['/application', app.applicationId]); }

  duplicateJob(): void {
    const job = this.job();
    if (!job) return;
    this.router.navigate(['/add-job'], {
      state: {
        duplicate: {
          title:            job.title,
          description:      job.description,
          location:         job.location,
          employmentType:   job.employmentType,
          minSalary:        job.minSalary,
          maxSalary:        job.maxSalary,
          skillsWeight:     Math.round((job.skillsWeight     ?? 0.40) * 100),
          semanticWeight:   Math.round((job.semanticWeight   ?? 0.35) * 100),
          experienceWeight: Math.round((job.experienceWeight ?? 0.15) * 100),
          seniorityWeight:  Math.round((job.seniorityWeight  ?? 0.10) * 100),
          requirements:     job.requirements ?? [],
        }
      }
    });
  }

  downloadCv(app: ApplicationDto, event: Event): void {
    event.stopPropagation();
    this.applicationService.downloadCv(app.applicationId).subscribe(blob => {
      const url = window.URL.createObjectURL(blob);
      const a   = document.createElement('a');
      a.href     = url;
      a.download = app.cvFileName || 'cv.pdf';
      a.click();
      window.URL.revokeObjectURL(url);
    });
  }

  onApplyOrView(): void {
    const job = this.job();
    if (!job) return;
    const app = this.myApplication();
    if (app?.applicationId) this.router.navigate(['/my-application', app.applicationId]);
    else this.router.navigate(['/apply', job.id]);
  }
  salaryText(): string {
    const job = this.job();
    if (!job) return '-';
    const min = job.minSalary ?? null;
    const max = job.maxSalary ?? null;
    if (min == null && max == null) return 'Not specified';
    if (min != null && max == null) return `From ${min} TND`;
    if (min == null && max != null) return `Up to ${max} TND`;
    return `${min}-${max} TND`;
  }

  // ── Edit / Close eligibility ────────────────────────────────────────────────
  // Total applicants across all statuses (statusCounts is per-status).
  get totalApplicants(): number {
    return Object.values(this.statusCounts()).reduce((sum, n) => sum + (n || 0), 0);
  }

  /** A job can be edited only while DRAFT, or PUBLISHED with zero applicants.
   *  Once CLOSED, or once a candidate has applied, it is locked. */
  get canEditJob(): boolean {
    const j = this.job();
    if (!j || !this.canManageJobs) return false;
    if (j.jobStatus === 'DRAFT')     return true;
    if (j.jobStatus === 'PUBLISHED') return this.statusCountsLoaded() && this.totalApplicants === 0;
    return false;   // CLOSED → locked
  }

  /** Only a PUBLISHED job can be closed by the recruiter. */
  get canCloseJob(): boolean {
    const j = this.job();
    return !!j && this.canManageJobs && j.jobStatus === 'PUBLISHED';
  }

  async closeJob(id: string): Promise<void> {
    const result = await Swal.fire({
      icon: 'warning',
      title: 'Close this job offer?',
      text: 'It will stop accepting new applications. Provide a reason - it is recorded in the audit trail.',
      input: 'textarea',
      inputPlaceholder: 'e.g. Position filled, budget cancelled, requirements changed…',
      inputAttributes: { rows: '3' },
      inputValidator: (value) =>
        (!value || !value.trim()) ? 'A reason is required to close the job.' : null,
      showCancelButton: true,
      confirmButtonText: 'Close job',
      cancelButtonText: 'Cancel',
      confirmButtonColor: '#ed8936',
    });
    if (!result.isConfirmed) return;

    const reason = (result.value as string).trim();
    this.jobService.closeJob(id, reason).subscribe({
      next:  job => {
        this.job.set(job);
        Swal.fire({ icon: 'success', title: 'Job closed', timer: 1600, showConfirmButton: false });
      },
      error: err => this.error.set(err?.status ? `Close failed (HTTP ${err.status}).` : 'Failed to close job offer.'),
    });
  }

  statusColor(s: string): string { return (this.STATUS_COLORS as any)[s]?.color ?? '#79a4e9'; }
  statusBg(s: string):    string { return (this.STATUS_COLORS as any)[s]?.bg    ?? 'rgba(121,164,233,0.12)'; }

  openShortlist(): void {
    this.shortlistResult.set(null);
    this.shortlistOpen.set(true);
  }

  closeShortlist(): void { this.shortlistOpen.set(false); }

  confirmShortlist(): void {
    const job = this.job();
    if (!job) return;
    this.shortlisting.set(true);
    this.shortlistResult.set(null);
    this.applicationService.shortlistApplications(job.id, this.shortlistThreshold()).subscribe({
      next: (res) => {
        this.shortlistResult.set({ shortlisted: res.shortlisted, skipped: res.skipped });
        this.shortlisting.set(false);
        if (res.shortlisted > 0) {
          this.loadStatusCounts(job.id);
          if (this.activeStatus() === 'APPLIED') { this.appPage.set(0); this.loadApps(); }
        }
      },
      error: () => this.shortlisting.set(false),
    });
  }

  workArrangementLabel(): string {
    const map: Record<string, string> = {
      ON_SITE: 'On-site', HYBRID: 'Hybrid', REMOTE: 'Remote',
    };
    const v = (this.job() as any)?.workArrangement;
    return v ? (map[v] ?? v) : '-';
  }

  spotsText(): string {
    const job = this.job();
    if (!job || job.openings == null) return '-';
    const left = Math.max(0, job.openings - (job.hiredCount ?? 0));
    if (left === 0) return 'Filled';
    if (left === 1) return '1 spot left';
    return `${left} of ${job.openings} spots left`;
  }

  spotsColor(): string {
    const job = this.job();
    if (!job || job.openings == null) return '';
    const left = Math.max(0, job.openings - (job.hiredCount ?? 0));
    if (left === 0) return '#f87171';
    if (left === 1) return '#fbbf24';
    return '#68d391';
  }

  scoreBadgeClass(score: number | null | undefined): string {
    if (score == null) return '';
    if (score >= 70)   return 'score-high';
    if (score >= 45)   return 'score-mid';
    return 'score-low';
  }

  formatDate(d: string): string {
    return new Date(d).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
  }
}