import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { InterviewService, InterviewResponse } from '../services/interview-service';
import { InterviewRoom } from '../interview-room/interview-room';
import Keycloak from 'keycloak-js';

@Component({
  selector: 'app-interview-room-page',
  imports: [CommonModule, InterviewRoom],
  templateUrl: './interview-room-page.html',
  styleUrl: './interview-room-page.css',
})
export class InterviewRoomPage implements OnInit {
  interview: InterviewResponse | null = null;
  loading = true;
  error: string | null = null;

  private keycloak = inject(Keycloak);

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private interviewService: InterviewService
  ) { }

  get displayName(): string {
    return this.keycloak.tokenParsed?.['name']
      ?? this.keycloak.tokenParsed?.['preferred_username']
      ?? 'Participant';
  }

  get isRecruiter(): boolean {
    const roles: string[] = this.keycloak.tokenParsed?.['realm_access']?.roles ?? [];
    return roles.includes('RECRUITER') || roles.includes('ADMIN');
  }

  ngOnInit() {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) { this.error = 'Missing interview ID'; this.loading = false; return; }

    this.interviewService.getById(id).subscribe({
      next: (iv) => { this.interview = iv; this.loading = false; },
      error: () => { this.error = 'Interview not found'; this.loading = false; }
    });
  }

  back() {
    this.router.navigate(['/interviews']);
  }
}