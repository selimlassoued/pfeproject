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
  preferredWorkArrangement?: string;
  preferredJobType?: string;
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
}
