import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import Keycloak from 'keycloak-js';
import Swal from 'sweetalert2';

import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import {
  InterviewService, InterviewResponse, ProposalResponse,
  ReschedRequestResponse, DelegationResponse,
} from '../services/interview-service';
import { UserService } from '../services/user-service';
import { AdminUserRow } from '../model/admin_users.type';
import { ScheduleInterview } from '../schedule-interview/schedule-interview';
import { ReschedulePicker } from '../reschedule-picker/reschedule-picker';
import { SeenTrackerService } from '../services/seen-tracker.service';
import { NotificationSocketService } from '../services/notification-socket.service';
import { Subscription } from 'rxjs';

/**
 * Dedicated page for managing everything around an application's interviews:
 * scheduled / completed list, proposal flow (recruiter offers slots, candidate
 * picks), reschedule requests, delegation requests, and recruiter invitations.
 * Lives at /application/:id/interviews and links back to the application detail.
 */
@Component({
  selector: 'app-application-interviews',
  imports: [CommonModule, FormsModule, RouterLink, ScheduleInterview, ReschedulePicker],
  templateUrl: './application-interviews.html',
  styleUrl: './application-interviews.css',
})
export class ApplicationInterviewsPage implements OnInit, OnDestroy {

  private keycloak = inject(Keycloak);

  app: ApplicationDto | null = null;
  loading = false;
  error: string | null = null;

  interviews: InterviewResponse[] = [];
  interviewsLoading = false;
  showScheduleForm = false;
  cancellingId: string | null = null;

  proposals: ProposalResponse[] = [];
  cancellingProposalId: string | null = null;

  reschedRequests: Record<string, ReschedRequestResponse[]> = {};
  reschedFormFor: string | null = null;
  acceptingReschedIdx: number | null = null;
  decliningReschedId: string | null = null;
  cancellingReschedId: string | null = null;

  delegations: Record<string, DelegationResponse[]> = {};
  delegating = false;
  acceptingDelegationId: string | null = null;
  decliningDelegationId: string | null = null;
  cancellingDelegationId: string | null = null;

  recruiters: AdminUserRow[] = [];
  inviteSelection = '';
  invitingId: string | null = null;
  requestingJoin = false;
  joinRequestSent = false;

  /** Set by the declined-proposal "Reject candidate" shortcut so the button
   *  disables while the status update is in flight. */
  rejecting = false;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private interviewService: InterviewService,
    private appService: ApplicationService,
    private userService: UserService,
    private seen: SeenTrackerService,
    private socket: NotificationSocketService,
  ) {}

  private wsSub?: Subscription;

  // ── Lifecycle ───────────────────────────────────────────────────────────
  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) { this.error = 'Missing application id'; return; }
    this.loadRecruiters();
    this.loading = true;
    this.appService.getOne(id).subscribe({
      next: (data) => {
        this.app = data;
        this.loading = false;
        this.loadInterviews(data.applicationId);
        // Visiting the page = acknowledging the NEW badge.
        this.seen.markSeen(data.applicationId, 'interviews');
      },
      error: (err) => {
        this.error = err?.error?.message || 'Failed to load application';
        this.loading = false;
      },
    });

    // Live: when something changes elsewhere, refetch and re-mark as seen.
    this.wsSub = this.socket.interviewListChanged$.subscribe(() => {
      if (this.app?.applicationId) {
        this.loadInterviews(this.app.applicationId);
        this.seen.markSeen(this.app.applicationId, 'interviews');
      }
    });
  }

  ngOnDestroy(): void { this.wsSub?.unsubscribe(); }

  // ── Role helpers ────────────────────────────────────────────────────────
  isSuperAdmin(): boolean { return this.keycloak.hasRealmRole('SUPERADMIN'); }
  isAdmin():      boolean { return this.keycloak.hasRealmRole('ADMIN'); }

  get recruiterEmail(): string { return this.keycloak.tokenParsed?.['email'] ?? ''; }
  get recruiterId(): string { return this.keycloak.subject ?? ''; }

  backToApplication(): void {
    if (this.app?.applicationId) this.router.navigate(['/application', this.app.applicationId]);
  }

  // ── Interview list loading ──────────────────────────────────────────────
  loadInterviews(applicationId: string): void {
    this.interviewsLoading = true;
    this.interviewService.getByApplication(applicationId).subscribe({
      next: (list) => {
        this.interviews = list;
        this.interviewsLoading = false;
        this.refreshAllResched();
      },
      error: () => { this.interviewsLoading = false; }
    });
    this.loadProposals(applicationId);
  }

  loadProposals(applicationId: string): void {
    this.interviewService.getProposalsByApplication(applicationId).subscribe({
      next: (list) => { this.proposals = list; },
      error: () => { this.proposals = []; },
    });
  }

  private refreshAllResched(): void {
    for (const iv of this.interviews) {
      if (iv.status === 'SCHEDULED') {
        this.loadReschedFor(iv.id);
        this.loadDelegationsFor(iv.id);
      }
    }
  }

  // ── Reschedule ──────────────────────────────────────────────────────────
  private loadReschedFor(interviewId: string): void {
    this.interviewService.getReschedRequests(interviewId).subscribe({
      next: (list) => { this.reschedRequests[interviewId] = list || []; },
      error: () => { this.reschedRequests[interviewId] = []; },
    });
  }

  pendingReschedFor(interviewId: string): ReschedRequestResponse | null {
    return (this.reschedRequests[interviewId] || []).find(r => r.status === 'PENDING') ?? null;
  }

  openReschedForm(interviewId: string): void { this.reschedFormFor = interviewId; }
  closeReschedForm(): void { this.reschedFormFor = null; }

  onReschedProposed(req: ReschedRequestResponse): void {
    const list = this.reschedRequests[req.interviewId] || [];
    this.reschedRequests[req.interviewId] = [req, ...list.filter(r => r.id !== req.id)];
    this.reschedFormFor = null;
  }

  acceptReschedule(req: ReschedRequestResponse, slotIndex: number): void {
    if (this.acceptingReschedIdx !== null) return;
    this.acceptingReschedIdx = slotIndex;
    this.interviewService.acceptReschedule(req.id, slotIndex).subscribe({
      next: (updated) => {
        const list = this.reschedRequests[req.interviewId] || [];
        this.reschedRequests[req.interviewId] = list.map(r => r.id === updated.id ? updated : r);
        this.acceptingReschedIdx = null;
        if (this.app?.applicationId) this.loadInterviews(this.app.applicationId);
      },
      error: (err) => {
        this.acceptingReschedIdx = null;
        Swal.fire({ icon: 'error', title: 'Failed to accept',
                    text: err?.error?.message || 'Please try again.' });
      },
    });
  }

  declineReschedule(req: ReschedRequestResponse): void {
    Swal.fire({
      title: 'Keep the original time?',
      text: 'The reschedule request will be declined.',
      icon: 'question',
      showCancelButton: true,
      confirmButtonText: 'Yes, keep it',
      cancelButtonText: 'Cancel',
      background: '#141c3c',
      color: '#e8f0fe',
    }).then((result) => {
      if (!result.isConfirmed) return;
      this.decliningReschedId = req.id;
      this.interviewService.declineReschedule(req.id).subscribe({
        next: (updated) => {
          const list = this.reschedRequests[req.interviewId] || [];
          this.reschedRequests[req.interviewId] = list.map(r => r.id === updated.id ? updated : r);
          this.decliningReschedId = null;
        },
        error: () => { this.decliningReschedId = null; },
      });
    });
  }

  cancelReschedule(req: ReschedRequestResponse): void {
    this.cancellingReschedId = req.id;
    this.interviewService.cancelReschedule(req.id).subscribe({
      next: (updated) => {
        const list = this.reschedRequests[req.interviewId] || [];
        this.reschedRequests[req.interviewId] = list.map(r => r.id === updated.id ? updated : r);
        this.cancellingReschedId = null;
      },
      error: () => { this.cancellingReschedId = null; },
    });
  }

  // ── Delegation ──────────────────────────────────────────────────────────
  private loadDelegationsFor(interviewId: string): void {
    this.interviewService.getDelegationsForInterview(interviewId).subscribe({
      next: (list) => { this.delegations[interviewId] = list || []; },
      error: () => { this.delegations[interviewId] = []; },
    });
  }

  pendingDelegationFor(interviewId: string): DelegationResponse | null {
    return (this.delegations[interviewId] || []).find(d => d.status === 'PENDING') ?? null;
  }

  private otherRecruiters(): AdminUserRow[] {
    const me = this.recruiterId;
    return this.recruiters.filter(r => r.id !== me);
  }

  async openDelegationModal(iv: InterviewResponse): Promise<void> {
    const others = this.otherRecruiters();
    if (others.length === 0) {
      Swal.fire({ icon: 'info', title: 'No other recruiters available',
                  text: 'There are no colleagues to delegate to.' });
      return;
    }
    const recruiterOptions = others
      .map(r => `<option value="${r.id}">${this.recruiterLabel(r.id)}</option>`)
      .join('');
    const result = await Swal.fire({
      title: 'Delegate this interview',
      html: `
        <p style="color:rgba(255,255,255,0.7);font-size:.9rem;text-align:left;margin-bottom:.85rem">
          Ask a colleague to take over. They'll get a request and can accept or decline.
          You'll stay as an observer.
        </p>
        <label style="display:block;font-size:.85rem;text-align:left;margin-bottom:.3rem;font-weight:600">Recruiter</label>
        <select id="deleg-to" class="swal2-select" style="width:100%;margin-bottom:.7rem">
          <option value="">Select a colleague…</option>
          ${recruiterOptions}
        </select>
        <label style="display:block;font-size:.85rem;text-align:left;margin-bottom:.3rem;font-weight:600">Reason (optional)</label>
        <textarea id="deleg-msg" class="swal2-textarea" rows="3" maxlength="500"
                  style="width:100%"
                  placeholder="e.g. I have a board meeting at that time - can you cover this one?"></textarea>
      `,
      showCancelButton: true,
      confirmButtonText: 'Send request',
      cancelButtonText: 'Cancel',
      background: '#141c3c',
      color: '#e8f0fe',
      focusConfirm: false,
      preConfirm: () => {
        const to = (document.getElementById('deleg-to') as HTMLSelectElement)?.value;
        const msg = (document.getElementById('deleg-msg') as HTMLTextAreaElement)?.value;
        if (!to) {
          Swal.showValidationMessage('Please pick a recruiter.');
          return false;
        }
        return { to, msg };
      },
    });
    if (!result.isConfirmed || !result.value) return;

    this.delegating = true;
    this.interviewService.proposeDelegation(iv.id, {
      toRecruiterId: result.value.to,
      message: result.value.msg?.trim() || undefined,
    }).subscribe({
      next: (d) => {
        const list = this.delegations[iv.id] || [];
        this.delegations[iv.id] = [d, ...list.filter(x => x.id !== d.id)];
        this.delegating = false;
      },
      error: (err) => {
        this.delegating = false;
        Swal.fire({ icon: 'error', title: 'Failed to send',
                    text: err?.error?.message || 'Please try again.' });
      },
    });
  }

  acceptDelegation(d: DelegationResponse): void {
    if (this.acceptingDelegationId) return;
    this.acceptingDelegationId = d.id;
    this.interviewService.acceptDelegation(d.id).subscribe({
      next: (updated) => {
        const list = this.delegations[d.interviewId] || [];
        this.delegations[d.interviewId] = list.map(x => x.id === updated.id ? updated : x);
        this.acceptingDelegationId = null;
        if (this.app?.applicationId) this.loadInterviews(this.app.applicationId);
        Swal.fire({ icon: 'success', title: 'Interview is now yours',
                    text: 'You\'re the new organizer.',
                    timer: 2500, showConfirmButton: false });
      },
      error: (err) => {
        this.acceptingDelegationId = null;
        Swal.fire({ icon: 'error', title: 'Failed to accept',
                    text: err?.error?.message || 'Please try again.' });
      },
    });
  }

  declineDelegation(d: DelegationResponse): void {
    Swal.fire({
      title: 'Decline this delegation?',
      text: 'The original recruiter stays as the organizer.',
      icon: 'question',
      showCancelButton: true,
      confirmButtonText: 'Yes, decline',
      cancelButtonText: 'Cancel',
      background: '#141c3c',
      color: '#e8f0fe',
    }).then((r) => {
      if (!r.isConfirmed) return;
      this.decliningDelegationId = d.id;
      this.interviewService.declineDelegation(d.id).subscribe({
        next: (updated) => {
          const list = this.delegations[d.interviewId] || [];
          this.delegations[d.interviewId] = list.map(x => x.id === updated.id ? updated : x);
          this.decliningDelegationId = null;
        },
        error: () => { this.decliningDelegationId = null; },
      });
    });
  }

  cancelDelegation(d: DelegationResponse): void {
    this.cancellingDelegationId = d.id;
    this.interviewService.cancelDelegation(d.id).subscribe({
      next: (updated) => {
        const list = this.delegations[d.interviewId] || [];
        this.delegations[d.interviewId] = list.map(x => x.id === updated.id ? updated : x);
        this.cancellingDelegationId = null;
      },
      error: () => { this.cancellingDelegationId = null; },
    });
  }

  // ── Proposals ───────────────────────────────────────────────────────────
  get pendingProposal(): ProposalResponse | null {
    return this.proposals.find(p => p.status === 'PENDING') ?? null;
  }

  get declinedProposal(): ProposalResponse | null {
    if (this.pendingProposal || this.activeInterview) return null;
    const declined = this.proposals
      .filter(p => p.status === 'DECLINED')
      .sort((a, b) => (b.respondedAt || '').localeCompare(a.respondedAt || ''));
    return declined[0] ?? null;
  }

  cancelProposal(proposal: ProposalResponse): void {
    Swal.fire({
      title: 'Cancel this proposal?',
      text: 'The candidate will no longer be able to pick a slot. You can send a new proposal afterwards.',
      icon: 'warning',
      showCancelButton: true,
      confirmButtonText: 'Yes, cancel',
      cancelButtonText: 'Keep it',
      confirmButtonColor: '#dc2626',
      cancelButtonColor: '#374151',
      background: '#141c3c',
      color: '#e8f0fe',
    }).then((result) => {
      if (!result.isConfirmed) return;
      this.cancellingProposalId = proposal.id;
      const admin = this.isAdmin() || this.isSuperAdmin();
      this.interviewService.cancelProposal(proposal.id, this.recruiterId, admin).subscribe({
        next: (updated) => {
          const idx = this.proposals.findIndex(p => p.id === updated.id);
          if (idx !== -1) this.proposals[idx] = updated;
          this.cancellingProposalId = null;
        },
        error: () => { this.cancellingProposalId = null; },
      });
    });
  }

  onProposalSent(proposal: ProposalResponse): void {
    this.showScheduleForm = false;
    this.proposals = [proposal, ...this.proposals.filter(p => p.id !== proposal.id)];
  }

  /** Used by the declined-proposal panel "Reject candidate" shortcut. */
  rejectCandidate(): void {
    if (!this.app?.applicationId || this.rejecting) return;
    Swal.fire({
      title: 'Reject this application?',
      text: 'The candidate will be notified. This action cannot be undone.',
      icon: 'warning',
      showCancelButton: true,
      confirmButtonText: 'Yes, reject',
      cancelButtonText: 'Cancel',
      confirmButtonColor: '#dc2626',
      cancelButtonColor: '#374151',
      background: '#141c3c',
      color: '#e8f0fe',
    }).then((r) => {
      if (!r.isConfirmed || !this.app?.applicationId) return;
      this.rejecting = true;
      this.appService.updateApplicationStatus(this.app.applicationId, 'REJECTED').subscribe({
        next: () => { this.rejecting = false; this.backToApplication(); },
        error: () => { this.rejecting = false; },
      });
    });
  }

  // ── Scheduled-interview actions ─────────────────────────────────────────
  get activeInterview(): InterviewResponse | null {
    return this.interviews.find(
      i => i.status === 'SCHEDULED' || i.status === 'IN_PROGRESS'
    ) ?? null;
  }

  canCancel(iv: InterviewResponse): boolean {
    return this.isAdmin() || this.isSuperAdmin() || iv.recruiterId === this.recruiterId;
  }

  cancelInterview(interview: InterviewResponse): void {
    Swal.fire({
      title: 'Cancel this interview?',
      text: 'The interview will be marked as cancelled and the room closed. This cannot be undone.',
      icon: 'warning',
      showCancelButton: true,
      confirmButtonText: 'Yes, cancel it',
      cancelButtonText: 'Keep it',
      confirmButtonColor: '#dc2626',
      cancelButtonColor: '#374151',
      background: '#141c3c',
      color: '#e8f0fe',
    }).then((result) => {
      if (!result.isConfirmed) return;
      this.cancellingId = interview.id;
      const admin = this.isAdmin() || this.isSuperAdmin();
      this.interviewService.cancelInterview(interview.id, this.recruiterId, admin).subscribe({
        next: (updated) => {
          const idx = this.interviews.findIndex(i => i.id === updated.id);
          if (idx !== -1) this.interviews[idx] = updated;
          this.cancellingId = null;
        },
        error: () => { this.cancellingId = null; },
      });
    });
  }

  get canSchedule(): boolean {
    const s = this.app?.status;
    return (s === 'APPLIED' || s === 'UNDER_REVIEW' || s === 'INTERVIEW_PHASE')
      && this.activeInterview === null
      && this.pendingProposal === null;
  }

  onInterviewScheduled(_iv: InterviewResponse): void {
    this.showScheduleForm = false;
    if (this.app?.applicationId) {
      this.loadInterviews(this.app.applicationId);
      this.appService.getOne(this.app.applicationId).subscribe({
        next: (data) => { this.app = data; },
        error: () => {},
      });
    }
  }

  joinInterview(): void {
    const iv = this.activeInterview;
    if (!iv || !this.canJoin(iv)) return;
    this.router.navigate(['/interview', iv.id, 'room']);
  }

  viewResult(interviewId: string): void {
    this.router.navigate(['/interview', interviewId, 'result']);
  }

  viewSummary(): void {
    if (this.app?.applicationId) this.router.navigate(['/application', this.app.applicationId, 'summary']);
  }

  // ── Recruiter invitations ───────────────────────────────────────────────
  private loadRecruiters(): void {
    this.userService.listUsers({ max: 200 })
      .then(users => {
        this.recruiters = users.filter(u =>
          ((u.roles ?? []).includes('RECRUITER') || u.role === 'RECRUITER')
          && u.id !== this.recruiterId);
      })
      .catch(() => { this.recruiters = []; });
  }

  canJoin(iv: InterviewResponse): boolean {
    return this.isAdmin() || this.isSuperAdmin()
      || iv.recruiterId === this.recruiterId
      || (iv.invitedRecruiterIds ?? []).includes(this.recruiterId);
  }

  canManageInvites(iv: InterviewResponse): boolean {
    return iv.recruiterId === this.recruiterId;
  }

  requestToJoin(iv: InterviewResponse): void {
    this.requestingJoin = true;
    const t = this.keycloak.tokenParsed as Record<string, string> | undefined;
    const name = t?.['name']
      || `${t?.['given_name'] ?? ''} ${t?.['family_name'] ?? ''}`.trim()
      || t?.['preferred_username']
      || this.recruiterEmail
      || 'A recruiter';
    this.interviewService.requestJoin(iv.id, this.recruiterId, name).subscribe({
      next: () => { this.requestingJoin = false; this.joinRequestSent = true; },
      error: () => { this.requestingJoin = false; },
    });
  }

  availableRecruiters(iv: InterviewResponse): AdminUserRow[] {
    const invited = new Set(iv.invitedRecruiterIds ?? []);
    return this.recruiters.filter(r => !invited.has(r.id));
  }

  recruiterLabel(id: string): string {
    const r = this.recruiters.find(x => x.id === id);
    if (!r) return 'Recruiter';
    const name = `${r.firstName ?? ''} ${r.lastName ?? ''}`.trim();
    return name || r.username || r.email || 'Recruiter';
  }

  inviteRecruiter(iv: InterviewResponse): void {
    if (!this.inviteSelection) return;
    this.invitingId = iv.id;
    this.interviewService.invite(iv.id, this.inviteSelection).subscribe({
      next: (updated) => {
        const idx = this.interviews.findIndex(i => i.id === updated.id);
        if (idx !== -1) this.interviews[idx] = updated;
        this.inviteSelection = '';
        this.invitingId = null;
      },
      error: () => { this.invitingId = null; },
    });
  }

  uninviteRecruiter(iv: InterviewResponse, recruiterId: string): void {
    this.invitingId = iv.id;
    this.interviewService.uninvite(iv.id, recruiterId).subscribe({
      next: (updated) => {
        const idx = this.interviews.findIndex(i => i.id === updated.id);
        if (idx !== -1) this.interviews[idx] = updated;
        this.invitingId = null;
      },
      error: () => { this.invitingId = null; },
    });
  }
}
