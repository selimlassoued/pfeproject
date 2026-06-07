import { Component, inject, OnInit } from '@angular/core';
import { InterviewService, InterviewResponse } from '../services/interview-service';
import Keycloak from 'keycloak-js';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { matchesWordStart } from '../utils/suggestion-match';

/** Max items shown in any datalist popup on this page. Keeps the popup
 *  short even when the interview history is large. */
const MAX_SUGGESTIONS = 10;

@Component({
  selector: 'app-interview-list',
  imports: [CommonModule, FormsModule],
  templateUrl: './interview-list.html',
  styleUrl: './interview-list.css',
})
export class InterviewList implements OnInit {
  /** Full set fetched once for the recruiter; filtering/paging is client-side. */
  allInterviews: InterviewResponse[] = [];
  loading = true;
  error: string | null = null;
  cancellingId: string | null = null;

  // Filter inputs (bound to the controls).
  searchCandidate = '';
  searchPosition = '';
  recruiterFilter = '';
  statusFilter = '';

  // True after the user presses Enter in the Position field — hides the
  // <datalist> popup so it doesn't keep covering the table. Reset when
  // the field is cleared so suggestions come back for the next search.
  suppressPositionSuggest = false;
  suppressCandidateSuggest = false;

  // True when the current user is ADMIN or SUPERADMIN, so the template
  // can show the recruiter filter (useless for RECRUITER who only sees
  // their own interviews).
  isAdminOrSuper = false;

  // Applied filters - committed only when "Search" is clicked, so the table
  // doesn't churn on every keystroke (mirrors the applications page).
  private appliedCandidate = '';
  private appliedPosition = '';
  private appliedRecruiter = '';
  private appliedStatus = '';

  // Pagination.
  pageIndex = 0;
  pageSize = 10;

  private keycloak = inject(Keycloak);

  constructor(
    private interviewService: InterviewService,
    private router: Router,
  ) {}

  ngOnInit() {
    this.isAdminOrSuper =
      this.keycloak.hasRealmRole('ADMIN') || this.keycloak.hasRealmRole('SUPERADMIN');

    const source$ = this.isAdminOrSuper
      ? this.interviewService.getAll()
      : this.interviewService.getByRecruiter(this.keycloak.subject!);

    source$.subscribe({
      next: (data) => {
        this.allInterviews = data.sort(
          (a, b) => new Date(b.scheduledAt).getTime() - new Date(a.scheduledAt).getTime()
        );
        this.loading = false;
      },
      error: () => {
        this.error = 'Could not load interviews.';
        this.loading = false;
      },
    });
  }

  // ── Filtering ───────────────────────────────────────────────────────────
  onPositionChange(v: string) {
    this.searchPosition = v;
    if (!v) this.suppressPositionSuggest = false;
    this.search();
  }

  onCandidateChange(v: string) {
    this.searchCandidate = v;
    if (!v) this.suppressCandidateSuggest = false;
    this.search();
  }

  search() {
    this.appliedCandidate = this.searchCandidate.trim().toLowerCase();
    this.appliedPosition  = this.searchPosition.trim().toLowerCase();
    this.appliedRecruiter = this.recruiterFilter;
    this.appliedStatus    = this.statusFilter;
    this.pageIndex = 0;
  }

  clearFilters() {
    this.searchCandidate = '';
    this.searchPosition = '';
    this.recruiterFilter = '';
    this.statusFilter = '';
    this.suppressPositionSuggest = false;
    this.suppressCandidateSuggest = false;
    this.search();
  }

  get hasActiveFilters(): boolean {
    return !!(
      this.appliedCandidate ||
      this.appliedPosition ||
      this.appliedRecruiter ||
      this.appliedStatus
    );
  }

  /** Up to MAX_SUGGESTIONS unique job titles that contain whatever the
   *  user has typed so far. Feeds the position <datalist>. Capping keeps
   *  the popup short even when the team has hundreds of postings. */
  get positionOptions(): string[] {
    const q = this.searchPosition.trim().toLowerCase();
    const set = new Set<string>();
    for (const iv of this.allInterviews) {
      const t = iv.jobTitle?.trim();
      if (t) set.add(t);
    }
    let out = Array.from(set).sort((a, b) => a.localeCompare(b));
    if (q) out = out.filter(s => matchesWordStart(s, q));
    return out.slice(0, MAX_SUGGESTIONS);
  }

  /** Up to MAX_SUGGESTIONS candidates matching whatever the user has
   *  typed. Always "Name (email)" — candidates without a real email
   *  are dropped as bad data. */
  get candidateOptions(): string[] {
    const q = this.searchCandidate.trim().toLowerCase();
    const byEmail = new Map<string, string>();
    for (const iv of this.allInterviews) {
      const email = (iv.candidateEmail ?? '').trim();
      if (!email.includes('@') || byEmail.has(email)) continue;
      const name = (iv.candidateName ?? '').trim();
      byEmail.set(email, name ? `${name} (${email})` : email);
    }
    let out = Array.from(byEmail.values()).sort((a, b) => a.localeCompare(b));
    if (q) out = out.filter(s => matchesWordStart(s, q));
    return out.slice(0, MAX_SUGGESTIONS);
  }

  /** Unique {id, label} recruiters across the loaded interviews. Used to
   *  populate the recruiter dropdown for ADMIN/SUPERADMIN. */
  get recruiterOptions(): { id: string; label: string }[] {
    const seen = new Map<string, string>();
    for (const iv of this.allInterviews) {
      if (!iv.recruiterId || seen.has(iv.recruiterId)) continue;
      const label = (iv.recruiterName?.trim() || iv.recruiterEmail || iv.recruiterId);
      seen.set(iv.recruiterId, label);
    }
    return Array.from(seen.entries())
      .map(([id, label]) => ({ id, label }))
      .sort((a, b) => a.label.localeCompare(b.label));
  }

  get filtered(): InterviewResponse[] {
    return this.allInterviews.filter(iv => {
      if (this.appliedStatus && iv.status !== this.appliedStatus) return false;
      if (this.appliedCandidate) {
        const name  = iv.candidateName  || '';
        const email = iv.candidateEmail || '';
        // Include the "Name (email)" form so that clicking that exact
        // suggestion still matches via substring search.
        const hay = `${name} ${email} ${name} (${email})`.toLowerCase();
        if (!hay.includes(this.appliedCandidate)) return false;
      }
      if (this.appliedPosition &&
          !(iv.jobTitle || '').toLowerCase().includes(this.appliedPosition)) {
        return false;
      }
      if (this.appliedRecruiter && iv.recruiterId !== this.appliedRecruiter) {
        return false;
      }
      return true;
    });
  }

  // ── Pagination ──────────────────────────────────────────────────────────
  get totalElements(): number { return this.filtered.length; }

  get totalPages(): number {
    return Math.max(1, Math.ceil(this.filtered.length / this.pageSize));
  }

  get pagedInterviews(): InterviewResponse[] {
    const start = this.pageIndex * this.pageSize;
    return this.filtered.slice(start, start + this.pageSize);
  }

  onPageSizeChange() {
    this.pageIndex = 0;
  }

  goToPage(p: number) {
    if (p < 0 || p >= this.totalPages || p === this.pageIndex) return;
    this.pageIndex = p;
  }

  prev() { this.goToPage(this.pageIndex - 1); }
  next() { this.goToPage(this.pageIndex + 1); }

  pageItems(): Array<number | '...'> {
    const total = this.totalPages;
    const current = this.pageIndex;
    if (total <= 1) return [0];

    const windowSize = 3;
    const items: Array<number | '...'> = [];
    const last = total - 1;

    items.push(0);
    let start = Math.max(1, current - Math.floor(windowSize / 2));
    let end   = Math.min(last - 1, start + windowSize - 1);
    start     = Math.max(1, end - windowSize + 1);

    if (start > 1)      items.push('...');
    for (let i = start; i <= end; i++) items.push(i);
    if (end < last - 1) items.push('...');

    items.push(last);
    return items.filter((v, i, arr) => i === 0 || v !== arr[i - 1]);
  }

  isPageNumber(v: number | '...'): v is number { return v !== '...'; }

  // ── Display helpers ─────────────────────────────────────────────────────
  candidateLabel(iv: InterviewResponse): string {
    return iv.candidateName || iv.candidateEmail || 'Candidate';
  }

  statusLabel(status: string): string {
    const map: Record<string, string> = {
      SCHEDULED: 'Scheduled',
      IN_PROGRESS: 'In progress',
      COMPLETED: 'Completed',
      CANCELLED: 'Cancelled',
    };
    return map[status] ?? status;
  }

  getStatusClass(status: string): string { return status.toLowerCase(); }

  // ── Actions ─────────────────────────────────────────────────────────────
  join(interview: InterviewResponse) {
    this.router.navigate(['/interview', interview.id, 'room']);
  }

  cancel(interview: InterviewResponse) {
    if (!confirm('Cancel this interview? This cannot be undone.')) return;
    this.cancellingId = interview.id;
    const requesterId = this.keycloak.subject ?? '';
    const admin = this.keycloak.hasRealmRole('ADMIN')
      || this.keycloak.hasRealmRole('SUPERADMIN');
    this.interviewService.cancelInterview(interview.id, requesterId, admin).subscribe({
      next: (updated) => {
        const idx = this.allInterviews.findIndex(i => i.id === updated.id);
        if (idx !== -1) this.allInterviews[idx] = updated;
        this.cancellingId = null;
      },
      error: () => { this.cancellingId = null; },
    });
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
