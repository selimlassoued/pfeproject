package com.recrutment.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * Shape returned to the frontend by the Skill Catalog endpoints. Combines
 * the persisted entry with a per-render `currentDemandCount` computed at
 * request time (how many PUBLISHED jobs currently mention the skill).
 *
 *   • firstSeenAt          → drives the ⭐NEW badge (compared to candidate's
 *                            lastPreferencesAcknowledgedAt)
 *   • currentDemandCount   → drives the 🔥 in-demand badge (0 means dormant)
 *   • source ("EXTRACTED" | "MANUAL")
 *                          → shown on the recruiter admin page (✋ icon for
 *                            manual entries) but not surfaced to candidates
 *   • removed              → tombstoned entries — only the admin page sees
 *                            them, candidates never do
 */
public record SkillCatalogDto(
        String name,            // canonical lowercase form, stable key
        String displayName,     // pretty form for UI
        Instant firstSeenAt,
        Integer currentDemandCount,
        String source,          // "EXTRACTED" | "MANUAL"
        boolean removed,
        String type,            // "HARD" | "SOFT" — drives which chip grid renders it
        List<String> domains,   // SOFTWARE_ENGINEERING / FINANCE_BANKING / etc.
                                // empty list = universal (shown to every domain)
        List<String> synonyms   // recruiter-curated paraphrases / aliases
                                // primary use: SOFT skills, where CV phrasing
                                // varies wildly ("team lead" → "leadership").
                                // exact lookup on this list short-circuits the
                                // embedding cascade. always lowercase.
) {}
