import { AuthGuardData, createAuthGuard } from 'keycloak-angular';
import { ActivatedRouteSnapshot, CanActivateFn, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { inject } from '@angular/core';

const isAccessAllowed = async (
  route: ActivatedRouteSnapshot,
  __: RouterStateSnapshot,
  authData: AuthGuardData
): Promise<boolean | UrlTree> => {
  const { authenticated, grantedRoles } = authData;

 
  const requiredRole = route.data['role'];
  const allowedRoles = route.data['allowedRoles'];



  // No role / allowedRoles configured on the route means "any
  // authenticated user." Returning false here used to send legitimate
  // logged-in users to /forbidden whenever a route was protected purely
  // for login (no role filter), which was wrong.
  if (!requiredRole && !allowedRoles) {
    return authenticated;
  }

  const hasRequiredRole = (role: string): boolean =>

      Object.values(grantedRoles.realmRoles).some((roles) => roles.includes(role));
   // Object.values(grantedRoles.resourceRoles).some((roles) => roles.includes(role));
 
  
 
  if (requiredRole) {
    if (authenticated && hasRequiredRole(requiredRole)) {
      return true;
    }
  }

  if (allowedRoles && Array.isArray(allowedRoles)) {
    if (authenticated && allowedRoles.some(role => hasRequiredRole(role))) {
      return true;
    }
  }

  const router = inject(Router);
  return router.parseUrl('/forbidden');
};

export const canActivateAuthRole = createAuthGuard<CanActivateFn>(isAccessAllowed);