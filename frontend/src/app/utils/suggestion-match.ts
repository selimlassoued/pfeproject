/**
 * Word-start match for typeahead suggestions.
 *
 * Splits the label on whitespace and common punctuation, then returns
 * true when any resulting word starts with the query. This is the
 * standard typeahead behavior people expect: typing "A" hits
 * "AI Engineer" and "Frontend Developer (Angular)" (via "Angular"),
 * but not "Local" (whose only word starts with "L").
 *
 * Empty query matches everything so callers can use the same getter
 * for the unfiltered baseline.
 */
export function matchesWordStart(label: string, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  const tokens = label.toLowerCase().split(/[\s,()|\-/]+/).filter(Boolean);
  return tokens.some(t => t.startsWith(q));
}
