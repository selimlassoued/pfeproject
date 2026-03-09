import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink, Router } from '@angular/router';
import { JobService } from '../services/job.service';
import { JobOffer } from '../model/jobOffer.model';
import { AuthService } from '../services/AuthService.service';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import { PageResponse } from '../model/page-response';

const STATUS_LIST = ['APPLIED', 'UNDER_REVIEW', 'INTERVIEW_PHASE', 'OFFER', 'HIRED', 'REJECTED'] as const;
type AppStatus = typeof STATUS_LIST[number];

@Component({
  selector: 'app-job-details',
  imports: [CommonModule, RouterLink],
  templateUrl: './job-details.html',
  styleUrl: './job-details.css',
})
export class JobDetails implements OnInit {
  private readonly jobService        = inject(JobService);
  private readonly applicationService = inject(ApplicationService);
  private readonly route             = inject(ActivatedRoute);
  private readonly router            = inject(Router);
  private readonly authService       = inject(AuthService);

  job              = signal<JobOffer | null>(null);
  loading          = signal(true);
  error            = signal<string | null>(null);
  myApplication    = signal<ApplicationDto | null>(null);
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

  activeStatus   = signal<AppStatus>('APPLIED');
  appLoading     = signal(false);
  applications   = signal<ApplicationDto[]>([]);
  appPage        = signal(0);
  appTotalPages  = signal(1);
  appTotal       = signal(0);
  readonly APP_PAGE_SIZE = 8;

  statusCounts   = signal<Partial<Record<AppStatus, number>>>({});

  get isRecruiter(): boolean { return this.authService.isRecruiter(); }
  get isAdmin():     boolean { return this.authService.isAdmin(); }
  get isCandidate(): boolean { return !this.isRecruiter && !this.isAdmin && this.authService.isCandidate(); }
  get showAppsPanel(): boolean { return this.isRecruiter || this.isAdmin; }
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
    for (const s of STATUS_LIST) {
      this.applicationService.listApplicationsPaged({ jobId, status: s, page: 0, size: 1 })
        .subscribe({
          next: res => {
            counts[s] = res.totalElements;
            done++;
            if (done === STATUS_LIST.length) this.statusCounts.set({ ...counts });
          },
          error: () => { done++; }
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
      next: res => {
        this.applications.set(res.content ?? []);
        this.appTotalPages.set(res.totalPages ?? 1);
        this.appTotal.set(res.totalElements ?? 0);
        this.appLoading.set(false);
      },
      error: () => this.appLoading.set(false),
    });
  }

  setStatus(s: AppStatus): void {
    this.activeStatus.set(s);
    this.appPage.set(0);
    this.loadApps();
  }

  appPrev(): void { if (this.appPage() > 0) { this.appPage.update(p => p - 1); this.loadApps(); } }
  appNext(): void { if (this.appPage() + 1 < this.appTotalPages()) { this.appPage.update(p => p + 1); this.loadApps(); } }

  openApplication(app: ApplicationDto): void {
    this.router.navigate(['/application', app.applicationId]);
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
    if (!job) return '—';
    const min = job.minSalary ?? null;
    const max = job.maxSalary ?? null;
    if (min == null && max == null) return 'Not specified';
    if (min != null && max == null) return `From ${min} TND`;
    if (min == null && max != null) return `Up to ${max} TND`;
    return `${min}–${max} TND`;
  }

  deleteJob(id: string): void {
    if (confirm('Are you sure you want to delete this job offer? This action cannot be undone.')) {
      this.jobService.deleteJob(id).subscribe({
        next:  ()  => this.router.navigate(['/browse']),
        error: err => this.error.set(err?.status ? `Delete failed (HTTP ${err.status}).` : 'Failed to delete job offer.'),
      });
    }
  }

  statusColor(s: string): string {
    return (this.STATUS_COLORS as any)[s]?.color ?? '#79a4e9';
  }
  statusBg(s: string): string {
    return (this.STATUS_COLORS as any)[s]?.bg ?? 'rgba(121,164,233,0.12)';
  }
  formatDate(d: string): string {
    return new Date(d).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
  }
}