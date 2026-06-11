import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import { normalizeHttpError } from '../utils/http-error';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import Swal from 'sweetalert2';
import { InterviewService, InterviewResponse, ProposalResponse, ReschedRequestResponse } from '../services/interview-service';
import { OfferPanel } from '../offer-panel/offer-panel';
import { ReschedulePicker } from '../reschedule-picker/reschedule-picker';
import { NotificationSocketService } from '../services/notification-socket.service';
import { Subscription } from 'rxjs';
import { inject } from '@angular/core';

@Component({
  selector: 'app-my-application-detail',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, OfferPanel, ReschedulePicker],
  templateUrl: './my-application-detail.html',
  styleUrl: './my-application-detail.css',
})
export class MyApplicationDetail implements OnInit, OnDestroy {
  app: ApplicationDto | null = null;
  loading = false;
  error: string | null = null;

  interviews: InterviewResponse[] = [];
  interviewsLoading = false;

  /** Pending interview proposals from recruiters waiting on this candidate. */
  proposals: ProposalResponse[] = [];
  pickingSlotIdx: number | null = null;
  proposalError: string | null = null;

  /** Reschedule requests, keyed by interviewId. */
  reschedRequests: Record<string, ReschedRequestResponse[]> = {};
  reschedFormFor: string | null = null;
  acceptingReschedIdx: number | null = null;
  decliningReschedId: string | null = null;
  cancellingReschedId: string | null = null;

  consentInterviewId = signal<string | null>(null);
  consentLoading = signal(false);
  consentError = signal<string | null>(null);

  private interviewService = inject(InterviewService);
  private socket = inject(NotificationSocketService);
  private wsInterviewSub?: Subscription;
  private wsOfferSub?: Subscription;

  private order = ['APPLIED', 'UNDER_REVIEW', 'INTERVIEW_PHASE', 'OFFER', 'HIRED', 'REJECTED'];

  editing = signal(false);
  saving = signal(false);
  success = signal<string | null>(null);
  githubChecking = signal(false);
  githubValid = signal<boolean | null>(null);
  withdrawing = signal(false);

  form!: FormGroup;
  newCvFile = signal<File | null>(null);
  newCvName = signal<string | null>(null);

  // Statuses from which withdraw is NOT allowed
  private readonly NON_WITHDRAWABLE = ['HIRED', 'REJECTED', 'FLAGGED', 'BLOCKED', 'WITHDRAWN'];

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private appService: ApplicationService,
    private fb: FormBuilder
  ) {
    this.form = this.fb.group({
      githubUrl: ['', [Validators.pattern(/^https?:\/\/.+/i)]],
    });
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) { this.error = 'Missing application id'; return; }
    this.fetch(id);

    // Live updates from the server: when the recruiter sends a proposal,
    // reschedules, schedules an interview, etc., reload silently so the
    // candidate sees it without a page refresh. Same for offer transitions
    // (the embedded OfferPanel reloads itself; we still refresh interviews
    // here because they're owned by this component).
    this.wsInterviewSub = this.socket.interviewListChanged$.subscribe(() => {
      if (this.app?.applicationId) this.loadInterviews(this.app.applicationId);
    });
    this.wsOfferSub = this.socket.offerChanged$.subscribe(ev => {
      // After accept/decline the application status flips (HIRED on accept),
      // so refetch the app to keep the pipeline strip accurate.
      if (!this.app?.applicationId) return;
      if (ev?.applicationId && ev.applicationId !== this.app.applicationId) return;
      this.appService.getMyApplicationById(this.app.applicationId).subscribe({
        next: (data) => { this.app = data; },
        error: () => {},
      });
    });
  }

  ngOnDestroy(): void {
    this.wsInterviewSub?.unsubscribe();
    this.wsOfferSub?.unsubscribe();
  }

  private fetch(id: string) {
    this.loading = true;
    this.error = null;
    this.success.set(null);

    this.appService.getMyApplicationById(id).subscribe({
      next: (data) => {
        this.app = data;
        this.loading = false;
        this.form.patchValue({ githubUrl: data.githubUrl || '' });
        this.syncGithubVerifyStateFromUrl(data.githubUrl);
        this.newCvFile.set(null);
        this.newCvName.set(null);
        this.loadInterviews(data.applicationId);
      },
      error: (err) => {
        this.error = normalizeHttpError(err).message || 'Failed to load application';
        this.loading = false;
      },
    });
  }

  // ── Withdraw ──────────────────────────────────────────────────────────────

  /**
   * Candidate can withdraw from any status except:
   * HIRED, REJECTED, FLAGGED, BLOCKED, WITHDRAWN
   * The candidate has the right to withdraw at any stage - e.g. found a better offer.
   */
  canWithdraw(): boolean {
    return !this.NON_WITHDRAWABLE.includes(this.app?.status ?? '');
  }

  async withdraw(): Promise<void> {
    if (!this.app?.applicationId || !this.canWithdraw()) return;

    const result = await Swal.fire({
      title: 'Withdraw application?',
      html: `<p style="font-size:.9rem">
        Your application for <strong>${this.app.jobTitle ?? 'this job'}</strong>
        will be withdrawn. You can re-apply later if you change your mind.
      </p>`,
      showCancelButton: true,
      confirmButtonText: 'Yes, withdraw',
      cancelButtonText: 'Cancel',
      confirmButtonColor: '#d32f2f',
    });

    if (!result.isConfirmed) return;

    this.withdrawing.set(true);
    this.error = null;

    this.appService.withdrawApplication(this.app.applicationId).subscribe({
      next: () => {
        this.withdrawing.set(false);
        this.router.navigate(['/my-applications']);
      },
      error: (err) => {
        this.withdrawing.set(false);
        this.error = normalizeHttpError(err).message || 'Failed to withdraw application.';
      },
    });
  }


  /** OfferPanel emits this on accept / decline / counter - refresh the
   *  application status so the pipeline strip updates to HIRED if accepted. */
  onOfferChanged(): void {
    if (!this.app?.applicationId) return;
    this.appService.getMyApplicationById(this.app.applicationId).subscribe({
      next: (data) => { this.app = data; },
      error: () => {},
    });
  }

  loadInterviews(applicationId: string) {
    this.interviewsLoading = true;
    this.interviewService.getByApplication(applicationId).subscribe({
      next: (list) => {
        this.interviews = list;
        this.interviewsLoading = false;
        for (const iv of list) {
          if (iv.status === 'SCHEDULED') this.loadReschedFor(iv.id);
        }
      },
      error: () => { this.interviewsLoading = false; }
    });
    this.loadProposals(applicationId);
  }

  // ── Reschedule (candidate side) ─────────────────────────────────────────
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
        Swal.fire({ icon: 'success', title: 'New time confirmed',
                    text: 'Your interview has been moved.',
                    timer: 2500, showConfirmButton: false });
      },
      error: (err) => {
        this.acceptingReschedIdx = null;
        Swal.fire({ icon: 'error', title: 'Failed to accept',
                    text: normalizeHttpError(err).message || 'Please try again.' });
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

  loadProposals(applicationId: string) {
    this.interviewService.getProposalsByApplication(applicationId).subscribe({
      next: (list) => { this.proposals = list; },
      error: () => { this.proposals = []; },
    });
  }

  /** The proposal waiting for this candidate to pick a slot, if any. */
  get pendingProposal(): ProposalResponse | null {
    return this.proposals.find(p => p.status === 'PENDING') ?? null;
  }

  /** Format the deadline for display in the proposal card. */
  proposalDeadlineLabel(p: ProposalResponse): string {
    const d = new Date(p.deadline);
    const now = new Date();
    const msLeft = d.getTime() - now.getTime();
    if (msLeft <= 0) return 'expired';
    const hours = Math.floor(msLeft / 3_600_000);
    if (hours < 24) return `${hours}h left`;
    const days = Math.floor(hours / 24);
    return `${days}d left`;
  }

  async declineProposal(p: ProposalResponse): Promise<void> {
    const result = await Swal.fire({
      title: "None of these times work?",
      input: 'textarea',
      inputLabel: 'Let the recruiter know (optional)',
      inputPlaceholder: 'e.g. I have exams that week - could we do the following week?',
      showCancelButton: true,
      confirmButtonText: 'Send - ask for other times',
      cancelButtonText: 'Back',
      confirmButtonColor: '#dc2626',
      background: '#141c3c',
      color: '#e8f0fe',
    });
    if (!result.isConfirmed) return;
    this.interviewService.declineProposal(p.id, result.value || undefined).subscribe({
      next: (updated) => {
        const i = this.proposals.findIndex(x => x.id === updated.id);
        if (i !== -1) this.proposals[i] = updated;
        Swal.fire({
          icon: 'success',
          title: 'The recruiter has been notified',
          text: 'They\'ll propose new times shortly.',
          timer: 2800,
          showConfirmButton: false,
        });
      },
      error: (err) => {
        Swal.fire({ icon: 'error', title: 'Could not send',
                    text: normalizeHttpError(err).message || 'Please try again.' });
      },
    });
  }

  pickProposalSlot(p: ProposalResponse, idx: number): void {
    if (this.pickingSlotIdx !== null) return;
    this.pickingSlotIdx = idx;
    this.proposalError = null;
    this.interviewService.pickProposalSlot(p.id, idx).subscribe({
      next: (updated) => {
        const i = this.proposals.findIndex(x => x.id === updated.id);
        if (i !== -1) this.proposals[i] = updated;
        this.pickingSlotIdx = null;
        // The pick created a real Interview - refresh that list too so the
        // candidate immediately sees "Scheduled" status instead of "Pending".
        if (this.app?.applicationId) {
          this.loadInterviews(this.app.applicationId);
          this.appService.getMyApplicationById(this.app.applicationId).subscribe({
            next: (data) => { this.app = data; },
            error: () => {},
          });
        }
        Swal.fire({
          icon: 'success',
          title: 'Interview confirmed',
          text: 'You\'ll receive a calendar invite shortly.',
          timer: 2500,
          showConfirmButton: false,
        });
      },
      error: (err) => {
        this.pickingSlotIdx = null;
        this.proposalError = normalizeHttpError(err).message
          || 'Could not confirm that slot. Please try another.';
      },
    });
  }
  openConsentModal(iv: InterviewResponse) {
    if (iv.recordingConsent) {
      // Already consented - go straight to embedded room
      this.router.navigate(['/join', iv.id]);
      return;
    }
    this.consentInterviewId.set(iv.id);
    this.consentError.set(null);
  }

  closeConsentModal() {
    this.consentInterviewId.set(null);
    this.consentError.set(null);
  }

  confirmConsent() {
    const id = this.consentInterviewId();
    if (!id) return;

    this.consentLoading.set(true);
    this.consentError.set(null);

    this.interviewService.updateConsent(id, true).subscribe({
      next: (updated) => {
        this.interviews = this.interviews.map(i => i.id === updated.id ? updated : i);
        this.consentLoading.set(false);
        this.closeConsentModal();
        // Navigate to embedded room instead of opening raw Jitsi
        this.router.navigate(['/join', id]);
      },
      error: () => {
        this.consentLoading.set(false);
        this.consentError.set('Failed to save consent. Please try again.');
      }
    });
  }


  canEdit(): boolean { return (this.app?.status || '') === 'APPLIED'; }

  toggleEdit() {
    if (!this.canEdit()) return;
    this.success.set(null);
    this.error = null;
    const next = !this.editing();
    this.editing.set(next);
    if (next && this.app) {
      this.form.patchValue({ githubUrl: this.app.githubUrl || '' });
      this.syncGithubVerifyStateFromUrl(this.app.githubUrl);
      this.newCvFile.set(null);
      this.newCvName.set(null);
    }
  }

  private syncGithubVerifyStateFromUrl(url: string | null | undefined): void {
    this.githubValid.set((url || '').trim() ? true : null);
  }

  onGithubInput(): void { this.githubValid.set(null); }

  checkGithub(): void {
    const url = this.form.value.githubUrl?.trim();
    if (!url) return;
    this.githubChecking.set(true);
    this.githubValid.set(null);
    this.error = null;
    this.appService.checkGithubLink(url).subscribe({
      next: (valid) => { this.githubChecking.set(false); this.githubValid.set(valid); },
      error: () => { this.githubChecking.set(false); this.githubValid.set(false); },
    });
  }

  onCvChange(e: Event) {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.success.set(null);
    this.error = null;

    if (!file) { this.newCvFile.set(null); this.newCvName.set(null); return; }

    const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
    if (!isPdf) {
      this.error = 'CV must be a PDF file only.';
      this.newCvFile.set(null); this.newCvName.set(null);
      (input).value = '';
      return;
    }
    this.newCvFile.set(file);
    this.newCvName.set(file.name);
  }

  saveChanges() {
    this.success.set(null);
    this.error = null;
    if (!this.app?.applicationId) return;
    if (!this.canEdit()) { this.error = 'Updates are allowed only while status is APPLIED.'; return; }

    const githubUrl = (this.form.value.githubUrl || '').trim();
    const previousGithub = (this.app.githubUrl || '').trim();
    const githubChanged = githubUrl !== previousGithub;
    const hasCv = !!this.newCvFile();

    if (!hasCv && !githubChanged) { this.error = 'Nothing to update.'; return; }

    if (githubChanged && githubUrl) {
      if (this.form.get('githubUrl')?.invalid) {
        this.error = 'Please enter a valid URL (must start with http:// or https://).';
        return;
      }
      if (this.githubValid() === false) {
        this.error = 'GitHub link is broken or unreachable. Please fix it or leave it empty.';
        return;
      }
      if (this.githubValid() === null) {
        this.error = 'Please click "Verify" to validate your GitHub link before saving.';
        return;
      }
    }
    this.saving.set(true);

    this.appService.updateMyApplication(this.app.applicationId, {
      githubUrl: githubChanged ? githubUrl : undefined,
      cv: hasCv ? this.newCvFile()! : undefined,
    }).subscribe({
      next: (updated) => {
        this.app = updated;
        this.saving.set(false);
        this.success.set('Updated successfully.');
        this.editing.set(false);
        this.newCvFile.set(null);
        this.newCvName.set(null);
        this.form.patchValue({ githubUrl: updated.githubUrl || '' });
        this.syncGithubVerifyStateFromUrl(updated.githubUrl);
      },
      error: (err) => {
        this.saving.set(false);
        this.error = normalizeHttpError(err).message || 'Failed to update application';
      },
    });
  }

  // ── Timeline ──────────────────────────────────────────────────────────────

  isStepActive(step: string): boolean {
    return (this.app?.status || '') === step;
  }

  isStepDone(step: string): boolean {
    const s = this.app?.status || '';
    if (!s) return false;
    if (s === 'REJECTED') return this.order.indexOf(step) < this.order.indexOf('REJECTED');
    if (s === 'HIRED')    return this.order.indexOf(step) < this.order.indexOf('HIRED');
    return this.order.indexOf(step) !== -1 && this.order.indexOf(step) < this.order.indexOf(s);
  }

  goToJob() {
    if (this.app?.jobId) this.router.navigate(['/jobs', this.app.jobId]);
  }

  downloadCv() {
    if (!this.app?.applicationId) return;
    this.appService.downloadMyCv(this.app.applicationId).subscribe(blob => {
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = this.app?.cvFileName || 'cv.pdf';
      a.click();
      window.URL.revokeObjectURL(url);
    });
  }

  backToList() { this.router.navigate(['/my-applications']); }
}