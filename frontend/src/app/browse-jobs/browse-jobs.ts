import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { JobService } from '../services/job.service';
import { JobOffer } from '../model/jobOffer.model';
import { RouterLink } from "@angular/router";
import { AuthService } from '../services/AuthService.service';


type SalaryRange = 'any' | 'specified' | '0-1000' | '1000-2000' | '2000-5000' | '5000+';

@Component({
  selector: 'app-browse-jobs',
  imports: [CommonModule, RouterLink],
  templateUrl: './browse-jobs.html',
  styleUrl: './browse-jobs.css',
})
export class BrowseJobsComponent implements OnInit {
  jobs: JobOffer[] = [];
  filteredJobs: JobOffer[] = [];

  loading = false;
  error: string | null = null;

  query = '';
  employmentType = '';
  status = '';
  salaryRange: SalaryRange = 'any';

  employmentTypes: string[] = [];
  statuses: string[] = [];

  constructor(private jobService: JobService, private authService: AuthService) {}

  ngOnInit(): void {
    this.fetchJobs();
  }

  get isRecruiter(): boolean {
    return this.authService.isRecruiter();
  }

  get isAdmin(): boolean {
    return this.authService.isAdmin();
  }

  fetchJobs(): void {
    this.loading = true;
    this.error = null;

    this.jobService.getAllJobs().subscribe({
      next: (data) => {
        this.jobs = data ?? [];

        this.employmentTypes = this.uniqueNonEmpty(this.jobs.map(j => j.employmentType ?? ''));
        this.statuses = this.uniqueNonEmpty(this.jobs.map(j => j.jobStatus ?? ''));

        this.applyFilters();
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;

        if (err?.status == 0) this.error = 'Backend not reachable.';
        else if (err?.status) this.error = `Failed to load jobs (HTTP ${err.status}).`;
        else this.error = 'Failed to load jobs.';

        console.error('getAllJobs error:', err);
      },
    });
  }

  onQueryChange(e: Event): void {
    const v = (e.target as HTMLInputElement).value ?? '';
    this.query = v;
    this.applyFilters();
  }

  onEmploymentTypeChange(e: Event): void {
    this.employmentType = (e.target as HTMLSelectElement).value ?? '';
    this.applyFilters();
  }

  onStatusChange(e: Event): void {
    this.status = (e.target as HTMLSelectElement).value ?? '';
    this.applyFilters();
  }

  onSalaryRangeChange(e: Event): void {
    this.salaryRange = ((e.target as HTMLSelectElement).value ?? 'any') as SalaryRange;
    this.applyFilters();
  }

  resetFilters(): void {
    this.query = '';
    this.employmentType = '';
    this.status = '';
    this.salaryRange = 'any';
    this.applyFilters();
  }

  hasActiveFilters(): boolean {
    return !!(this.query.trim() || this.employmentType || this.status || this.salaryRange !== 'any');
  }

  applyFilters(): void {
    const q = this.query.trim().toLowerCase();

    this.filteredJobs = (this.jobs ?? []).filter((job) => {
      const haystack = `${job.title ?? ''} ${job.location ?? ''} ${job.description ?? ''} ${job.employmentType ?? ''} ${job.jobStatus ?? ''}`
        .toLowerCase();

      const matchesQuery = !q || haystack.includes(q);
      const matchesType = !this.employmentType || (job.employmentType ?? '') === this.employmentType;
      const matchesStatus = !this.status || (job.jobStatus ?? '') === this.status;
      const matchesSalary = this.matchesSalary(job);

      return matchesQuery && matchesType && matchesStatus && matchesSalary;
    });
  }

  matchesSalary(job: JobOffer): boolean {
    const min = job.minSalary ?? null;
    const max = job.maxSalary ?? null;

    if (this.salaryRange === 'any') return true;

    const hasSpecified = min != null || max != null;
    if (this.salaryRange === 'specified') return hasSpecified;

    const value = (min != null ? min : (max != null ? max : null));
    if (value == null) return false;

    if (this.salaryRange === '0-1000') return value >= 0 && value <= 1000;
    if (this.salaryRange === '1000-2000') return value >= 1000 && value <= 2000;
    if (this.salaryRange === '2000-5000') return value >= 2000 && value <= 5000;
    if (this.salaryRange === '5000+') return value >= 5000;

    return true;
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
    // Recruiters and admins see all jobs
    if (this.isRecruiter || this.isAdmin) {
      return this.filteredJobs;
    }
    // Candidates see only published jobs
    return this.filteredJobs.filter(job => job.jobStatus === 'PUBLISHED');
  }

  deleteJob(id: string): void {
    if (confirm('Are you sure you want to delete this job offer?')) {
      this.jobService.deleteJob(id).subscribe({
        next: () => {
          this.jobs = this.jobs.filter(j => j.id !== id);
          this.applyFilters();
        },
        error: (err) => {
          console.error('Delete failed:', err);
          alert('Failed to delete job offer.');
        }
      });
    }
  }
}