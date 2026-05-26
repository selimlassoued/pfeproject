/**
 * Canonical business-domain list used by:
 *   • the job-creation form (recruiter picks one per job),
 *   • the candidate Preferences page (candidate picks one for themselves),
 *   • the Skills Catalog admin page (recruiter tags manual skills),
 *   • the chip-grid filter (skill shown only if its domains include the
 *     candidate's chosen domain, or it has no domain tags = universal).
 *
 * Adding a new domain here propagates everywhere — recruiter dropdowns,
 * candidate dropdowns, admin tagging, and matcher behavior all stay in sync.
 */
export interface DomainOption {
  value: string;          // canonical enum value stored in DB (UPPER_SNAKE)
  label: string;          // user-facing display name
}

export const DOMAIN_OPTIONS: readonly DomainOption[] = [
  { value: 'SOFTWARE_ENGINEERING', label: 'Software Engineering' },
  { value: 'FINANCE_BANKING',      label: 'Finance & Banking' },
  { value: 'INSURANCE',            label: 'Insurance' },
  { value: 'PROJECT_MANAGEMENT',   label: 'Project Management' },
  { value: 'QUALITY_ASSURANCE',    label: 'Quality Assurance' },
  { value: 'BUSINESS_ANALYSIS',    label: 'Business Analysis' },
];

/** Quick lookup helper — `domainLabel('FINANCE_BANKING')` → 'Finance & Banking'. */
export function domainLabel(value: string | null | undefined): string {
  if (!value) return '';
  const hit = DOMAIN_OPTIONS.find(d => d.value === value);
  return hit ? hit.label : value;
}
