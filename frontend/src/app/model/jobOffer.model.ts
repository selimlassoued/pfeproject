import { JobRequirement } from "./jobRequirement.model";

export interface JobOffer {
  id: string;
  title: string;
  description: string;
  location: string;
  minSalary?: number | null;
  maxSalary?: number | null;
  employmentType?: string;
  jobStatus?: string;
  requirements?: JobRequirement[];
}
