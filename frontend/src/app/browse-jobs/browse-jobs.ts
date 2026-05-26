import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { JobService } from '../services/job.service';
import { JobOffer } from '../model/jobOffer.model';
import { PageResponse } from '../model/page-response';
import { RouterLink } from "@angular/router";
import { Router, ActivatedRoute } from "@angular/router";
import { AuthService } from '../services/AuthService.service';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import { CandidateProfileService, CandidateProfile } from '../services/candidate-profile.service';
import { CatalogService } from '../services/catalog.service';
import { HttpClient } from '@angular/common/http';
import Swal from 'sweetalert2';
import { firstValueFrom } from 'rxjs';
import Keycloak from 'keycloak-js';
import { inject } from '@angular/core';

type SalaryRange = 'any' | 'specified' | '0-1000' | '1000-2000' | '2000-5000' | '5000+';

@Component({
  selector: 'app-browse-jobs',
  imports: [CommonModule, RouterLink],
  templateUrl: './browse-jobs.html',
  styleUrl: './browse-jobs.css',
})
export class BrowseJobsComponent implements OnInit {
  pageResponse: PageResponse<JobOffer> | null = null;

  loading = false;
  error: string | null = null;

  // Filters
  query = '';
  employmentType = '';
  status = '';
  salaryRange: SalaryRange = 'any';

  // Pagination
  currentPage = 0;
  pageSize = 10;

  // UI data for filter dropdowns
  employmentTypes: string[] = [];
  statuses: string[] = [];

  private myAppsByJobId = new Map<string, string>();
  checkingMyApps = false;
  private candidatePrefs: CandidateProfile | null = null;
  sortMode: 'date' | 'match' = 'date';
  private jobScores = new Map<string, number>();
  rankingLoading = false;

  private readonly keycloak = inject(Keycloak);

  /** Count of catalog items (skills + languages) that have been added since
   *  the candidate's last Preferences visit. Drives the small badge next to
   *  the "My Preferences" button. Zero hides the badge entirely. */
  newCatalogCount = 0;

  constructor(
    private jobService: JobService,
    private authService: AuthService,
    private applicationService: ApplicationService,
    private candidateProfileService: CandidateProfileService,
    private catalogService: CatalogService,
    private http: HttpClient,
    private router: Router,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.fetchJobs();
    if (this.isCandidate) {
      // Load preferences first, then check whether we should auto-resume the
      // Recommendation sort. The user lands here from /preferences with
      // ?resume=match after we redirected them away from an empty profile.
      this.loadMyApplications();
      this.loadCandidatePreferences().then(() => {
        this.maybeResumeRecommendation();
        this.computeNewCatalogCount();
      });
    }
  }

  private async maybeResumeRecommendation(): Promise<void> {
    const resume = this.route.snapshot.queryParamMap.get('resume');
    if (resume === 'match' && this.hasUsefulProfileData()) {
      this.sortMode = 'match';
      await this.triggerRanking();
    }
  }

  /** Counts skills + languages added to the catalog since the candidate's
   *  last Preferences visit. The CatalogService is cached, so this is cheap
   *  even when called on every browse-page load. */
  private async computeNewCatalogCount(): Promise<void> {
    const lastAck = this.candidatePrefs?.lastPreferencesAcknowledgedAt;
    if (!lastAck) { this.newCatalogCount = 0; return; }
    const lastMs = new Date(lastAck).getTime();
    try {
      const snap = await this.catalogService.getSnapshot();
      const isNew = (firstSeenAt: string | null) =>
        !!firstSeenAt && new Date(firstSeenAt).getTime() > lastMs;
      this.newCatalogCount =
        snap.skills.filter(s => isNew(s.firstSeenAt)).length +
        snap.languages.filter(l => isNew(l.firstSeenAt)).length;
    } catch {
      this.newCatalogCount = 0;
    }
  }

  // ── Role helpers — strict hierarchy ──────────────────────────────────────

  get isSuperAdmin(): boolean { return this.authService.isSuperAdmin(); }

  get isRecruiter(): boolean { return this.authService.isRecruiter(); }

  get isAdmin(): boolean { return this.authService.isAdmin(); }

  get isGuest(): boolean { return !this.keycloak.authenticated; }

  get isCandidate(): boolean { return this.authService.isCandidate(); }

  /** Only RECRUITER can create/edit/delete jobs */
  get canManageJobs(): boolean { return this.isRecruiter; }

  /** RECRUITER + ADMIN + SUPERADMIN see all job statuses */
  get showAllJobs(): boolean { return this.isRecruiter || this.isAdmin || this.isSuperAdmin; }

  private loadMyApplications(): void {
      this.checkingMyApps = true;
      this.myAppsByJobId.clear();

      this.applicationService.getMyApplications().subscribe({
        next: (apps: ApplicationDto[]) => {
          for (const app of (apps ?? [])) {
            if (app?.jobId && app?.applicationId) {
              this.myAppsByJobId.set(app.jobId, app.applicationId);
            }
          }
          this.checkingMyApps = false;
        },
        error: (err) => {
          console.error('getMyApplications error:', err);
          this.checkingMyApps = false;
        }
      });
    }

  applicationIdFor(jobId: string): string | null {
    return this.myAppsByJobId.get(jobId) ?? null;
  }

  applyBtnLabel(jobId: string): string {
    return this.applicationIdFor(jobId) ? 'View application' : 'Apply';
  }

  applyOrView(jobId: string): void {
  if (this.isGuest) {
    this.keycloak.login()
    return;
  }
  const appId = this.applicationIdFor(jobId);
  if (appId) {
    this.router.navigate(['/my-application', appId]);
  } else {
    this.router.navigate(['/apply', jobId]);
  }
}
  get filteredJobs(): JobOffer[] {
  return this.pageResponse?.content ?? [];
}

  fetchJobs(): void {
    this.loading = true;
    this.error = null;
    this.jobScores.clear();

    const [minSalary, maxSalary] = this.getSalaryRange();

    // Candidates always see only PUBLISHED jobs — filter server-side
    const effectiveStatus = this.isCandidate ? 'PUBLISHED' : this.status;

    this.jobService.searchJobs(
      this.query,
      this.employmentType,
      effectiveStatus,
      minSalary,
      maxSalary,
      this.currentPage,
      this.pageSize
    ).subscribe({
      next: (response) => {
        this.pageResponse = response;

        // Extract unique values for filter dropdowns (only on first page load)
        if (this.currentPage === 0) {
          this.extractFilterOptions();
        }

        this.loading = false;
      },
      error: (err) => {
        this.loading = false;

        if (err?.status === 0) {
          this.error = 'Backend not reachable.';
        } else if (err?.status) {
          this.error = `Failed to load jobs (HTTP ${err.status}).`;
        } else {
          this.error = 'Failed to load jobs.';
        }

        console.error('searchJobs error:', err);
      },
    });
  }

  private extractFilterOptions(): void {
    this.jobService.getAllJobs().subscribe({
      next: (allJobs) => {
        this.employmentTypes = this.uniqueNonEmpty(
          allJobs.map(j => j.employmentType ?? '')
        );
        this.statuses = this.uniqueNonEmpty(
          allJobs.map(j => j.jobStatus ?? '')
        );
      },
      error: (err) => {
        console.error('Failed to extract filter options:', err);
      }
    });
  }

  onQueryChange(e: Event): void {
    const v = (e.target as HTMLInputElement).value ?? '';
    this.query = v;
    this.currentPage = 0; // Reset to first page
    this.fetchJobs();
  }

  onEmploymentTypeChange(e: Event): void {
    this.employmentType = (e.target as HTMLSelectElement).value ?? '';
    this.currentPage = 0;
    this.fetchJobs();
  }

  onStatusChange(e: Event): void {
    this.status = (e.target as HTMLSelectElement).value ?? '';
    this.currentPage = 0;
    this.fetchJobs();
  }

  onSalaryRangeChange(e: Event): void {
    this.salaryRange = ((e.target as HTMLSelectElement).value ?? 'any') as SalaryRange;
    this.currentPage = 0;
    this.fetchJobs();
  }

  /**
   * Determine min and max salary based on selected range
   */
  private getSalaryRange(): [number | undefined, number | undefined] {
    if (this.salaryRange === 'any') return [undefined, undefined];
    if (this.salaryRange === 'specified') return [0, undefined];

    const ranges: Record<string, [number, number | undefined]> = {
      '0-1000': [0, 1000],
      '1000-2000': [1000, 2000],
      '2000-5000': [2000, 5000],
      '5000+': [5000, undefined],
    };

    const [min, max] = ranges[this.salaryRange] || [undefined, undefined];
    return [min, max];
  }

  resetFilters(): void {
    this.query = '';
    this.employmentType = '';
    this.status = '';
    this.salaryRange = 'any';
    this.currentPage = 0;
    this.fetchJobs();
  }

  hasActiveFilters(): boolean {
    return !!(
      this.query.trim() ||
      this.employmentType ||
      this.status ||
      this.salaryRange !== 'any'
    );
  }

  uniqueNonEmpty(values: string[]): string[] {
    const set = new Set(values.map(v => (v ?? '').trim()).filter(v => !!v));
    return Array.from(set).sort((a, b) => a.localeCompare(b));
  }

  salaryText(job: JobOffer): string {
    const min = job.minSalary ?? null;
    const max = job.maxSalary ?? null;

    if (min == null && max == null) return 'Salary not specified';
    if (min != null && max == null) return `From ${min} TND`;
    if (min == null && max != null) return `Up to ${max} TND`;
    return `${min}–${max} TND`;
  }

  badgeText(job: JobOffer): string {
    return job.jobStatus ?? 'UNKNOWN';
  }

  spotsLeft(job: JobOffer): number | null {
    if (job.openings == null) return null;
    return Math.max(0, job.openings - (job.hiredCount ?? 0));
  }

  spotsLabel(job: JobOffer): string | null {
    const left = this.spotsLeft(job);
    if (left == null) return null;
    if (left === 0) return 'Filled';
    if (left === 1) return '1 spot left';
    return `${left} spots left`;
  }

  spotsStyle(job: JobOffer): string {
    const left = this.spotsLeft(job);
    if (left == null) return '';
    if (left === 0) return 'color:#f87171;';
    if (left === 1) return 'color:#fbbf24;';
    return 'color:#68d391;';
  }

  badgeStyle(job: JobOffer): string {
    switch (job.jobStatus) {
      case 'PUBLISHED': return 'background:rgba(72,187,120,0.12);color:#68d391;border:1px solid rgba(72,187,120,0.25);';
      case 'DRAFT':     return 'background:rgba(251,191,36,0.12);color:#fbbf24;border:1px solid rgba(251,191,36,0.25);';
      case 'CLOSED':    return 'background:rgba(248,113,113,0.12);color:#f87171;border:1px solid rgba(248,113,113,0.25);';
      default:          return '';
    }
  }

  private async loadCandidatePreferences(): Promise<void> {
    try {
      this.candidatePrefs = await this.candidateProfileService.get();
    } catch { /* ignore — leave candidatePrefs null */ }
  }

  // ── Semantic ranking ──────────────────────────────────────────────────────

  private buildCandidateText(): string {
    const p = this.candidatePrefs;
    if (!p) return '';
    const domainLabels: Record<string, string> = {
      SOFTWARE_ENGINEERING: 'Software Engineering and IT development',
      FINANCE_BANKING:      'Finance and Banking',
      INSURANCE:            'Insurance',
      PROJECT_MANAGEMENT:   'Project Management',
      QUALITY_ASSURANCE:    'Quality Assurance and Testing',
      BUSINESS_ANALYSIS:    'Business Analysis',
    };
    const parts: string[] = [];
    if (p.domain)          parts.push(`Domain: ${domainLabels[p.domain] ?? p.domain}`);
    if (p.hardSkills?.length) parts.push(`Technical skills: ${p.hardSkills.join(', ')}`);
    if (p.softSkills?.length) parts.push(`Soft skills: ${p.softSkills.join(', ')}`);
    if (p.languages?.length)  parts.push(`Languages: ${p.languages.map(l => `${l.language} ${l.level}`).join(', ')}`);
    if (p.yearsOfExperience)  parts.push(`Years of experience: ${p.yearsOfExperience}`);
    if (p.educationLevel)     parts.push(`Education: ${p.educationLevel}`);
    if (p.preferredJobType)   parts.push(`Looking for: ${p.preferredJobType}`);
    return parts.join('\n');
  }

  private buildJobText(job: JobOffer): string {
    const parts: string[] = [];
    if (job.title)       parts.push(`Job title: ${job.title}`);
    if (job.description) parts.push(`Description: ${job.description.slice(0, 400)}`);
    if (job.requirements?.length) {
      const reqs = job.requirements.map(r => `${r.category}: ${r.description}`).join('; ');
      parts.push(`Requirements: ${reqs}`);
    }
    if (job.employmentType)   parts.push(`Type: ${job.employmentType}`);
    if (job.workArrangement)  parts.push(`Work arrangement: ${job.workArrangement}`);
    return parts.join('\n');
  }

  async triggerRanking() {
    if (!this.candidatePrefs || this.filteredJobs.length === 0) return;
    const candidateText = this.buildCandidateText();
    if (!candidateText) return;

    this.rankingLoading = true;
    try {
      const p = this.candidatePrefs;
      const body = {
        candidate_text: candidateText,
        // Structured signals so the backend can do skill overlap + preference
        // fit on top of the embedding similarity. Both arrangement and job
        // type are multi-select now — empty array (or all options selected)
        // means "no preference" and every job stays at pref_fit = 1.0.
        candidate_hard_skills:                  p.hardSkills ?? [],
        candidate_preferred_work_arrangements:  p.preferredWorkArrangement ?? [],
        candidate_preferred_job_types:          p.preferredJobType ?? [],
        jobs: this.filteredJobs.map(j => ({
          id:               j.id,
          text:             this.buildJobText(j),
          work_arrangement: (j as any).workArrangement ?? null,
          employment_type:  j.employmentType ?? null,
          requirement_text: (j.requirements ?? [])
            .map(r => `${r.category}: ${r.description}`).join('; '),
        })),
      };
      const resp = await firstValueFrom(
        this.http.post<{ results: { id: string; score: number }[] }>(
          'http://localhost:8085/api/cv-parser/rank-jobs', body
        )
      );
      this.jobScores.clear();
      for (const r of resp.results) this.jobScores.set(r.id, r.score);
    } catch { /* silent — fall back to date order */ }
    finally { this.rankingLoading = false; }
  }

  // Thresholds are calibrated for the new score distribution produced by the
  // backend: semantic uses _normalize_cosine (0-1 over the document window)
  // blended 55/45 with skill_overlap, then multiplied by preference fit.
  // Real scores typically land in:
  //   • Strong match  — ≥ 0.65 (both signals high + preferences match)
  //   • Good match    — ≥ 0.45
  //   • Partial match — ≥ 0.25
  matchLabel(job: JobOffer): string | null {
    if (this.sortMode !== 'match' || !this.isCandidate) return null;
    if (this.rankingLoading) return null;
    const s = this.jobScores.get(job.id) ?? 0;
    if (s >= 0.65) return 'Strong match';
    if (s >= 0.45) return 'Good match';
    if (s >= 0.25) return 'Partial match';
    return null;
  }

  matchStyle(job: JobOffer): string {
    const s = this.jobScores.get(job.id) ?? 0;
    if (s >= 0.65) return 'background:rgba(72,187,120,0.15);color:#68d391;border:1px solid rgba(72,187,120,0.3);';
    if (s >= 0.45) return 'background:rgba(121,164,233,0.15);color:#79a4e9;border:1px solid rgba(121,164,233,0.3);';
    return 'background:rgba(251,191,36,0.12);color:#fbbf24;border:1px solid rgba(251,191,36,0.25);';
  }

  /**
   * Returns true when the candidate profile has at least one signal the ranker
   * actually consumes. Without any of these, the recommendation collapses to
   * plain text-embedding similarity and produces near-identical scores for
   * every job — better to send the user to set their preferences first.
   */
  private hasUsefulProfileData(): boolean {
    const p = this.candidatePrefs;
    if (!p) return false;
    return !!(
      (p.domain && p.domain.trim()) ||
      (p.hardSkills && p.hardSkills.length > 0) ||
      (p.preferredJobType && p.preferredJobType.length > 0) ||
      (p.preferredWorkArrangement && p.preferredWorkArrangement.length > 0)
    );
  }

  setSortMode(mode: 'date' | 'match') {
    // Block the Recommendation sort when the candidate has nothing the ranker
    // can use. Show a toast explaining why and redirect to /preferences after
    // a beat — keeps the user in flow instead of silently failing.
    if (mode === 'match' && this.isCandidate && !this.hasUsefulProfileData()) {
      Swal.fire({
        icon: 'info',
        title: 'Set your preferences first',
        text: 'We need to know what you’re looking for before we can recommend jobs. Taking you there now…',
        timer: 1800,
        showConfirmButton: false,
        timerProgressBar: true,
      }).then(() => {
        // Remember that the user wanted recommendations so we can re-enter
        // this sort mode automatically after they save their preferences.
        this.router.navigate(['/preferences'], {
          queryParams: { returnTo: '/browse', resume: 'match' },
        });
      });
      return;  // do NOT flip sortMode — leave Newest selected
    }

    this.sortMode = mode;
    if (mode === 'match' && this.jobScores.size === 0) {
      this.triggerRanking();
    }
  }

  getVisibleJobs(): JobOffer[] {
    const jobs = this.showAllJobs
      ? this.filteredJobs
      : this.filteredJobs.filter(job => job.jobStatus === 'PUBLISHED');
    if (this.sortMode === 'match' && this.isCandidate && this.jobScores.size > 0) {
      return [...jobs].sort((a, b) => (this.jobScores.get(b.id) ?? 0) - (this.jobScores.get(a.id) ?? 0));
    }
    return jobs;
  }

  async closeJob(id: string): Promise<void> {
    const result = await Swal.fire({
      icon: 'warning',
      title: 'Close this job offer?',
      text: 'It will stop accepting new applications. Provide a reason — it is recorded in the audit trail.',
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

    this.jobService.closeJob(id, (result.value as string).trim()).subscribe({
      next:  () => {
        this.fetchJobs();   // refresh the list
        Swal.fire({ icon: 'success', title: 'Job closed', timer: 1600, showConfirmButton: false });
      },
      error: (err) => {
        console.error('Close failed:', err);
        Swal.fire({ icon: 'error', title: 'Failed to close job offer.' });
      }
    });
  }

  /**
   * Navigate to previous page
   */
  previousPage(): void {
    if (this.pageResponse?.hasPrevious) {
      this.currentPage--;
      this.fetchJobs();
    }
  }

  /**
   * Navigate to next page
   */
  nextPage(): void {
    if (this.pageResponse?.hasNext) {
      this.currentPage++;
      this.fetchJobs();
    }
  }

  /**
   * Change page size and reset to first page
   */
  changePageSize(event: Event): void {
    const newSize = parseInt((event.target as HTMLSelectElement).value, 10);
    this.pageSize = newSize;
    this.currentPage = 0;
    this.fetchJobs();
  }
  formatEmploymentType(type: string | null | undefined): string {
  if (!type) return '';

  return type
    .toLowerCase()
    .replace(/_/g, ' ')
    .replace(/\b\w/g, c => c.toUpperCase());
}
}
