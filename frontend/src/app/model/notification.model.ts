export interface Notification {
  id: string;
  userId: string;
  type: 'USER_BLOCK' | 'USER_UNBLOCK' | 'ROLE_UPDATE' | 'APPLICATION_STATUS_UPDATE' | 'JOB_UPDATED';
  title: string;
  body: string;
  read: boolean;
  createdAt: string;
}