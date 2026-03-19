import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../services/application.service';
import { CvAnalysis } from '../model/cv-analysis.model';

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
 
  constructor(private appService: ApplicationService) {}
 
  ngOnChanges(changes: SimpleChanges): void {
    if (changes['applicationId'] && this.applicationId) {
      this.load();
    }
  }
 
  load(): void {
    this.loading = true;
    this.error = null;
    this.analysis = null;
 
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
 
  get statusColor(): string {
    return this.analysis?.parsingStatus === 'SUCCESS' ? 'status-success' : 'status-failed';
  }
 
  evidenceLevel(level: string): string {
    switch (level) {
      case 'HIGH':   return 'ev-high';
      case 'MEDIUM': return 'ev-medium';
      default:       return 'ev-low';
    }
  }
 
  trackByIndex(index: number): number { return index; }
}