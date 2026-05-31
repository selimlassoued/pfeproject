import { JobRequirement } from "./jobRequirement.model";

export interface JobOffer {
  id: string;
  refNumber?: string;
  title: string;
  description: string;
  location: string;
  workArrangement?: string | null;
  // Business domain - one of SOFTWARE_ENGINEERING / FINANCE_BANKING /
  // INSURANCE / PROJECT_MANAGEMENT / QUALITY_ASSURANCE / BUSINESS_ANALYSIS.
  // Feeds the candidate-side chip-grid filter so each domain's candidates
  // only see relevant skills in their Preferences.
  domain?: string | null;
  minSalary?: number | null;
  maxSalary?: number | null;
  employmentType?: string;
  jobStatus?: string;
  openings?: number | null;
  hiredCount?: number | null;
  skillsWeight?: number;
  semanticWeight?: number;
  experienceWeight?: number;
  seniorityWeight?: number;
  requirements?: JobRequirement[];
  // ISO timestamp from the backend (Hibernate @CreationTimestamp). Used by
  // the catalog extractor to stamp each skill's "first seen" date.
  createdAt?: string;
}