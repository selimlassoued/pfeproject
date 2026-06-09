export type RequirementCategory =
  | 'SKILL'
  | 'EXPERIENCE'
  | 'EDUCATION'
  | 'CERTIFICATION'
  | 'LANGUAGE';

export type SkillLevel = 'BASIC' | 'INTERMEDIATE' | 'ADVANCED' | 'EXPERT';
// SKILL sub-type. Drives which catalog the matcher consults. Recruiter picks
// HARD or SOFT at requirement-creation time. Null on legacy rows = treat as HARD.
export type SkillType = 'HARD' | 'SOFT';
// A single selectable degree value. ENGINEER (ingénieur, bac+5) ranks with MASTER.
// TRAINING = vocational / short professional training ("formation" in FR).
export type DegreeLevel = 'BAC' | 'BTS_DUT' | 'TRAINING' | 'LICENCE_BACHELOR' | 'ENGINEER' | 'MASTER' | 'PHD';
export type EnrollmentType = 'STUDENT' | 'GRADUATE' | 'BOTH';

export interface JobRequirement {
  id?: string;
  category: RequirementCategory;
  description: string;
  weight?: number | null;
  minYears?: number | null;
  skillLevel?: SkillLevel | null;
  // SKILL: "HARD" or "SOFT". Null = legacy/HARD. Mirrors the JobRequirement
  // entity's skillType column on the backend.
  skillType?: SkillType | null;
  // Comma-separated list of accepted degrees, e.g. "LICENCE_BACHELOR,ENGINEER,MASTER".
  // Empty/null = any degree accepted. Recruiter multi-select.
  degreeLevel?: string | null;
  enrollmentType?: EnrollmentType | null;
  // EDUCATION: name of the school/university, optional free-text.
  institute?: string | null;
  languageLevel?: string | null;
  // CERTIFICATION: vendor / organization (AWS, Microsoft, Cisco, ...).
  // When set, the matcher only counts certs whose text mentions this org.
  // Special value "OTHER" pairs with customIssuingOrg for free-text orgs.
  issuingOrg?: string | null;
  // CERTIFICATION: free-text org name when issuingOrg = "OTHER".
  customIssuingOrg?: string | null;
  // CERTIFICATION: when true, expired certs no longer count as matched.
  requireCurrent?: boolean | null;
  // CERTIFICATION: years of validity after issue date (default per cert).
  validityYears?: number | null;
  // Hard knockout flag - if true, a candidate that fails this requirement is
  // visually demoted as "auto-rejected" regardless of overall score. Recruiter
  // still has final say (no automatic status change).
  mustHave?: boolean | null;
}
