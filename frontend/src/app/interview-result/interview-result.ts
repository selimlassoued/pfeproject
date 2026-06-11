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

  // Theme-aware tier colors. Dark mode keeps the cream / sky-blue / amber
  // / muted-red palette; light mode swaps to saturated dark variants
  // because cream and amber especially become invisible on the white
  // card. Mirrors the same fix in interview-evaluation.ts.
  scoreColor(score: number | null | undefined): string {
    const isLight = typeof document !== 'undefined'
      && document.documentElement.getAttribute('data-theme') === 'light';
    if (score == null) {
      return isLight ? 'rgba(11,16,38,0.45)' : 'rgba(248,250,252,0.45)';
    }
    if (score >= 80) return isLight ? '#92400e' : '#fffce5';
    if (score >= 65) return isLight ? '#1e40bc' : '#79a4e9';
    if (score >= 50) return isLight ? '#b45309' : '#f5c674';
    return           isLight ? '#9f1239' : '#e8889a';
  }

  // The headline score on this page is the unified final_score (0-100) when
  // available, falling back to the legacy 1-10 candidateScore for any old
  // results processed before the Phase-1 scoring pipeline.
  get headlineScore(): number | null {
    if (this.result?.finalScore != null) return this.result.finalScore;
    if (this.result?.candidateScore != null) return this.result.candidateScore * 10;
    return null;
  }

  get headlineDenom(): string {
    return this.result?.finalScore != null ? '/ 100' : '/ 100';
  }

  get hasUnifiedScoring(): boolean {
    return this.result?.finalScore != null;
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
      STRONG_YES: 'Strong yes',
      YES:        'Yes',
      MAYBE:      'Maybe',
      NO:         'No',
    };
    return map[this.result?.hiringRecommendation ?? ''] ?? '-';
  }

  back() {
    this.router.navigate(['/interviews']);
  }

  viewFullEvaluation() {
    this.router.navigate(['/interview', this.interviewId, 'evaluation']);
  }
}
