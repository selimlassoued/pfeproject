import { Component, computed, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormArray, FormBuilder, Validators, AbstractControl, ValidationErrors } from '@angular/forms';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import Swal from 'sweetalert2';
import { JobService } from '../services/job.service';
import { ApplicationService } from '../services/application.service';
import { CatalogService, CatalogItem } from '../services/catalog.service';
import { JobOffer } from '../model/jobOffer.model';
import { RequirementCategory } from '../model/jobRequirement.model';
import { JobRequirement } from '../model/jobRequirement.model';
import { getCanonicalLanguages, canonicalizeLanguage } from '../services/language-options.service';
import { applyServerErrors, clearServerErrors, normalizeHttpError } from '../utils/http-error';
import { DOMAIN_OPTIONS } from '../services/domains';
import { OrgComboboxComponent } from '../org-combobox/org-combobox.component';

/** Group-level validator: when a requirement's category is EDUCATION, at least
 *  one degree chip must be selected. Without it, the requirement says nothing
 *  useful ("any candidate with any/no degree"). */
function educationDegreeRequiredValidator(group: AbstractControl): ValidationErrors | null {
  if (group.get('category')?.value !== 'EDUCATION') return null;
  const raw = (group.get('degreeLevel')?.value as string) || '';
  const selected = raw.split(',').map(s => s.trim()).filter(Boolean);
  return selected.length === 0 ? { degreeRequired: true } : null;
}

@Component({
  selector: 'app-update-job',
  imports: [CommonModule, ReactiveFormsModule, OrgComboboxComponent],
  templateUrl: './update-job.html',
  styleUrl: './update-job.css',
})
export class UpdateJob implements OnInit{
private readonly fb = inject(FormBuilder);
  private readonly jobService = inject(JobService);
  private readonly appService = inject(ApplicationService);
  private readonly catalogService = inject(CatalogService);
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

  /** Catalog autocomplete sources, populated on init. Filtered by type so
   *  the description field suggests HARD names when the recruiter picks
   *  SKILL → HARD, and SOFT names when they pick SKILL → SOFT. */
  hardSkillSuggestions: string[] = [];
  softSkillSuggestions: string[] = [];

  // Canonical language names - drives the strict dropdown for LANGUAGE-
  // category requirements. Same list used on add-job and on the candidate
  // side so values stay consistent across the app.
  readonly canonicalLanguages: string[] = getCanonicalLanguages();

  // Business-domain dropdown - same canonical list used by add-job and
  // the candidate Preferences page.
  readonly domainOptions = DOMAIN_OPTIONS;

  readonly form = this.fb.group({
    title: ['', [Validators.required, Validators.minLength(3)]],
    description: ['', [Validators.required, Validators.minLength(10)]],
    // Location is fixed - VERMEG sits at Les Berges du Lac 1, Tunis. The
    // input was removed from the form; the value is injected at submit time
    // (or carried over from the existing job on edit).
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

  ngOnInit(): void {
    // Catalog autocomplete sources for the description <datalist>. Loaded
    // in parallel with the job fetch so the recruiter sees suggestions as
    // soon as they pick SKILL → HARD/SOFT.
    this.catalogService.getSnapshot().then(snap => {
      const skills: CatalogItem[] = snap.skills ?? [];
      this.hardSkillSuggestions = skills
        .filter(s => s.type === 'HARD')
        .map(s => s.displayName)
        .sort((a, b) => a.localeCompare(b));
      this.softSkillSuggestions = skills
        .filter(s => s.type === 'SOFT')
        .map(s => s.displayName)
        .sort((a, b) => a.localeCompare(b));
    }).catch(() => { /* degraded silently - free-text still works */ });

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
      workArrangement: (job as any).workArrangement ?? '',
      domain: (job as any).domain ?? '',
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
        // For LANGUAGE requirements, try to map any legacy non-canonical
        // description (e.g. "Italien", "Anglais B2") to the canonical name
        // so the strict dropdown shows a sensible pre-selection. Falls back
        // to the raw value if no alias matches - caller picks manually.
        let description = req.description;
        if ((req.category || '').toUpperCase() === 'LANGUAGE') {
          description = canonicalizeLanguage(req.description) ?? req.description;
        }
        this.addRequirement({
        ...req,
        description,
        skillLevel:     (req as any).skillLevel     ?? null,
        // skillType comes from the new backend field. Legacy SKILL rows
        // without a skillType default to HARD in addRequirement's mapping.
        skillType:      (req as any).skillType      ?? null,
        degreeLevel:    (req as any).degreeLevel    ?? null,
        enrollmentType: (req as any).enrollmentType ?? null,
        institute:      (req as any).institute      ?? null,
        issuingOrg:       (req as any).issuingOrg       ?? null,
        customIssuingOrg: (req as any).customIssuingOrg ?? null,
        requireCurrent:   !!(req as any).requireCurrent,
        validityYears:    (req as any).validityYears    ?? null,
        mustHave:       !!(req as any).mustHave,
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
    // Pre-fill skillType on SKILL rows so the HARD/SOFT toggle always has
    // a selection. Legacy SKILL rows without skillType default to HARD.
    const defaultSkillType = preset?.category === 'SKILL'
      ? (preset?.skillType ?? 'HARD')
      : null;
    const group = this.fb.group({
      category:       [preset?.category      ?? 'SKILL', [Validators.required]],
      description:    [preset?.description   ?? '',      [Validators.required, Validators.minLength(2)]],
      weight:         [preset?.weight        ?? null],
      minYears:       [preset?.minYears      ?? null],
      skillLevel:     [preset?.skillLevel     ?? null],
      skillType:      [defaultSkillType],
      degreeLevel:    [preset?.degreeLevel    ?? null],
      enrollmentType: [preset?.enrollmentType ?? null],
      institute:      [(preset as any)?.institute     ?? null],
      languageLevel:  [(preset as any)?.languageLevel  ?? null],
      issuingOrg:       [(preset as any)?.issuingOrg       ?? null],
      customIssuingOrg: [(preset as any)?.customIssuingOrg ?? null],
      requireCurrent:   [!!(preset as any)?.requireCurrent],
      validityYears:    [(preset as any)?.validityYears    ?? null],
      mustHave:       [!!(preset as any)?.mustHave],
    }, { validators: [educationDegreeRequiredValidator] });
    this.requirements().push(group);
    if (this.customizeWeights) this.redistributeWeights();
  }

  /** True when this EDUCATION requirement has no degree chips selected. */
  needsDegreeSelection(req: AbstractControl): boolean {
    return !!req.errors?.['degreeRequired'];
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
    { value: 'TRAINING',         label: 'Training' },
    { value: 'LICENCE_BACHELOR', label: 'Licence / Bachelor' },
    { value: 'ENGINEER',         label: 'Engineering degree' },
    { value: 'MASTER',           label: 'Master' },
    { value: 'PHD',              label: 'PhD / Doctorate' },
  ];

  readonly issuingOrgs: { value: string; label: string; defaultValidity: number | null }[] = [
    { value: 'AWS',              label: 'Amazon Web Services (AWS)',     defaultValidity: 3 },
    { value: 'MICROSOFT',        label: 'Microsoft / Azure',             defaultValidity: 1 },
    { value: 'GOOGLE_CLOUD',     label: 'Google Cloud',                  defaultValidity: 2 },
    { value: 'ORACLE',           label: 'Oracle',                        defaultValidity: null },
    { value: 'IBM',              label: 'IBM',                           defaultValidity: null },
    { value: 'CISCO',            label: 'Cisco',                         defaultValidity: 3 },
    { value: 'COMPTIA',          label: 'CompTIA',                       defaultValidity: 3 },
    { value: 'LINUX_FOUNDATION', label: 'Linux Foundation (CKA, CKAD…)', defaultValidity: 3 },
    { value: 'HASHICORP',        label: 'HashiCorp (Terraform, Vault)',  defaultValidity: 2 },
    { value: 'DOCKER',           label: 'Docker',                        defaultValidity: 2 },
    { value: 'RED_HAT',          label: 'Red Hat',                       defaultValidity: 3 },
    { value: 'ISC2',             label: '(ISC)² - CISSP, CCSP, …',       defaultValidity: 3 },
    { value: 'ISACA',            label: 'ISACA (CISA, CISM, CRISC)',     defaultValidity: 3 },
    { value: 'PMI',              label: 'PMI (PMP, etc.)',               defaultValidity: 3 },
    { value: 'SCRUM_ALLIANCE',   label: 'Scrum Alliance',                defaultValidity: 2 },
    { value: 'TOGAF',            label: 'TOGAF (Open Group)',            defaultValidity: null },
    { value: 'ITIL',             label: 'ITIL / AXELOS',                 defaultValidity: null },
    { value: 'SALESFORCE',       label: 'Salesforce',                    defaultValidity: null },
    { value: 'OTHER',            label: 'Other / Custom',                defaultValidity: null },
  ];

  readonly issuingOrgGroups: { label: string; orgs: string[] }[] = [
    { label: 'Cloud',                 orgs: ['AWS', 'MICROSOFT', 'GOOGLE_CLOUD', 'ORACLE', 'IBM'] },
    { label: 'Network & Infrastructure', orgs: ['CISCO', 'COMPTIA', 'LINUX_FOUNDATION', 'HASHICORP', 'DOCKER', 'RED_HAT'] },
    { label: 'Security',              orgs: ['ISC2', 'ISACA'] },
    { label: 'Process & Architecture', orgs: ['PMI', 'SCRUM_ALLIANCE', 'TOGAF', 'ITIL'] },
    { label: 'Other',                 orgs: ['SALESFORCE', 'OTHER'] },
  ];

  orgMeta(code: string): { value: string; label: string; defaultValidity: number | null } | undefined {
    return this.issuingOrgs.find(o => o.value === code);
  }

  onIssuingOrgChange(req: AbstractControl, orgValue: string | null): void {
    if (!orgValue) return;
    const meta = this.issuingOrgs.find(o => o.value === orgValue);
    if (!meta || meta.defaultValidity == null) return;
    const requireCurrent = req.get('requireCurrent')?.value;
    const current = req.get('validityYears')?.value;
    if (requireCurrent && (current == null || current === '')) {
      req.get('validityYears')?.setValue(meta.defaultValidity);
    }
  }

  onRequireCurrentToggle(req: AbstractControl, on: boolean): void {
    if (!on) return;
    const current = req.get('validityYears')?.value;
    if (current != null && current !== '') return;
    const orgValue = req.get('issuingOrg')?.value;
    const meta = this.issuingOrgs.find(o => o.value === orgValue);
    req.get('validityYears')?.setValue(meta?.defaultValidity ?? 3);
  }

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

    return null;
  }

  private buildPayload(): Omit<JobOffer, 'id'> {
    const v = this.form.getRawValue();

    const reqs: JobRequirement[] = (v.requirements ?? []).map((r: any) => {
      const isSkill = r.category === 'SKILL';
      const skillType: 'HARD' | 'SOFT' | null = isSkill
        ? (r.skillType === 'SOFT' ? 'SOFT' : 'HARD')
        : null;
      return {
        category:       r.category,
        description:    r.description,
        weight:         this.customizeWeights ? (r.weight ?? null) : null,
        minYears:       r.minYears      ?? null,
        // SOFT skills have no proficiency tier - drop any stale value.
        skillLevel:     skillType === 'SOFT' ? null : (r.skillLevel ?? null),
        skillType,
        degreeLevel:    r.degreeLevel    ?? null,
        enrollmentType: r.enrollmentType ?? null,
        institute:      (r.institute && String(r.institute).trim()) || null,
        languageLevel:  (r as any).languageLevel  ?? null,
        issuingOrg:       r.issuingOrg       ?? null,
        customIssuingOrg: (r.customIssuingOrg && String(r.customIssuingOrg).trim()) || null,
        requireCurrent:   !!r.requireCurrent,
        validityYears:    r.validityYears    ?? null,
        mustHave:         !!r.mustHave,
      };
    });

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
      skillsWeight:     (v.skillsWeight     ?? 40) / 100,
      semanticWeight:   (v.semanticWeight   ?? 35) / 100,
      experienceWeight: (v.experienceWeight ?? 15) / 100,
      seniorityWeight:  (v.seniorityWeight  ?? 10) / 100,
      requirements: reqs,
    };
  }

  async submit() {
    this.error = null;
    clearServerErrors(this.form);

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

      // Same enrichment as add-job: any SOFT skill requirement that wasn't
      // in the catalog before this edit gets added with AI synonyms. Runs
      // AFTER the job save so an Ollama hiccup can't roll back the edit.
      try {
        const added = await this.catalogService
          .enrichNewSoftSkillsFromRequirements(payload.requirements ?? []);
        if (added.length > 0) {
          await Swal.fire({
            icon: 'success',
            title: 'Catalog updated',
            html:
              `<p>Added ${added.length} new soft skill${added.length > 1 ? 's' : ''} ` +
              `to the catalog with AI-suggested synonyms:</p>` +
              `<p style="margin-top:0.6rem;font-weight:600;color:#1e40bc;">` +
                `${added.join(', ')}` +
              `</p>`,
            timer: 3500,
            timerProgressBar: true,
            showConfirmButton: false,
          });
        }
      } catch (e) {
        console.warn('[update-job] soft-skill enrichment failed:', e);
      }

      // Ask recruiter if they want to re-score all existing applications
      const rematchResult = await Swal.fire({
        title: 'Re-score applications?',
        text: 'Job requirements or scoring weights changed. Re-calculating AI scores for all existing applications will update their rankings. This runs in the background.',
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: 'Yes, re-score all',
        cancelButtonText: 'Skip',
      });

      if (rematchResult.isConfirmed && this.jobId) {
        this.appService.rematchApplicationsForJob(this.jobId).subscribe();
        await Swal.fire({
          title: 'Re-scoring started',
          text: 'AI scores are being recalculated in the background. This takes roughly 20-40 seconds per application - refresh the applications table in a minute to see updated rankings.',
          icon: 'success',
          confirmButtonText: 'Go to job page',
        });
        await this.router.navigate(['/jobs', this.jobId]);
      } else {
        await this.router.navigate(['/browse']);
      }
    } catch (e: any) {
      const httpError = normalizeHttpError(e);
      applyServerErrors(this.form, httpError.fieldErrors);
      this.error = httpError.message || 'Update failed.';
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

  /** Server message takes priority over the client-validator default. */
  fieldError(path: string, defaultMsg: string): string | null {
    const ctl = this.form.get(path);
    if (!ctl) return null;
    if (ctl.errors?.['server']) return ctl.errors['server'] as string;
    if (ctl.touched && ctl.invalid) return defaultMsg;
    return null;
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