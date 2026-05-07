import { Component, effect, inject } from '@angular/core';
import Keycloak, { KeycloakProfile } from 'keycloak-js';

import {
  KEYCLOAK_EVENT_SIGNAL,
  KeycloakEventType,
  typeEventArgs,
  ReadyArgs,
} from 'keycloak-angular';
import { RouterLink, RouterOutlet } from '@angular/router';
import { NotificationsMenu } from './notification-menu/notification-menu';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, NotificationsMenu],
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

  // ── Role helpers — strict hierarchy ──────────────────────────────────────
  isSuperAdmin(): boolean { return this.keycloak.hasRealmRole('SUPERADMIN'); }
  isAdmin():      boolean { return this.keycloak.hasRealmRole('ADMIN') && !this.isSuperAdmin(); }
  isRecruiter():  boolean { return this.keycloak.hasRealmRole('RECRUITER') && !this.isAdmin() && !this.isSuperAdmin(); }
  isCandidate():  boolean { return !this.isSuperAdmin() && !this.isAdmin() && !this.isRecruiter(); }
}