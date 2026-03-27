import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import Keycloak from 'keycloak-js';

export const homeGuard: CanActivateFn = () => {
  const keycloak = inject(Keycloak);
  const router = inject(Router);

  if (keycloak.authenticated && keycloak.realmAccess?.roles) {
    const roles = keycloak.realmAccess.roles;

    if (roles.includes('ADMIN')) {
      return router.createUrlTree(['/admin-dashboard']);
    }

    if (roles.includes('RECRUITER')) {
      return router.createUrlTree(['/listApplications']);
    }
  }

  return true;
};