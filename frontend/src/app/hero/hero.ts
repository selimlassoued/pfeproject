import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { JobService } from '../services/job.service';
import { JobOffer } from '../model/jobOffer.model';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-hero',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './hero.html',
  styleUrl: './hero.css',
})
export class Hero implements OnInit {

  jobs: JobOffer[] = [];
  loading = true;
  error: string | null = null;

  constructor(private jobService: JobService) {}

  ngOnInit(): void {
    this.jobService.getAllJobs().subscribe({
      next: (data) => {
        this.jobs = data;
        this.loading = false;
      },
      error: () => {
        this.error = 'Failed to load jobs';
        this.loading = false;
      },
    });
  }

  scrollToCandidate() {
    document.getElementById('candidate')?.scrollIntoView({ behavior: 'smooth' });
  }
}
