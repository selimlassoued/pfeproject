package com.recrutment.application.entities;

import com.recrutment.application.converters.StringListConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One row per skill name that's ever been considered selectable by candidates.
 * The catalog grows monotonically — once a skill enters, it stays forever
 * (rows can be soft-removed via the `removed` flag, but never hard-deleted).
 *
 * Two ways a row appears:
 *   • source = EXTRACTED — auto-populated by the catalog refresh job when a
 *     recruiter publishes / saves a job whose requirements mention the skill.
 *   • source = MANUAL    — recruiter explicitly added it via the Skills Catalog
 *     admin page (lets them pre-add skills they anticipate needing even before
 *     any job posts that skill).
 *
 * The `removed` flag is a tombstone — when a recruiter cleans up a typo (e.g.,
 * "Hibrnate") we mark removed=true so future job postings can't resurrect the
 * bad name via auto-extraction. Candidates with the removed skill on their
 * profile keep their data untouched; the skill just stops appearing in chip
 * grids going forward.
 */
@Entity
@Table(name = "skill_catalog_entry")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkillCatalogEntry {

    @Id
    @Column(name = "name", length = 120)
    private String name;          // canonical lowercase form

    @Column(name = "display_name", length = 120)
    private String displayName;   // user-facing form, e.g. "Spring Boot"

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "source", length = 20, nullable = false)
    private String source;        // "EXTRACTED" | "MANUAL"

    @Column(name = "removed", nullable = false)
    @Builder.Default
    private boolean removed = false;

    @Column(name = "removed_at")
    private Instant removedAt;

    /**
     * HARD or SOFT. Hard skills (Java, Kafka, Spring Boot) are domain-tied
     * technical abilities; soft skills (Communication, Cross-cultural
     * collaboration) are universal-ish behaviors. The Preferences page
     * renders the two in separate sections so the candidate's chip grid
     * stays organized. Auto-extracted skills default to HARD because they
     * come from SKILL-category job requirements which are technical by
     * convention; recruiters can flip to SOFT manually via the admin page.
     */
    @Column(name = "type", length = 10, nullable = false)
    @Builder.Default
    private String type = "HARD";

    /**
     * Domains the skill is relevant to — accumulated from every job that
     * mentioned it. A back-end Java skill might be tagged with both
     * SOFTWARE_ENGINEERING and FINANCE_BANKING if both kinds of teams hire
     * for it. Empty list = universal (no domain filter applied, shown to
     * everyone). The frontend filters the candidate chip grid by their
     * selected domain: `skill.domains.includes(candidate.domain) || skill.domains.isEmpty()`.
     */
    @Convert(converter = StringListConverter.class)
    @Column(name = "domains", columnDefinition = "text")
    @Builder.Default
    private List<String> domains = new ArrayList<>();

    // ─── Skill intel: embedding + volatility + implies ───────────────────────
    // Stored alongside the catalog row so every fact about a skill (canonical
    // name, embedding, volatility, related skills, recency) lives in ONE place.
    // Replaces the in-container skill_intel_cache.json which didn't survive
    // rebuilds and lived separately from the catalog.

    /**
     * 768-dim nomic-embed-text embedding of the skill name. Used for:
     *   - matcher cosine similarity at scoring time (skill vs cv_skill)
     *   - typo detection when adding new skills (sim ≥ 0.95 → auto-merge)
     *   - similar-skill suggestions in admin / candidate UIs.
     *
     * insertable=false, updatable=false: Hibernate's generic INSERT/UPDATE
     * binds Strings as varchar and Postgres won't auto-cast varchar to vector.
     * Written through a native UPDATE with an explicit ::vector cast.
     */
    @Column(name = "embedding", columnDefinition = "vector(768)", insertable = false, updatable = false)
    private String embedding;

    /** Embedding model that produced the vector. Lets us invalidate on upgrade. */
    @Column(name = "embedding_model", length = 64, insertable = false, updatable = false)
    private String embeddingModel;

    /**
     * Volatility score 1-10 from the LLM. Higher = faster-aging tech.
     * Drives the matcher's recency penalty for old CV evidence. Null means
     * "not yet classified — fall back to the hardcoded dict or default."
     */
    @Column(name = "volatility")
    private Integer volatility;

    /**
     * Half-life in years derived from volatility. Denormalized for query
     * speed so the matcher doesn't recompute the exponential every call.
     * Formula: max(1.5, 2 ^ ((10 - volatility) / 3))
     */
    @Column(name = "half_life")
    private Double halfLife;

    /**
     * Other skills this one implies — e.g. "spring boot" implies "java".
     * Stored as JSON text via the existing StringListConverter so the catalog
     * controller can return it as a normal list without a join table.
     * Used by the matcher's framework→stack credit ("Angular declared →
     * TypeScript/HTML/CSS credited at declared tier").
     */
    @Convert(converter = StringListConverter.class)
    @Column(name = "implies", columnDefinition = "text")
    @Builder.Default
    private List<String> implies = new ArrayList<>();

    /**
     * Recruiter-curated synonyms / aliases for this skill — alternative phrasings
     * that should resolve to this canonical entry without going through the
     * embedding layer.
     *
     * Primary use: SOFT skills. "leadership" might list ["team lead",
     * "people management", "directing teams", "managing people"]. When CV
     * text says "team lead", /match-soft-skill checks this map first and
     * returns the canonical "leadership" with score=1.0 — no embedder, no
     * gray-band guessing. Catches the paraphrase failures that cosine
     * similarity (capped around 0.70 for abstract concepts) misses.
     *
     * Stored as JSON text via the existing StringListConverter, lowercase-
     * normalized at write time so lookups are case-insensitive without
     * runtime work.
     */
    @Convert(converter = StringListConverter.class)
    @Column(name = "synonyms", columnDefinition = "text")
    @Builder.Default
    private List<String> synonyms = new ArrayList<>();

    /**
     * When the matcher (or admin) last touched this row. Different from
     * `first_seen_at` — that's set once at creation. This bumps every time
     * the embedding is read or the skill appears on a job/CV at match time.
     * Lets the catalog admin sort by activity and tombstone genuinely-stale
     * skills.
     */
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
}
