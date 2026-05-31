import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormArray, FormBuilder, Validators, AbstractControl } from '@angular/forms';
import { Router, RouterLink, NavigationExtras } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { JobService } from '../services/job.service';
import { JobOffer } from '../model/jobOffer.model';
import { RequirementCategory } from '../model/jobRequirement.model';
import { JobRequirement } from '../model/jobRequirement.model';
import { getCanonicalLanguages } from '../services/language-options.service';
import { DOMAIN_OPTIONS } from '../services/domains';

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

  // Canonical language names for LANGUAGE-category requirements. Used by the
  // strict dropdown in the template so the recruiter can't store a typo or a
  // localized spelling - every requirement on the wire is in canonical form
  // (e.g. "Italian", never "Italien"). One source of truth lives in
  // language-options.service so the candidate dropdown and recruiter dropdown
  // can't drift apart.
  readonly canonicalLanguages: string[] = getCanonicalLanguages();

  // Business-domain dropdown options for the Domain field. Same canonical
  // list used by the candidate Preferences page and the Skills Catalog
  // admin page - one source of truth in services/domains.ts.
  readonly domainOptions = DOMAIN_OPTIONS;

  readonly form = this.fb.group({
    title: ['', [Validators.required, Validators.minLength(3)]],
    description: ['', [Validators.required, Validators.minLength(10)]],
    // Location is fixed - VERMEG sits at Les Berges du Lac 1, Tunis. The
    // input was removed from the form; the value is injected at submit time.
    workArrangement: ['', [Validators.required]],
    employmentType: ['', [Validators.required]],
    domain: ['', [Validators.required]],
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

    // Cascade down from changedIdx+1; if last slider changed, cascade up instead
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
      languageLevel:  [preset?.languageLevel  ?? null],
    });
    this.requirements().push(group);
    if (this.customizeWeights) this.redistributeWeights();
  }

  removeRequirement(i: number) {
    this.requirements().removeAt(i);
    if (this.customizeWeights) this.redistributeWeights();
  }

  // ── EDUCATION: multi-select accepted degrees ────────────────────────────────
  // degreeLevel is stored as a comma-joined string ("LICENCE_BACHELOR,ENGINEER").
  // Empty = any degree accepted.
  readonly degreeOptions: { value: string; label: string }[] = [
    { value: 'BAC',              label: 'Baccalaureate' },
    { value: 'BTS_DUT',          label: 'BTS / DUT' },
    { value: 'LICENCE_BACHELOR', label: 'Licence / Bachelor' },
    { value: 'ENGINEER',         label: 'Engineering degree' },
    { value: 'MASTER',           label: 'Master' },
    { value: 'PHD',              label: 'PhD / Doctorate' },
  ];

  isDegreeSelected(req: AbstractControl, value: string): boolean {
    const raw = (req.get('degreeLevel')?.value as string) || '';
    return raw.split(',').map(s => s.trim()).includes(value);
  }

  toggleDegree(req: AbstractControl, value: string, checked: boolean): void {
    const ctrl = req.get('degreeLevel');
    if (!ctrl) return;
    const current = ((ctrl.value as string) || '')
      .split(',').map(s => s.trim()).filter(Boolean);
    const next = checked
      ? (current.includes(value) ? current : [...current, value])
      : current.filter(v => v !== value);
    ctrl.setValue(next.length ? next.join(',') : null);
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
      languageLevel:  r.languageLevel  ?? null,
    }));

    return {
      title: (v.title ?? '').trim(),
      description: (v.description ?? '').trim(),
      location: 'Lac 1, Tunis',                     // VERMEG HQ - fixed value
      workArrangement: v.workArrangement || null,
      domain: v.domain || null,
      openings: (v.openings ?? null) as number,
      minSalary: (v.minSalary ?? null) as number,
      maxSalary: (v.maxSalary ?? null) as number,
      employmentType: (v.employmentType ?? '').trim(),
      jobStatus: (v.jobStatus === 'PUBLISHED' ? 'PUBLISHED' : 'DRAFT'),
      skillsWeight:     (v.skillsWeight ?? 40) / 100,
      semanticWeight:   (v.semanticWeight ?? 35) / 100,
      experienceWeight: (v.experienceWeight ?? 15) / 100,
      seniorityWeight:  (v.seniorityWeight ?? 10) / 100,
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

  isDuplicate = false;

  ngOnInit() {
    const state = this.router.getCurrentNavigation()?.extras?.state ?? history.state;
    const dup = state?.['duplicate'];
    if (dup) {
      this.isDuplicate = true;
      this.form.patchValue({
        title:            dup.title            ?? '',
        description:      dup.description      ?? '',
        employmentType:   dup.employmentType   ?? '',
        domain:           dup.domain           ?? '',
        minSalary:        dup.minSalary        ?? null,
        maxSalary:        dup.maxSalary        ?? null,
        skillsWeight:     dup.skillsWeight     ?? 40,
        semanticWeight:   dup.semanticWeight   ?? 35,
        experienceWeight: dup.experienceWeight ?? 15,
        seniorityWeight:  dup.seniorityWeight  ?? 10,
        openings:         null,   // quota must be set fresh
        jobStatus:        'DRAFT',
      });
      const reqArray = this.requirements();
      reqArray.clear();
      for (const r of (dup.requirements ?? [])) {
        this.addRequirement({
          category:       r.category,
          description:    r.description,
          weight:         null,
          minYears:       r.minYears,
          maxYears:       r.maxYears,
          skillLevel:     r.skillLevel,
          degreeLevel:    r.degreeLevel,
          enrollmentType: r.enrollmentType,
        });
      }
    } else {
      this.addRequirement();
    }
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
