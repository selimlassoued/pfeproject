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

  submitting = signal(false);
  error = signal<string | null>(null);
  success = signal<string | null>(null);

  cvFile = signal<File | null>(null);
  cvName = signal<string | null>(null);

  form!: FormGroup;

  constructor(
    private fb: FormBuilder,
    private route: ActivatedRoute,
    private router: Router,
    private appService: ApplicationService
  ) {
    this.form = this.fb.group({
      githubUrl: ['', [Validators.required, Validators.pattern(/^https?:\/\/.+/i)]],
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

    if (this.form.invalid || !this.cvFile()) {
      this.error.set('Please provide a valid GitHub link and upload a PDF CV.');
      return;
    }

    this.submitting.set(true);

    this.appService.applyToJob(this.jobId, {
  githubUrl: this.form.value.githubUrl,
  cv: this.cvFile()!,
}).subscribe({
  next: (res) => {
    this.submitting.set(false);
    this.success.set('Application submitted successfully.');

    // redirect to details page you already use in HR list: /application/:id
    setTimeout(() => {
      this.router.navigate(['/application', res.applicationId]);
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
