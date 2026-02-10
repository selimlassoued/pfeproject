export interface ApplicationDto {
  applicationId: string;
  jobId: string;
  candidateUserId: string;
  githubUrl: string;
  status: string;
  appliedAt: string;

  cvFileName: string;
  cvContentType: string;

  jobTitle?: string;
  candidateName?: string;
}
