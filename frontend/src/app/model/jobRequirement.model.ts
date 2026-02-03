export type RequirementCategory =
  | 'SKILL'
  | 'EXPERIENCE'
  | 'EDUCATION'
  | 'CERTIFICATION'
  | 'LANGUAGE';

export interface JobRequirement {
  id?: string;
  category: RequirementCategory;
  description: string;
  weight?: number | null;
  minYears?: number | null;
  maxYears?: number | null;
}
