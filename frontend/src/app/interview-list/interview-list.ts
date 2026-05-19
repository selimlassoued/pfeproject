import { Component, inject } from '@angular/core';
import { OnInit } from '@angular/core';
import { InterviewService, InterviewResponse } from '../services/interview-service';
import Keycloak from 'keycloak-js';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-interview-list',
  imports: [CommonModule],
  templateUrl: './interview-list.html',
  styleUrl: './interview-list.css',
})
export class InterviewList implements OnInit {
  statusClass(arg0: string): string | string[] | Set<string> | { [klass: string]: any; } | null | undefined {
    throw new Error('Method not implemented.');
  }

  interviews: InterviewResponse[] = [];
  loading = true;
  cancellingId: string | null = null;

  private keycloak = inject(Keycloak);
  error: any;

  constructor(
    private interviewService: InterviewService,
    private router: Router
  ) { }

  ngOnInit() {
    const recruiterId = this.keycloak.subject!;
    this.interviewService.getByRecruiter(recruiterId).subscribe({
      next: (data) => {
        this.interviews = data.sort(
          (a, b) => new Date(b.scheduledAt).getTime() - new Date(a.scheduledAt).getTime()
        );
        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }

  join(interview: InterviewResponse) {
    this.router.navigate(['/interview', interview.id, 'room']);
  }

  cancel(interview: InterviewResponse) {
    if (!confirm('Are you sure you want to cancel this interview?')) return;
    this.cancellingId = interview.id;
    const requesterId = this.keycloak.subject ?? '';
    const admin = this.keycloak.hasRealmRole('ADMIN')
      || this.keycloak.hasRealmRole('SUPERADMIN');
    this.interviewService.cancelInterview(interview.id, requesterId, admin).subscribe({
      next: (updated) => {
        const idx = this.interviews.findIndex(i => i.id === updated.id);
        if (idx !== -1) this.interviews[idx] = updated;
        this.cancellingId = null;
      },
      error: () => { this.cancellingId = null; }
    });
  }

  getStatusClass(status: string): string {
    const map: Record<string, string> = {
      SCHEDULED: 'badge-scheduled',
      IN_PROGRESS: 'badge-active',
      COMPLETED: 'badge-completed',
      CANCELLED: 'badge-cancelled'
    };
    return map[status] || '';
  }

  canJoin(interview: InterviewResponse): boolean {
    return interview.status === 'SCHEDULED' || interview.status === 'IN_PROGRESS';
  }

  canCancel(interview: InterviewResponse): boolean {
    return interview.status === 'SCHEDULED' || interview.status === 'IN_PROGRESS';
  }
  viewResult(interviewId: string) {
  this.router.navigate(['/interview', interviewId, 'result']);
}
}