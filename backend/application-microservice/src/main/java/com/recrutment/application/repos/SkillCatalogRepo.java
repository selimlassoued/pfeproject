package com.recrutment.application.repos;

import com.recrutment.application.entities.SkillCatalogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SkillCatalogRepo extends JpaRepository<SkillCatalogEntry, String> {

    /** All entries including removed ones — used by the recruiter admin page
     *  which has a "Show removed" section for restoring tombstoned skills. */
    List<SkillCatalogEntry> findAllByOrderByFirstSeenAtDesc();

    /** Active (non-removed) entries — used by the candidate-facing chip grid. */
    List<SkillCatalogEntry> findByRemovedFalseOrderByFirstSeenAtDesc();

    Optional<SkillCatalogEntry> findByNameIgnoreCase(String name);

    /**
     * All catalog rows missing a stored embedding — used by the backfill
     * endpoint to walk the table and request vectors from cv-parser-service.
     * Filters out tombstoned rows since recomputing for those is wasted work.
     */
    @Query(value = "SELECT * FROM skill_catalog_entry " +
                   "WHERE embedding IS NULL AND removed = false " +
                   "ORDER BY first_seen_at ASC",
           nativeQuery = true)
    List<SkillCatalogEntry> findRowsMissingEmbedding();

    /**
     * All active catalog rows of a given type ("HARD" / "SOFT") with their
     * embeddings already cached. Used by the cv-parser matcher to fetch the
     * soft-signal catalog (leadership, communication, etc.) at the start of
     * a match instead of hardcoding the list + re-embedding every call.
     */
    @Query(value = "SELECT * FROM skill_catalog_entry " +
                   "WHERE type = :type AND removed = false AND embedding IS NOT NULL " +
                   "ORDER BY first_seen_at ASC",
           nativeQuery = true)
    List<SkillCatalogEntry> findActiveByTypeWithEmbedding(@Param("type") String type);

    /**
     * All active catalog rows of a given type, regardless of embedding state.
     * Used by /match-soft-skill's synonym short-circuit: we need the synonyms
     * column for every active SOFT row even on rows that haven't been embedded
     * yet. With ~10-50 SOFT rows in the catalog this is a cheap full scan;
     * if it ever grows we can swap for an indexed `synonyms @> ARRAY[?]`
     * query but TEXT-via-StringListConverter doesn't let Postgres do that.
     */
    @Query(value = "SELECT * FROM skill_catalog_entry " +
                   "WHERE type = :type AND removed = false " +
                   "ORDER BY first_seen_at ASC",
           nativeQuery = true)
    List<SkillCatalogEntry> findActiveByType(@Param("type") String type);

    /**
     * Persist the cached embedding for a skill. Native SQL with an explicit
     * ::vector cast — Hibernate's generic UPDATE can't bind a varchar parameter
     * to a vector column. Bumps last_seen_at in the same statement.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE skill_catalog_entry " +
                   "SET embedding = CAST(:vec AS vector), " +
                   "    embedding_model = :model, " +
                   "    last_seen_at = :ts " +
                   "WHERE name = :name",
           nativeQuery = true)
    int updateEmbedding(@Param("name") String name,
                        @Param("vec") String vec,
                        @Param("model") String model,
                        @Param("ts") Instant ts);

    /**
     * Project of (name, similarity) for the resolve endpoint — finds the
     * nearest existing catalog entries to a query vector using pgvector's
     * cosine distance operator. Returns at most `limit` non-removed entries.
     *
     * cosine_similarity = 1 - cosine_distance, exposed as `score` so the
     * controller can apply the 0.95 auto-merge / 0.85 review thresholds.
     */
    @Query(value = "SELECT name, display_name, " +
                   "       (1.0 - (embedding <=> CAST(:vec AS vector))) AS score " +
                   "FROM skill_catalog_entry " +
                   "WHERE embedding IS NOT NULL " +
                   "  AND removed = false " +
                   "  AND name <> :exclude_name " +
                   "ORDER BY embedding <=> CAST(:vec AS vector) " +
                   "LIMIT :max_results",
           nativeQuery = true)
    List<Object[]> findNearestSkills(@Param("vec") String vec,
                                     @Param("exclude_name") String excludeName,
                                     @Param("max_results") int maxResults);

    /**
     * Type-aware variant: only search within rows whose type matches (HARD or SOFT).
     * Used by the resolve endpoint when the caller knows what they expect so we
     * don't merge a hard skill into a soft skill or vice versa.
     */
    @Query(value = "SELECT name, display_name, " +
                   "       (1.0 - (embedding <=> CAST(:vec AS vector))) AS score " +
                   "FROM skill_catalog_entry " +
                   "WHERE embedding IS NOT NULL " +
                   "  AND removed = false " +
                   "  AND type = :type " +
                   "  AND name <> :exclude_name " +
                   "ORDER BY embedding <=> CAST(:vec AS vector) " +
                   "LIMIT :max_results",
           nativeQuery = true)
    List<Object[]> findNearestSkillsByType(@Param("vec") String vec,
                                           @Param("type") String type,
                                           @Param("exclude_name") String excludeName,
                                           @Param("max_results") int maxResults);

    /**
     * Lexical typo detection via PostgreSQL's fuzzystrmatch.levenshtein().
     *
     * Catches single-character typos that embeddings score too low to merge
     * (e.g. "doker" vs "docker" embeds at 0.41 but is 1 edit away).
     *
     * Guards:
     *   - Length guard: candidate length within 2 chars of input - prevents
     *     "js" being merged into "as" or "cs".
     *   - Distance <= 2: catches single insert/delete/substitute and double
     *     errors, but not genuinely different words.
     *   - Minimum input length 4: very short strings (like "go") would be
     *     within distance 2 of everything else; require a few chars to risk it.
     *
     * Type filter optional - pass null to search across both HARD and SOFT.
     */
    @Query(value = "SELECT name, display_name, " +
                   "       levenshtein(name, :input) AS dist " +
                   "FROM skill_catalog_entry " +
                   "WHERE removed = false " +
                   "  AND length(:input) >= 4 " +
                   "  AND abs(length(name) - length(:input)) <= 2 " +
                   "  AND length(name) >= 4 " +
                   "  AND name <> :input " +
                   "  AND (:type IS NULL OR type = :type) " +
                   "  AND levenshtein(name, :input) <= 2 " +
                   "ORDER BY levenshtein(name, :input) ASC, length(name) ASC " +
                   "LIMIT 1",
           nativeQuery = true)
    List<Object[]> findNearestByLevenshtein(@Param("input") String input,
                                            @Param("type") String type);

    /** Touch the last_seen_at without changing anything else. */
    @Modifying
    @Transactional
    @Query(value = "UPDATE skill_catalog_entry SET last_seen_at = :ts WHERE name = :name",
           nativeQuery = true)
    int touchLastSeen(@Param("name") String name, @Param("ts") Instant ts);

    /**
     * Find the SINGLE closest catalog row to :vec from the restricted set of
     * :candidate_names. Used by the matcher's Track 1 proxy search — instead
     * of fetching every CV-skill's vector to Python and looping, the matcher
     * sends ALL the CV skill names and Postgres returns the best match.
     *
     * Returns one row {name, display_name, score} where score is cosine
     * similarity in [0, 1]. HNSW index on `embedding` keeps this sub-ms even
     * as the catalog grows.
     *
     * Returns empty when no candidate has an embedding yet (caller treats
     * this as "no proxy" — fall back to SEMANTIC-only mode).
     */
    @Query(value = "SELECT name, display_name, " +
                   "       (1.0 - (embedding <=> CAST(:vec AS vector))) AS score " +
                   "FROM skill_catalog_entry " +
                   "WHERE embedding IS NOT NULL " +
                   "  AND removed = false " +
                   "  AND name = ANY(CAST(:names AS text[])) " +
                   "ORDER BY embedding <=> CAST(:vec AS vector) " +
                   "LIMIT 1",
           nativeQuery = true)
    List<Object[]> findNearestOf(@Param("vec") String vec,
                                 @Param("names") String namesArray);
}
