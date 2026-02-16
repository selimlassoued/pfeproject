import { Component, computed, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormArray, FormBuilder, Validators } from '@angular/forms';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { JobService } from '../services/job.service';
import { JobOffer } from '../model/jobOffer.model';
import { RequirementCategory } from '../model/jobRequirement.model';
import { JobRequirement } from '../model/jobRequirement.model';

@Component({
  selector: 'app-update-job',
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './update-job.html',
  styleUrl: './update-job.css',
})
export class UpdateJob implements OnInit{
private readonly fb = inject(FormBuilder);
  private readonly jobService = inject(JobService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  saving = false;
  loading = true;
  error: string | null = null;
  jobId: string | null = null;

  readonly categories: RequirementCategory[] = [
    'SKILL',
    'EXPERIENCE',
    'EDUCATION',
    'CERTIFICATION',
    'LANGUAGE',
  ];

  readonly form = this.fb.group({
    title: ['', [Validators.required, Validators.minLength(3)]],
    description: ['', [Validators.required, Validators.minLength(10)]],
    location: ['', [Validators.required]],
    employmentType: ['', [Validators.required]],
    jobStatus: ['DRAFT', [Validators.required]],
    minSalary: [null as number | null, [Validators.min(0)]],
    maxSalary: [null as number | null],
    requirements: this.fb.array([] as any[]),
  });

  readonly requirements = computed(() => this.form.get('requirements') as FormArray);

  ngOnInit(): void {
    this.jobId = this.route.snapshot.paramMap.get('id');
    if (this.jobId) {
      this.loadJob();
    } else {
      this.error = 'Job ID not found.';
      this.loading = false;
    }
  }

  private loadJob(): void {
    this.loading = true;
    this.error = null;

    this.jobService.getJobById(this.jobId!).subscribe({
      next: (job) => {
        this.populateForm(job);
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        if (err?.status) this.error = `Failed to load job (HTTP ${err.status}).`;
        else this.error = 'Failed to load job.';
        console.error('getJobById error:', err);
      },
    });
  }

  private populateForm(job: JobOffer): void {
    this.form.patchValue({
      title: job.title,
      description: job.description,
      location: job.location,
      employmentType: job.employmentType,
      jobStatus: job.jobStatus,
      minSalary: job.minSalary ?? null,
      maxSalary: job.maxSalary ?? null,
    });

    // Clear existing requirements and add from job
    const reqArray = this.requirements();
    reqArray.clear();

    if (job.requirements && job.requirements.length > 0) {
      for (const req of job.requirements) {
        this.addRequirement(req);
      }
    } else {
      this.addRequirement();
    }
  }

  addRequirement(preset?: Partial<JobRequirement>) {
    const group = this.fb.group({
      category: [preset?.category ?? 'SKILL', [Validators.required]],
      description: [preset?.description ?? '', [Validators.required, Validators.minLength(2)]],
      weight: [preset?.weight ?? null, [Validators.required, Validators.min(0)]],
      minYears: [preset?.minYears ?? null],
      maxYears: [preset?.maxYears ?? null],
    });

    this.requirements().push(group);
  }

  removeRequirement(i: number) {
    this.requirements().removeAt(i);
  }

  private validateRanges(): string | null {
    const minSalary = this.form.value.minSalary ?? null;
    const maxSalary = this.form.value.maxSalary ?? null;

    if (minSalary != null && maxSalary != null && minSalary > maxSalary) {
      return 'Min salary cannot be greater than max salary.';
    }

    const reqs = this.requirements().controls;
    for (let i = 0; i < reqs.length; i++) {
      const r = reqs[i].value as any;
      const minY = r.minYears ?? null;
      const maxY = r.maxYears ?? null;

      if (minY != null && maxY != null && minY > maxY) {
        return `Requirement #${i + 1}: min years cannot be greater than max years.`;
      }
    }
    return null;
  }

  private buildPayload(): Omit<JobOffer, 'id'> {
    const v = this.form.getRawValue();

    const reqs: JobRequirement[] = (v.requirements ?? []).map((r: any) => ({
      category: r.category,
      description: r.description,
      weight: r.weight ?? null,
      minYears: r.minYears ?? null,
      maxYears: r.maxYears ?? null,
    }));

    return {
      title: (v.title ?? '').trim(),
      description: (v.description ?? '').trim(),
      location: (v.location ?? '').trim(),
      minSalary: (v.minSalary ?? null) as number,
      maxSalary: (v.maxSalary ?? null) as number,
      employmentType: (v.employmentType ?? '').trim(),
      jobStatus: (v.jobStatus === 'PUBLISHED' ? 'PUBLISHED' : 'DRAFT'),
      requirements: reqs,
    };
  }

  async submit() {
    this.error = null;

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.error = 'Please fix the highlighted fields.';
      return;
    }

    const rangeErr = this.validateRanges();
    if (rangeErr) {
      this.error = rangeErr;
      return;
    }

    const payload = this.buildPayload();

    this.saving = true;
    try {
      await firstValueFrom(this.jobService.updateJob(this.jobId!, payload));
      await this.router.navigate(['/browse']);
    } catch (e: any) {
      if (e?.status) this.error = `Update failed (HTTP ${e.status}).`;
      else this.error = 'Update failed.';
      console.error('updateJob error:', e);
    } finally {
      this.saving = false;
    }
  }

  cancel() {
    this.router.navigate(['/browse']);
  }

  // convenience for template
  c(path: string) {
    return this.form.get(path);
  }

  get status(): 'DRAFT' | 'PUBLISHED' {
    const v = this.form.value.jobStatus;
    return v === 'PUBLISHED' ? 'PUBLISHED' : 'DRAFT';
  }

  setStatus(s: 'DRAFT' | 'PUBLISHED') {
    this.form.patchValue({ jobStatus: s });
  }

  async submitAs(status: 'DRAFT' | 'PUBLISHED') {
    this.setStatus(status);
    await this.submit();
  }
}