export interface SocialLinks {
  linkedin?: string | null;
  github?: string | null;
  portfolio?: string | null;
}

export interface Language {
  name: string;
  level?: string | null;
}

export interface WorkExperience {
  title?: string | null;
  company?: string | null;
  duration?: string | null;
  description?: string | null;
  skillsUsed: string[];
}

export interface Education {
  degree?: string | null;
  institution?: string | null;
  year?: string | null;
  field?: string | null;
  mention?: string | null;
}

export interface Hackathon {
  title: string;
  rank?: string | null;
  date?: string | null;
  description?: string | null;
  skillsUsed: string[];
}

export interface Project {
  title: string;
  description?: string | null;
  skillsUsed: string[];
  url?: string | null;
}

export interface VolunteerWork {
  role: string;
  organization?: string | null;
  duration?: string | null;
  description?: string | null;
}

// ── GitHub interfaces ─────────────────────────────────────────────────────────

export interface CommitActivity {
  weeklyCounts: number[];
  activeWeeks: number;
  recentWeeksActive: number;
  longestStreak: number;
  isConsistent: boolean;
  recentlyActive: boolean;
  daysSincePush: number;
}

export interface CollaborationSignals {
  activeForkCount: number;
  collaboratedRepos: string[];
  hasCollaboration: boolean;
}

export interface GitHubRepo {
  name?: string | null;
  description?: string | null;
  language?: string | null;
  allLanguages: string[];
  frameworks: string[];
  technologies: string[];   // frameworks + non-implied languages — use this for display
  stars: number;
  url?: string | null;
  isFork: boolean;
  sizeKb: number;
  commitCount: number;
  branchCount: number;
  daysOfActivity: number;
  lastPushed?: string | null;
  topics: string[];
  score: number;
  isReal: boolean;
  scoreReasons: string[];
  // New fields
  ownershipRatio?: number | null;       // 0.0–1.0, candidate's share of commits
  commitActivity?: CommitActivity | null;
  complexityScore?: number | null;      // 0–10
  complexityLabel?: string | null;      // HIGH / MEDIUM / LOW
  complexityReasons: string[];
}

export interface GitHubProfile {
  username?: string | null;
  accountUrl?: string | null;
  name?: string | null;
  bio?: string | null;
  location?: string | null;
  publicReposCount: number;
  ownReposCount: number;
  forkedReposCount: number;
  accountAgeDays: number;
  followers: number;
  lastActive?: string | null;
  // topLanguages removed — no longer in response
  allTechnologies: string[];            // frameworks + non-implied languages (clean)
  allRepoFrameworks: string[];          // all frameworks found across ALL repos (sorted)
  totalStars: number;
  realReposCount: number;
  scoredRepos: GitHubRepo[];
  githubScore: string;                  // STRONG / MODERATE / WEAK / INACTIVE / RATE_LIMITED
  // CV skills verification
  cvSkillsConfirmed: string[];
  cvSkillsLikely: string[];
  cvSkillsNoEvidence: string[];
  // New profile-level fields
  consistentRepos: string[];            // repo names where is_consistent=true
  recentlyActiveRepos: number;          // count of top repos pushed within last 6 months
  avgOwnershipRatio?: number | null;    // weighted by complexity_score across top 3 repos
  collaboration?: CollaborationSignals | null;
}

// ── Evaluation interfaces ─────────────────────────────────────────────────────

export interface EvidenceSignals {
  technicalEvidence: string;
  projectEvidence: string;
  leadershipEvidence: string;
  competitionEvidence: string;
  publicPortfolioEvidence: string;
  githubActivityEvidence: string;       // HIGH / MEDIUM / LOW / N/A
}

export interface CvEvaluation {
  missingSections: string[];
  structureWarnings: string[];
  spellingWarnings: string[];
  dateWarnings: string[];
  gapWarnings: string[];
  profileStrengths: string[];
  profileWeaknesses: string[];
  recruiterInsights: string[];
  likelyTyposCount: number;
  experienceGapCount: number;
  incompleteExperienceEntriesCount: number;
  incompleteEducationEntriesCount: number;
  hasEmail: boolean;
  hasPhone: boolean;
  hasLinkedin: boolean;
  hasGithub: boolean;
  hasPortfolio: boolean;
  hasProjects: boolean;
  hasExperience: boolean;
  hasEducation: boolean;
  hasSkills: boolean;
  hasLanguages: boolean;
  evidenceSignals?: EvidenceSignals | null;
}

// ── Main model ────────────────────────────────────────────────────────────────

export interface CvAnalysis {
  id: string;
  applicationId: string;
  candidateName?: string | null;
  email?: string | null;
  phone?: string | null;
  location?: string | null;
  desiredPosition?: string | null;
  availability?: string | null;
  summary?: string | null;
  seniorityLevel?: string | null;
  socialLinks?: SocialLinks | null;
  skills: string[];
  softSkills: string[];
  certifications: string[];
  awards: string[];
  languages: Language[];
  workExperience: WorkExperience[];
  education: Education[];
  hackathons: Hackathon[];
  projects: Project[];
  volunteerWork: VolunteerWork[];
  githubProfile?: GitHubProfile | null;
  totalYearsExperience?: number | null;
  rawTextLength?: number | null;
  parsingStatus: string;
  errorMessage?: string | null;
  analyzedAt?: string | null;
  evaluation?: CvEvaluation | null;
}