export interface Notification {
  id: string;
  userId: string;
  type: 'USER_BLOCK' | 'USER_UNBLOCK' | 'ROLE_UPDATE' | 'APPLICATION_STATUS_UPDATE'
      | 'JOB_UPDATED' | 'JOB_QUOTA_REACHED' | 'INTERVIEW_INVITE' | 'INTERVIEW_JOIN_REQUEST'
      | 'INTERVIEW_PROPOSAL_DECLINED';
  title: string;
  body: string;
  read: boolean;
  createdAt: string;
  relatedEntityType?: string;
  relatedEntityId?: string;
}
