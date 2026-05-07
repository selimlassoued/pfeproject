import { Component, computed, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormArray, FormBuilder, Validators } from '@angular/forms';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import Swal from 'sweetalert2';
import { JobService } from '../services/job.service';
import { ApplicationService } from '../services/application.service';
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
  private readonly appService = inject(ApplicationService);
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
    workArrangement: ['', [Validators.required]],
    employmentType: ['', [Validators.required]],
    jobStatus: ['DRAFT', [Validators.required]],
    openings: [null as number | null, [Validators.min(1)]],
    minSalary: [null as number | null, [Validators.min(0)]],
    maxSalary: [null as number | null],
    skillsWeight:     [40, [Validators.required, Validators.min(0), Validators.max(100)]],
    semanticWeight:   [35, [Validators.required, Validators.min(0), Validators.max(100)]],
    experienceWeight: [15, [Validators.required, Validators.min(0), Validators.max(100)]],
    seniorityWeight:  [10, [Validators.required, Validators.min(0), Validators.max(100)]],
    requirements: this.fb.array([] as any[]),
  });

  readonly requirements = computed(() => this.form.get('requirements') as FormArray);

  readonly weightKeys = ['skillsWeight', 'semanticWeight', 'experienceWeight', 'seniorityWeight'] as const;

  get weightsTotal(): number {
    const v = this.form.value;
    return (v.skillsWeight ?? 0) + (v.semanticWeight ?? 0) +
           (v.experienceWeight ?? 0) + (v.seniorityWeight ?? 0);
  }

  onWeightChange(changedKey: string): void {
    const idx = this.weightKeys.indexOf(changedKey as any);
    const n   = this.weightKeys.length;
    const total = this.weightKeys.reduce((s, k) => s + (this.form.get(k)?.value ?? 0), 0);
    let diff = total - 100;
    if (diff === 0) return;

    const candidates: string[] = idx === n - 1
      ? Array.from({ length: n - 1 }, (_, i) => this.weightKeys[n - 2 - i])
      : [
          ...Array.from({ length: n - idx - 1 }, (_, i) => this.weightKeys[idx + 1 + i]),
          ...Array.from({ length: idx },          (_, i) => this.weightKeys[idx - 1 - i]),
        ];

    const patch: any = {};
    for (const key of candidates) {
      if (diff === 0) break;
      const cur = this.form.get(key)?.value ?? 0;
      if (diff > 0) {
        const take = Math.min(cur, diff);
        patch[key] = cur - take;
        diff -= take;
      } else {
        patch[key] = cur - diff;
        diff = 0;
      }
    }
    this.form.patchValue(patch, { emitEvent: false });
  }

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
      workArrangement: (job as any).workArrangement ?? '',
      employmentType: job.employmentType,
      jobStatus: job.jobStatus,
      openings: job.openings ?? null,
      minSalary: job.minSalary ?? null,
      maxSalary: job.maxSalary ?? null,
      skillsWeight:     Math.round((job.skillsWeight     ?? 0.40) * 100),
      semanticWeight:   Math.round((job.semanticWeight   ?? 0.35) * 100),
      experienceWeight: Math.round((job.experienceWeight ?? 0.15) * 100),
      seniorityWeight:  Math.round((job.seniorityWeight  ?? 0.10) * 100),
    });

    // Clear existing requirements and add from job
    const reqArray = this.requirements();
    reqArray.clear();

    if (job.requirements && job.requirements.length > 0) {
      for (const req of job.requirements) {
        this.addRequirement({
        ...req,
        skillLevel:     (req as any).skillLevel     ?? null,
        degreeLevel:    (req as any).degreeLevel    ?? null,
        enrollmentType: (req as any).enrollmentType ?? null,
      });
      }
    } else {
      this.addRequirement();
    }
  }

  customizeWeights = false;

  toggleCustomizeWeights(): void {
    this.customizeWeights = !this.customizeWeights;
    if (this.customizeWeights) this.redistributeWeights();
    else this.requirements().controls.forEach(c => c.get('weight')?.setValue(null));
  }

  private redistributeWeights(): void {
    const n = this.requirements().length;
    if (n === 0) return;
    const share = Math.floor(100 / n);
    this.requirements().controls.forEach((c, i) => {
      c.get('weight')?.setValue(i === n - 1 ? 100 - share * (n - 1) : share);
    });
  }

  onReqWeightChange(changedIdx: number): void {
    const controls = this.requirements().controls;
    const n = controls.length;
    if (n <= 1) return;

    const total = controls.reduce((s, c) => s + (c.get('weight')?.value ?? 0), 0);
    let diff = total - 100;
    if (diff === 0) return;

    const candidates: number[] = changedIdx === n - 1
      ? Array.from({ length: n - 1 }, (_, i) => n - 2 - i)
      : [
          ...Array.from({ length: n - changedIdx - 1 }, (_, i) => changedIdx + 1 + i),
          ...Array.from({ length: changedIdx },          (_, i) => changedIdx - 1 - i),
        ];

    for (const idx of candidates) {
      if (diff === 0) break;
      const cur = controls[idx].get('weight')?.value ?? 0;
      if (diff > 0) {
        const take = Math.min(cur, diff);
        controls[idx].get('weight')?.setValue(cur - take, { emitEvent: false });
        diff -= take;
      } else {
        controls[idx].get('weight')?.setValue(cur - diff, { emitEvent: false });
        diff = 0;
      }
    }
  }

  get reqWeightsTotal(): number {
    return this.requirements().controls.reduce((s, c) => s + (c.get('weight')?.value ?? 0), 0);
  }

  addRequirement(preset?: Partial<JobRequirement>) {
    const group = this.fb.group({
      category:       [preset?.category      ?? 'SKILL', [Validators.required]],
      description:    [preset?.description   ?? '',      [Validators.required, Validators.minLength(2)]],
      weight:         [preset?.weight        ?? null],
      minYears:       [preset?.minYears      ?? null],
      maxYears:       [preset?.maxYears      ?? null],
      skillLevel:     [preset?.skillLevel     ?? null],
      degreeLevel:    [preset?.degreeLevel    ?? null],
      enrollmentType: [preset?.enrollmentType ?? null],
      languageLevel:  [(preset as any)?.languageLevel  ?? null],
    });
    this.requirements().push(group);
    if (this.customizeWeights) this.redistributeWeights();
  }

  removeRequirement(i: number) {
    this.requirements().removeAt(i);
    if (this.customizeWeights) this.redistributeWeights();
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
      category:       r.category,
      description:    r.description,
      weight:         this.customizeWeights ? (r.weight ?? null) : null,
      minYears:       r.minYears      ?? null,
      maxYears:       r.maxYears      ?? null,
      skillLevel:     r.skillLevel     ?? null,
      degreeLevel:    r.degreeLevel    ?? null,
      enrollmentType: r.enrollmentType ?? null,
      languageLevel:  (r as any).languageLevel  ?? null,
    }));

    return {
      title: (v.title ?? '').trim(),
      description: (v.description ?? '').trim(),
      location: (v.location ?? '').trim(),
      workArrangement: v.workArrangement || null,
      openings: (v.openings ?? null) as number,
      minSalary: (v.minSalary ?? null) as number,
      maxSalary: (v.maxSalary ?? null) as number,
      employmentType: (v.employmentType ?? '').trim(),
      jobStatus: (v.jobStatus === 'PUBLISHED' ? 'PUBLISHED' : 'DRAFT'),
      skillsWeight:     (v.skillsWeight     ?? 40) / 100,
      semanticWeight:   (v.semanticWeight   ?? 35) / 100,
      experienceWeight: (v.experienceWeight ?? 15) / 100,
      seniorityWeight:  (v.seniorityWeight  ?? 10) / 100,
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

    const reasonResult = await Swal.fire({
      title: 'Reason for update',
      text: 'Optionally provide a reason for this change (stored in audit log).',
      input: 'textarea',
      inputPlaceholder: 'e.g. Salary adjustment, requirements updated…',
      inputAttributes: { rows: '3' },
      showCancelButton: true,
      confirmButtonText: 'Save',
      cancelButtonText: 'Cancel',
    });

    if (reasonResult.isDismissed && reasonResult.dismiss === Swal.DismissReason.cancel) {
      return;
    }

    const reason = typeof reasonResult.value === 'string' ? reasonResult.value.trim() : undefined;

    this.saving = true;
    try {
      await firstValueFrom(this.jobService.updateJob(this.jobId!, payload, reason));

      // Ask recruiter if they want to re-score all existing applications
      const rematchResult = await Swal.fire({
        title: 'Re-score applications?',
        text: 'Job requirements or scoring weights changed. Re-calculating AI scores for all existing applications will update their rankings. This runs in the background.',
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: '🔄 Yes, re-score all',
        cancelButtonText: 'Skip',
      });

      if (rematchResult.isConfirmed && this.jobId) {
        this.appService.rematchApplicationsForJob(this.jobId).subscribe();
        await Swal.fire({
          title: 'Re-scoring started',
          text: 'AI scores are being recalculated in the background. This takes roughly 20–40 seconds per application — refresh the applications table in a minute to see updated rankings.',
          icon: 'success',
          confirmButtonText: 'Go to job page',
        });
        await this.router.navigate(['/jobs', this.jobId]);
      } else {
        await this.router.navigate(['/browse']);
      }
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

  get isQuotaLocked(): boolean {
    const s = this.form.value.jobStatus;
    return s === 'PUBLISHED' || s === 'CLOSED';
  }

  duplicateJob(): void {
    const v = this.form.getRawValue();
    this.router.navigate(['/add-job'], {
      state: {
        duplicate: {
          title:            v.title,
          description:      v.description,
          location:         v.location,
          employmentType:   v.employmentType,
          minSalary:        v.minSalary,
          maxSalary:        v.maxSalary,
          skillsWeight:     v.skillsWeight,
          semanticWeight:   v.semanticWeight,
          experienceWeight: v.experienceWeight,
          seniorityWeight:  v.seniorityWeight,
          requirements:     v.requirements,
        }
      }
    });
  }

  setStatus(s: 'DRAFT' | 'PUBLISHED') {
    this.form.patchValue({ jobStatus: s });
  }

  async submitAs(status: 'DRAFT' | 'PUBLISHED') {
    this.setStatus(status);
    await this.submit();
  }
}