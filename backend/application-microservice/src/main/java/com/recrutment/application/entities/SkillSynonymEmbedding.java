package com.recrutment.application.entities;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Per-phrase embedding row for the SOFT skill multi-vector retrieval index.
 *
 * Why this exists: the original skill_catalog_entry has ONE embedding per
 * row (the canonical name only). Empirical measurement on nomic-embed-text
 * showed that synonyms of a soft skill embed only at cosine 0.58-0.73 to
 * their canonical (not "essentially the same vector" as a naive read would
 * suggest), and CV paraphrases that don't share tokens with the canonical
 * miss the 0.70 match threshold consistently. The fix is multi-vector
 * retrieval: index the canonical AND each curated synonym as its own
 * vector, and at match time take MAX score across all phrases of each
 * skill. This is the ColBERT-style pattern.
 *
 * The table is SOFT-only by convention. HARD skills already have a
 * 5-layer resolve cascade (abbreviation, Levenshtein, LLM tiebreaker)
 * that handles paraphrase variation through other means; adding a second
 * vector store for them would be redundant.
 *
 * Schema notes:
 *   - composite PK (skill_name, phrase) lets us update a skill's
 *     synonym set by deleting its phrase rows and re-inserting
 *   - phrase_type is for debugging only (queries don't filter by it -
 *     both CANONICAL and SYNONYM contribute to the MAX score)
 *   - embedding uses the same insertable=false/updatable=false trick as
 *     skill_catalog_entry.embedding; persisted via a native INSERT with
 *     an explicit ::vector cast in the repo
 *   - we don't FK to skill_catalog_entry.name because Hibernate's cascade
 *     plays badly with the no-binding vector column; the controller
 *     manages lifecycle explicitly (delete-on-PATCH, insert-on-add)
 */
@Entity
@Table(name = "skill_synonym_embedding")
@IdClass(SkillSynonymEmbedding.PhraseKey.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkillSynonymEmbedding {

    /** Canonical key of the parent skill in skill_catalog_entry. */
    @Id
    @Column(name = "skill_name", length = 120, nullable = false)
    private String skillName;

    /** The exact text (lowercased, whitespace-collapsed) of either the
     *  canonical name or one of its synonyms. */
    @Id
    @Column(name = "phrase", length = 200, nullable = false)
    private String phrase;

    /** "CANONICAL" when phrase == skill_name, "SYNONYM" otherwise.
     *  Stored only for debugging / observability. The match query uses
     *  both types uniformly via MAX(). */
    @Column(name = "phrase_type", length = 16, nullable = false)
    private String phraseType;

    @Column(name = "embedding", columnDefinition = "vector(768)",
            insertable = false, updatable = false)
    private String embedding;

    @Column(name = "embedding_model", length = 64,
            insertable = false, updatable = false)
    private String embeddingModel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Composite-key carrier required by @IdClass. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhraseKey implements Serializable {
        private String skillName;
        private String phrase;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PhraseKey k)) return false;
            return Objects.equals(skillName, k.skillName)
                && Objects.equals(phrase, k.phrase);
        }
        @Override
        public int hashCode() { return Objects.hash(skillName, phrase); }
    }
}
