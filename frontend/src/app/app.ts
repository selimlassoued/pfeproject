import { Component, effect, inject } from '@angular/core';
import Keycloak, { KeycloakProfile } from 'keycloak-js';

import {
  KEYCLOAK_EVENT_SIGNAL,
  KeycloakEventType,
  typeEventArgs,
  ReadyArgs,
} from 'keycloak-angular';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { NotificationsMenu } from './notification-menu/notification-menu';
import { ImminentInterview } from './imminent-interview/imminent-interview';
import { CandidateProfileService } from './services/candidate-profile.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, NotificationsMenu, ImminentInterview],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
  public profile?: KeycloakProfile;
  authenticated = false;
  displayName = '';
  keycloakStatus: string | undefined;
  private readonly keycloak = inject(Keycloak);
  private readonly keycloakSignal = inject(KEYCLOAK_EVENT_SIGNAL);
  private readonly router = inject(Router);
  private readonly candidateProfileService = inject(CandidateProfileService);

  constructor() {
    effect(() => {
      const keycloakEvent = this.keycloakSignal();
      this.keycloakStatus = keycloakEvent.type;

      if (keycloakEvent.type === KeycloakEventType.Ready) {
        this.authenticated = typeEventArgs<ReadyArgs>(keycloakEvent.args);

        if (this.authenticated) {
          this.keycloak.loadUserProfile().then(profile => {
            this.profile = profile;
            const first = profile.firstName ?? '';
            const last  = profile.lastName  ?? '';
            this.displayName = `${first} ${last}`.trim() || profile.username || 'Account';
          });

          // Prompt new candidates to onboard - but only ONCE per browser
          // session (not on every page reload), and never again if they
          // chose "Don't ask again".
          if (this.isCandidate()
              && localStorage.getItem('onboardingDismissed') !== 'true'
              && sessionStorage.getItem('onboardingPrompted') !== 'true') {
            this.candidateProfileService.get().then(p => {
              const isNew = !p.status && !p.domain && !p.hardSkills?.length;
              if (isNew) {
                sessionStorage.setItem('onboardingPrompted', 'true');
                this.router.navigate(['/onboarding']);
              }
            }).catch(() => {});
          }
        }
      }

      if (keycloakEvent.type === KeycloakEventType.AuthLogout) {
        this.authenticated = false;
        this.displayName = '';
        this.profile = undefined;
      }
    });
  }

  login()  { this.keycloak.login();  }
  logout() { this.keycloak.logout(); }

  // ── Role helpers - strict hierarchy ──────────────────────────────────────
  isSuperAdmin(): boolean { return this.keycloak.hasRealmRole('SUPERADMIN'); }
  isAdmin():      boolean { return this.keycloak.hasRealmRole('ADMIN') && !this.isSuperAdmin(); }
  isRecruiter():  boolean { return this.keycloak.hasRealmRole('RECRUITER') && !this.isAdmin() && !this.isSuperAdmin(); }
  isCandidate():  boolean { return !this.isSuperAdmin() && !this.isAdmin() && !this.isRecruiter(); }
}
