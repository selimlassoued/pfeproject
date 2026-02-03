import { Component, computed, signal } from '@angular/core';
import { JobOffer } from '../model/jobOffer.model';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { JobService } from '../services/job.service';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-job-details',
  imports: [RouterLink,CommonModule],
  templateUrl: './job-details.html',
  styleUrl: './job-details.css',
})
export class JobDetails {
  
  loading = signal(true);
  error = signal<string | null>(null);
  job = signal<JobOffer| null>(null);

  salaryText = computed(() => {
    const j = this.job();
    if (!j) return '';
    const min = j.minSalary ?? null;
    const max = j.maxSalary ?? null;

    if (min == null && max == null) return 'Salary not specified';
    if (min != null && max != null) return `${min} – ${max} TND`;
    if (min != null) return `From ${min} TND`;
    return `Up to ${max} TND`;
  });

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private jobServ: JobService
  ) {}

  ngOnInit() {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.loading.set(false);
      this.error.set('Missing job id in URL.');
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.jobServ.getJobById(id).subscribe({
      next: (res) => {
        this.job.set(res);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err?.error?.message ?? 'Could not load job details.');
      },
    });
  }

}
