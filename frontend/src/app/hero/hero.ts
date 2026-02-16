import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { JobService } from '../services/job.service';
import { JobOffer } from '../model/jobOffer.model';
import { RouterLink } from '@angular/router';
import Keycloak, { KeycloakProfile } from 'keycloak-js';

@Component({
  selector: 'app-hero',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './hero.html',
  styleUrl: './hero.css',
})
export class Hero implements OnInit {

  private readonly keycloak = inject(Keycloak);
  jobs: JobOffer[] = [];
  loading = true;
  error: string | null = null;

  constructor(private jobService: JobService) {}

  ngOnInit(): void {
    this.jobService.getAllJobs().subscribe({
      next: (data) => {
        this.jobs = data.slice(0, 4);
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

    login() {
        console.log(this.keycloak.token);

    this.keycloak.login();
    console.log(this.keycloak.token);

  }
}
