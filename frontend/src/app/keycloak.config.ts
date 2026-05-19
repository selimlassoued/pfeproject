import {
  provideKeycloak,
  createInterceptorCondition,
  IncludeBearerTokenCondition,
  INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
  withAutoRefreshToken,
  AutoRefreshTokenService,
  UserActivityService
} from 'keycloak-angular';

const keycloakCondition =
  createInterceptorCondition<IncludeBearerTokenCondition>({
    urlPattern: /^(http:\/\/localhost:8090)(\/.*)?$/i
  });

const gatewayCondition =
  createInterceptorCondition<IncludeBearerTokenCondition>({
    urlPattern: /^(http:\/\/localhost:8888)(\/.*)?$/i
  });

export const provideKeycloakAngular = () =>
  provideKeycloak({
    config: {
      url: 'http://localhost:8090',
      realm: 'ai-recruitment',
      clientId: 'hireAI-frontend'
    },
    initOptions: {
      onLoad: 'check-sso',
      silentCheckSsoRedirectUri: 'http://localhost:4200/silent-check-sso.html',
      redirectUri: 'http://localhost:4200/',
      // PKCE (Proof Key for Code Exchange) — required for a public SPA client.
      // The app generates a random verifier and sends only its SHA-256 hash;
      // a stolen authorization code is useless without the original verifier.
      pkceMethod: 'S256'
    },
    features: [
      withAutoRefreshToken({
        // While the user is active, the token is refreshed silently and the
        // session stays alive (up to Keycloak's SSO Session Max). The user is
        // only logged out after this much GENUINE inactivity (no mouse/keyboard).
        // Kept just under Keycloak's "SSO Session Idle" (30 min) so the app
        // ends the session cleanly before the server-side session idles out.
        onInactivityTimeout: 'logout',
        sessionTimeout: 29 * 60 * 1000   // 29 minutes (was 60000 ms = 1 minute)
      })
    ],
    providers: [
      AutoRefreshTokenService,
      UserActivityService,
      {
        provide: INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
        useValue: [keycloakCondition, gatewayCondition]
      }
    ]
  });
