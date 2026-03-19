import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { JobService } from '../services/job.service';
import { JobOffer } from '../model/jobOffer.model';
import { PageResponse } from '../model/page-response';
import { RouterLink } from "@angular/router";
import { Router, RouterLink } from "@angular/router";
import { AuthService } from '../services/AuthService.service';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';

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

  constructor(private jobService: JobService, private authService: AuthService, private applicationService: ApplicationService,private router: Router) {}

  ngOnInit(): void {
    this.fetchJobs();
     if (this.isCandidate) {
      this.loadMyApplications();
    }
  }

  get isRecruiter(): boolean {
    return this.authService.isRecruiter();
  }

  get isAdmin(): boolean {
    return this.authService.isAdmin();
  }

  /**
   * Fetch jobs from backend with current filters and pagination
   */
   get isCandidate(): boolean {
    return !this.isRecruiter && !this.isAdmin && this.authService.isCandidate();
  }

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
    const appId = this.applicationIdFor(jobId);
    if (appId) {
      this.router.navigate(['/my-application', appId]);
    } else {
      this.router.navigate(['/apply', jobId]);
    }
  }
  fetchJobs(): void {
    this.loading = true;
    this.error = null;

    const [minSalary, maxSalary] = this.getSalaryRange();

    this.jobService.searchJobs(
      this.query,
      this.employmentType,
      this.status,
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

  /**
   * Get visible jobs based on user role
   */
  getVisibleJobs(): JobOffer[] {
    if (!this.pageResponse?.content) return [];
        return this.pageResponse.content;
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

  getVisibleJobs(): JobOffer[] {
    if (this.isRecruiter || this.isAdmin) {
      return this.filteredJobs;
    }
    return this.filteredJobs.filter(job => job.jobStatus === 'PUBLISHED');
  }

  deleteJob(id: string): void {
    if (confirm('Are you sure you want to delete this job offer?')) {
      this.jobService.deleteJob(id).subscribe({
        next: () => {
          this.fetchJobs(); // Refresh the list
        },
        error: (err) => {
          console.error('Delete failed:', err);
          alert('Failed to delete job offer.');
        }
      });
    }
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
}