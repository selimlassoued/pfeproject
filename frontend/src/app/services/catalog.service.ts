import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { JobService } from './job.service';
import { LANGUAGE_WHITELIST, ALWAYS_SHOWN_LANGUAGES } from './language-options.service';
import { SOFT_SKILLS_NORMALIZED } from './soft-skills';
import type { JobOffer } from '../model/jobOffer.model';

/**
 * Skills + languages the candidate can pick from on Preferences / Onboarding.
 *
 * The catalog has TWO sources merged together:
 *
 *   1. Auto-extracted from current jobs - cv-parser-service runs the existing
 *      Python skill parser over every PUBLISHED + CLOSED job's requirements
 *      and returns the distinct skills + languages along with first-seen and
 *      current-demand metadata.
 *
 *   2. Manual entries - application-microservice owns a small table
 *      `skill_catalog_entry` where recruiters can pre-add skills they
 *      anticipate needing, or tombstone skills they want to remove (typos,
 *      retired tech). Tombstones override extracted entries so cleanup sticks.
 *
 * The frontend pulls both, merges them, and emits a single sorted list:
 *   - 🔥 in-demand + ⭐NEW   (just added + currently being hired for)
 *   - 🔥 in-demand
 *   - ⭐NEW                  (added since the candidate's last acknowledge)
 *   - everything else
 *
 * Within each tier, sort alphabetically. Tombstoned entries are filtered out
 * entirely - candidates never see them.
 */
export interface CatalogItem {
  name: string;                       // canonical lowercase key
  displayName: string;                // user-facing name
  firstSeenAt: string | null;         // ISO 8601
  currentDemandCount: number;         // # of PUBLISHED jobs mentioning it
  source: 'EXTRACTED' | 'MANUAL';
  type: 'HARD' | 'SOFT';              // drives which chip grid renders it
  domains: string[];                  // SOFTWARE_ENGINEERING / ... ; [] = universal
}

export interface CatalogSnapshot {
  skills: CatalogItem[];
  languages: CatalogItem[];
}

interface ExtractedResponse {
  skills:    Array<{ name: string; display_name: string; first_seen_at: string | null; current_demand_count: number; source: string; domains: string[] }>;
  languages: Array<{ name: string; display_name: string; first_seen_at: string | null; current_demand_count: number; source: string; domains: string[] }>;
}

interface ManualEntry {
  name: string;
  displayName: string;
  firstSeenAt: string | null;
  currentDemandCount: number | null;
  source: 'EXTRACTED' | 'MANUAL';
  removed: boolean;
  type?: 'HARD' | 'SOFT';
  domains?: string[];
}

@Injectable({ providedIn: 'root' })
export class CatalogService {
  private snapshotCache: CatalogSnapshot | null = null;
  private inFlight: Promise<CatalogSnapshot> | null = null;

  private readonly cvParserUrl = 'http://localhost:8085/api/cv-parser/extract-catalog';
  private readonly catalogUrl  = 'http://localhost:8888/api/applications/skill-catalog';

  constructor(private http: HttpClient, private jobService: JobService) {}

  async getSnapshot(): Promise<CatalogSnapshot> {
    if (this.snapshotCache) return this.snapshotCache;
    if (this.inFlight)      return this.inFlight;

    // Build the in-flight promise as `Promise<CatalogSnapshot>` explicitly so
    // both branches (resolved snapshot + degraded fallback) satisfy the type
    // and so TS narrows `this.inFlight` correctly after the assignment.
    const promise: Promise<CatalogSnapshot> = this.computeSnapshot()
      .then(snap => { this.snapshotCache = snap; this.inFlight = null; return snap; })
      .catch((): CatalogSnapshot => {
        // Degrade to the always-shown languages and an empty skill list so
        // the chip grid still renders something on hard failure. Each item
        // must be a complete CatalogItem now that type + domains are required.
        this.inFlight = null;
        return {
          skills: [],
          languages: ALWAYS_SHOWN_LANGUAGES.map(l => ({
            name: l.toLowerCase(),
            displayName: l,
            firstSeenAt: null,
            currentDemandCount: 0,
            source: 'EXTRACTED' as const,
            type: 'HARD' as const,
            domains: [] as string[],
          })),
        };
      });
    this.inFlight = promise;
    return promise;
  }

  /** Force a re-fetch - recruiter just added/removed a manual entry, for
   *  example, or the candidate just opened /preferences and we want the
   *  freshest catalog state. Clears BOTH the resolved cache and any
   *  in-flight promise (the latter matters when another component already
   *  triggered a fetch this session - that promise's result is now stale
   *  and the next getSnapshot must start a brand-new fetch). */
  invalidate(): void {
    this.snapshotCache = null;
    this.inFlight = null;
  }

  private async computeSnapshot(): Promise<CatalogSnapshot> {
    // Three parallel calls: jobs (to feed the extractor), manual catalog,
    // and the candidate profile (for the acknowledged timestamp - but that
    // belongs to the consumer, not this service).
    const [jobs, manualEntries] = await Promise.all([
      firstValueFrom(this.jobService.getAllJobs()),
      this.fetchManualCatalog(),
    ]);

    const extracted = await this.fetchExtracted(jobs as JobOffer[]);

    // Build a tombstone set: any manual entry with removed=true blocks the
    // matching skill name from showing up regardless of extraction.
    const tombstones = new Set<string>(
      manualEntries.filter(e => e.removed).map(e => e.name.toLowerCase()),
    );

    // ── Skills: extracted ∪ manual, minus tombstones, minus soft-skill /
    //    generic-token pollution ────────────────────────────────────────────
    // Why filter: recruiters often write soft skills ("Leadership",
    // "Communication") and generic tokens ("Analysis", "Methodology",
    // "Tracking", "Data", "KPI") inside SKILL-category requirements.
    // The Python parser extracts them without classifying, so we strip them
    // here on the way into the HARD-skill chip grid. They're shown in the
    // separate Soft Skills section, so we never lose them - we just stop them
    // from polluting hard skills.
    const HARD_SKILL_DENYLIST = new Set<string>([
      ...SOFT_SKILLS_NORMALIZED,
      // Generic tokens that aren't real skills on their own - they show up
      // when recruiters write things like "Strong analysis skills" or "Track
      // KPIs" inside a SKILL requirement.
      'analysis', 'methodology', 'tracking', 'data', 'kpi', 'kpis',
      'management', 'organization', 'planning', 'reporting',
    ]);

    const skillMap = new Map<string, CatalogItem>();
    for (const e of extracted.skills) {
      const key = e.name.toLowerCase();
      if (tombstones.has(key)) continue;
      if (HARD_SKILL_DENYLIST.has(key)) continue;
      skillMap.set(key, {
        name: key,
        displayName: e.display_name,
        firstSeenAt: e.first_seen_at,
        currentDemandCount: e.current_demand_count,
        source: 'EXTRACTED',
        type: 'HARD',          // extracted from SKILL-category job requirements
        domains: e.domains || [],
      });
    }
    for (const m of manualEntries) {
      if (m.removed) continue;
      const key = m.name.toLowerCase();
      const existing = skillMap.get(key);
      if (existing) {
        // Both extracted AND manual rows exist. The manual row is the
        // recruiter's curated source of truth - its domain list REPLACES the
        // extracted union (so a recruiter can actually REMOVE a domain tag
        // without it being added back by the next extraction). We keep the
        // EXTRACTED source label so the recruiter still knows the skill came
        // from a job, only swap source to MANUAL when there's no extraction.
        skillMap.set(key, {
          ...existing,
          type: m.type || existing.type,
          domains: m.domains && m.domains.length > 0 ? m.domains : existing.domains,
        });
      } else {
        skillMap.set(key, {
          name: key,
          displayName: m.displayName,
          firstSeenAt: m.firstSeenAt,
          currentDemandCount: m.currentDemandCount ?? 0,
          source: 'MANUAL',
          type: m.type || 'HARD',
          domains: m.domains || [],
        });
      }
    }

    // ── Languages: extracted only (no manual layer for languages) plus
    //    the always-shown trio guaranteed by the LANGUAGE_WHITELIST. The
    //    trio gets firstSeenAt=null so they never show ⭐NEW (correct -
    //    they've always been there).
    const langMap = new Map<string, CatalogItem>();
    for (const l of ALWAYS_SHOWN_LANGUAGES) {
      langMap.set(l.toLowerCase(), {
        name: l.toLowerCase(),
        displayName: l,
        firstSeenAt: null,
        currentDemandCount: 0,
        source: 'EXTRACTED',
        type: 'HARD',
        domains: [],
      });
    }
    for (const e of extracted.languages) {
      const key = e.name.toLowerCase();
      const existing = langMap.get(key);
      // If this language is in the always-shown set, only override
      // currentDemandCount/firstSeenAt if they're actually populated.
      if (existing && existing.firstSeenAt === null) {
        langMap.set(key, {
          ...existing,
          firstSeenAt: e.first_seen_at ?? null,
          currentDemandCount: e.current_demand_count,
          domains: e.domains || [],
        });
      } else {
        langMap.set(key, {
          name: key,
          displayName: e.display_name,
          firstSeenAt: e.first_seen_at,
          currentDemandCount: e.current_demand_count,
          source: 'EXTRACTED',
          type: 'HARD',
          domains: e.domains || [],
        });
      }
    }

    return {
      skills:    [...skillMap.values()].sort((a, b) => a.displayName.localeCompare(b.displayName)),
      languages: [...langMap.values()].sort((a, b) => {
        // Always-shown trio first, then alphabetical
        const ai = ALWAYS_SHOWN_LANGUAGES.indexOf(a.displayName as any);
        const bi = ALWAYS_SHOWN_LANGUAGES.indexOf(b.displayName as any);
        if (ai !== -1 && bi !== -1) return ai - bi;
        if (ai !== -1) return -1;
        if (bi !== -1) return 1;
        return a.displayName.localeCompare(b.displayName);
      }),
    };
  }

  private async fetchExtracted(jobs: JobOffer[]): Promise<ExtractedResponse> {
    const payload = {
      jobs: (jobs || []).map(j => ({
        id: j.id,
        created_at: (j as any).createdAt ?? null,
        job_status: j.jobStatus ?? null,
        domain:     (j as any).domain ?? null,
        requirements: (j.requirements || []).map(r => ({
          category:    r.category,
          description: r.description,
          skill_level: (r as any).skillLevel ?? null,
        })),
      })),
    };
    return firstValueFrom(this.http.post<ExtractedResponse>(this.cvParserUrl, payload));
  }

  private async fetchManualCatalog(): Promise<ManualEntry[]> {
    try {
      return await firstValueFrom(this.http.get<ManualEntry[]>(`${this.catalogUrl}?includeRemoved=true`));
    } catch (err) {
      // Log the error so silent failures become visible. We still return []
      // as a graceful fallback so the candidate UI keeps rendering - the
      // recruiter's manual entries are absent until the issue is fixed.
      console.error('[CatalogService] Manual catalog fetch failed:', err);
      return [];
    }
  }

  // ── Recruiter-only mutations (used by the admin page) ─────────────────────

  async addManual(name: string, opts?: { type?: 'HARD' | 'SOFT'; domains?: string[] }): Promise<void> {
    const body: Record<string, unknown> = { name };
    if (opts?.type)    body['type']    = opts.type;
    if (opts?.domains) body['domains'] = opts.domains;
    await firstValueFrom(this.http.post(this.catalogUrl, body));
    this.invalidate();
  }

  async removeFromCatalog(name: string): Promise<void> {
    await firstValueFrom(this.http.delete(`${this.catalogUrl}/${encodeURIComponent(name)}`));
    this.invalidate();
  }

  async restoreInCatalog(name: string): Promise<void> {
    await firstValueFrom(this.http.post(`${this.catalogUrl}/${encodeURIComponent(name)}/restore`, {}));
    this.invalidate();
  }

  /**
   * Replace the domain tags on an existing skill. Use this when the recruiter
   * wants to change the classification of a skill that's already in the
   * catalog - empty `domains` makes it Universal again, a populated list
   * narrows it to those domains. Persists across catalog refreshes because
   * the manual row in `skill_catalog_entry` overrides extracted domains.
   */
  async updateSkillDomains(name: string, domains: string[]): Promise<void> {
    await firstValueFrom(this.http.patch(
      `${this.catalogUrl}/${encodeURIComponent(name)}`,
      { domains },
    ));
    this.invalidate();
  }

  /** Returns just the display names of tombstoned skills - used by the
   *  recruiter admin page to show a "Removed skills" section with Restore
   *  buttons. Skipped on candidate flows. */
  async getRemovedDisplayNames(): Promise<string[]> {
    const all = await this.fetchManualCatalog();
    return all
      .filter(e => e.removed)
      .map(e => e.displayName || e.name);
  }
}
