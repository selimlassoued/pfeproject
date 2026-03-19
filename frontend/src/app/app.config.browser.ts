import { ApplicationConfig, mergeApplicationConfig } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { includeBearerTokenInterceptor } from 'keycloak-angular';
import { appConfig } from './app.config';
import { provideKeycloakAngular } from './keycloak.config';

const browserAuthConfig: ApplicationConfig = {
  providers: [
    provideKeycloakAngular(),
    provideHttpClient(withInterceptors([includeBearerTokenInterceptor])),
  ],
};

export const browserConfig = mergeApplicationConfig(appConfig, browserAuthConfig);

