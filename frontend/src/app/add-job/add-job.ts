import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormArray, FormBuilder, Validators, AbstractControl, ValidationErrors } from '@angular/forms';
import { Router, RouterLink, NavigationExtras } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import Swal from 'sweetalert2';

import { JobService } from '../services/job.service';
import { CatalogService, CatalogItem } from '../services/catalog.service';
import { JobOffer } from '../model/jobOffer.model';
import { RequirementCategory } from '../model/jobRequirement.model';
import { JobRequirement } from '../model/jobRequirement.model';
import { getCanonicalLanguages } from '../services/language-options.service';
import { DOMAIN_OPTIONS } from '../services/domains';
import { OrgComboboxComponent } from '../org-combobox/org-combobox.component';
import { applyServerErrors, clearServerErrors, normalizeHttpError } from '../utils/http-error';

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
  selector: 'app-add-job',
  imports: [CommonModule, ReactiveFormsModule, OrgComboboxComponent],
  templateUrl: './add-job.html',
  styleUrl: './add-job.css',
})
export class AddJob{
  private readonly fb = inject(FormBuilder);
  private readonly jobService = inject(JobService);
  private readonly catalogService = inject(CatalogService);
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

  /** Catalog autocomplete sources, populated on init. Filtered by type so
   *  the description field suggests the right names once the recruiter
   *  picks SKILL → HARD or SKILL → SOFT. Powers the <datalist> in the
   *  template. */
  hardSkillSuggestions: string[] = [];
  softSkillSuggestions: string[] = [];

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
    // For a SKILL row default skillType to HARD so the recruiter doesn't
    // see an empty toggle - they can flip to SOFT in one click. Legacy
    // rows without a skillType also default to HARD on edit.
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
      institute:      [preset?.institute      ?? null],
      languageLevel:  [preset?.languageLevel  ?? null],
      issuingOrg:       [preset?.issuingOrg       ?? null],
      customIssuingOrg: [preset?.customIssuingOrg ?? null],
      requireCurrent:   [preset?.requireCurrent   ?? false],
      validityYears:    [preset?.validityYears    ?? null],
      mustHave:       [preset?.mustHave       ?? false],
    }, { validators: [educationDegreeRequiredValidator] });
    this.requirements().push(group);
    if (this.customizeWeights) this.redistributeWeights();
  }

  /** True when this EDUCATION requirement has no degree chips selected.
   *  Used to render an inline error and gate the submit. */
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

  // CERTIFICATION: known issuing organizations + their typical validity in
  // years. The validity is used as a sensible default when the recruiter
  // toggles "must be current" without explicitly setting validityYears.
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

  /** Same orgs grouped into <optgroup> sections so the dropdown is easier
   *  to scan. The flat `issuingOrgs` above is kept for the validity-lookup
   *  helpers (onIssuingOrgChange, onRequireCurrentToggle). */
  readonly issuingOrgGroups: { label: string; orgs: string[] }[] = [
    { label: 'Cloud',                 orgs: ['AWS', 'MICROSOFT', 'GOOGLE_CLOUD', 'ORACLE', 'IBM'] },
    { label: 'Network & Infrastructure', orgs: ['CISCO', 'COMPTIA', 'LINUX_FOUNDATION', 'HASHICORP', 'DOCKER', 'RED_HAT'] },
    { label: 'Security',              orgs: ['ISC2', 'ISACA'] },
    { label: 'Process & Architecture', orgs: ['PMI', 'SCRUM_ALLIANCE', 'TOGAF', 'ITIL'] },
    { label: 'Other',                 orgs: ['SALESFORCE', 'OTHER'] },
  ];

  /** Look up an org meta record by code — used by the template to render
   *  each <option> with the right label, since the grouped structure only
   *  holds codes. */
  orgMeta(code: string): { value: string; label: string; defaultValidity: number | null } | undefined {
    return this.issuingOrgs.find(o => o.value === code);
  }

  /** When the recruiter picks an issuing org, fill validityYears with its
   *  typical lifetime if requireCurrent is on and validityYears is empty. */
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

  /** When the recruiter toggles "must be current" on and validityYears is
   *  empty, prefill with the org default (or 3 years as fallback). */
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
      // skillType only applies when category=SKILL. Non-skill categories
      // store null so the backend doesn't carry meaningless sub-type data
      // on EDUCATION / LANGUAGE / etc. rows.
      const isSkill = r.category === 'SKILL';
      const skillType: 'HARD' | 'SOFT' | null = isSkill
        ? (r.skillType === 'SOFT' ? 'SOFT' : 'HARD')
        : null;
      return {
        category:       r.category,
        description:    r.description,
        weight:         this.customizeWeights ? (r.weight ?? null) : null,
        minYears:       r.minYears      ?? null,
        // SOFT skills have no proficiency tier - drop skillLevel even if
        // the form value got stuck on something from a previous HARD pick.
        skillLevel:     skillType === 'SOFT' ? null : (r.skillLevel ?? null),
        skillType,
        degreeLevel:    r.degreeLevel    ?? null,
        enrollmentType: r.enrollmentType ?? null,
        institute:      (r.institute && String(r.institute).trim()) || null,
        languageLevel:  r.languageLevel  ?? null,
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
      skillsWeight:     (v.skillsWeight ?? 40) / 100,
      semanticWeight:   (v.semanticWeight ?? 35) / 100,
      experienceWeight: (v.experienceWeight ?? 15) / 100,
      seniorityWeight:  (v.seniorityWeight ?? 10) / 100,
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

    this.saving = true;
    try {
      await firstValueFrom(this.jobService.createJob(payload));

      // Auto-enrich the SOFT catalog with any newly-mentioned soft skills.
      // Why this runs AFTER createJob and not before: the job save is the
      // hard requirement; enrichment is a nice-to-have that shouldn't be
      // able to block (or abort) the recruiter's primary action. Worst
      // case the Ollama call fails - we swallow it, log, and move on; the
      // job is already saved and the next visit to the Skills Catalog
      // page can fix any missing synonyms by hand.
      try {
        const added = await this.catalogService
          .enrichNewSoftSkillsFromRequirements(payload.requirements ?? []);
        if (added.length > 0) {
          await Swal.fire({
            icon: 'success',
            title: `Catalog updated`,
            html:
              `<p>Added ${added.length} new soft skill${added.length > 1 ? 's' : ''} ` +
              `to the catalog with AI-suggested synonyms:</p>` +
              `<p style="margin-top:0.6rem;font-weight:600;color:#1e40bc;">` +
                `${added.join(', ')}` +
              `</p>` +
              `<p style="margin-top:0.6rem;font-size:0.85rem;">` +
                `Edit them anytime from the Skills Catalog admin page.` +
              `</p>`,
            timer: 3800,
            timerProgressBar: true,
            showConfirmButton: false,
          });
        }
      } catch (e) {
        console.warn('[add-job] soft-skill enrichment failed:', e);
      }

      await this.router.navigate(['/browse']);
    } catch (e: any) {
      const httpError = normalizeHttpError(e);
      // Push per-field server messages onto the controls so existing
      // template-level @if (c('title')?.errors) blocks surface them
      // alongside the Angular validators that already render below
      // each input.
      applyServerErrors(this.form, httpError.fieldErrors);
      this.error = httpError.message || 'Create failed.';
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

  /**
   * Best error message to show next to a control. Prefers a server-side
   * message (from applyServerErrors) when one exists, otherwise falls
   * back to the supplied client-validator default. Returns null when the
   * control is untouched or valid so the existing @if-as-msg blocks can
   * suppress the row entirely.
   */
  fieldError(path: string, defaultMsg: string): string | null {
    const ctl = this.form.get(path);
    if (!ctl) return null;
    if (ctl.errors?.['server']) return ctl.errors['server'] as string;
    if (ctl.touched && ctl.invalid) return defaultMsg;
    return null;
  }

  isDuplicate = false;

  ngOnInit() {
    // Catalog autocomplete sources. Loaded once on init; the form's <datalist>
    // for the Description field reads from these arrays so the recruiter sees
    // existing skill names while typing (avoids "Java" vs "java" vs "JAVA"
    // typos, and helps them discover what's already curated).
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
    }).catch(() => { /* degraded silently - the input still works as free text */ });

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
          skillLevel:     r.skillLevel,
          skillType:      r.skillType ?? null,
          degreeLevel:    r.degreeLevel,
          enrollmentType: r.enrollmentType,
          institute:      r.institute ?? null,
          languageLevel:  r.languageLevel ?? null,
          issuingOrg:       r.issuingOrg ?? null,
          customIssuingOrg: r.customIssuingOrg ?? null,
          requireCurrent:   !!r.requireCurrent,
          validityYears:    r.validityYears ?? null,
          mustHave:       !!r.mustHave,
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
