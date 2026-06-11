package com.recrutment.application.repos;

import com.recrutment.application.entities.SkillSynonymEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Multi-vector retrieval store for SOFT skill phrases (canonical name +
 * each curated synonym). See SkillSynonymEmbedding javadoc for the
 * motivation.
 *
 * All write paths are native SQL because pgvector's vector(768) column
 * can't be bound through Hibernate's generic INSERT/UPDATE binding -
 * varchar->vector is not auto-cast. Same pattern as the parent table's
 * embedding column.
 */
public interface SkillSynonymEmbeddingRepo
        extends JpaRepository<SkillSynonymEmbedding, SkillSynonymEmbedding.PhraseKey> {

    /**
     * Insert a phrase row with its 768-dim embedding. ON CONFLICT lets the
     * caller upsert (e.g. when re-embedding after the embedder model
     * changes). The :vec literal is the pgvector text format produced by
     * SkillCatalogController.toVectorLiteral.
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO skill_synonym_embedding " +
                   "  (skill_name, phrase, phrase_type, embedding, embedding_model, created_at) " +
                   "VALUES " +
                   "  (:skillName, :phrase, :phraseType, " +
                   "   CAST(:vec AS vector), :model, :ts) " +
                   "ON CONFLICT (skill_name, phrase) DO UPDATE SET " +
                   "  phrase_type = EXCLUDED.phrase_type, " +
                   "  embedding = EXCLUDED.embedding, " +
                   "  embedding_model = EXCLUDED.embedding_model, " +
                   "  created_at = EXCLUDED.created_at",
           nativeQuery = true)
    int upsertPhrase(@Param("skillName") String skillName,
                     @Param("phrase") String phrase,
                     @Param("phraseType") String phraseType,
                     @Param("vec") String vec,
                     @Param("model") String model,
                     @Param("ts") Instant ts);

    /**
     * Wipe all phrase rows for a skill. Used on PATCH /skill-catalog/{name}
     * when the recruiter REPLACES the synonym list (REPLACE semantics).
     * The controller then re-inserts canonical + each new synonym.
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM skill_synonym_embedding WHERE skill_name = :skillName",
           nativeQuery = true)
    int deleteAllForSkill(@Param("skillName") String skillName);

    /**
     * Multi-vector cosine NN search. For each parent SOFT skill, aggregates
     * the MAX similarity across all its indexed phrases (canonical + each
     * synonym), then returns the top-K skills by that aggregated score.
     *
     * The JOIN against skill_catalog_entry filters to active SOFT rows so
     * tombstoned skills don't leak into the result set, and exposes the
     * display_name for the caller.
     *
     * Returns Object[] columns: [name, display_name, score].
     */
    @Query(value = "SELECT sce.name, sce.display_name, " +
                   "       MAX(1.0 - (sse.embedding <=> CAST(:vec AS vector))) AS score " +
                   "FROM skill_synonym_embedding sse " +
                   "JOIN skill_catalog_entry sce ON sce.name = sse.skill_name " +
                   "WHERE sce.type = :type " +
                   "  AND sce.removed = false " +
                   "  AND sse.embedding IS NOT NULL " +
                   "GROUP BY sce.name, sce.display_name " +
                   "ORDER BY MAX(1.0 - (sse.embedding <=> CAST(:vec AS vector))) DESC " +
                   "LIMIT :limit",
           nativeQuery = true)
    List<Object[]> nearestByMaxScore(@Param("vec") String vec,
                                     @Param("type") String type,
                                     @Param("limit") int limit);

    /**
     * For the backfill job: list every active SOFT skill row that has at
     * least one phrase missing from the multi-vector store. Returns
     * (name, phrase, phrase_type) tuples for each missing row, so the
     * backfill can embed-then-insert without separately re-deriving the
     * synonym list.
     *
     * The "missing" set is computed as: for each (skill, canonical) and
     * (skill, synonym_i) pair that should exist per skill_catalog_entry's
     * synonyms JSON column, do we have a corresponding row in
     * skill_synonym_embedding? UNION ALL with anti-join via NOT EXISTS.
     *
     * NOTE: synonyms is stored as JSON text (StringListConverter), not as
     * a proper jsonb column. We unmarshal it cheaply with json_array_elements
     * over a cast to jsonb. Safe because StringListConverter writes
     * "[\"a\",\"b\"]" which is valid JSON.
     */
    @Query(value = "WITH expected AS (" +
                   "  SELECT sce.name AS skill_name, " +
                   "         LOWER(sce.name) AS phrase, " +
                   "         'CANONICAL' AS phrase_type " +
                   "  FROM skill_catalog_entry sce " +
                   "  WHERE sce.type = 'SOFT' AND sce.removed = false " +
                   "  UNION ALL " +
                   "  SELECT sce.name AS skill_name, " +
                   "         LOWER(syn::text) AS phrase, " +
                   "         'SYNONYM' AS phrase_type " +
                   "  FROM skill_catalog_entry sce, " +
                   "       LATERAL jsonb_array_elements_text(COALESCE(sce.synonyms, '[]')::jsonb) AS syn " +
                   "  WHERE sce.type = 'SOFT' AND sce.removed = false " +
                   "    AND sce.synonyms IS NOT NULL AND sce.synonyms <> '' AND sce.synonyms <> '[]' " +
                   ") " +
                   "SELECT e.skill_name, e.phrase, e.phrase_type " +
                   "FROM expected e " +
                   "WHERE NOT EXISTS ( " +
                   "  SELECT 1 FROM skill_synonym_embedding sse " +
                   "  WHERE sse.skill_name = e.skill_name AND sse.phrase = e.phrase " +
                   ")",
           nativeQuery = true)
    List<Object[]> findMissingPhrases();
}
