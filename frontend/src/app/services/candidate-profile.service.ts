import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export interface CandidateLanguage {
  language: string;
  level: string;
}

export interface CandidateProfile {
  userId?: string;
  status?: string;
  yearsOfExperience?: string;
  educationLevel?: string;
  domain?: string;
  hardSkills?: string[];
  softSkills?: string[];
  languages?: CandidateLanguage[];
  // Multi-select preferences - the candidate may accept any of the listed
  // values. Empty array (or all options selected) means "no preference"; the
  // ranker treats those cases as a full pref_fit = 1.0.
  preferredWorkArrangement?: string[];
  preferredJobType?: string[];
  // ISO-8601 timestamp of the last time the candidate visited their Preferences
  // page. Drives the ⭐NEW chip badges: anything in the catalog with
  // firstSeenAt > this timestamp is shown as new. Seeded to "now" on profile
  // creation so the first visit shows zero NEW chips.
  lastPreferencesAcknowledgedAt?: string | null;
}

@Injectable({ providedIn: 'root' })
export class CandidateProfileService {
  private readonly url = 'http://localhost:8888/api/applications/profile/me';

  constructor(private http: HttpClient) {}

  async get(): Promise<CandidateProfile> {
    return firstValueFrom(this.http.get<CandidateProfile>(this.url));
  }

  async save(profile: CandidateProfile): Promise<CandidateProfile> {
    return firstValueFrom(this.http.put<CandidateProfile>(this.url, profile));
  }

  /**
   * Marks the candidate's Preferences as "just viewed". Backend updates
   * lastPreferencesAcknowledgedAt = now, which clears ⭐NEW badges on every
   * chip currently in the catalog. Called from the Preferences page on open.
   */
  async acknowledge(): Promise<void> {
    await firstValueFrom(this.http.post(`${this.url}/acknowledge`, {}));
  }
}
