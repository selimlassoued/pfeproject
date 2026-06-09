import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import Swal from 'sweetalert2';
import { CatalogService, CatalogItem } from '../services/catalog.service';
import { DOMAIN_OPTIONS, domainLabel } from '../services/domains';

/**
 * Recruiter-facing admin page for the skill catalog. Lets recruiters:
 *
 *   • Pre-add skills they expect to hire for (typed into the input)
 *   • See the full live catalog (auto-extracted from jobs + manual entries)
 *   • Remove typos / retired tech (soft-delete; tombstone prevents
 *     re-extraction from sneaking the bad name back in)
 *   • Restore previously-removed skills
 *
 * Access: RECRUITER + ADMIN + SUPERADMIN (gated by the route guard).
 * Candidates never see this page - they consume the same catalog via the
 * Preferences page's chip grid.
 */
@Component({
  selector: 'app-skills-catalog',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './skills-catalog.html',
  styleUrls: ['./skills-catalog.css'],
})
export class SkillsCatalog implements OnInit {
  loading = false;
  saving = false;
  error: string | null = null;

  // Active skills the catalog currently offers to candidates.
  activeSkills: CatalogItem[] = [];

  // Tombstoned entries - shown in a collapsible "Removed" section with a
  // Restore button. Stored separately so the main table stays focused on
  // what candidates actually see.
  removedNames: string[] = [];

  newSkillInput = '';
  // The Skills Catalog now curates BOTH HARD and SOFT skills.
  // viewType drives what the table shows AND what type new entries get when added.
  // SOFT skills are universal across industries, so the domain filter is hidden
  // when viewing them.
  viewType: 'HARD' | 'SOFT' = 'HARD';
  newSkillDomains: string[] = [];

  // ── Synonym editor (SOFT skills only) ───────────────────────────────────
  // Recruiter-curated paraphrases that short-circuit the embedding cascade
  // in /match-soft-skill. Built up via the chip input below + the Suggest
  // button (which calls the LLM via cv-parser-service for candidates).
  newSkillSynonyms: string[] = [];
  synonymInput = '';
  suggestingSynonyms = false;

  // ── Pagination state ────────────────────────────────────────────────────
  readonly pageSize = 10;
  currentPage = 0;

  // Canonical lists used by the Add form.
  readonly domainOptions = DOMAIN_OPTIONS;
  readonly domainLabel = domainLabel;

  // ── Derived stats for the header banner ─────────────────────────────────
  // All counts are filtered by the current viewType so the numbers always
  // reflect what's actually shown in the table below.
  get typeFiltered(): CatalogItem[] {
    return this.activeSkills.filter(s => s.type === this.viewType);
  }
  get totalSkillsCount(): number     { return this.typeFiltered.length; }
  get inDemandCount(): number        { return this.typeFiltered.filter(s => s.currentDemandCount > 0).length; }
  get universalCount(): number       { return this.typeFiltered.filter(s => s.domains.length === 0).length; }
  get manualCount(): number          { return this.typeFiltered.filter(s => s.source === 'MANUAL').length; }
  /** Cross-type totals for the type toggle badge - tells the recruiter how
   *  many entries exist on the OTHER tab so they can spot empty/light state. */
  get hardSkillsCount(): number      { return this.activeSkills.filter(s => s.type === 'HARD').length; }
  get softSkillsCount(): number      { return this.activeSkills.filter(s => s.type === 'SOFT').length; }

  /**
   * Maps each canonical domain code to a stable accent color used for chip
   * styling. Picked by hand so each domain feels distinct at a glance -
   * software=blue (matches the brand secondary), finance=green (money),
   * insurance=teal (cool/calm), PM=amber (energetic), QA=violet,
   * BA=rose. Returned as a CSS custom-property string per chip.
   */
  readonly domainAccent: Record<string, string> = {
    SOFTWARE_ENGINEERING: '121,164,233', // brand blue
    FINANCE_BANKING:      '120,200,140', // green
    INSURANCE:            ' 90,200,200', // teal
    PROJECT_MANAGEMENT:   '230,170, 80', // amber
    QUALITY_ASSURANCE:    '180,140,230', // violet
    BUSINESS_ANALYSIS:    '230,130,170', // rose
  };
  /** RGB triplet for inline `style` - falls back to brand blue. */
  accentFor(domain: string): string {
    return this.domainAccent[domain] ?? '121,164,233';
  }

  constructor(private catalogService: CatalogService) {}

  /** Toggle a domain in the "Add" form's domain multi-select. The checked
   *  set serves two purposes - when adding, it's the tag list for the new
   *  skill; while typing, it also filters the catalog table to skills in
   *  those domains (so the recruiter can see what's already there before
   *  adding a possible duplicate). Reset pagination on every toggle. */
  toggleNewDomain(value: string): void {
    const i = this.newSkillDomains.indexOf(value);
    this.newSkillDomains = i === -1
      ? [...this.newSkillDomains, value]
      : this.newSkillDomains.filter(x => x !== value);
    this.currentPage = 0;
  }
  isNewDomainSelected(value: string): boolean {
    return this.newSkillDomains.includes(value);
  }

  // ── Derived views ───────────────────────────────────────────────────────

  /**
   * Filters the active skill list by what the recruiter is typing in the
   * "add" input. As soon as they type "kaf", the table shrinks to skills
   * containing "kaf" - making it obvious if "Kafka" already exists before
   * they add a duplicate. Case-insensitive substring match.
   * Empty input shows everything.
   */
  get filteredSkills(): CatalogItem[] {
    const q = this.newSkillInput.trim().toLowerCase();
    // Domain filter only applies when viewing HARD skills - SOFT skills are
    // universal, so the domain pills are hidden in the UI when viewType=SOFT.
    const domainFilter = this.viewType === 'SOFT' ? [] : this.newSkillDomains;

    return this.activeSkills.filter(s => {
      // 0. Type filter - separate tab for HARD vs SOFT.
      if (s.type !== this.viewType) return false;

      // 1. Text filter - skip rows that don't match the search box (when set).
      if (q && !s.name.toLowerCase().includes(q) &&
              !s.displayName.toLowerCase().includes(q)) {
        return false;
      }

      // 2. Domain filter - when at least one domain checkbox is ticked, show
      // only skills that match at least one selected domain OR are universal
      // (empty domains list = applies everywhere). When zero domains are
      // ticked, the filter is off and every skill passes.
      if (domainFilter.length > 0) {
        const isUniversal = s.domains.length === 0;
        const overlaps   = s.domains.some(d => domainFilter.includes(d));
        if (!isUniversal && !overlaps) return false;
      }

      return true;
    });
  }

  /**
   * Switch between the HARD and SOFT views. Resets all in-progress filter and
   * pagination state because they don't translate across tabs - someone who
   * typed "kaf" looking for Kafka shouldn't see the SOFT search apply to that
   * query when they switch tabs.
   */
  setViewType(t: 'HARD' | 'SOFT'): void {
    if (this.viewType === t) return;
    this.viewType = t;
    this.newSkillInput = '';
    this.newSkillDomains = [];
    this.newSkillSynonyms = [];
    this.synonymInput = '';
    this.currentPage = 0;
  }

  // ── Synonym chip input helpers ──────────────────────────────────────────

  /** Add the current text in the chip input to the pending synonyms list.
   *  Normalizes (lowercase + trim + collapse whitespace) and dedupes. Bound
   *  to Enter on the input. Splits on comma so the recruiter can paste
   *  "team lead, people management, directing" and get three chips. */
  addSynonymFromInput(): void {
    const raw = this.synonymInput;
    if (!raw || !raw.trim()) return;
    const parts = raw.split(',');
    for (const part of parts) {
      const norm = part.trim().toLowerCase().replace(/\s+/g, ' ');
      if (!norm) continue;
      if (norm === this.newSkillInput.trim().toLowerCase()) continue;
      if (!this.newSkillSynonyms.includes(norm)) {
        this.newSkillSynonyms.push(norm);
      }
    }
    this.synonymInput = '';
  }

  removeSynonym(syn: string): void {
    this.newSkillSynonyms = this.newSkillSynonyms.filter(s => s !== syn);
  }

  /** Ask Qwen (via cv-parser-service) to propose synonyms for the canonical
   *  name currently in the input. Recruiter reviews the suggestions as chips
   *  and edits/removes any they disagree with before saving. */
  async suggestSynonyms(): Promise<void> {
    const name = this.newSkillInput.trim();
    if (!name) {
      Swal.fire({
        icon: 'info',
        title: 'Type a skill name first',
        text: 'Enter the canonical name above, then click Suggest to get paraphrases.',
        timer: 2000, showConfirmButton: false,
      });
      return;
    }
    this.suggestingSynonyms = true;
    try {
      const suggested = await this.catalogService.suggestSynonyms(name, this.viewType);
      let added = 0;
      for (const s of suggested) {
        const norm = s.trim().toLowerCase();
        if (!norm || norm === name.toLowerCase()) continue;
        if (!this.newSkillSynonyms.includes(norm)) {
          this.newSkillSynonyms.push(norm);
          added++;
        }
      }
      if (added === 0) {
        Swal.fire({
          icon: 'info',
          title: 'No new suggestions',
          text: 'The LLM did not return any paraphrases beyond what you already have.',
          timer: 2200, showConfirmButton: false,
        });
      }
    } catch {
      Swal.fire({ icon: 'error', title: 'Failed to suggest synonyms.' });
    } finally {
      this.suggestingSynonyms = false;
    }
  }

  /** Slice of filteredSkills for the current page. */
  get pagedSkills(): CatalogItem[] {
    const start = this.currentPage * this.pageSize;
    return this.filteredSkills.slice(start, start + this.pageSize);
  }

  get totalPages(): number {
    return Math.max(1, Math.ceil(this.filteredSkills.length / this.pageSize));
  }

  /**
   * Exact-match check (case-insensitive) - disables the Add button when the
   * recruiter is about to add a skill that already exists in the catalog.
   * Looks at both the canonical key and the display name so they catch
   * "Kafka" vs "kafka" vs "KAFKA" as the same skill.
   */
  get isDuplicate(): boolean {
    const q = this.newSkillInput.trim().toLowerCase();
    if (!q) return false;
    // Only consider duplicates within the SAME viewType - a HARD skill and a
    // SOFT skill can share a name in theory (e.g. "leadership" could be both
    // a soft trait and a domain knowledge tag). In practice the LLM
    // classifier will reject the wrong-type one, but the UI doesn't preempt.
    return this.activeSkills.some(s =>
      s.type === this.viewType &&
      (s.name.toLowerCase() === q || s.displayName.toLowerCase() === q),
    );
  }

  /** True when the input has at least one character but no matches exist -
   *  this is the "OK to add" state where the row would be genuinely new. */
  get isNewName(): boolean {
    return this.newSkillInput.trim().length > 0 && !this.isDuplicate;
  }

  /** Reset pagination whenever the filter changes - bound to (ngModelChange)
   *  on the input. Without this, typing "kaf" while on page 4 would leave
   *  you stranded past the end of the filtered list. */
  onInputChange(): void {
    this.currentPage = 0;
  }

  previousPage(): void { if (this.currentPage > 0) this.currentPage--; }
  nextPage():     void { if (this.currentPage < this.totalPages - 1) this.currentPage++; }

  async ngOnInit(): Promise<void> { await this.load(); }

  private async load(): Promise<void> {
    this.loading = true;
    this.error = null;
    try {
      // Force a fresh fetch - recruiter wants to see current state, not a
      // cache that might be stale because a candidate page loaded it earlier.
      this.catalogService.invalidate();
      const snap = await this.catalogService.getSnapshot();
      this.activeSkills = snap.skills;

      // Removed skills come from a separate fetch on the manual catalog
      // (with includeRemoved=true). Stored as a name list so we don't need
      // their metadata for restoration - just the name.
      this.removedNames = await this.fetchRemovedNames();
    } catch (e) {
      this.error = 'Failed to load the skills catalog.';
    } finally {
      this.loading = false;
    }
  }

  private async fetchRemovedNames(): Promise<string[]> {
    try {
      return await this.catalogService.getRemovedDisplayNames();
    } catch {
      return [];
    }
  }

  /**
   * Detect compound skill names like "Communication & Public Speaking" or
   * "Initiative and Ownership". Returns the two parts if the name is a
   * compound, null otherwise. Splits on " & " or " and " (case-insensitive)
   * but only when both halves are non-empty after trimming - this avoids
   * false positives like "C&C" or trailing "& Co".
   *
   * Why this matters: each soft skill should have its own synonym universe
   * and its own job-requirement footprint. A row called
   * "Communication & Public Speaking" lumps the two together so the matcher
   * can't credit a candidate for one without the other and the synonym
   * list mixes paraphrases from two unrelated concepts.
   */
  private splitCompoundName(name: string): { left: string; right: string } | null {
    const match = name.match(/^(.+?)\s+(?:&|and)\s+(.+)$/i);
    if (!match) return null;
    const left  = match[1].trim();
    const right = match[2].trim();
    if (!left || !right) return null;
    return { left, right };
  }

  async addSkill(): Promise<void> {
    const name = this.newSkillInput.trim();
    if (!name) return;

    // Compound-name guard: "Communication & Public Speaking" / "Initiative
    // and Ownership" should be two separate catalog rows. Offer to auto-
    // split. Recruiter can also force-add as-is via the third button
    // (rare - e.g. "DevOps & CI/CD" might genuinely be one practice).
    const compound = this.splitCompoundName(name);
    if (compound) {
      const result = await Swal.fire({
        icon: 'warning',
        title: 'Looks like two skills',
        html:
          `<p>"<strong>${name}</strong>" reads as two skills joined together.</p>` +
          `<p style="margin-top:0.5rem;font-size:0.9rem;">Each one deserves its own row so:</p>` +
          `<ul style="text-align:left;margin:0.5rem auto;max-width:24rem;font-size:0.88rem;">` +
            `<li>each gets its own AI-suggested synonyms</li>` +
            `<li>recruiters can require just one on a job posting</li>` +
            `<li>candidates' CV phrases credit the right competency</li>` +
          `</ul>` +
          `<p style="margin-top:0.75rem;font-size:0.92rem;">Add as:</p>` +
          `<p style="font-weight:600;color:#1e40bc;">${compound.left} &nbsp;+&nbsp; ${compound.right}</p>`,
        showCancelButton: true,
        showDenyButton: true,
        confirmButtonText: 'Split into two',
        denyButtonText: 'Add as-is anyway',
        cancelButtonText: 'Cancel',
        reverseButtons: true,
      });

      if (result.isDismissed) return;          // Cancel
      if (result.isConfirmed) {                // Split into two
        await this.addPair(compound.left, compound.right);
        return;
      }
      // result.isDenied → fall through and add the compound as-is (escape hatch)
    }

    // Block duplicates with a clear error. The recruiter could land here via:
    //   • the Add button (still clickable so the action gives explicit feedback)
    //   • pressing Enter from the keyboard
    // Either way they get a red dialog explaining the conflict and pointing
    // at the table row that already has the skill.
    if (this.isDuplicate) {
      const existing = this.activeSkills.find(s =>
        s.name.toLowerCase() === name.toLowerCase() ||
        s.displayName.toLowerCase() === name.toLowerCase(),
      );
      await Swal.fire({
        icon: 'error',
        title: 'Skill already exists',
        html:
          `<p>"<strong>${name}</strong>" is already in the catalog and can't be added twice.</p>` +
          (existing
            ? `<p class="text-muted" style="margin-top:0.5rem;font-size:0.85rem;">` +
              `Existing entry: <strong>${existing.displayName}</strong>` +
              (existing.source === 'MANUAL' ? ' (added manually)' : ' (extracted from a job)') +
              `</p>`
            : ''),
        confirmButtonText: 'Got it',
      });
      return;
    }
    this.saving = true;
    try {
      // SOFT skills are universal across industries - never tag with domains.
      // The viewType drives both type and the domains-or-empty decision.
      const domains = this.viewType === 'SOFT' ? [] : this.newSkillDomains;
      // Flush any half-typed text in the synonym input before sending so the
      // recruiter doesn't lose unsubmitted chips.
      if (this.synonymInput.trim()) this.addSynonymFromInput();
      await this.catalogService.addManual(name, {
        type:    this.viewType,
        domains,
        synonyms: this.newSkillSynonyms.length > 0 ? this.newSkillSynonyms : undefined,
      });
      this.newSkillInput    = '';
      this.newSkillDomains  = [];
      this.newSkillSynonyms = [];
      this.synonymInput     = '';
      this.currentPage = 0;
      await this.load();
      Swal.fire({
        icon: 'success',
        title: `Added "${name}"`,
        timer: 1200,
        showConfirmButton: false,
      });
    } catch {
      Swal.fire({ icon: 'error', title: 'Failed to add skill.' });
    } finally {
      this.saving = false;
    }
  }

  /**
   * Add two skills at once - used when the compound-name guard splits
   * "Foo & Bar" into "Foo" + "Bar". Each row is created via the same
   * MANUAL flow as a single add, then auto-populated with AI synonyms so
   * the recruiter gets a useful starting point without two extra clicks.
   *
   * Failures on either half are surfaced together (we don't half-commit -
   * if the first one creates and the second errors, we still tell the user
   * about both outcomes so they can retry just the missing one).
   */
  private async addPair(left: string, right: string): Promise<void> {
    this.saving = true;
    const created: string[] = [];
    const failed: string[] = [];
    try {
      for (const part of [left, right]) {
        // Skip if it already exists - avoids the duplicate-error path.
        const dupe = this.activeSkills.some(s =>
          s.type === this.viewType &&
          (s.name.toLowerCase() === part.toLowerCase() ||
           s.displayName.toLowerCase() === part.toLowerCase()),
        );
        if (dupe) {
          failed.push(`${part} (already exists)`);
          continue;
        }
        try {
          // Ask the AI for synonyms BEFORE creating the row, so the row
          // lands with its synonym list already populated. Empty array on
          // LLM hiccup (we still create the row).
          const synonyms = await this.catalogService.suggestSynonyms(part, this.viewType);
          await this.catalogService.addManual(part, {
            type:     this.viewType,
            domains:  this.viewType === 'SOFT' ? [] : this.newSkillDomains,
            synonyms: synonyms.length > 0 ? synonyms : undefined,
          });
          created.push(part);
        } catch {
          failed.push(part);
        }
      }
      this.newSkillInput    = '';
      this.newSkillDomains  = [];
      this.newSkillSynonyms = [];
      this.synonymInput     = '';
      this.currentPage = 0;
      await this.load();

      // Combined feedback - whichever path the recruiter ends up in, they
      // see what landed and what didn't.
      if (created.length === 2 && failed.length === 0) {
        Swal.fire({
          icon: 'success',
          title: `Added "${created[0]}" and "${created[1]}"`,
          html: `<p>Each one got AI-suggested synonyms. Use the Synonyms button to edit.</p>`,
          timer: 2000, showConfirmButton: false,
        });
      } else if (created.length > 0) {
        Swal.fire({
          icon: 'warning',
          title: 'Partially added',
          html:
            `<p>Created: <strong>${created.join(', ')}</strong></p>` +
            `<p style="margin-top:0.4rem;">Skipped: <strong>${failed.join(', ')}</strong></p>`,
        });
      } else {
        Swal.fire({ icon: 'error', title: 'Failed to add either skill' });
      }
    } finally {
      this.saving = false;
    }
  }

  /**
   * Open an "Edit domains" dialog for an existing catalog row. The recruiter
   * checks/unchecks the domain boxes; on Save we PATCH the entry, which
   * persists across catalog refreshes (manual row wins over extracted tags).
   * Works for both auto-extracted and manual entries - a row will be created
   * if none exists yet.
   */
  async editSkill(item: CatalogItem): Promise<void> {
    // Build the checkbox list HTML - pre-check the domains the skill already has.
    const checkboxes = this.domainOptions.map(d => `
      <label class="hireai-edit-domain">
        <input type="checkbox" value="${d.value}"
               ${item.domains.includes(d.value) ? 'checked' : ''} />
        <span>${d.label}</span>
      </label>
    `).join('');

    const result = await Swal.fire({
      title: `Edit domains for "${item.displayName}"`,
      html: `
        <p style="margin:0 0 1rem;color:#cbd5e1;font-size:0.88rem;">
          Pick the domains this skill applies to. Uncheck everything to make
          it Universal (shown to every candidate).
        </p>
        <div id="edit-domains-list" style="display:flex;flex-direction:column;gap:0.55rem;text-align:left;">
          ${checkboxes}
        </div>
      `,
      showCancelButton: true,
      confirmButtonText: 'Save',
      cancelButtonText: 'Cancel',
      reverseButtons: true,
      focusConfirm: false,
      preConfirm: () => {
        const root = document.getElementById('edit-domains-list');
        if (!root) return [];
        return Array.from(root.querySelectorAll<HTMLInputElement>('input[type="checkbox"]:checked'))
          .map(cb => cb.value);
      },
    });

    if (!result.isConfirmed) return;
    const newDomains = (result.value || []) as string[];

    // Skip the network call if nothing actually changed - avoids a useless
    // round-trip and the toast spam.
    const changed = newDomains.length !== item.domains.length ||
                    newDomains.some(d => !item.domains.includes(d));
    if (!changed) return;

    try {
      await this.catalogService.updateSkillDomains(item.name, newDomains);
      await this.load();
      Swal.fire({
        icon: 'success',
        title: 'Domains updated',
        timer: 1200,
        showConfirmButton: false,
      });
    } catch {
      Swal.fire({ icon: 'error', title: 'Failed to update domains.' });
    }
  }

  /**
   * Edit the synonyms list on an existing SOFT skill. Opens a dialog where
   * the recruiter can review the chips, remove any, type new ones, or click
   * Suggest to ask the LLM for more. On Save we PATCH with the full
   * replacement list (empty list explicitly clears).
   */
  async editSynonyms(item: CatalogItem): Promise<void> {
    const initial = [...(item.synonyms || [])];
    const renderChip = (s: string) => `
      <span class="hireai-syn-chip" data-syn="${s}">
        ${s}
        <button type="button" class="hireai-syn-chip__x" data-remove="${s}" title="Remove">&times;</button>
      </span>
    `;

    const result = await Swal.fire({
      title: `Synonyms for "${item.displayName}"`,
      // All styling lives in swal2-hireai.css under the .hireai-syn-* classes
      // so the dialog adapts cleanly to both dark and light themes. Avoid
      // inline style="..." on these elements - they hardcode colors and
      // override the theme-aware rules.
      html: `
        <p class="hireai-syn-intro">
          Paraphrases that should resolve to <strong>${item.displayName}</strong>
          when a candidate's CV uses them. Press Enter or comma to add a chip.
          Click <em>Suggest</em> to ask the AI for more.
        </p>
        <div id="hireai-syn-chips" class="hireai-syn-host">
          ${initial.map(renderChip).join('')}
        </div>
        <div class="hireai-syn-input-row">
          <input id="hireai-syn-input" type="text"
                 class="hireai-syn-input"
                 placeholder="Type a paraphrase + Enter" />
          <button type="button" id="hireai-syn-suggest"
                  class="hireai-syn-suggest">
            Suggest
          </button>
        </div>
      `,
      showCancelButton: true,
      confirmButtonText: 'Save',
      cancelButtonText: 'Cancel',
      reverseButtons: true,
      focusConfirm: false,
      didOpen: () => {
        const chipsHost  = document.getElementById('hireai-syn-chips');
        const inputEl    = document.getElementById('hireai-syn-input') as HTMLInputElement | null;
        const suggestBtn = document.getElementById('hireai-syn-suggest');
        if (!chipsHost || !inputEl || !suggestBtn) return;

        const getChips = () => Array.from(chipsHost.querySelectorAll<HTMLElement>('[data-syn]'))
          .map(el => el.getAttribute('data-syn') || '');
        const setChips = (list: string[]) => {
          const seen = new Set<string>();
          const deduped = list.filter(s => {
            const n = (s || '').trim().toLowerCase();
            if (!n || seen.has(n)) return false;
            seen.add(n);
            return true;
          });
          chipsHost.innerHTML = deduped.map(renderChip).join('');
          chipsHost.querySelectorAll<HTMLButtonElement>('[data-remove]').forEach(btn => {
            btn.addEventListener('click', () => {
              const v = btn.getAttribute('data-remove');
              setChips(getChips().filter(s => s !== v));
            });
          });
        };
        setChips(initial);

        const submit = () => {
          const raw = inputEl.value || '';
          const parts = raw.split(',');
          const cur = getChips();
          for (const p of parts) {
            const n = p.trim().toLowerCase().replace(/\s+/g, ' ');
            if (n && n !== item.name.toLowerCase() && !cur.includes(n)) cur.push(n);
          }
          inputEl.value = '';
          setChips(cur);
        };
        inputEl.addEventListener('keydown', (ev) => {
          if (ev.key === 'Enter' || ev.key === ',') {
            ev.preventDefault();
            submit();
          }
        });

        suggestBtn.addEventListener('click', async () => {
          (suggestBtn as HTMLButtonElement).disabled = true;
          (suggestBtn as HTMLButtonElement).innerText = 'Asking AI…';
          try {
            const suggested = await this.catalogService.suggestSynonyms(item.displayName, item.type);
            const cur = getChips();
            const canonicalLc = item.name.toLowerCase();
            for (const s of suggested) {
              const n = (s || '').trim().toLowerCase();
              if (n && n !== canonicalLc && !cur.includes(n)) cur.push(n);
            }
            setChips(cur);
          } finally {
            (suggestBtn as HTMLButtonElement).disabled = false;
            (suggestBtn as HTMLButtonElement).innerText = 'Suggest';
          }
        });
      },
      preConfirm: () => {
        const chipsHost = document.getElementById('hireai-syn-chips');
        if (!chipsHost) return [];
        return Array.from(chipsHost.querySelectorAll<HTMLElement>('[data-syn]'))
          .map(el => el.getAttribute('data-syn') || '')
          .filter(s => s.length > 0);
      },
    });

    if (!result.isConfirmed) return;
    const newSynonyms = (result.value || []) as string[];

    const changed = newSynonyms.length !== initial.length ||
                    newSynonyms.some(s => !initial.includes(s));
    if (!changed) return;

    try {
      await this.catalogService.updateSkillSynonyms(item.name, newSynonyms);
      await this.load();
      Swal.fire({
        icon: 'success',
        title: 'Synonyms updated',
        timer: 1200,
        showConfirmButton: false,
      });
    } catch {
      Swal.fire({ icon: 'error', title: 'Failed to update synonyms.' });
    }
  }

  async removeSkill(item: CatalogItem): Promise<void> {
    // Block removal when the skill is actively required by published jobs.
    // Tombstoning would hide it from candidate Preferences while jobs still
    // demand it - silently breaking matching. The button is also disabled
    // in the template; this guards programmatic / keyboard activations.
    if (item.currentDemandCount > 0) {
      await Swal.fire({
        icon: 'info',
        title: 'Can\'t remove this skill',
        html: `<p><strong>${item.displayName}</strong> is currently required by ` +
              `<strong>${item.currentDemandCount}</strong> active job(s).</p>` +
              `<p>Close or update those jobs first, then come back to remove it.</p>`,
        confirmButtonText: 'OK',
      });
      return;
    }

    const confirmed = await Swal.fire({
      icon: 'warning',
      title: `Remove "${item.displayName}" from the catalog?`,
      html: `<p>Candidate profiles that already declared it stay intact, but new candidates won't be able to select it.</p>`,
      showCancelButton: true,
      confirmButtonText: 'Remove',
      cancelButtonText: 'Cancel',
      reverseButtons: true,
    });
    if (!confirmed.isConfirmed) return;

    try {
      await this.catalogService.removeFromCatalog(item.name);
      await this.load();
    } catch {
      Swal.fire({ icon: 'error', title: 'Failed to remove skill.' });
    }
  }

  async restoreSkill(name: string): Promise<void> {
    try {
      await this.catalogService.restoreInCatalog(name);
      await this.load();
    } catch {
      Swal.fire({ icon: 'error', title: 'Failed to restore skill.' });
    }
  }

  /** Pretty-format an ISO timestamp as "YYYY-MM-DD" for the table. */
  formatDate(iso: string | null): string {
    if (!iso) return '-';
    return iso.slice(0, 10);
  }
}
