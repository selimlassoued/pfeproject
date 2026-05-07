import { Injectable, inject } from '@angular/core';
import Keycloak from 'keycloak-js';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly keycloak = inject(Keycloak);

  isSuperAdmin(): boolean {
    return this.keycloak.hasRealmRole('SUPERADMIN');
  }

  isAdmin(): boolean {
    return this.keycloak.hasRealmRole('ADMIN') ;
  }

  isRecruiter(): boolean {
    return this.keycloak.hasRealmRole('RECRUITER') ;
  }

  isCandidate(): boolean {
    return this.keycloak.hasRealmRole('CANDIDATE') && !this.isSuperAdmin() && !this.isAdmin() && !this.isRecruiter();
  }
}