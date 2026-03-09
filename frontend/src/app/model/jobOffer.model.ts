import { JobRequirement } from "./jobRequirement.model";

export interface JobOffer {
  id: string;
  refNumber?: string;   // e.g. "JOB-00001"
  title: string;
  description: string;
  location: string;
  minSalary: number;
  maxSalary: number;
  employmentType?: string;
  jobStatus?: string;
  requirements?: JobRequirement[];
}