import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import { normalizeHttpError } from '../utils/http-error';
import { InterviewService, ProposalResponse } from '../services/interview-service';
import { NotificationSocketService } from '../services/notification-socket.service';
import { Subscription } from 'rxjs';
import Keycloak from 'keycloak-js';
import Swal from 'sweetalert2';

@Component({
  selector: 'app-my-applications',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './my-applications.html',
  styleUrl: './my-applications.css',
})
export class MyApplications implements OnInit, OnDestroy {
  applications: ApplicationDto[] = [];
  loading = false;
  error: string | null = null;
  withdrawingId: string | null = null;

  // ── pagination ────────────────────────────────────────────────
  readonly pageSize = 10;
  currentPage = 0;

  /** Pending interview proposals - surfaced as a banner. */
  pendingProposals: ProposalResponse[] = [];

  private keycloak = inject(Keycloak);
  private interviewService = inject(InterviewService);
  private socket = inject(NotificationSocketService);
  private wsInterviewSub?: Subscription;
  private wsOfferSub?: Subscription;

  // Statuses from which withdraw is NOT allowed
  private readonly NON_WITHDRAWABLE = ['HIRED', 'REJECTED', 'FLAGGED', 'BLOCKED', 'WITHDRAWN'];

  constructor(private appService: ApplicationService, private router: Router) {}

  ngOnInit(): void {
    this.load();
    this.loadPendingProposals();
    // Live: when something changes server-side, refresh both the application
    // list (status flips) and the pending-proposal banners. Offer transitions
    // can flip status to HIRED, so we also reload the list on offer pings.
    this.wsInterviewSub = this.socket.interviewListChanged$.subscribe(() => {
      this.loadPendingProposals();
      this.load();
    });
    this.wsOfferSub = this.socket.offerChanged$.subscribe(() => {
      this.load();
    });
  }

  ngOnDestroy(): void {
    this.wsInterviewSub?.unsubscribe();
    this.wsOfferSub?.unsubscribe();
  }

  private loadPendingProposals(): void {
    const candidateId = this.keycloak.subject;
    if (!candidateId) return;
    this.interviewService.getProposalsByCandidate(candidateId).subscribe({
      next: (list) => {
        this.pendingProposals = (list || []).filter(p => p.status === 'PENDING');
      },
      error: () => { this.pendingProposals = []; },
    });
  }

  openProposalApp(p: ProposalResponse): void {
    this.router.navigate(['/my-application', p.applicationId]);
  }

  load() {
    this.loading = true;
    this.error = null;

    this.appService.getMyApplications().subscribe({
      next: (data) => {
        this.applications = data ?? [];
        this.clampPage();
        this.loading = false;
      },
      error: (err) => {
        this.error = normalizeHttpError(err).message || 'Failed to load your applications';
        this.loading = false;
      },
    });
  }

  // ── pagination helpers ───────────────────────────────────────
  totalPages(): number {
    return Math.max(1, Math.ceil(this.applications.length / this.pageSize));
  }

  pagedApplications(): ApplicationDto[] {
    const start = this.currentPage * this.pageSize;
    return this.applications.slice(start, start + this.pageSize);
  }

  pageNumbers(): (number | '…')[] {
    const total = this.totalPages();
    if (total <= 7) return Array.from({ length: total }, (_, i) => i);
    const cur = this.currentPage;
    const out: (number | '…')[] = [0];
    if (cur > 2) out.push('…');
    const start = Math.max(1, cur - 1);
    const end   = Math.min(total - 2, cur + 1);
    for (let i = start; i <= end; i++) out.push(i);
    if (cur < total - 3) out.push('…');
    out.push(total - 1);
    return out;
  }

  goToPage(p: number | '…'): void {
    if (p === '…') return;
    const total = this.totalPages();
    this.currentPage = Math.max(0, Math.min(total - 1, p));
  }

  prevPage(): void { if (this.currentPage > 0) this.currentPage--; }
  nextPage(): void { if (this.currentPage < this.totalPages() - 1) this.currentPage++; }

  rangeStart(): number {
    return this.applications.length === 0 ? 0 : this.currentPage * this.pageSize + 1;
  }
  rangeEnd(): number {
    return Math.min(this.applications.length, (this.currentPage + 1) * this.pageSize);
  }

  /** Keep currentPage valid after the list shrinks (e.g. after withdraw). */
  private clampPage(): void {
    const max = this.totalPages() - 1;
    if (this.currentPage > max) this.currentPage = max;
    if (this.currentPage < 0) this.currentPage = 0;
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
      html: `<p style="font-size:.9rem">
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
        this.clampPage();
      },
      error: (err) => {
        this.withdrawingId = null;
        this.error = normalizeHttpError(err).message || 'Failed to withdraw application.';
      },
    });
  }
}