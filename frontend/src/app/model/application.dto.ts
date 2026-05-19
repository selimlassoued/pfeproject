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
    score: number;
    status: 'matched' | 'partial' | 'missing';
    evidence?: string | null;
    reason?: string | null;
  }[];
  requirementScores?: {
    category?: string | null;
    description?: string | null;
    score?: number | null;
    weight?: number | null;
    evidence?: string | null;
  }[];
  strengths?: string[];
  weaknesses?: string[];
  recommendation?: string | null;
  interviewQuestions?: string[];
  scoreExplanation?: string | null;
}
