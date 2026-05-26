/**
 * The canonical list of soft skills the candidate can declare. Lives in one
 * place so:
 *   • preferences.ts and onboarding.ts both import the same list (no drift),
 *   • catalog.service.ts can use it as a denylist when building the HARD-skill
 *     chip grid (so a recruiter writing "Leadership and communication" inside
 *     a SKILL requirement doesn't pollute hard-skills with soft-skill names).
 */
export const SOFT_SKILLS: readonly string[] = [
  'Communication', 'Leadership', 'Teamwork', 'Problem Solving', 'Time Management',
  'Adaptability', 'Critical Thinking', 'Creativity', 'Attention to Detail',
  'Conflict Resolution', 'Negotiation', 'Presentation Skills', 'Emotional Intelligence',
  'Decision Making', 'Autonomy',
];

/** Lowercase form for fast membership lookups. */
export const SOFT_SKILLS_NORMALIZED: ReadonlySet<string> = new Set(
  SOFT_SKILLS.map(s => s.toLowerCase()),
);
