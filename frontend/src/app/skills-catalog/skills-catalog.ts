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
 * Candidates never see this page — they consume the same catalog via the
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

  // Tombstoned entries — shown in a collapsible "Removed" section with a
  // Restore button. Stored separately so the main table stays focused on
  // what candidates actually see.
  removedNames: string[] = [];

  newSkillInput = '';
  // The Skills Catalog only curates HARD skills — soft skills stay as the
  // universal hardcoded list on the candidate Preferences page (deliberately
  // not editable here). This constant exists so the API contract still gets
  // an explicit type value.
  readonly newSkillType = 'HARD' as const;
  newSkillDomains: string[] = [];

  // ── Pagination state ────────────────────────────────────────────────────
  readonly pageSize = 10;
  currentPage = 0;

  // Canonical lists used by the Add form.
  readonly domainOptions = DOMAIN_OPTIONS;
  readonly domainLabel = domainLabel;

  // ── Derived stats for the header banner ─────────────────────────────────
  get totalSkillsCount(): number     { return this.activeSkills.length; }
  get inDemandCount(): number        { return this.activeSkills.filter(s => s.currentDemandCount > 0).length; }
  get universalCount(): number       { return this.activeSkills.filter(s => s.domains.length === 0).length; }
  get manualCount(): number          { return this.activeSkills.filter(s => s.source === 'MANUAL').length; }

  /**
   * Maps each canonical domain code to a stable accent color used for chip
   * styling. Picked by hand so each domain feels distinct at a glance —
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
  /** RGB triplet for inline `style` — falls back to brand blue. */
  accentFor(domain: string): string {
    return this.domainAccent[domain] ?? '121,164,233';
  }

  constructor(private catalogService: CatalogService) {}

  /** Toggle a domain in the "Add" form's domain multi-select. The checked
   *  set serves two purposes — when adding, it's the tag list for the new
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
   * containing "kaf" — making it obvious if "Kafka" already exists before
   * they add a duplicate. Case-insensitive substring match.
   * Empty input shows everything.
   */
  get filteredSkills(): CatalogItem[] {
    const q = this.newSkillInput.trim().toLowerCase();
    const domainFilter = this.newSkillDomains;

    return this.activeSkills.filter(s => {
      // 1. Text filter — skip rows that don't match the search box (when set).
      if (q && !s.name.toLowerCase().includes(q) &&
              !s.displayName.toLowerCase().includes(q)) {
        return false;
      }

      // 2. Domain filter — when at least one domain checkbox is ticked, show
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

  /** Slice of filteredSkills for the current page. */
  get pagedSkills(): CatalogItem[] {
    const start = this.currentPage * this.pageSize;
    return this.filteredSkills.slice(start, start + this.pageSize);
  }

  get totalPages(): number {
    return Math.max(1, Math.ceil(this.filteredSkills.length / this.pageSize));
  }

  /**
   * Exact-match check (case-insensitive) — disables the Add button when the
   * recruiter is about to add a skill that already exists in the catalog.
   * Looks at both the canonical key and the display name so they catch
   * "Kafka" vs "kafka" vs "KAFKA" as the same skill.
   */
  get isDuplicate(): boolean {
    const q = this.newSkillInput.trim().toLowerCase();
    if (!q) return false;
    return this.activeSkills.some(s =>
      s.name.toLowerCase() === q || s.displayName.toLowerCase() === q,
    );
  }

  /** True when the input has at least one character but no matches exist —
   *  this is the "OK to add" state where the row would be genuinely new. */
  get isNewName(): boolean {
    return this.newSkillInput.trim().length > 0 && !this.isDuplicate;
  }

  /** Reset pagination whenever the filter changes — bound to (ngModelChange)
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
      // Force a fresh fetch — recruiter wants to see current state, not a
      // cache that might be stale because a candidate page loaded it earlier.
      this.catalogService.invalidate();
      const snap = await this.catalogService.getSnapshot();
      this.activeSkills = snap.skills;

      // Removed skills come from a separate fetch on the manual catalog
      // (with includeRemoved=true). Stored as a name list so we don't need
      // their metadata for restoration — just the name.
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

  async addSkill(): Promise<void> {
    const name = this.newSkillInput.trim();
    if (!name) return;
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
      await this.catalogService.addManual(name, {
        type:    this.newSkillType,
        domains: this.newSkillDomains,
      });
      this.newSkillInput   = '';
      this.newSkillDomains = [];
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
   * Open an "Edit domains" dialog for an existing catalog row. The recruiter
   * checks/unchecks the domain boxes; on Save we PATCH the entry, which
   * persists across catalog refreshes (manual row wins over extracted tags).
   * Works for both auto-extracted and manual entries — a row will be created
   * if none exists yet.
   */
  async editSkill(item: CatalogItem): Promise<void> {
    // Build the checkbox list HTML — pre-check the domains the skill already has.
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

    // Skip the network call if nothing actually changed — avoids a useless
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

  async removeSkill(item: CatalogItem): Promise<void> {
    const confirmed = await Swal.fire({
      icon: 'warning',
      title: `Remove "${item.displayName}" from the catalog?`,
      html: item.currentDemandCount > 0
        ? `<p>This skill is currently required by <strong>${item.currentDemandCount}</strong> active job(s).</p>` +
          `<p>Candidate profiles that already declared it stay intact, but new candidates won't be able to select it.</p>`
        : `<p>Candidate profiles that already declared it stay intact, but new candidates won't be able to select it.</p>`,
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
    if (!iso) return '—';
    return iso.slice(0, 10);
  }
}
