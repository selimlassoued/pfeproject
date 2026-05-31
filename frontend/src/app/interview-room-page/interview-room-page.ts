import { Component, OnInit, OnDestroy, inject } from '@angular/core';
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
export class InterviewRoomPage implements OnInit, OnDestroy {
  interview: InterviewResponse | null = null;
  loading = true;
  error: string | null = null;
  admitting = false;

  private keycloak = inject(Keycloak);
  /** While the candidate is waiting we re-fetch the interview every few seconds. */
  private poller?: ReturnType<typeof setInterval>;

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

  /**
   * Who this user is in THIS interview:
   *  - 'recruiter' - the organiser; records the recruiter track, runs the script
   *  - 'candidate' - the interviewee; records the candidate track
   *  - 'observer'  - an invited recruiter / admin / superadmin: watch-only,
   *                  mic & camera disabled, records nothing - keeps the
   *                  2-speaker transcript clean.
   */
  get role(): 'recruiter' | 'candidate' | 'observer' {
    const roles: string[] = this.keycloak.tokenParsed?.['realm_access']?.roles ?? [];
    const sub = this.keycloak.subject;
    if (this.interview && sub && sub === this.interview.recruiterId) return 'recruiter';
    const isCandidate = roles.includes('CANDIDATE')
      && !roles.includes('RECRUITER')
      && !roles.includes('ADMIN')
      && !roles.includes('SUPERADMIN');
    return isCandidate ? 'candidate' : 'observer';
  }

  ngOnInit() {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) { this.error = 'Missing interview ID'; this.loading = false; return; }

    this.interviewService.getById(id).subscribe({
      next: (iv) => {
        this.interview = iv;
        this.loading = false;
        // Candidate landed but hasn't been admitted yet → poll until they are.
        if (this.role === 'candidate' && !iv.candidateAdmitted) {
          this.poller = setInterval(() => this.refresh(id), 5_000);
        }
      },
      error: () => { this.error = 'Interview not found'; this.loading = false; }
    });
  }

  ngOnDestroy(): void {
    if (this.poller) clearInterval(this.poller);
  }

  private refresh(id: string) {
    this.interviewService.getById(id).subscribe({
      next: (iv) => {
        this.interview = iv;
        if (iv.candidateAdmitted && this.poller) {
          clearInterval(this.poller);
          this.poller = undefined;
        }
      },
      error: () => { /* keep waiting on transient errors */ },
    });
  }

  /** The recruiter clicked "Admit" - let the candidate in. */
  onAdmitCandidate(): void {
    if (!this.interview || this.admitting) return;
    this.admitting = true;
    const requesterId = this.keycloak.subject ?? '';
    const isAdmin = this.keycloak.hasRealmRole('ADMIN')
      || this.keycloak.hasRealmRole('SUPERADMIN');
    this.interviewService.admitCandidate(this.interview.id, requesterId, isAdmin).subscribe({
      next: (iv) => {
        this.interview = iv;
        this.admitting = false;
        this.interviewService.notifyChanged(); // candidate side will see the badge flip immediately
      },
      error: () => { this.admitting = false; },
    });
  }

  /**
   * The Jitsi conference closed (hang-up or "End & Save"). Recruiter marks it complete,
   * the navbar badge is told to refresh, then we send the user back to a sensible page -
   * the application detail for staff, the interviews list for the candidate.
   */
  onInterviewEnded(): void {
    if (!this.interview) return;

    const iv = this.interview;
    const role = this.role;
    const goNext = () => {
      this.interviewService.notifyChanged();
      // Let the recording upload (kicked off by stopRecording) get its request out
      // before we tear the page down. The HTTP call survives navigation regardless,
      // but a short pause makes the UX feel less abrupt.
      setTimeout(() => {
        if (role !== 'candidate' && iv.applicationId) {
          this.router.navigate(['/application', iv.applicationId]);
        } else {
          this.router.navigate(['/interviews']);
        }
      }, 800);
    };

    // Only the organiser marks completion - observers/candidates just navigate.
    if (role === 'recruiter' && iv.status !== 'COMPLETED') {
      this.interviewService.complete(iv.id).subscribe({
        next: () => goNext(),
        error: () => goNext(),
      });
    } else {
      goNext();
    }
  }

  back() {
    this.router.navigate(['/interviews']);
  }
}