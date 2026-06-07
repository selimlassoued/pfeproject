import { Injectable, inject } from '@angular/core';
import Keycloak from 'keycloak-js';

/**
 * Role hierarchy: SUPERADMIN > ADMIN > RECRUITER > CANDIDATE.
 *
 * The "is X?" checks below filter so a user reports as exactly one
 * level: a SUPERADMIN gets isSuperAdmin() === true and isAdmin() ===
 * false, even though Keycloak grants them both realm roles. This
 * matches the filtering already done in the App root component and
 * stops UI inconsistencies where one component asks AuthService and
 * another asks App.
 *
 * Use hasRealmRole() directly when you genuinely need "do they have
 * this role at all" (e.g., for an "Admin or higher" gate).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly keycloak = inject(Keycloak);

  isSuperAdmin(): boolean {
    return this.keycloak.hasRealmRole('SUPERADMIN');
  }

  isAdmin(): boolean {
    return this.keycloak.hasRealmRole('ADMIN') && !this.isSuperAdmin();
  }

  isRecruiter(): boolean {
    return this.keycloak.hasRealmRole('RECRUITER')
        && !this.isAdmin() && !this.isSuperAdmin();
  }

  isCandidate(): boolean {
    return this.keycloak.hasRealmRole('CANDIDATE')
        && !this.isSuperAdmin() && !this.isAdmin() && !this.isRecruiter();
  }
}