package com.recrutment.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * POST /api/skill-catalog/resolve payload.
 *
 * The caller (cv-parser-service or the admin UI) sends the raw skill text
 * they want to dedupe, along with its embedding (already computed via Ollama).
 * The endpoint answers whether this is an existing skill, a typo of one, or
 * a brand-new entry.
 *
 * Keeping the embedding compute on the Python side avoids application-
 * microservice having to call Ollama directly — preserves the existing
 * service boundary.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillResolveRequest {
    /** Raw user input or parsed CV skill text. Will be normalized server-side. */
    private String input;
    /** 768-dim vector of the normalized input, computed by the caller. */
    private List<Float> embedding;
    /** Embedding model identifier. Required so we don't auto-merge across models. */
    private String embeddingModel;
    /**
     * What the caller expects this skill to be: "HARD" or "SOFT" (or null to skip
     * the type validation step). When set, the resolve endpoint asks the LLM to
     * classify the skill and returns INVALID if the LLM says it's not a real skill,
     * or TYPE_MISMATCH if the LLM disagrees with the caller's expected type.
     */
    private String expectedType;
}
