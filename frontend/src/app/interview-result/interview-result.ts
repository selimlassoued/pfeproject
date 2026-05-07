import { Component, OnInit, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { InterviewService, InterviewResultResponse } from '../services/interview-service';

@Component({
  selector: 'app-interview-result',
  imports: [CommonModule],
  templateUrl: './interview-result.html',
  styleUrl: './interview-result.css',
})
export class InterviewResult implements OnInit, OnDestroy {
  interviewId!: string;
  result: InterviewResultResponse | null = null;
  loading = true;
  error: string | null = null;
  showTranscript = false;

  private pollTimer: any = null;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private interviewService: InterviewService
  ) {}

  ngOnInit() {
    this.interviewId = this.route.snapshot.paramMap.get('id')!;
    this.load();
  }

  ngOnDestroy() {
    clearTimeout(this.pollTimer);
  }

  private load() {
    this.interviewService.getResult(this.interviewId).subscribe({
      next: (r) => {
        this.result = r;
        this.loading = false;
        // Keep polling until done or failed
        if (r.processingStatus !== 'COMPLETED' && r.processingStatus !== 'FAILED') {
          this.pollTimer = setTimeout(() => this.load(), 5000);
        }
      },
      error: () => {
        this.error = 'Could not load interview result.';
        this.loading = false;
      }
    });
  }

  get recommendationClass(): string {
    const map: Record<string, string> = {
      STRONG_YES: 'rec-strong-yes',
      YES:        'rec-yes',
      MAYBE:      'rec-maybe',
      NO:         'rec-no',
    };
    return map[this.result?.hiringRecommendation ?? ''] ?? '';
  }

  get recommendationLabel(): string {
    const map: Record<string, string> = {
      STRONG_YES: '✅ Strong Yes',
      YES:        '👍 Yes',
      MAYBE:      '🤔 Maybe',
      NO:         '❌ No',
    };
    return map[this.result?.hiringRecommendation ?? ''] ?? '—';
  }

  get scoreColor(): string {
    const s = this.result?.candidateScore ?? 0;
    if (s >= 8) return '#22c55e';
    if (s >= 5) return '#f59e0b';
    return '#ef4444';
  }

  back() {
    this.router.navigate(['/interviews']);
  }
}