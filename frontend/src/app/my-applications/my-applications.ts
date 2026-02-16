import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';

@Component({
  selector: 'app-my-applications',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './my-applications.html',
  styleUrl: './my-applications.css',
})
export class MyApplications implements OnInit {
  applications: ApplicationDto[] = [];
  loading = false;
  error: string | null = null;

  constructor(private appService: ApplicationService, private router: Router) {}

  ngOnInit(): void {
    this.load();
  }

  load() {
    this.loading = true;
    this.error = null;

    this.appService.getMyApplications().subscribe({
      next: (data) => {
        this.applications = data ?? [];
        this.loading = false;
      },
      error: (err) => {
        this.error = err?.error?.message || 'Failed to load your applications';
        this.loading = false;
      },
    });
  }

  openDetails(app: ApplicationDto) {
    // candidate-safe route (you will create it or reuse existing details page)
    this.router.navigate(['/my-application', app.applicationId]);
  }

  statusClass(status: string): string {
    return (status || '').toLowerCase();
  }
}
