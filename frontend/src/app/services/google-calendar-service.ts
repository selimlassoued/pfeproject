import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/**
 * Links a recruiter's Google Calendar so the interviews they schedule are
 * mirrored onto it. The OAuth *client id* is public by design and lives here;
 * the *client secret* never leaves the backend.
 */
@Injectable({ providedIn: 'root' })
export class GoogleCalendarService {

  private readonly http = inject(HttpClient);
  private readonly base = 'http://localhost:8888/api/interviews/google';

  private readonly clientId =
    '1000932339148-as8sko37j0aim3kpilu1652qt9tiuvgi.apps.googleusercontent.com';
  private readonly redirectUri = 'http://localhost:4200/google-callback';
  private readonly scope = 'https://www.googleapis.com/auth/calendar.events';

  /** The Google consent-screen URL the recruiter is sent to. */
  authUrl(): string {
    const params = new URLSearchParams({
      client_id: this.clientId,
      redirect_uri: this.redirectUri,
      response_type: 'code',
      scope: this.scope,
      access_type: 'offline',   // ask for a refresh token
      prompt: 'consent',        // force the refresh token every time
    });
    return 'https://accounts.google.com/o/oauth2/v2/auth?' + params.toString();
  }

  /** Has this recruiter already linked their calendar? */
  status(recruiterId: string): Observable<{ connected: boolean }> {
    return this.http.get<{ connected: boolean }>(
      `${this.base}/status?recruiterId=${recruiterId}`);
  }

  /** Hand the one-time auth code to the backend to complete the link. */
  connect(code: string, recruiterId: string): Observable<{ connected: boolean }> {
    return this.http.post<{ connected: boolean }>(
      `${this.base}/connect`, { code, recruiterId });
  }

  disconnect(recruiterId: string): Observable<{ connected: boolean }> {
    return this.http.post<{ connected: boolean }>(
      `${this.base}/disconnect?recruiterId=${recruiterId}`, {});
  }
}
