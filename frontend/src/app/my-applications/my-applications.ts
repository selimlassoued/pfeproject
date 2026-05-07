import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import Swal from 'sweetalert2';

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
  withdrawingId: string | null = null;

  // Statuses from which withdraw is NOT allowed
  private readonly NON_WITHDRAWABLE = ['HIRED', 'REJECTED', 'FLAGGED', 'BLOCKED', 'WITHDRAWN'];

  constructor(private appService: ApplicationService, private router: Router) {}

  ngOnInit(): void { this.load(); }

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
    this.router.navigate(['/my-application', app.applicationId]);
  }

  statusClass(status: string): string {
    return (status || '').toLowerCase();
  }

  /**
   * Candidate can withdraw from any status except:
   * HIRED, REJECTED, FLAGGED, BLOCKED, WITHDRAWN
   */
  canWithdraw(app: ApplicationDto): boolean {
    return !this.NON_WITHDRAWABLE.includes(app.status ?? '');
  }

  async withdraw(app: ApplicationDto, event: Event): Promise<void> {
    event.stopPropagation();

    const result = await Swal.fire({
      title: 'Withdraw application?',
      html: `<p style="color:rgba(255,255,255,0.7);font-size:.9rem">
        Your application for <strong>${app.jobTitle ?? 'this job'}</strong>
        will be withdrawn. You can re-apply later if you change your mind.
      </p>`,
      showCancelButton: true,
      confirmButtonText: 'Yes, withdraw',
      cancelButtonText: 'Cancel',
      confirmButtonColor: '#d32f2f',
    });

    if (!result.isConfirmed) return;

    this.withdrawingId = app.applicationId;
    this.error = null;

    this.appService.withdrawApplication(app.applicationId).subscribe({
      next: () => {
        this.withdrawingId = null;
        this.applications = this.applications.filter(
          a => a.applicationId !== app.applicationId
        );
      },
      error: (err) => {
        this.withdrawingId = null;
        this.error = err?.error?.message || 'Failed to withdraw application.';
      },
    });
  }
}