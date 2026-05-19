import { Component, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import Swal from 'sweetalert2';
import { InterviewService, InterviewResponse } from '../services/interview-service';
import { inject } from '@angular/core';

@Component({
  selector: 'app-my-application-detail',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './my-application-detail.html',
  styleUrl: './my-application-detail.css',
})
export class MyApplicationDetail implements OnInit {
  app: ApplicationDto | null = null;
  loading = false;
  error: string | null = null;

  interviews: InterviewResponse[] = [];
  interviewsLoading = false;

  consentInterviewId = signal<string | null>(null);
  consentLoading = signal(false);
  consentError = signal<string | null>(null);

  private interviewService = inject(InterviewService);

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
        this.error = err?.error?.message || 'Failed to load application';
        this.loading = false;
      },
    });
  }

  // ── Withdraw ──────────────────────────────────────────────────────────────

  /**
   * Candidate can withdraw from any status except:
   * HIRED, REJECTED, FLAGGED, BLOCKED, WITHDRAWN
   * The candidate has the right to withdraw at any stage — e.g. found a better offer.
   */
  canWithdraw(): boolean {
    return !this.NON_WITHDRAWABLE.includes(this.app?.status ?? '');
  }

  async withdraw(): Promise<void> {
    if (!this.app?.applicationId || !this.canWithdraw()) return;

    const result = await Swal.fire({
      title: 'Withdraw application?',
      html: `<p style="color:rgba(255,255,255,0.7);font-size:.9rem">
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
        this.error = err?.error?.message || 'Failed to withdraw application.';
      },
    });
  }


  loadInterviews(applicationId: string) {
    this.interviewsLoading = true;
    this.interviewService.getByApplication(applicationId).subscribe({
      next: (list) => { this.interviews = list; this.interviewsLoading = false; },
      error: () => { this.interviewsLoading = false; }
    });
  }
  openConsentModal(iv: InterviewResponse) {
    if (iv.recordingConsent) {
      // Already consented — go straight to embedded room
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
        this.error = err?.error?.message || 'Failed to update application';
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