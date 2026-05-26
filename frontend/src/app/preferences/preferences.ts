import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { CandidateProfileService, CandidateLanguage } from '../services/candidate-profile.service';
import { LanguageOptionsService, ALWAYS_SHOWN_LANGUAGES } from '../services/language-options.service';
import { CatalogService, CatalogItem } from '../services/catalog.service';
import { SOFT_SKILLS } from '../services/soft-skills';

const DOMAIN_SKILLS: Record<string, string[]> = {
  SOFTWARE_ENGINEERING: [
    'Java', 'Spring Boot', 'Angular', 'React', 'Vue.js', 'Python', 'Node.js',
    'TypeScript', 'JavaScript', 'Docker', 'Kubernetes', 'PostgreSQL', 'MongoDB',
    'SQL', 'Git', 'REST API', 'GraphQL', 'Microservices', 'CI/CD', 'Jenkins',
    'AWS', 'Azure', 'Linux', 'C++', 'C#', '.NET', 'Hibernate', 'Redis',
  ],
  FINANCE_BANKING: [
    'Financial Analysis', 'Risk Management', 'Excel', 'SAP', 'Bloomberg', 'SWIFT',
    'Accounting', 'Audit', 'Basel III', 'Anti-Money Laundering', 'Treasury Management',
    'Financial Modeling', 'Power BI', 'VBA', 'SQL',
  ],
  INSURANCE: [
    'Actuarial Analysis', 'Claims Management', 'Policy Administration', 'Underwriting',
    'Reinsurance', 'Solvency II', 'Insurance Software', 'Risk Assessment', 'Excel',
  ],
  PROJECT_MANAGEMENT: [
    'Agile', 'Scrum', 'PMP', 'PRINCE2', 'JIRA', 'MS Project', 'Risk Management',
    'Stakeholder Management', 'Waterfall', 'Kanban', 'Confluence', 'SAFe',
  ],
  QUALITY_ASSURANCE: [
    'Manual Testing', 'Selenium', 'JUnit', 'Test Automation', 'JIRA', 'Postman',
    'Load Testing', 'JMeter', 'SoapUI', 'API Testing', 'Cucumber', 'TestNG',
  ],
  BUSINESS_ANALYSIS: [
    'Requirements Analysis', 'UML', 'BPMN', 'Use Cases', 'Wireframing', 'SQL',
    'Stakeholder Management', 'Process Modeling', 'Agile', 'JIRA', 'Confluence', 'Power BI',
  ],
};

// SOFT_SKILLS canonical list lives in services/soft-skills.ts so the
// hard-skill catalog (CatalogService) and the soft-skills chip grid (this
// component) share one source of truth. Imported at top of file.

@Component({
  selector: 'app-preferences',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, FormsModule],
  templateUrl: './preferences.html',
  styleUrls: ['./preferences.css'],
})
export class Preferences implements OnInit, OnDestroy {
  loading = true;
  saving  = false;
  error?: string;
  success?: string;

  form: FormGroup;

  readonly domains = [
    { value: 'SOFTWARE_ENGINEERING', label: 'Software Engineering / IT' },
    { value: 'FINANCE_BANKING',      label: 'Finance & Banking' },
    { value: 'INSURANCE',            label: 'Insurance' },
    { value: 'PROJECT_MANAGEMENT',   label: 'Project Management' },
    { value: 'QUALITY_ASSURANCE',    label: 'Quality Assurance / Testing' },
    { value: 'BUSINESS_ANALYSIS',    label: 'Business Analysis' },
  ];

  readonly softSkills = SOFT_SKILLS;

  selectedHardSkills: string[] = [];
  selectedSoftSkills: string[] = [];
  selectedArrangements: string[] = [];  // multi-select chips
  selectedJobTypes:    string[] = [];   // multi-select chips
  languages: CandidateLanguage[] = [];

  // ── Catalog state (Phase 3) ─────────────────────────────────────────────
  // Skills + languages come from CatalogService — auto-extracted from current
  // jobs PLUS manual recruiter additions. Both are merged client-side so the
  // UI sees one unified list per category. ⭐NEW badges fire when an item's
  // firstSeenAt is later than the candidate's lastPreferencesAcknowledgedAt.
  catalogSkills: CatalogItem[] = [];
  catalogLanguages: CatalogItem[] = [];
  private lastAcknowledgedAtMs = 0;  // local cache for fast comparisons

  // ── Auto-save plumbing ──────────────────────────────────────────────────
  // Most candidates expect "click chip → it's saved", not "click chip then
  // hunt for a Save button". We debounce the actual PUT by 500ms so rapid
  // clicks (e.g. re-ordering 5 skills in a row) coalesce into a single API
  // call instead of 5.
  private autoSaveTimer: ReturnType<typeof setTimeout> | null = null;
  /** Bumps the debounce; call from any handler that mutates form state. */
  private queueAutoSave(): void {
    if (this.autoSaveTimer) clearTimeout(this.autoSaveTimer);
    this.autoSaveTimer = setTimeout(() => {
      this.autoSaveTimer = null;
      // Skip auto-save while the initial profile load is still in flight —
      // the local state may not match the server's snapshot yet and we'd
      // overwrite it. `loading` is set inside load() so this gates correctly.
      if (this.loading) return;
      // Pass `silent=true` so save() skips the returnTo-redirect side
      // effect — only the explicit "Save preferences" button should redirect.
      this.save(true);
    }, 500);
  }

  readonly cefrLevels = ['A1', 'A2', 'B1', 'B2', 'C1', 'C2'];

  // commonLanguages is now resolved at runtime: Arabic/French/English are
  // always shown (cultural baseline for Tunisia + tech) plus any language
  // mentioned in a current PUBLISHED job's LANGUAGE requirement. The list is
  // fetched once from LanguageOptionsService and falls back to the always-
  // shown trio if the jobs request fails.
  commonLanguages: readonly string[] = ALWAYS_SHOWN_LANGUAGES;
  newLanguage = '';
  newLevel = 'B1';

  /** Languages the candidate hasn't already added — drives the Add-language
   *  dropdown so duplicates can't be selected. */
  get availableLanguagesToAdd(): string[] {
    const already = new Set(this.languages.map(l => l.language.toLowerCase()));
    return this.commonLanguages.filter(l => !already.has(l.toLowerCase()));
  }

  /** Catalog-rich version of the above — used by the strict dropdown so it
   *  can render 🔥 (in demand) and ⭐ (new) markers alongside each option. */
  get availableLanguagesToAddCatalog(): CatalogItem[] {
    const already = new Set(this.languages.map(l => l.language.toLowerCase()));
    return this.catalogLanguages.filter(l => !already.has(l.name));
  }

  constructor(
    private fb: FormBuilder,
    private profileService: CandidateProfileService,
    private router: Router,
    private route: ActivatedRoute,
    private languageOptions: LanguageOptionsService,
    private catalogService: CatalogService,
  ) {
    this.form = this.fb.group({
      status:                   [''],
      yearsOfExperience:        [''],
      educationLevel:           [''],
      domain:                   [''],
      // Multi-select chips are tracked in selectedArrangements /
      // selectedJobTypes — these form controls are kept for backwards
      // compatibility with the existing patchValue/getRawValue plumbing.
    });
  }

  async ngOnInit() {
    // Load profile + catalog in parallel so the chip grid + NEW badges are
    // ready by the time the user scrolls past the basic fields.
    await Promise.all([
      this.load(),
      this.loadAvailableLanguages(),
      this.loadCatalog(),
    ]);

    // Acknowledge the visit AFTER we've cached lastAcknowledgedAtMs locally —
    // backend now bumps the timestamp to "now", but our in-memory copy keeps
    // the OLD value so the NEW badges remain visible during THIS page view.
    // The badges only clear on the NEXT visit, which is the right UX.
    this.profileService.acknowledge().catch(() => { /* non-critical */ });

    // Auto-save on any reactive form field change (status, yearsOfExperience,
    // educationLevel, domain). Same debounced save the chip toggles use.
    // Subscribed AFTER initial load() so patchValue during load doesn't
    // trigger a redundant save.
    this.form.valueChanges.subscribe(() => this.queueAutoSave());
  }

  /**
   * Flush any pending auto-save when the candidate navigates away.
   * Without this, clicking a chip and then immediately clicking a nav link
   * would lose the click — the 500ms debounce wouldn't have fired before
   * the component is destroyed.
   *
   * We call save(true) directly here instead of letting the timer fire,
   * because Angular tears down the component before the next setTimeout
   * tick. The save is fire-and-forget; the user is already leaving the page.
   */
  ngOnDestroy(): void {
    if (this.autoSaveTimer) {
      clearTimeout(this.autoSaveTimer);
      this.autoSaveTimer = null;
      // Fire one final save with whatever the current state is. We don't
      // await it — the component is being destroyed, the consumer of any
      // result is already gone.
      if (!this.loading) this.save(true).catch(() => { /* logged inside */ });
    }
  }

  private async loadAvailableLanguages(): Promise<void> {
    try {
      this.commonLanguages = await this.languageOptions.getAvailableLanguages();
    } catch {
      // Service has its own fallback to ALWAYS_SHOWN_LANGUAGES; nothing to do.
    }
  }

  private async loadCatalog(): Promise<void> {
    try {
      // Invalidate before fetching so the candidate always sees the latest
      // catalog state when they open Preferences. Without this, a recruiter
      // adding/removing skills mid-session would be invisible to the
      // candidate until they fully reload the browser. One extra HTTP call
      // per /preferences visit is a fair trade for live data.
      this.catalogService.invalidate();
      const snap = await this.catalogService.getSnapshot();
      this.catalogSkills    = snap.skills;
      this.catalogLanguages = snap.languages;
    } catch {
      // CatalogService has its own fallback; nothing to do.
    }
  }

  /** ⭐NEW badge condition — fires when the catalog item was first seen after
   *  the candidate's last preferences acknowledgment. */
  isNew(item: CatalogItem): boolean {
    if (!item.firstSeenAt) return false;
    const seenMs = new Date(item.firstSeenAt).getTime();
    return seenMs > this.lastAcknowledgedAtMs;
  }

  /** Hard skills filtered by the candidate's selected domain. Universal
   *  skills (empty `domains` list) are always included. */
  get visibleHardSkills(): CatalogItem[] {
    const domain = this.form.get('domain')?.value as string | null;
    return this.catalogSkills.filter(s =>
      s.type === 'HARD' &&
      (s.domains.length === 0 || (domain && s.domains.includes(domain))),
    );
  }

  /** Soft skills shown in the chip grid — the universal hardcoded list,
   *  same for every domain. Deliberately NOT merged with the recruiter
   *  catalog: soft skills are domain-agnostic universal behaviors, and the
   *  Skills Catalog only curates HARD skills. Keeps the model simple. */
  get visibleSoftSkills(): Array<{ displayName: string; isNew: boolean }> {
    return SOFT_SKILLS.map(n => ({ displayName: n, isNew: false }));
  }

  async load() {
    this.loading = true;
    this.error   = undefined;
    try {
      const p = await this.profileService.get();
      this.form.patchValue({
        status:            p.status            ?? '',
        yearsOfExperience: p.yearsOfExperience ?? '',
        educationLevel:    p.educationLevel    ?? '',
        domain:            p.domain            ?? '',
      });
      this.selectedHardSkills    = p.hardSkills ?? [];
      this.selectedSoftSkills    = p.softSkills ?? [];
      this.selectedArrangements  = p.preferredWorkArrangement ?? [];
      this.selectedJobTypes      = p.preferredJobType         ?? [];
      this.languages             = p.languages ?? [];
      // Snapshot the OLD acknowledged timestamp for the ⭐NEW badge logic.
      // We compute against this throughout the current page view, even
      // though the backend bumps it to "now" via the acknowledge call —
      // that way badges stay visible while the user is looking and clear
      // only on the next visit.
      this.lastAcknowledgedAtMs = p.lastPreferencesAcknowledgedAt
        ? new Date(p.lastPreferencesAcknowledgedAt).getTime()
        : 0;
    } catch {
      this.error = 'Failed to load preferences';
    } finally {
      this.loading = false;
    }
  }

  get availableHardSkills(): string[] {
    return DOMAIN_SKILLS[this.form.get('domain')?.value ?? ''] ?? [];
  }

  hardSkillRank(skill: string): number | null {
    const idx = this.selectedHardSkills.indexOf(skill);
    return idx === -1 ? null : idx + 1;
  }

  softSkillRank(skill: string): number | null {
    const idx = this.selectedSoftSkills.indexOf(skill);
    return idx === -1 ? null : idx + 1;
  }

  toggleHardSkill(skill: string) {
    const idx = this.selectedHardSkills.indexOf(skill);
    this.selectedHardSkills = idx === -1
      ? [...this.selectedHardSkills, skill]
      : this.selectedHardSkills.filter(s => s !== skill);
    this.queueAutoSave();
  }

  toggleSoftSkill(skill: string) {
    const idx = this.selectedSoftSkills.indexOf(skill);
    this.selectedSoftSkills = idx === -1
      ? [...this.selectedSoftSkills, skill]
      : this.selectedSoftSkills.filter(s => s !== skill);
    this.queueAutoSave();
  }

  /**
   * Domain change just triggers an auto-save — we DON'T clear hard skills
   * anymore. A candidate might have legitimate cross-domain expertise (a
   * backend dev who also knows Excel from a previous Finance role), and
   * erasing it on every domain change loses real data.
   *
   * The chip grid filters what's *visible* per domain (via visibleHardSkills),
   * so selections from other domains stay in the saved profile but disappear
   * from view until the candidate switches back. No data is lost.
   */
  onDomainChange() {
    this.queueAutoSave();
  }

  addLanguage() {
    const lang = this.newLanguage.trim();
    if (!lang || this.languages.some(l => l.language.toLowerCase() === lang.toLowerCase())) return;
    this.languages = [...this.languages, { language: lang, level: this.newLevel }];
    this.newLanguage = '';
    this.newLevel = 'B1';
    this.queueAutoSave();
  }

  removeLanguage(index: number) {
    this.languages = this.languages.filter((_, i) => i !== index);
    this.queueAutoSave();
  }

  updateLanguageLevel(index: number, level: string) {
    this.languages = this.languages.map((l, i) => i === index ? { ...l, level } : l);
    this.queueAutoSave();
  }

  setDomain(v: string)          { this.form.get('domain')?.setValue(v); this.onDomainChange(); }

  // Multi-select toggles for the chips. Click once = add, click again = remove.
  // Selecting every option is equivalent to selecting none — the ranker treats
  // both as "no preference" — but we keep the visual state honest (empty stays
  // empty, full stays full) so the user sees exactly what they picked.
  toggleArrangement(v: string) {
    const i = this.selectedArrangements.indexOf(v);
    this.selectedArrangements = i === -1
      ? [...this.selectedArrangements, v]
      : this.selectedArrangements.filter(x => x !== v);
    this.queueAutoSave();
  }

  toggleJobType(v: string) {
    const i = this.selectedJobTypes.indexOf(v);
    this.selectedJobTypes = i === -1
      ? [...this.selectedJobTypes, v]
      : this.selectedJobTypes.filter(x => x !== v);
    this.queueAutoSave();
  }

  isArrangementSelected(v: string): boolean { return this.selectedArrangements.includes(v); }
  isJobTypeSelected(v: string):    boolean { return this.selectedJobTypes.includes(v); }

  /**
   * Persist the current form + selections to the backend.
   *
   * @param silent when true (auto-save path), skip the success toast and the
   *               ?returnTo redirect — those are user-facing side effects
   *               only the explicit "Save preferences" button should trigger.
   *               Auto-save should be invisible.
   */
  async save(silent = false) {
    this.saving  = true;
    this.error   = undefined;
    if (!silent) this.success  = undefined;
    try {
      const raw = this.form.getRawValue();
      await this.profileService.save({
        status:                   raw.status                   || undefined,
        yearsOfExperience:        raw.yearsOfExperience        || undefined,
        educationLevel:           raw.educationLevel           || undefined,
        domain:                   raw.domain                   || undefined,
        hardSkills:               this.selectedHardSkills,
        softSkills:               this.selectedSoftSkills,
        languages:                this.languages,
        preferredWorkArrangement: this.selectedArrangements,
        preferredJobType:         this.selectedJobTypes,
      });
      if (!silent) {
        this.success = 'Preferences saved successfully.';

        // If the user got bounced here from /browse trying to use the
        // Recommendation sort, send them back so the ranking can run with
        // their fresh data. The `resume` flag tells the browse page to flip
        // straight into match-sort mode after load.
        const returnTo = this.route.snapshot.queryParamMap.get('returnTo');
        const resume   = this.route.snapshot.queryParamMap.get('resume');
        if (returnTo) {
          setTimeout(() => {
            this.router.navigate([returnTo], {
              queryParams: resume ? { resume } : {},
            });
          }, 800);  // brief pause so the user sees the "Saved" toast
        }
      }
    } catch (err) {
      // Set the error for BOTH paths now — auto-save failures need to surface
      // in the header indicator so the candidate knows their picks aren't
      // saved (otherwise the indicator would silently go from "Saving…" back
      // to hidden as if everything were fine).
      this.error = 'Failed to save preferences.';
      console.error('[Preferences] save() failed', { silent, err });
    } finally {
      this.saving = false;
    }
  }

  goBack() { this.router.navigate(['/profile']); }

  /**
   * True when this Preferences visit was triggered by /browse trying to use
   * Recommendation with an empty profile — in that case we show a "Back to
   * recommendations" button at the bottom of the page (drives the resume
   * flow). For normal visits, no button; candidates leave via the nav.
   */
  get cameFromBrowse(): boolean {
    return this.route.snapshot.queryParamMap.get('returnTo') === '/browse';
  }

  /**
   * Navigate back to /browse with the resume flag so it auto-triggers the
   * match-sort. Auto-save has already persisted everything, so no save call
   * is needed here — this is purely navigation.
   */
  returnToBrowse(): void {
    const resume = this.route.snapshot.queryParamMap.get('resume');
    this.router.navigate(['/browse'], {
      queryParams: resume ? { resume } : {},
    });
  }
}
