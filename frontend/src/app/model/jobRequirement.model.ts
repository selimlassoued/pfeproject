export type RequirementCategory =
  | 'SKILL'
  | 'EXPERIENCE'
  | 'EDUCATION'
  | 'CERTIFICATION'
  | 'LANGUAGE';

export type SkillLevel = 'BASIC' | 'INTERMEDIATE' | 'ADVANCED';
// A single selectable degree value. ENGINEER (ingénieur, bac+5) ranks with MASTER.
export type DegreeLevel = 'BAC' | 'BTS_DUT' | 'LICENCE_BACHELOR' | 'ENGINEER' | 'MASTER' | 'PHD';
export type EnrollmentType = 'STUDENT' | 'GRADUATE' | 'BOTH';

export interface JobRequirement {
  id?: string;
  category: RequirementCategory;
  description: string;
  weight?: number | null;
  minYears?: number | null;
  maxYears?: number | null;
  skillLevel?: SkillLevel | null;
  // Comma-separated list of accepted degrees, e.g. "LICENCE_BACHELOR,ENGINEER,MASTER".
  // Empty/null = any degree accepted. Recruiter multi-select.
  degreeLevel?: string | null;
  enrollmentType?: EnrollmentType | null;
  languageLevel?: string | null;
}
