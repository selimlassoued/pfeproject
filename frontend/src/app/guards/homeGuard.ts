import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import Keycloak from 'keycloak-js';

export const homeGuard: CanActivateFn = () => {
  const keycloak = inject(Keycloak);
  const router   = inject(Router);

  if (keycloak.authenticated && keycloak.realmAccess?.roles) {
    const roles = keycloak.realmAccess.roles;

    const isSuperAdmin = roles.includes('SUPERADMIN');
    const isAdmin      = roles.includes('ADMIN') && !isSuperAdmin;
    const isRecruiter  = roles.includes('RECRUITER') && !isAdmin && !isSuperAdmin;

    if (isSuperAdmin || isAdmin || isRecruiter) {
      return router.createUrlTree(['/admin-dashboard']);
    }
  }

  return true; // CANDIDATE + unauthenticated → Hero
};