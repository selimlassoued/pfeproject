export interface ApplicationDto {
  applicationId: string;
  jobId: string;
  candidateUserId: string;
  githubUrl: string;
  status: string;
  appliedAt: string;

  cvFileName: string;
  cvContentType: string;

  jobTitle?: string;
  candidateName?: string;
  candidateEmail?: string;
  jobFitScore?: number | null;
  requiredSkillsMatched?: string[];
  requiredSkillsMissing?: string[];
  experienceGap?: number | null;
  seniorityMatch?: boolean | null;
  embeddingScore?: number | null;
  skillScores?: {
    skill: string;
    score: number;           // effective score (post-qualifier-curve)
    status: 'matched' | 'partial' | 'missing';
    evidence?: string | null;
    reason?: string | null;
    // Qualifier-aware fields (BASIC / INTERMEDIATE / ADVANCED / EXPERT requirements):
    rawScore?: number;       // pre-curve raw score
    qualifier?: 'basic' | 'intermediate' | 'advanced' | 'expert' | 'any';
    qualifierBar?: number;   // 0 for 'any', else 45/65/80/90
    meetsQualifier?: boolean;
    gapFromQualifier?: number;
    signal?: 'strength' | 'meets' | 'gap' | 'critical_gap' | '';
  }[];
  requirementScores?: {
    category?: string | null;
    description?: string | null;
    score?: number | null;
    weight?: number | null;
    evidence?: string | null;
    skillLevel?: 'BASIC' | 'INTERMEDIATE' | 'ADVANCED' | 'EXPERT' | null;
    criticalGap?: boolean;
  }[];
  // Non-scoring advisory signals shown as banners on the application detail page.
  // Currently produced: 'distance_far' (ON_SITE job + candidate far from HQ),
  // 'name_mismatch' (CV / GitHub / LinkedIn names disagree).
  warnings?: {
    kind: 'distance_far' | 'name_mismatch' | string;
    severity: 'warning' | 'info' | string;
    message: string;
    details?: Record<string, unknown>;
  }[];
  strengths?: string[];
  weaknesses?: string[];
  recommendation?: string | null;
  interviewQuestions?: string[];
  scoreExplanation?: string | null;
}
