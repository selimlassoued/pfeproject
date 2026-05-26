import { Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { JobService } from './job.service';
import type { JobOffer } from '../model/jobOffer.model';

/**
 * The universe of languages this app knows how to handle. We never extract a
 * language name from a job requirement unless it appears in this list — the
 * list acts as a safety net that filters out typos and unrelated tokens.
 *
 * Adding a new language here makes it eligible to surface in the candidate
 * dropdown as soon as a recruiter mentions it in any LANGUAGE requirement.
 * Removing one hides it from candidate UIs (existing saved entries on
 * candidate profiles remain visible but the language won't be re-selectable).
 */
export const LANGUAGE_WHITELIST: readonly string[] = [
  // Tunisia + business defaults
  'Arabic', 'French', 'English',
  // Common European languages used by VERMEG's potential client base
  'German', 'Spanish', 'Italian', 'Portuguese', 'Dutch',
  'Polish', 'Russian', 'Turkish', 'Swedish', 'Norwegian',
  // World languages that occasionally show up on tech CVs
  'Mandarin', 'Japanese', 'Korean', 'Hindi', 'Urdu',
];

/**
 * Spelling variants that recruiters actually write in job requirements but
 * which would miss a strict `"Italian".toLowerCase()` substring match.
 * Each canonical English name maps to a list of known spellings in French,
 * the native language, and common abbreviations. When ANY variant is found
 * in a requirement description, the canonical English name is used in the
 * candidate dropdown so spellings stay consistent across the app.
 *
 * Add a variant here whenever you see a recruiter write a language differently
 * (e.g. "Italien" for Italian, "Espagnol" for Spanish) and it doesn't surface.
 */
const LANGUAGE_ALIASES: Record<string, readonly string[]> = {
  Arabic:     ['arabic', 'arabe', 'العربية'],
  French:     ['french', 'francais', 'français', 'fr'],
  English:    ['english', 'anglais', 'en'],
  German:     ['german', 'allemand', 'deutsch', 'de'],
  Spanish:    ['spanish', 'espagnol', 'español', 'espanol', 'castellano', 'es'],
  Italian:    ['italian', 'italien', 'italiano', 'it'],
  Portuguese: ['portuguese', 'portugais', 'português', 'portugues', 'pt'],
  Dutch:      ['dutch', 'néerlandais', 'neerlandais', 'nederlands'],
  Polish:     ['polish', 'polonais', 'polski'],
  Russian:    ['russian', 'russe', 'русский'],
  Turkish:    ['turkish', 'turc', 'türkçe', 'turkce'],
  Swedish:    ['swedish', 'suédois', 'suedois', 'svenska'],
  Norwegian:  ['norwegian', 'norvégien', 'norvegien', 'norsk'],
  Mandarin:   ['mandarin', 'chinese', 'chinois', '中文'],
  Japanese:   ['japanese', 'japonais', '日本語'],
  Korean:     ['korean', 'coréen', 'coreen', '한국어'],
  Hindi:      ['hindi', 'हिन्दी'],
  Urdu:       ['urdu', 'اردو'],
};

/**
 * Languages that are always offered in the candidate dropdown, even before
 * the user has loaded a single job. They represent the Tunisian cultural
 * baseline — every candidate's CV mentions some combination of these three
 * and forcing them to always appear means the dropdown never feels empty.
 */
export const ALWAYS_SHOWN_LANGUAGES: readonly string[] = ['Arabic', 'French', 'English'];

/**
 * The full canonical list of languages the app supports — exposed for screens
 * that need it synchronously (e.g. the recruiter's add-job / update-job pages,
 * where a LANGUAGE requirement is picked from a strict dropdown so it's
 * stored canonically in the DB and the candidate side never has to deal with
 * "Italien" vs "Italian"). Always-shown trio is ordered first, the rest is
 * alphabetical. Derived from LANGUAGE_ALIASES above — single source of truth.
 */
export function getCanonicalLanguages(): string[] {
  const always = ALWAYS_SHOWN_LANGUAGES;
  const others = Object.keys(LANGUAGE_ALIASES)
    .filter(l => !always.includes(l))
    .sort((a, b) => a.localeCompare(b));
  return [...always, ...others];
}

/**
 * Map a legacy / non-canonical language string (e.g. "Italien", "Anglais B2",
 * "FR") to its canonical English name (e.g. "Italian", "English", "French").
 * Returns null when no alias matches — caller decides whether to keep the
 * raw value, clear the field, or warn the user.
 *
 * Used when loading an existing job into the update-job form so a recruiter
 * who previously saved "Italien" sees "Italian" pre-selected in the strict
 * dropdown instead of an empty selector.
 */
export function canonicalizeLanguage(input: string | null | undefined): string | null {
  if (!input) return null;
  const haystack = input.toLowerCase();
  for (const [canonical, aliases] of Object.entries(LANGUAGE_ALIASES)) {
    if (aliases.some(a => haystack.includes(a))) return canonical;
  }
  return null;
}

/**
 * Resolves the list of languages to offer in the candidate-side dropdown.
 *
 * Result = ALWAYS_SHOWN ∪ (LANGUAGE_WHITELIST ∩ "language names mentioned in
 * any current job's LANGUAGE requirement description").
 *
 * Cached after the first successful fetch so Preferences and Onboarding share
 * one result per page load — there's no value in re-scanning jobs every time
 * the user opens the language dropdown.
 */
@Injectable({ providedIn: 'root' })
export class LanguageOptionsService {
  private cache: string[] | null = null;
  private inFlight: Promise<string[]> | null = null;

  constructor(private jobService: JobService) {}

  async getAvailableLanguages(): Promise<string[]> {
    if (this.cache) return this.cache;
    if (this.inFlight) return this.inFlight;

    this.inFlight = this.computeFromJobs()
      .then(list => { this.cache = list; this.inFlight = null; return list; })
      .catch(() => {
        // If the jobs fetch fails for any reason, degrade to the always-shown
        // baseline so the candidate UI keeps working.
        this.inFlight = null;
        return [...ALWAYS_SHOWN_LANGUAGES];
      });
    return this.inFlight;
  }

  /** Force a re-extract from jobs (e.g. after a recruiter posts a new job). */
  invalidate(): void {
    this.cache = null;
  }

  private async computeFromJobs(): Promise<string[]> {
    const jobs = await firstValueFrom(this.jobService.getAllJobs());
    const extracted = new Set<string>(ALWAYS_SHOWN_LANGUAGES);

    for (const job of (jobs as JobOffer[] | undefined) || []) {
      // Only mine PUBLISHED jobs — DRAFTs and CLOSEDs don't reflect current need.
      if (job.jobStatus && job.jobStatus !== 'PUBLISHED') continue;
      const reqs = job.requirements ?? [];
      for (const req of reqs) {
        const cat = (req.category || '').toUpperCase();
        if (cat !== 'LANGUAGE' && cat !== 'LANGUE' && cat !== 'LANGUAGES') continue;
        const text = (req.description || '').toLowerCase();
        if (!text) continue;
        // Alias-aware match: each canonical English name has a list of known
        // spellings (English/French/native/code). If ANY alias appears in the
        // requirement text, we add the canonical name to the extracted set.
        // That's how "Italien" in a recruiter's posting surfaces as "Italian"
        // in the candidate dropdown — same selection, one canonical spelling.
        for (const canonical of LANGUAGE_WHITELIST) {
          const aliases = LANGUAGE_ALIASES[canonical] ?? [canonical.toLowerCase()];
          if (aliases.some(alias => text.includes(alias))) {
            extracted.add(canonical);
          }
        }
      }
    }

    // Sort with ALWAYS_SHOWN first (in declared order), rest alphabetical so
    // Arabic/French/English stay at the top of the dropdown.
    const always = ALWAYS_SHOWN_LANGUAGES.filter(l => extracted.has(l));
    const others = [...extracted]
      .filter(l => !ALWAYS_SHOWN_LANGUAGES.includes(l))
      .sort((a, b) => a.localeCompare(b));
    return [...always, ...others];
  }
}
