import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormArray, FormBuilder, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { JobService } from '../services/job.service';
import { JobOffer } from '../model/jobOffer.model';
import { RequirementCategory } from '../model/jobRequirement.model';
import { JobRequirement } from '../model/jobRequirement.model';

@Component({
  selector: 'app-add-job',
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './add-job.html',
  styleUrl: './add-job.css',
})
export class AddJob{
  private readonly fb = inject(FormBuilder);
  private readonly jobService = inject(JobService);
  private readonly router = inject(Router);

  saving = false;
  error: string | null = null;

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

  addRequirement(preset?: Partial<JobRequirement>) {
    const group = this.fb.group({
      category: [preset?.category ?? 'SKILL', [Validators.required]],
      description: [preset?.description ?? '', [Validators.required, Validators.minLength(2)]],
      weight: [preset?.weight ?? null,[Validators.required, Validators.min(0)]],
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
      await firstValueFrom(this.jobService.createJob(payload));
      await this.router.navigate(['/browse']);
    } catch (e: any) {
      if (e?.status) this.error = `Create failed (HTTP ${e.status}).`;
      else this.error = 'Create failed.';
      console.error('createJob error:', e);
    } finally {
      this.saving = false;
    }
  }

  cancel() {
    this.router.navigate(['/browse']);
  }

  c(path: string) {
    return this.form.get(path);
  }

  ngOnInit() {
    this.addRequirement();
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
