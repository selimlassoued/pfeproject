export type AdminUserRow = {
  id: string;

  username?: string;
  email?: string;

  firstName?: string;
  lastName?: string;

  phoneNumber?: string;

  enabled?: boolean;
  createdTimestamp?: number;

  attributes?: Record<string, string[]>;

  roles?: string[];     // e.g. ["ADMIN","HR"]
  role?: string;        // e.g. "ADMIN" (main label to show)
};
