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
      githubUrl: ['', [Validators.required, Validators.pattern(/^https?:\/\/.+/i)]],
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
      this.newCvFile.set(null);
      this.newCvName.set(null);
    }
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

    const githubUrl = this.form.value.githubUrl?.trim();

    // allow save if github valid OR cv selected
    const hasCv = !!this.newCvFile();
    const hasGithub = !!githubUrl;

    if (!hasCv && !hasGithub) {
      this.error = 'Nothing to update.';
      return;
    }

    if (hasGithub && this.form.invalid) {
      this.error = 'Please enter a valid GitHub URL.';
      return;
    }

    this.saving.set(true);

    this.appService
      .updateMyApplication(this.app.applicationId, {
        githubUrl: hasGithub ? githubUrl : undefined,
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
