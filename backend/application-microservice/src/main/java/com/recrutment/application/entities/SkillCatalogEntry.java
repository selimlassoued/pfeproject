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
}
