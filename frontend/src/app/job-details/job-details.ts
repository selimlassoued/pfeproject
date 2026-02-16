import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink, Router } from '@angular/router';
import { JobService } from '../services/job.service';
import { JobOffer } from '../model/jobOffer.model';
import { signal } from '@angular/core';
import { AuthService } from '../services/AuthService.service';

@Component({
  selector: 'app-job-details',
  imports: [CommonModule, RouterLink],
  templateUrl: './job-details.html',
  styleUrl: './job-details.css',
})
export class JobDetails implements OnInit {
  private readonly jobService = inject(JobService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly authService = inject(AuthService);

  job = signal<JobOffer | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);

  get isRecruiter(): boolean {
    return this.authService.isRecruiter();
  }

  get isAdmin(): boolean {
    return this.authService.isAdmin();
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.loadJob(id);
    } else {
      this.error.set('Job ID not found.');
      this.loading.set(false);
    }
  }

  private loadJob(id: string): void {
    this.loading.set(true);
    this.error.set(null);

    this.jobService.getJobById(id).subscribe({
      next: (job) => {
        this.job.set(job);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        if (err?.status === 404) {
          this.error.set('Job not found.');
        } else if (err?.status) {
          this.error.set(`Failed to load job (HTTP ${err.status}).`);
        } else {
          this.error.set('Failed to load job.');
        }
        console.error('getJobById error:', err);
      },
    });
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
        next: () => {
          this.router.navigate(['/browse']);
        },
        error: (err) => {
          console.error('Delete failed:', err);
          if (err?.status) {
            this.error.set(`Delete failed (HTTP ${err.status}).`);
          } else {
            this.error.set('Failed to delete job offer.');
          }
        }
      });
    }
  }
}