import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-list-applications',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './list-applications.html',
  styleUrl: './list-applications.css',
})
export class ListApplications implements OnInit {
  applications: ApplicationDto[] = [];
  loading = false;
  error: string | null = null;

  // ✅ new filters (backend)
  applicationId = '';
  jobTitle = '';
  candidateName = '';
  status = '';

  constructor(private appService: ApplicationService, private router: Router) {}

  ngOnInit(): void {
    this.load();
  }

  load() {
    this.loading = true;
    this.error = null;

    this.appService
      .listApplications({
        applicationId: this.applicationId.trim() || undefined,
        jobTitle: this.jobTitle.trim() || undefined,
        candidateName: this.candidateName.trim() || undefined,
        status: this.status || undefined,
      })
      .subscribe({
        next: (data) => {
          this.applications = data ?? [];
          this.loading = false;
        },
        error: (err) => {
          this.error = err?.error?.message || 'Failed to load applications';
          this.loading = false;
        },
      });
  }

  openDetails(app: ApplicationDto) {
    this.router.navigate(['/application', app.applicationId]);
  }

  downloadCv(app: ApplicationDto) {
    this.appService.downloadCv(app.applicationId).subscribe((blob) => {
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = app.cvFileName || 'cv.pdf';
      a.click();
      window.URL.revokeObjectURL(url);
    });
  }

  statusClass(status: string): string {
    return (status || '').toLowerCase();
  }
}
