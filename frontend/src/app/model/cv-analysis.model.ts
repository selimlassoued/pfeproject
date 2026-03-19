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

export interface EvidenceSignals {
  technicalEvidence: string;
  projectEvidence: string;
  leadershipEvidence: string;
  competitionEvidence: string;
  publicPortfolioEvidence: string;
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
  evidenceSignals?: EvidenceSignals;
}

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
  totalYearsExperience?: number | null;
  rawTextLength?: number | null;
  parsingStatus: string;
  errorMessage?: string | null;
  analyzedAt?: string | null;
  evaluation?: CvEvaluation | null;
}