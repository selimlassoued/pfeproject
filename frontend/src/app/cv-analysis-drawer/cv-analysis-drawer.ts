import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core';
import { ApplicationService } from '../services/application.service';
import { CvAnalysis } from '../model/cv-analysis.model';
import { normalizeHttpError } from '../utils/http-error';
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
  notStarted = false;          // polled long enough - no analysis exists or is running
  retrying = false;

  // Bounded polling: 30 × 4s = 120s - covers a slow parse (~96s) + semantic match.
  // After this, we stop the spinner and offer a "Run Analysis" button instead of
  // polling forever (the old behaviour, which spun indefinitely for never-analyzed apps).
  private pollCount = 0;
  private readonly MAX_POLLS = 30;

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
    this.notStarted = false;
    this.pollCount = 0;
    this.poll();
  }

  /** Poll for an analysis result, bounded by MAX_POLLS so the spinner never
   *  runs forever when no analysis was ever triggered for this application. */
  private poll(): void {
    const idAtStart = this.applicationId;
    this.appService.hasCvAnalysis(idAtStart!).subscribe({
      next: (exists) => {
        if (this.applicationId !== idAtStart) return;   // drawer switched apps - abort

        if (exists) {
          this.appService.getCvAnalysis(idAtStart!).subscribe({
            next: (data) => {
              this.analysis = data;
              this.loading = false;
              this.pending = false;
            },
            error: (err) => {
              this.error = normalizeHttpError(err).message || 'Analysis not available yet.';
              this.loading = false;
              this.pending = false;
            },
          });
          return;
        }

        // No analysis row yet - keep polling up to MAX_POLLS, then give up.
        this.loading = false;
        this.pollCount++;
        if (this.pollCount >= this.MAX_POLLS) {
          this.pending = false;
          this.notStarted = true;       // → UI shows "Run AI Analysis" button
          return;
        }
        this.pending = true;
        setTimeout(() => {
          if (this.applicationId === idAtStart) this.poll();
        }, 4000);
      },
      error: () => {
        this.loading = false;
        this.pending = false;
        this.notStarted = true;
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

  /** Format 0.0-1.0 ratio as a percentage string e.g. "73%" */
  ownershipPercent(ratio: number | null | undefined): string {
    if (ratio == null) return '';
    return Math.round(ratio * 100) + '%';
  }

  get isFailed(): boolean {
    return this.analysis?.parsingStatus === 'FAILED';
  }

  /** Triggers an analysis - used both to retry a FAILED one and to start one
   *  for an application that was never analyzed (notStarted state). */
  retry(): void {
    if (!this.applicationId || this.retrying) return;
    this.retrying = true;
    this.appService.retryAnalysis(this.applicationId).subscribe({
      next: () => {
        this.retrying = false;
        this.load();   // resets pollCount + state, starts bounded polling fresh
      },
      error: () => { this.retrying = false; }
    });
  }

  trackByIndex(index: number): number { return index; }
}