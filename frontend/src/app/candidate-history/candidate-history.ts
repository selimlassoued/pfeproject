import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';

import { ApplicationService, CandidateApplicationSummary } from '../services/application.service';
import { UserService } from '../services/user-service';
import { AdminUserRow } from '../model/admin_users.type';

@Component({
  selector: 'app-candidate-history',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './candidate-history.html',
  styleUrl: './candidate-history.css',
})
export class CandidateHistory implements OnInit {
  private route   = inject(ActivatedRoute);
  private router  = inject(Router);
  private apps    = inject(ApplicationService);
  private users   = inject(UserService);

  candidateUserId = '';
  candidate?: AdminUserRow;

  loading = true;
  loadError: string | null = null;

  applications: CandidateApplicationSummary[] = [];

  // ── pagination ────────────────────────────────────────────────
  readonly pageSize = 10;
  currentPage = 0;

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.loadError = 'Missing candidate id.';
      this.loading = false;
      return;
    }
    this.candidateUserId = id;
    this.load();
  }

  private async load(): Promise<void> {
    this.loading = true;
    this.loadError = null;
    try {
      this.candidate = await this.users.getUser(this.candidateUserId);
    } catch {
      this.candidate = undefined;
    }
    this.apps.listByCandidate(this.candidateUserId).subscribe({
      next: (rows) => {
        this.applications = rows || [];
        this.currentPage = 0;
        this.loading = false;
      },
      error: () => {
        this.applications = [];
        this.loadError = 'Failed to load applications.';
        this.loading = false;
      },
    });
  }

  // ── pagination helpers ───────────────────────────────────────
  totalPages(): number {
    return Math.max(1, Math.ceil(this.applications.length / this.pageSize));
  }

  pagedApplications(): CandidateApplicationSummary[] {
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

  back(): void {
    this.router.navigate(['/user', this.candidateUserId]);
  }

  openApplication(row: CandidateApplicationSummary): void {
    if (row?.applicationId) {
      this.router.navigate(['/application', row.applicationId]);
    }
  }

  candidateName(): string {
    if (!this.candidate) return 'Candidate';
    const first = (this.candidate.firstName || '').trim();
    const last  = (this.candidate.lastName  || '').trim();
    const full  = `${first} ${last}`.trim();
    return full || this.candidate.username || this.candidate.email || 'Candidate';
  }

  initials(): string {
    if (!this.candidate) return 'C';
    const a = (this.candidate.firstName || this.candidate.username || '?').slice(0, 1);
    const b = (this.candidate.lastName  || '').slice(0, 1);
    return (a + b).toUpperCase();
  }

  statusClass(status: string | null | undefined): string {
    const s = (status || '').toLowerCase();
    return `ch-status ch-status-${s || 'unknown'}`;
  }

  scoreClass(score: number | null | undefined): string {
    if (score == null) return 'ch-score ch-score-none';
    if (score >= 80) return 'ch-score ch-score-high';
    if (score >= 60) return 'ch-score ch-score-mid';
    return 'ch-score ch-score-low';
  }

  total(): number { return this.applications.length; }

  countByStatus(status: string): number {
    const target = status.toLowerCase();
    return this.applications.filter(a => (a.status || '').toLowerCase() === target).length;
  }
}
