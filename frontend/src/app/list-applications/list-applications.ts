// list-applications.ts
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

  applicationId = '';
  jobTitle = '';
  candidateName = '';
  status = '';

  pageIndex = 0; // 0-based
  pageSize = 10;
  totalPages = 0;
  totalElements = 0;

  constructor(private appService: ApplicationService, private router: Router) {}

  ngOnInit(): void {
    this.load();
  }

  search() {
    this.pageIndex = 0;
    this.load();
  }

  load() {
    this.loading = true;
    this.error = null;

    this.appService
      .listApplicationsPaged({
        applicationId: this.applicationId.trim() || undefined,
        jobTitle: this.jobTitle.trim() || undefined,
        candidateName: this.candidateName.trim() || undefined,
        status: this.status || undefined,
        page: this.pageIndex,
        size: this.pageSize,
      })
      .subscribe({
        next: (res) => {
          this.applications = res?.content ?? [];
          this.pageIndex = res?.page ?? 0;
          this.pageSize = res?.size ?? this.pageSize;
          this.totalPages = res?.totalPages ?? 0;
          this.totalElements = res?.totalElements ?? 0;
          this.loading = false;
        },
        error: (err) => {
          this.error = err?.error?.message || 'Failed to load applications';
          this.loading = false;
        },
      });
  }

  goToPage(p: number) {
    if (p < 0 || p >= this.totalPages || p === this.pageIndex) return;
    this.pageIndex = p;
    this.load();
  }

  prev() {
    this.goToPage(this.pageIndex - 1);
  }
  next() {
    this.goToPage(this.pageIndex + 1);
  }

  // ✅ Always show first + last, with a small window + ellipses
  pageItems(): Array<number | '...'> {
    const total = this.totalPages;
    const current = this.pageIndex;

    if (total <= 1) return [0];

    const windowSize = 3; // middle window size
    const items: Array<number | '...'> = [];
    const last = total - 1;

    // first
    items.push(0);

    // middle window boundaries
    let start = Math.max(1, current - Math.floor(windowSize / 2));
    let end = Math.min(last - 1, start + windowSize - 1);

    // shift window back if near end
    start = Math.max(1, end - windowSize + 1);

    // ellipsis after first
    if (start > 1) items.push('...');

    // window
    for (let i = start; i <= end; i++) items.push(i);

    // ellipsis before last
    if (end < last - 1) items.push('...');

    // last
    items.push(last);

    // remove accidental duplicates
    return items.filter((v, i, arr) => i === 0 || v !== arr[i - 1]);
  }

  isPageNumber(v: number | '...'): v is number {
    return v !== '...';
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
