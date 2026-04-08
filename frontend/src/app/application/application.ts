import { Component, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApplicationService } from '../services/application.service';

@Component({
  selector: 'app-application',
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './application.html',
  styleUrl: './application.css',
})
export class Application {
  jobId!: string;

  submitting    = signal(false);
  error         = signal<string | null>(null);
  success       = signal<string | null>(null);
  cvFile        = signal<File | null>(null);
  cvName        = signal<string | null>(null);
  githubChecking = signal(false);
  githubValid    = signal<boolean | null>(null); // null = not checked, true = ok, false = broken

  form!: FormGroup;

  constructor(
    private fb: FormBuilder,
    private route: ActivatedRoute,
    private router: Router,
    private appService: ApplicationService
  ) {
    this.form = this.fb.group({
      githubUrl: ['', [Validators.pattern(/^https?:\/\/.+/i)]],
    });
  }

  ngOnInit() {
    const id = this.route.snapshot.paramMap.get('jobId');
    if (!id) {
      this.error.set('Missing jobId in URL.');
      return;
    }
    this.jobId = id;
  }

  onGithubInput() {
    // Reset validation state whenever the user edits the URL
    this.githubValid.set(null);
  }

  checkGithub() {
    const url = this.form.value.githubUrl?.trim();
    if (!url) return;

    this.githubChecking.set(true);
    this.githubValid.set(null);
    this.error.set(null);

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

  onFileChange(e: Event) {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;

    this.error.set(null);
    this.success.set(null);

    if (!file) {
      this.cvFile.set(null);
      this.cvName.set(null);
      return;
    }

    const isPdfByType = file.type === 'application/pdf';
    const isPdfByName = file.name.toLowerCase().endsWith('.pdf');

    if (!isPdfByType && !isPdfByName) {
      this.error.set('CV must be a PDF file only.');
      this.cvFile.set(null);
      this.cvName.set(null);
      input.value = '';
      return;
    }

    this.cvFile.set(file);
    this.cvName.set(file.name);
  }

  submit() {
    this.error.set(null);
    this.success.set(null);

    const githubUrl = this.form.value.githubUrl?.trim();

    // If a URL was entered, enforce verification
    if (githubUrl) {
      if (this.githubValid() === false) {
        this.error.set('GitHub link is broken or unreachable. Please fix it or leave it empty.');
        return;
      }
      if (this.githubValid() === null) {
        this.error.set('Please click "Verify" to validate your GitHub link before submitting.');
        return;
      }
    }

    if (this.form.invalid || !this.cvFile()) {
      this.error.set('Please upload a PDF CV.');
      return;
    }

    this.submitting.set(true);

    this.appService.applyToJob(this.jobId, {
      githubUrl: githubUrl ?? '',
      cv: this.cvFile()!,
    }).subscribe({
      next: (res) => {
        this.submitting.set(false);
        this.success.set('Application submitted successfully.');
        setTimeout(() => {
          this.router.navigate(['/my-application', res.applicationId]);
        }, 600);
      },
      error: (err) => {
        this.submitting.set(false);
        if (err?.status === 409) {
          this.error.set('You already applied to this job.');
          return;
        }
        this.error.set(err?.error?.message ?? 'Failed to submit application.');
      },
    });
  }

  goBack() {
    this.router.navigate(['/browse']);
  }
}