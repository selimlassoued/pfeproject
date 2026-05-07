import { JobRequirement } from "./jobRequirement.model";

export interface JobOffer {
  id: string;
  refNumber?: string;
  title: string;
  description: string;
  location: string;
  workArrangement?: string | null;
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
}