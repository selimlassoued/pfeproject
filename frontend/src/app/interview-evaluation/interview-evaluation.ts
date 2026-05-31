import { Component, OnInit, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import {
  InterviewService,
  InterviewResultResponse,
  DimensionKey,
} from '../services/interview-service';

interface JourneyStep {
  key: string;
  label: string;
  sublabel: string;
  score: number | null;
  display: string;
  status: 'available' | 'missing' | 'final';
  color: string;
}

interface DimensionRow {
  key: DimensionKey;
  label: string;
  score: number;
  evidence: string;
  prePhase: string;
}

const DIMENSION_META: Array<{ key: DimensionKey; label: string; prePhase: string }> = [
  { key: 'technical_depth',       label: 'Technical Depth',       prePhase: 'GitHub-confirmed frameworks' },
  { key: 'problem_solving',       label: 'Problem Solving',       prePhase: 'Interview-only signal' },
  { key: 'requirements_coverage', label: 'Requirements Coverage', prePhase: 'Job-fit semantic match' },
  { key: 'claim_verification',    label: 'Claim Verification',    prePhase: 'Unverified CV skills' },
  { key: 'communication',         label: 'Communication',         prePhase: 'Interview-only signal' },
  { key: 'motivation_fit',        label: 'Motivation & Fit',      prePhase: 'Interview-only signal' },
];

// Interview-alone score = weighted mean of the six dimensions. Must mirror
// `_DIM_WEIGHTS` in analysis-service/main.py - the backend uses the same
// weights to derive the delta and final score.
const DIM_WEIGHTS: Record<DimensionKey, number> = {
  technical_depth:       0.24,
  problem_solving:       0.20,
  requirements_coverage: 0.18,
  claim_verification:    0.16,
  communication:         0.12,
  motivation_fit:        0.10,
};

@Component({
  selector: 'app-interview-evaluation',
  imports: [CommonModule],
  templateUrl: './interview-evaluation.html',
  styleUrl: './interview-evaluation.css',
})
export class InterviewEvaluation implements OnInit, OnDestroy {
  interviewId!: string;
  result: InterviewResultResponse | null = null;
  loading = true;
  error: string | null = null;

  private pollTimer: any = null;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private interviewService: InterviewService,
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
        this.error = 'Could not load evaluation.';
        this.loading = false;
      }
    });
  }

  // ── Color helper aligned to the app's semantic palette ────────────────
  // Cream for top tier, secondary-blue for solid, amber for borderline,
  // muted-red for weak. Stays inside the app's existing palette.
  scoreColor(score: number | null | undefined): string {
    if (score == null) return 'rgba(248,250,252,0.45)';
    if (score >= 80) return '#fffce5';        // cream - top tier
    if (score >= 65) return '#79a4e9';        // secondary-blue - strong
    if (score >= 50) return '#f5c674';        // amber on dark - borderline
    return '#e8889a';                          // muted-red on dark - weak
  }


  // Map the GitHub categorical signal to the same numeric proxy the Python
  // sidecar uses, so the journey bubble shows the value the LLM saw.
  private cvGithubScore(): number | null {
    const g = this.result?.githubScore;
    if (!g) return null;
    const map: Record<string, number> = {
      STRONG: 85,
      MODERATE: 60,
      INACTIVE: 40,
      NO_PUBLIC_WORK: 25,
    };
    return map[g] ?? null;
  }

  private humanGitHubScore(s: string): string {
    const map: Record<string, string> = {
      STRONG: 'Strong public footprint',
      MODERATE: 'Some public work',
      INACTIVE: 'Account inactive',
      NO_PUBLIC_WORK: 'No public repos',
      RATE_LIMITED: 'GitHub rate-limited',
    };
    return map[s] ?? s;
  }

  private humanRecommendation(s: string): string {
    const map: Record<string, string> = {
      STRONG_YES: 'Strong fit',
      YES: 'Good fit',
      MAYBE: 'Borderline fit',
      NO: 'Poor fit',
      INTERVIEW: 'Move to interview',
    };
    return map[s] ?? s;
  }

  private formatDelta(d: number): string {
    if (d > 0) return `+${d} vs baseline`;
    if (d < 0) return `${d} vs baseline`;
    return 'In line with baseline';
  }

  // Interview-alone score: the weighted mean of the dimensional scores. This
  // is the candidate's performance in the interview itself, independent of
  // the CV/GitHub baseline. Mirrors the backend's derivation.
  get interviewScore(): number | null {
    const dims = this.result?.dimensionalScores;
    if (!dims) return null;
    let sum = 0;
    let wSum = 0;
    for (const meta of DIMENSION_META) {
      const d = dims[meta.key];
      const w = DIM_WEIGHTS[meta.key];
      if (d) { sum += d.score * w; wSum += w; }
    }
    return wSum > 0 ? Math.round(sum / wSum) : null;
  }

  get journeySteps(): JourneyStep[] {
    const r = this.result;
    if (!r) return [];
    const cvScore  = this.cvGithubScore();
    const semScore = r.jobFitScore;
    return [
      {
        key: 'cv',
        label: 'CV + GitHub',
        sublabel: r.githubScore ? this.humanGitHubScore(r.githubScore) : 'No profile signal',
        score: cvScore,
        display: cvScore != null ? String(cvScore) : '-',
        status: cvScore != null ? 'available' : 'missing',
        color: this.scoreColor(cvScore),
      },
      {
        key: 'semantic',
        label: 'Semantic Match',
        sublabel: r.preInterviewRecommendation
          ? this.humanRecommendation(r.preInterviewRecommendation)
          : 'No job-fit run',
        score: semScore,
        display: semScore != null ? String(semScore) : '-',
        status: semScore != null ? 'available' : 'missing',
        color: this.scoreColor(semScore),
      },
      {
        key: 'interview',
        label: 'Interview',
        sublabel: r.interviewDelta != null
          ? this.formatDelta(r.interviewDelta)
          : 'Interview-only score',
        score: this.interviewScore,
        display: this.interviewScore != null ? String(this.interviewScore) : '-',
        status: this.interviewScore != null ? 'available' : 'missing',
        color: this.scoreColor(this.interviewScore),
      },
      {
        key: 'final',
        label: 'Final',
        sublabel: r.finalGrade ?? '-',
        score: r.finalScore,
        display: r.finalScore != null ? String(r.finalScore) : '-',
        status: 'final',
        color: this.scoreColor(r.finalScore),
      },
    ];
  }

  get dimensionRows(): DimensionRow[] {
    const dims = this.result?.dimensionalScores;
    if (!dims) return [];
    return DIMENSION_META
      .filter(m => dims[m.key] != null)
      .map(m => ({
        key: m.key,
        label: m.label,
        score: dims[m.key].score,
        evidence: dims[m.key].evidence,
        prePhase: m.prePhase,
      }));
  }

  get gradeClass(): string {
    const g = this.result?.finalGrade;
    if (g === 'A+' || g === 'A') return 'grade-a';
    if (g === 'B') return 'grade-b';
    if (g === 'C') return 'grade-c';
    return 'grade-d';
  }

  get verdictLabel(): string {
    const v = this.result?.interviewVerdict;
    const map: Record<string, string> = {
      CONFIRMED: 'Interview confirmed the verdict',
      RAISED:    'Interview raised the verdict',
      LOWERED:   'Interview lowered the verdict',
      NEW:       'Verdict set by the interview',
    };
    return map[v ?? ''] ?? '';
  }

  get verdictClass(): string {
    return 'verdict-' + (this.result?.interviewVerdict ?? '').toLowerCase();
  }

  hasPreInterviewContext(): boolean {
    const r = this.result;
    if (!r) return false;
    return !!(r.jobFitScore != null ||
      r.githubScore ||
      (r.requiredSkillsMatched && r.requiredSkillsMatched.length > 0) ||
      (r.requiredSkillsMissing && r.requiredSkillsMissing.length > 0) ||
      (r.githubFrameworks && r.githubFrameworks.length > 0) ||
      (r.cvWeaknesses && r.cvWeaknesses.length > 0));
  }

  backToResult() {
    this.router.navigate(['/interview', this.interviewId, 'result']);
  }
}
