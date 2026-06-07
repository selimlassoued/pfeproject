import { Component, OnInit } from '@angular/core';
import { Router, ActivatedRoute } from '@angular/router';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { matchesWordStart } from '../utils/suggestion-match';
import { normalizeHttpError } from '../utils/http-error';

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

  applicationId = '';
  jobId         = '';   // ← pre-filled from query param
  jobTitle      = '';
  candidateName = '';
  status        = '';

  // Suppress datalist popup once Enter is hit so it doesn't keep
  // hovering over the table during the rest of the search session.
  suppressJobTitleSuggest = false;
  suppressCandidateSuggest = false;

  pageIndex = 0;
  pageSize  = 10;
  totalPages    = 0;
  totalElements = 0;

  constructor(
    private appService: ApplicationService,
    private router: Router,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    // pre-fill jobId if coming from dashboard top-jobs click
    const qJobId = this.route.snapshot.queryParamMap.get('jobId');
    if (qJobId) this.jobId = qJobId;

    // pre-fill status if passed
    const qStatus = this.route.snapshot.queryParamMap.get('status');
    if (qStatus) this.status = qStatus;

    this.load();
  }

  search() {
    this.pageIndex = 0;
    this.load();
  }

  onJobTitleChange(v: string) {
    this.jobTitle = v;
    if (!v) this.suppressJobTitleSuggest = false;
  }
  onCandidateChange(v: string) {
    this.candidateName = v;
    if (!v) this.suppressCandidateSuggest = false;
  }

  /** Suggestions derived from the currently-loaded page. Server-side
   *  pagination means we can't see everything, but repeats on this
   *  page are still worth offering. */
  get jobTitleOptions(): string[] {
    const q = this.jobTitle.trim().toLowerCase();
    const s = new Set<string>();
    for (const a of this.applications) {
      const t = a.jobTitle?.trim();
      if (t) s.add(t);
    }
    let out = Array.from(s).sort((x, y) => x.localeCompare(y));
    if (q) out = out.filter(t => matchesWordStart(t, q));
    return out.slice(0, 10);
  }
  get candidateOptions(): string[] {
    const q = this.candidateName.trim().toLowerCase();
    const s = new Set<string>();
    for (const a of this.applications) {
      const n = a.candidateName?.trim();
      if (n) s.add(n);
    }
    let out = Array.from(s).sort((x, y) => x.localeCompare(y));
    if (q) out = out.filter(n => matchesWordStart(n, q));
    return out.slice(0, 10);
  }

  load() {
    this.loading = true;
    this.error   = null;

    this.appService
      .listApplicationsPaged({
        applicationId: this.applicationId.trim() || undefined,
        jobId:         this.jobId.trim()         || undefined,   // ← NEW
        jobTitle:      this.jobTitle.trim()       || undefined,
        candidateName: this.candidateName.trim()  || undefined,
        status:        this.status                || undefined,
        page: this.pageIndex,
        size: this.pageSize,
      })
      .subscribe({
        next: (res) => {
          this.applications = res?.content      ?? [];
          this.pageIndex    = res?.page         ?? 0;
          this.pageSize     = res?.size         ?? this.pageSize;
          this.totalPages   = res?.totalPages   ?? 0;
          this.totalElements= res?.totalElements ?? 0;
          this.loading      = false;
        },
        error: (err) => {
          this.error   = normalizeHttpError(err).message || 'Failed to load applications';
          this.loading = false;
        },
      });
  }

  goToPage(p: number) {
    if (p < 0 || p >= this.totalPages || p === this.pageIndex) return;
    this.pageIndex = p;
    this.load();
  }

  prev() { this.goToPage(this.pageIndex - 1); }
  next() { this.goToPage(this.pageIndex + 1); }

  pageItems(): Array<number | '...'> {
    const total   = this.totalPages;
    const current = this.pageIndex;
    if (total <= 1) return [0];

    const windowSize = 3;
    const items: Array<number | '...'> = [];
    const last = total - 1;

    items.push(0);

    let start = Math.max(1, current - Math.floor(windowSize / 2));
    let end   = Math.min(last - 1, start + windowSize - 1);
    start     = Math.max(1, end - windowSize + 1);

    if (start > 1)        items.push('...');
    for (let i = start; i <= end; i++) items.push(i);
    if (end < last - 1)   items.push('...');

    items.push(last);
    return items.filter((v, i, arr) => i === 0 || v !== arr[i - 1]);
  }

  isPageNumber(v: number | '...'): v is number { return v !== '...'; }

  openDetails(app: ApplicationDto) {
    this.router.navigate(['/application', app.applicationId]);
  }

  downloadCv(app: ApplicationDto) {
    this.appService.downloadCv(app.applicationId).subscribe((blob) => {
      const url = window.URL.createObjectURL(blob);
      const a   = document.createElement('a');
      a.href     = url;
      a.download = app.cvFileName || 'cv.pdf';
      a.click();
      window.URL.revokeObjectURL(url);
    });
  }

  statusClass(status: string): string { return (status || '').toLowerCase(); }

  scoreBadgeClass(score: number | null | undefined): string {
    if (score == null) return 'score-pending';
    if (score >= 70)   return 'score-high';
    if (score >= 45)   return 'score-mid';
    return 'score-low';
  }
}