import { Component, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';

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

  // ✅ Timeline order
  private order = ['APPLIED', 'UNDER_REVIEW', 'INTERVIEW_PHASE', 'OFFER', 'HIRED', 'REJECTED'];

  // ✅ Edit mode
  editing = signal(false);
  saving = signal(false);
  success = signal<string | null>(null);
  githubChecking = signal(false);
  githubValid = signal<boolean | null>(null);

  form!: FormGroup;

  newCvFile = signal<File | null>(null);
  newCvName = signal<string | null>(null);

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
    if (!id) {
      this.error = 'Missing application id';
      return;
    }
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

        // preload form from backend
        this.form.patchValue({ githubUrl: data.githubUrl || '' });
        this.syncGithubVerifyStateFromUrl(data.githubUrl);
        this.newCvFile.set(null);
        this.newCvName.set(null);
      },
      error: (err) => {
        this.error = err?.error?.message || 'Failed to load application';
        this.loading = false;
      },
    });
  }

  // ✅ candidate can edit only while APPLIED
  canEdit(): boolean {
    return (this.app?.status || '') === 'APPLIED';
  }

  toggleEdit() {
    if (!this.canEdit()) return;

    this.success.set(null);
    this.error = null;

    const next = !this.editing();
    this.editing.set(next);

    // reset file when opening edit
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

  onGithubInput(): void {
    this.githubValid.set(null);
  }

  checkGithub(): void {
    const url = this.form.value.githubUrl?.trim();
    if (!url) return;

    this.githubChecking.set(true);
    this.githubValid.set(null);
    this.error = null;

    this.appService.checkGithubLink(url).subscribe({
      next: (valid) => {
        this.githubChecking.set(false);
        this.githubValid.set(valid);
      },
      error: () => {
        this.githubChecking.set(false);
        this.githubValid.set(false);
      },
    });
  }

  onCvChange(e: Event) {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;

    this.success.set(null);
    this.error = null;

    if (!file) {
      this.newCvFile.set(null);
      this.newCvName.set(null);
      return;
    }

    const isPdfByType = file.type === 'application/pdf';
    const isPdfByName = file.name.toLowerCase().endsWith('.pdf');

    if (!isPdfByType && !isPdfByName) {
      this.error = 'CV must be a PDF file only.';
      this.newCvFile.set(null);
      this.newCvName.set(null);
      input.value = '';
      return;
    }

    this.newCvFile.set(file);
    this.newCvName.set(file.name);
  }

  saveChanges() {
    this.success.set(null);
    this.error = null;

    if (!this.app?.applicationId) return;
    if (!this.canEdit()) {
      this.error = 'Updates are allowed only while status is APPLIED.';
      return;
    }

    const githubUrl = (this.form.value.githubUrl || '').trim();
    const previousGithub = (this.app.githubUrl || '').trim();
    const githubChanged = githubUrl !== previousGithub;
    const hasCv = !!this.newCvFile();

    if (!hasCv && !githubChanged) {
      this.error = 'Nothing to update.';
      return;
    }

    if (githubChanged && githubUrl) {
      if (this.form.get('githubUrl')?.invalid) {
        this.error =
          'Please enter a valid URL (must start with http:// or https://).';
        return;
      }
      if (this.githubValid() === false) {
        this.error =
          'GitHub link is broken or unreachable. Please fix it or leave it empty.';
        return;
      }
      if (this.githubValid() === null) {
        this.error =
          'Please click "Verify" to validate your GitHub link before saving.';
        return;
      }
    }

    this.saving.set(true);

    this.appService
      .updateMyApplication(this.app.applicationId, {
        githubUrl: githubChanged ? githubUrl : undefined,
        cv: hasCv ? this.newCvFile()! : undefined,
      })
      .subscribe({
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

  // ✅ TIMELINE HELPERS
  isStepActive(step: string): boolean {
    return (this.app?.status || '') === step;
  }

  isStepDone(step: string): boolean {
    const s = this.app?.status || '';
    if (!s) return false;

    if (s === 'REJECTED') return this.order.indexOf(step) < this.order.indexOf('REJECTED');
    if (s === 'HIRED') return this.order.indexOf(step) < this.order.indexOf('HIRED');

    return this.order.indexOf(step) !== -1 && this.order.indexOf(step) < this.order.indexOf(s);
  }

  goToJob() {
    if (!this.app?.jobId) return;
    this.router.navigate(['/jobs', this.app.jobId]);
  }

  downloadCv() {
    if (!this.app?.applicationId) return;

    this.appService.downloadMyCv(this.app.applicationId).subscribe((blob) => {
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = this.app?.cvFileName || 'cv.pdf';
      a.click();
      window.URL.revokeObjectURL(url);
    });
  }

  backToList() {
    this.router.navigate(['/my-applications']);
  }
}
