package com.recrutment.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Everything the matcher needs to know about a skill in one payload:
 * canonical name, the 768-dim embedding, the LLM-derived volatility and
 * its derived half-life, the list of skills it implies, and recency.
 *
 * Used in three flows:
 *   - GET /api/skill-catalog/{name}/intel returns this shape.
 *   - PUT /api/skill-catalog/{name}/intel accepts this shape (any field
 *     null = "don't update that field" so the matcher can write the embedding
 *     and volatility in separate calls).
 *   - Inside SkillResolveResponse to describe the matched skill.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillIntelDto {
    /** Canonical lowercase name — primary key. */
    private String name;

    /** User-facing form, e.g. "Spring Boot". */
    private String displayName;

    /** 768 floats. Null on GET means "not yet embedded." */
    private List<Float> embedding;

    /** Model that produced the embedding, e.g. "nomic-embed-text". */
    private String embeddingModel;

    /** 1-10 volatility from the LLM. */
    private Integer volatility;

    /** Half-life in years, denormalized from volatility for query speed. */
    private Double halfLife;

    /** Other skill names this one implies (e.g. "spring boot" → ["java"]). */
    private List<String> implies;

    /** Last time the matcher or an admin touched this row. */
    private Instant lastSeenAt;
}
