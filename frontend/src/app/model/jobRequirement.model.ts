export type RequirementCategory =
  | 'SKILL'
  | 'EXPERIENCE'
  | 'EDUCATION'
  | 'CERTIFICATION'
  | 'LANGUAGE';

export type SkillLevel = 'BASIC' | 'INTERMEDIATE' | 'ADVANCED';
export type DegreeLevel = 'ANY' | 'BAC' | 'BTS_DUT' | 'LICENCE_BACHELOR' | 'MASTER' | 'PHD';
export type EnrollmentType = 'STUDENT' | 'GRADUATE' | 'BOTH';

export interface JobRequirement {
  id?: string;
  category: RequirementCategory;
  description: string;
  weight?: number | null;
  minYears?: number | null;
  maxYears?: number | null;
  skillLevel?: SkillLevel | null;
  degreeLevel?: DegreeLevel | null;
  enrollmentType?: EnrollmentType | null;
  languageLevel?: string | null;
}
