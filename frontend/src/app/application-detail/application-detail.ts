import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';

@Component({
  selector: 'app-application-detail',
  imports: [CommonModule],
  templateUrl: './application-detail.html',
  styleUrl: './application-detail.css',
})
export class ApplicationDetail implements OnInit {
  app: ApplicationDto | null = null;
  loading = false;
  error: string | null = null;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private appService: ApplicationService
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.error = 'Missing application id';
      return;
    }

    this.loading = true;
    this.appService.getOne(id).subscribe({
      next: (data) => {
        this.app = data;
        this.loading = false;
      },
      error: (err) => {
        this.error = err?.error?.message || 'Failed to load application';
        this.loading = false;
      },
    });
  }

  goToJob() {
    if (!this.app?.jobId) return;
    this.router.navigate(['/jobs', this.app.jobId]);
  }

  goToUser() {
    if (!this.app?.candidateUserId) return;
    this.router.navigate(['/user', this.app.candidateUserId]);
  }

  downloadCv() {
    if (!this.app?.applicationId) return;

    this.appService.downloadCv(this.app.applicationId).subscribe(blob => {
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = this.app?.cvFileName || 'cv.pdf';
      a.click();
      window.URL.revokeObjectURL(url);
    });
  }
  backToList(): void {
  this.router.navigate(['/listApplications']);
}
}