import { JobRequirement } from "./jobRequirement.model";

export interface JobOffer {
  id: string;
  title: string;
  description: string;
  location: string;
  minSalary: number;
  maxSalary: number;
  employmentType?: string;
  jobStatus?: string;
  requirements?: JobRequirement[];
}
