
import {
  provideKeycloak,
  createInterceptorCondition,
  IncludeBearerTokenCondition,
  INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
  withAutoRefreshToken,
  AutoRefreshTokenService,
  UserActivityService
} from 'keycloak-angular';

const localhostCondition = createInterceptorCondition<IncludeBearerTokenCondition>({
  urlPattern: /^(http:\/\/localhost:8080)(\/.*)?$/i
});

function getWindow(): Window | undefined {
  return typeof window !== 'undefined' ? window : undefined;
}

const origin = getWindow()?.location.origin ?? 'http://localhost:4200';

export const provideKeycloakAngular = () =>
  provideKeycloak({
    config: {
      url: 'http://localhost:8090',
      realm: 'ai-recrutment',
      clientId: 'recrutment-id'
    },
    initOptions: {
      onLoad: 'check-sso',
      silentCheckSsoRedirectUri: `${origin}/silent-check-sso.html`,
      redirectUri: `${origin}/`
    },
    features: [
      withAutoRefreshToken({
        onInactivityTimeout: 'logout',
        sessionTimeout: 60000
      })
    ],
    providers: [
      AutoRefreshTokenService,
      UserActivityService,
      {
        provide: INCLUDE_BEARER_TOKEN_INTERCEPTOR_CONFIG,
        useValue: [localhostCondition]
      }
    ]
  });
