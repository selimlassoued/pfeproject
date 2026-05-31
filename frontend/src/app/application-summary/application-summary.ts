import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import {
  InterviewService,
  InterviewResultResponse,
  DimensionKey,
} from '../services/interview-service';

interface InterviewRow {
  interviewId: string;
  status: string;
  finalScore: number | null;
  finalGrade: string | null;
  recommendation: string | null;
  verdict: string | null;
  processedAt: string | null;
}

interface DimensionAverage {
  key: DimensionKey;
  label: string;
  score: number;
}

const DIMENSION_META: Array<{ key: DimensionKey; label: string }> = [
  { key: 'technical_depth',       label: 'Technical Depth' },
  { key: 'problem_solving',       label: 'Problem Solving' },
  { key: 'requirements_coverage', label: 'Requirements Coverage' },
  { key: 'claim_verification',    label: 'Claim Verification' },
  { key: 'communication',         label: 'Communication' },
  { key: 'motivation_fit',        label: 'Motivation & Fit' },
];

@Component({
  selector: 'app-application-summary',
  imports: [CommonModule],
  templateUrl: './application-summary.html',
  styleUrl: './application-summary.css',
})
export class ApplicationSummary implements OnInit {
  applicationId!: string;
  results: InterviewResultResponse[] = [];
  loading = true;
  error: string | null = null;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private interviewService: InterviewService,
  ) {}

  ngOnInit() {
    this.applicationId = this.route.snapshot.paramMap.get('id')!;
    this.interviewService.getResultsByApplication(this.applicationId).subscribe({
      next: (r) => {
        // Newest interview first.
        this.results = [...r].sort((a, b) =>
          (b.createdAt || '').localeCompare(a.createdAt || ''));
        this.loading = false;
      },
      error: () => {
        this.error = 'Could not load the candidate summary.';
        this.loading = false;
      },
    });
  }

  // ── Colour helper - same palette as the rest of the app ─────────────────
  scoreColor(score: number | null | undefined): string {
    if (score == null) return 'rgba(248,250,252,0.45)';
    if (score >= 80) return '#fffce5';
    if (score >= 65) return '#79a4e9';
    if (score >= 50) return '#f5c674';
    return '#e8889a';
  }

  private letterGrade(s: number): string {
    if (s >= 90) return 'A+';
    if (s >= 80) return 'A';
    if (s >= 65) return 'B';
    if (s >= 50) return 'C';
    return 'D';
  }

  // ── Source records ──────────────────────────────────────────────────────
  // Every result carries the same CV/GitHub/semantic snapshot for this
  // application, so any record serves as the candidate profile.
  get profile(): InterviewResultResponse | null {
    return this.results[0] ?? null;
  }

  /** Interviews that finished analysis and carry a usable final score. */
  get completed(): InterviewResultResponse[] {
    return this.results.filter(
      r => r.processingStatus === 'COMPLETED' && r.finalScore != null);
  }

  get hasCompleted(): boolean {
    return this.completed.length > 0;
  }

  // ── Overall score = average of every completed interview ────────────────
  get overallScore(): number | null {
    const c = this.completed;
    if (!c.length) return null;
    const sum = c.reduce((acc, r) => acc + (r.finalScore || 0), 0);
    return Math.round(sum / c.length);
  }

  get overallGrade(): string {
    const s = this.overallScore;
    return s == null ? '-' : this.letterGrade(s);
  }

  get overallGradeClass(): string {
    const g = this.overallGrade;
    if (g === 'A+' || g === 'A') return 'grade-a';
    if (g === 'B') return 'grade-b';
    if (g === 'C') return 'grade-c';
    return 'grade-d';
  }

  get overallRecommendation(): string {
    const s = this.overallScore;
    if (s == null) return 'Pending';
    if (s >= 85) return 'Strong yes';
    if (s >= 65) return 'Yes';
    if (s >= 50) return 'Maybe';
    return 'No';
  }

  get recommendationClass(): string {
    const s = this.overallScore;
    if (s == null) return 'rec-pending';
    if (s >= 85) return 'rec-strong-yes';
    if (s >= 65) return 'rec-yes';
    if (s >= 50) return 'rec-maybe';
    return 'rec-no';
  }

  // ── Dimensional averages across all completed interviews ────────────────
  get dimensionRows(): DimensionAverage[] {
    const comp = this.completed.filter(r => r.dimensionalScores);
    if (!comp.length) return [];
    const out: DimensionAverage[] = [];
    for (const meta of DIMENSION_META) {
      const scores = comp
        .map(r => r.dimensionalScores![meta.key]?.score)
        .filter((s): s is number => s != null);
      if (scores.length) {
        out.push({
          key: meta.key,
          label: meta.label,
          score: Math.round(scores.reduce((a, b) => a + b, 0) / scores.length),
        });
      }
    }
    return out;
  }

  // ── Per-interview rows ──────────────────────────────────────────────────
  get interviewRows(): InterviewRow[] {
    return this.results.map((r, i) => ({
      interviewId: r.interviewId,
      status: r.processingStatus,
      finalScore: r.finalScore,
      finalGrade: r.finalGrade,
      recommendation: r.hiringRecommendation,
      verdict: r.interviewVerdict,
      processedAt: r.processedAt,
    }));
  }

  recommendationLabel(rec: string | null): string {
    const map: Record<string, string> = {
      STRONG_YES: 'Strong yes', YES: 'Yes', MAYBE: 'Maybe', NO: 'No',
    };
    return map[rec ?? ''] ?? '-';
  }

  statusLabel(status: string): string {
    const map: Record<string, string> = {
      PENDING: 'Awaiting recordings',
      TRANSCRIBING: 'Transcribing',
      ANALYSING: 'Analysing',
      COMPLETED: 'Analysed',
      FAILED: 'Analysis failed',
    };
    return map[status] ?? status;
  }

  // ── GitHub numeric proxy (mirrors the backend fallback) ─────────────────
  get cvGithubScore(): number | null {
    const g = this.profile?.githubScore;
    if (!g) return null;
    const map: Record<string, number> = {
      STRONG: 85, MODERATE: 60, INACTIVE: 40, NO_PUBLIC_WORK: 25,
    };
    return map[g] ?? null;
  }

  openInterview(interviewId: string) {
    this.router.navigate(['/interview', interviewId, 'evaluation']);
  }

  back() {
    this.router.navigate(['/application', this.applicationId]);
  }
}
