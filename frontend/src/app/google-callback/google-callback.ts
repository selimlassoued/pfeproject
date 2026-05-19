import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import Keycloak from 'keycloak-js';
import { GoogleCalendarService } from '../services/google-calendar-service';

/**
 * Landing route for the Google OAuth redirect (/google-callback?code=…).
 * Extracts the authorization code, ships it to the backend to complete the
 * link, then bounces the recruiter back to the calendar.
 */
@Component({
  selector: 'app-google-callback',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './google-callback.html',
  styleUrl: './google-callback.css',
})
export class GoogleCallback implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly keycloak = inject(Keycloak);
  private readonly google = inject(GoogleCalendarService);

  state: 'working' | 'done' | 'error' = 'working';
  message = 'Connecting your Google Calendar…';

  ngOnInit(): void {
    const params = this.route.snapshot.queryParamMap;
    const code = params.get('code');
    const err = params.get('error');
    const recruiterId = this.keycloak.subject ?? '';

    if (err) { this.fail('Google authorization was cancelled.'); return; }
    if (!code) { this.fail('No authorization code was returned by Google.'); return; }
    if (!recruiterId) { this.fail('Your session expired — please sign in again.'); return; }

    this.google.connect(code, recruiterId).subscribe({
      next: () => {
        this.state = 'done';
        this.message = 'Google Calendar connected — redirecting…';
        setTimeout(() => this.router.navigate(['/calendar']), 1600);
      },
      error: () => this.fail('Could not connect Google Calendar. Please try again.'),
    });
  }

  private fail(msg: string): void {
    this.state = 'error';
    this.message = msg;
  }

  back(): void { this.router.navigate(['/calendar']); }
}
