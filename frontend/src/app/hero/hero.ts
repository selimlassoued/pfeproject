import { Component, inject, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { JobService } from '../services/job.service';
import { PageResponse } from '../model/page-response';
import { JobOffer } from '../model/jobOffer.model';
import { RouterLink } from '@angular/router';
import Keycloak from 'keycloak-js';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

@Component({
  selector: 'app-hero',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './hero.html',
  styleUrl: './hero.css',
})
export class Hero implements OnInit, OnDestroy {
  private readonly keycloak = inject(Keycloak);
  private readonly jobService = inject(JobService);
  private destroy$ = new Subject<void>();

  jobs: JobOffer[] = [];
  loading = true;
  error: string | null = null;

  ngOnInit(): void {
    this.loadFeaturedJobs();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  /**
   * Load featured jobs (first 4 published jobs)
   * Uses the new paginated search endpoint
   */
  private loadFeaturedJobs(): void {
    this.loading = true;
    this.error = null;

    this.jobService
      .searchJobs(
        undefined,        // query
        undefined,        // employmentType
        'PUBLISHED',      // jobStatus
        undefined,        // minSalary
        undefined,        // maxSalary
        0,               // page
        4                // size - fetch 4 jobs for featured section
      )
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response: PageResponse<JobOffer>) => {
          this.jobs = response.content ?? [];
          this.loading = false;
        },
        error: (err) => {
          console.error('Failed to load featured jobs:', err);
          this.error = 'Failed to load featured jobs.';
          this.loading = false;
        },
      });
  }

  /**
   * Smooth scroll to candidate section
   */
  scrollToCandidate(): void {
    document.getElementById('candidate')?.scrollIntoView({ behavior: 'smooth' });
  }

  /**
   * Login with Keycloak
   */
  login(): void {
    this.keycloak.login();
  }

  /**
   * Check if user is authenticated
   */
  get isAuthenticated(): boolean {
    return this.keycloak.authenticated ?? false;
  }

  /**
   * Get authenticated user profile
   */
  get userProfile(): any {
    return this.keycloak.profile;
  }
}