import { Injectable, inject } from '@angular/core';
import Keycloak from 'keycloak-js';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly keycloak = inject(Keycloak);
  isRecruiter() { return this.keycloak.hasRealmRole('RECRUITER'); }
  isAdmin() { return this.keycloak.hasRealmRole('ADMIN'); }
}
