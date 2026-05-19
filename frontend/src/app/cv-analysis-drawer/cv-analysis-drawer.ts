import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core';
import { ApplicationService } from '../services/application.service';
import { CvAnalysis } from '../model/cv-analysis.model';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-cv-analysis-drawer',
  imports: [CommonModule],
  templateUrl: './cv-analysis-drawer.html',
  styleUrl: './cv-analysis-drawer.css',
})
export class CvAnalysisDrawer implements OnChanges {
  @Input() applicationId: string | null = null;
  @Input() candidateName: string | null = null;
  @Output() closed = new EventEmitter<void>();

  analysis: CvAnalysis | null = null;
  loading = false;
  error: string | null = null;
  pending = false;
  retrying = false;

  constructor(private appService: ApplicationService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['applicationId'] && this.applicationId) {
      console.log('Loading analysis for:', this.applicationId);
      this.load();
    }
  }

  load(): void {
    this.loading = true;
    this.error = null;
    this.analysis = null;
    this.pending = false;

    this.appService.hasCvAnalysis(this.applicationId!).subscribe({
      next: (exists) => {
        if (!exists) {
          this.loading = false;
          this.pending = true;
          setTimeout(() => {
            if (this.applicationId) this.load();
          }, 4000);
          return;
        }

        this.appService.getCvAnalysis(this.applicationId!).subscribe({
          next: (data) => {
            this.analysis = data;
            this.loading = false;
          },
          error: (err) => {
            this.error = err?.error?.message || err?.message || 'Analysis not available yet.';
            this.loading = false;
          },
        });
      },
      error: () => {
        this.loading = false;
        this.pending = true;
      }
    });
  }

  close(): void {
    this.closed.emit();
  }

  get seniorityColor(): string {
    switch (this.analysis?.seniorityLevel) {
      case 'INTERN':  return 'seniority-intern';
      case 'JUNIOR':  return 'seniority-junior';
      case 'MID':     return 'seniority-mid';
      case 'SENIOR':  return 'seniority-senior';
      default:        return 'seniority-unknown';
    }
  }

  evidenceLevel(level: string): string {
    switch (level) {
      case 'HIGH':   return 'ev-high';
      case 'MEDIUM': return 'ev-medium';
      default:       return 'ev-low';
    }
  }

  githubScoreClass(score: string): string {
    switch (score) {
      case 'STRONG':   return 'github-score-strong';
      case 'MODERATE': return 'github-score-moderate';
      case 'WEAK':     return 'github-score-weak';
      default:         return 'github-score-inactive';
    }
  }

  complexityClass(label: string | null | undefined): string {
    switch (label) {
      case 'HIGH':   return 'complexity-high';
      case 'MEDIUM': return 'complexity-medium';
      default:       return 'complexity-low';
    }
  }

  linkedinStatusClass(status: string): string {
    switch (status) {
      case 'SAFE':   return 'linkedin-status-safe';
      case 'FLAGGED': return 'linkedin-status-flagged';
      default:       return 'linkedin-status-unknown';
    }
  }

  linkedinActivityClass(level: string): string {
    switch (level) {
      case 'HIGH':   return 'linkedin-activity-high';
      case 'LOW':    return 'linkedin-activity-low';
      default:       return 'linkedin-activity-unknown';
    }
  }

  /** Format 0.0–1.0 ratio as a percentage string e.g. "73%" */
  ownershipPercent(ratio: number | null | undefined): string {
    if (ratio == null) return '';
    return Math.round(ratio * 100) + '%';
  }

  get isFailed(): boolean {
    return this.analysis?.parsingStatus === 'FAILED';
  }

  retry(): void {
    if (!this.applicationId || this.retrying) return;
    this.retrying = true;
    this.appService.retryAnalysis(this.applicationId).subscribe({
      next: () => {
        this.analysis = null;
        this.error = null;
        this.pending = true;
        this.retrying = false;
        setTimeout(() => {
          if (this.applicationId) this.load();
        }, 4000);
      },
      error: () => { this.retrying = false; }
    });
  }

  trackByIndex(index: number): number { return index; }
}