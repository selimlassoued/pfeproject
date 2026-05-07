import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import { FormsModule } from '@angular/forms';
import { CvAnalysisDrawer } from '../cv-analysis-drawer/cv-analysis-drawer';
import { InterviewResponse } from '../services/interview-service';
import { ScheduleInterview } from '../schedule-interview/schedule-interview';
import { inject } from '@angular/core';
import{ InterviewService } from '../services/interview-service';
import Keycloak from 'keycloak-js';
@Component({
  selector: 'app-application-detail',
  imports: [CommonModule, FormsModule, CvAnalysisDrawer,ScheduleInterview],
  templateUrl: './application-detail.html',
  styleUrl: './application-detail.css',
})
export class ApplicationDetail implements OnInit {
  newStatus = '';
  updatingStatus = false;
  app: ApplicationDto | null = null;
  loading = false;
  error: string | null = null;

  interviews: InterviewResponse[] = [];
  interviewsLoading = false;
  showScheduleForm = false;
  scheduledInterview: InterviewResponse | null = null;
  cancellingId: string | null = null;

  private keycloak = inject(Keycloak);

  drawerOpen = false;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private appService: ApplicationService,
    private interviewService: InterviewService
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.loadApplication(id);
    }
    if (!id) {
      this.error = 'Missing application id';
      return;
    }

    this.loading = true;
    this.appService.getOne(id).subscribe({
      next: (data) => {
        this.app = data;
        this.newStatus = data.status;
        this.loading = false;
      },
      error: (err) => {
        this.error = err?.error?.message || 'Failed to load application';
        this.loading = false;
      },
    });
  }

  openAnalysis(): void {
    this.drawerOpen = true;
  }

  closeDrawer(): void {
    this.drawerOpen = false;
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

  updateStatus() {
    if (!this.app?.applicationId) return;
    if (!this.newStatus) return;

    this.updatingStatus = true;

    this.appService.updateApplicationStatus(this.app.applicationId, this.newStatus).subscribe({
      next: (updated) => {
        this.app = updated;
        this.updatingStatus = false;
      },
      error: (err) => {
        this.error = err?.error?.message || 'Failed to update status';
        this.updatingStatus = false;
      },
    });
  }

  loadApplication(id: string) {
    this.loading = true;
    this.appService.getOne(id).subscribe({
      next: (app) => {
        this.app = app;
        this.loading = false;
        this.loadInterviews(app.applicationId);
      },
      error: (err) => {
        this.error = err?.error?.message || 'Failed to load application';
        this.loading = false;
      }
    });
  }


  loadInterviews(applicationId: string) {
    this.interviewsLoading = true;
    this.interviewService.getByApplication(applicationId).subscribe({
      next: (list: InterviewResponse[]) => {
        this.interviews = list;
        this.interviewsLoading = false;
      },
      error: () => { this.interviewsLoading = false; }
    });
  }

  // The most recent non-cancelled/completed interview
  get activeInterview(): InterviewResponse | null {
    return this.interviews.find(
      i => i.status === 'SCHEDULED' || i.status === 'IN_PROGRESS'
    ) ?? null;
  }

  cancelInterview(interview: InterviewResponse) {
  if (!confirm('Are you sure you want to cancel this interview?')) return;
  this.cancellingId = interview.id;
  this.interviewService.cancelInterview(interview.id).subscribe({
    next: (updated) => {
      const idx = this.interviews.findIndex(i => i.id === updated.id);
      if (idx !== -1) this.interviews[idx] = updated;
      this.cancellingId = null;
    },
    error: () => { this.cancellingId = null; }
  });
}
  get canSchedule(): boolean {
    return this.activeInterview === null;
  }
  get recruiterEmail(): string {
  return this.keycloak.tokenParsed?.['email'] ?? '';
}

get recruiterId(): string {
  return this.keycloak.subject ?? '';
}
get isKeycloakReady(): boolean {
  return !!this.keycloak.tokenParsed && !!this.keycloak.subject;
}
  onInterviewScheduled(interview: InterviewResponse) {
  this.scheduledInterview = interview;
  this.showScheduleForm = false;
  if (this.app!.applicationId) {
    this.loadInterviews(this.app!.applicationId);
  }
    this.scheduledInterview = null;
}
joinInterview() {
  if (!this.activeInterview) return;
  this.router.navigate(['/interview', this.activeInterview.id, 'room']);
}
viewResult(interviewId: string) {
  this.router.navigate(['/interview', interviewId, 'result']);
}
}